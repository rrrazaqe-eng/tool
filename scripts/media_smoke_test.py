#!/usr/bin/env python3
"""Run MediaCommands' exported commands against real fixtures with host FFmpeg.

This validates filters, streams, dimensions, durations, audio formats and rate
budgets. Software AVC/HEVC encoders replace Android MediaCodec only on the host;
this does not claim to test a Samsung codec or the Android UI/MediaStore paths.
Usage: python scripts/media_smoke_test.py app/build/media-cases.json
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time


def run(args: list[str], *, timeout: int = 90) -> str:
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args!r}\n{result.stderr[-5000:]}")
    return result.stdout


def ffmpeg(args: list[str]) -> str:
    return run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-filter_threads", "1", "-filter_complex_threads", "1", *args])


def make_fixtures(root: Path) -> dict[str, Path]:
    fixtures: dict[str, Path] = {}
    for name, size in (("landscape", "1920x1080"), ("portrait", "1080x1920"), ("silent", "1920x1080")):
        path = root / f"{name}.mp4"
        args = ["-y", "-f", "lavfi", "-i", f"testsrc2=size={size}:rate=8:duration=4"]
        if name != "silent":
            args += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4", "-c:a", "aac", "-b:a", "128k"]
        args += ["-c:v", "libx264", "-preset", "ultrafast", "-threads", "2", "-pix_fmt", "yuv420p", "-t", "4", str(path)]
        ffmpeg(args)
        fixtures[f"fixture:{name}"] = path
    for ext, codec in (("mp3", "libmp3lame"), ("m4a", "aac"), ("wav", "pcm_s16le")):
        path = root / f"audio.{ext}"
        ffmpeg(["-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4", "-c:a", codec, str(path)])
        fixtures[f"fixture:audio-{ext}"] = path
    for ratio, size in (("16x9", "1280x720"), ("9x16", "720x1280"), ("1x1", "1080x1080")):
        path = root / f"cover-{ratio}.png"
        ffmpeg(["-y", "-f", "lavfi", "-i", f"color=c=0xFFD400:size={size}:rate=1", "-frames:v", "1", "-threads", "1", str(path)])
        fixtures[f"fixture:cover-{ratio}"] = path
    return fixtures


def host_arguments(args: list[str]) -> list[str]:
    """Substitute host encoders and remove only their Android NDK options.

    extract_extradata and all other output/filter settings stay in the command;
    those must remain valid in the host integration checks too.
    """
    converted = []
    index = 0
    native_options = {"-ndk_codec:v", "-ndk_async:v"}
    encoder_names = {"h264_mediacodec": "libx264", "hevc_mediacodec": "libx265"}
    while index < len(args):
        arg = args[index]
        if arg in native_options:
            if index + 1 >= len(args):
                raise ValueError(f"Missing value for native encoder option {arg}")
            index += 2
            continue
        converted.append(encoder_names.get(arg, arg))
        index += 1
    return converted


def run_case(case: dict, root: Path, fixtures: dict[str, Path]) -> dict:
    name = case["name"]
    output = root / f"{name}.{case['extension']}"
    args = [str(output) if arg == "fixture:output" else str(fixtures.get(arg, arg)) for arg in case["args"]]
    args = host_arguments(args)
    if "-c:v" in args:
        args[-1:-1] = ["-preset", "ultrafast", "-threads", "2"]
        if "libx265" in args:
            args[-1:-1] = ["-x265-params", "pools=1:frame-threads=1:log-level=error"]
    started = time.monotonic()
    ffmpeg(args)
    probe = json.loads(run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(output)]))
    video = [stream for stream in probe["streams"] if stream["codec_type"] == "video"]
    audio = [stream for stream in probe["streams"] if stream["codec_type"] == "audio"]
    expected_video = case["videoCodec"]
    expected_audio = case["audioCodec"]
    assert len(video) == (1 if expected_video else 0), (name, "video stream count", video)
    assert len(audio) == (1 if expected_audio else 0), (name, "audio stream count", audio)
    if video:
        assert video[0]["codec_name"] == expected_video, (name, video[0]["codec_name"], expected_video)
        assert (video[0]["width"], video[0]["height"]) == (case["width"], case["height"]), (name, video[0]["width"], video[0]["height"], case["width"], case["height"])
        assert video[0].get("pix_fmt") == "yuv420p", (name, video[0].get("pix_fmt"))
    if audio:
        assert audio[0]["codec_name"] == expected_audio, (name, audio[0]["codec_name"], expected_audio)
    actual_duration = float(probe["format"]["duration"])
    expected_duration = case["durationMs"] / 1000
    assert abs(actual_duration - expected_duration) <= .3, (name, "duration", actual_duration, expected_duration)
    assert output.stat().st_size > 0, name
    if case["targetBytes"]:
        # The service additionally retries oversize hardware results. This fixture
        # checks the first-pass budget; software rate control differs from Android.
        assert output.stat().st_size <= case["targetBytes"] * 1.15, (name, "target bytes", output.stat().st_size, case["targetBytes"])
    return {"name": name, "status": "passed", "bytes": output.stat().st_size, "durationSeconds": actual_duration, "elapsedSeconds": round(time.monotonic() - started, 2)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", type=Path)
    parser.add_argument("--report", type=Path, default=Path("app/build/reports/media-smoke.json"))
    options = parser.parse_args()
    cases = json.loads(options.cases.read_text())
    if not cases:
        raise SystemExit("No MediaCommands fixtures exported; run :app:testDebugUnitTest first")
    results = []
    with tempfile.TemporaryDirectory(prefix="tools-strong-media-") as directory:
        root = Path(directory)
        fixtures = make_fixtures(root)
        for case in cases:
            try:
                result = run_case(case, root, fixtures)
                print(f"PASS {case['name']}", flush=True)
                results.append(result)
            except (AssertionError, RuntimeError, subprocess.TimeoutExpired) as error:
                results.append({"name": case["name"], "status": "failed", "error": str(error)})
                print(f"FAIL {case['name']}: {error}", flush=True)
    options.report.parent.mkdir(parents=True, exist_ok=True)
    options.report.write_text(json.dumps({"encoderScope": "host software; Android hardware requires device verification", "cases": results}, ensure_ascii=False, indent=2) + "\n")
    failed = sum(result["status"] == "failed" for result in results)
    print(f"{len(results) - failed}/{len(results)} host media cases passed; report: {options.report}")
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
