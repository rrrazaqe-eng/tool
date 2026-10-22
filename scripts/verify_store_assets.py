#!/usr/bin/env python3
"""Reject missing/corrupt screenshot exports before publishing the store artifact."""
import hashlib
import json
from pathlib import Path
import struct
import sys

directory = Path(sys.argv[1])
manifest = json.loads((directory / 'capture-manifest.json').read_text())
assert manifest['androidApi'] == 36
expected = {'01-home.png', '02-video-compression.png', '03-audio-cover.png', 'icon-512.png'}
assert {item['name'] for item in manifest['files']} == expected
hashes = []
for item in manifest['files']:
    data = (directory / item['name']).read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n' and data[12:16] == b'IHDR', 'Not a PNG: ' + item['name']
    width, height = struct.unpack('>II', data[16:24])
    assert (width, height) == (item['width'], item['height'])
    assert len(data) <= 3 * 1024 * 1024
    digest = hashlib.sha256(data).hexdigest()
    assert digest == item['sha256']
    if item['name'] == 'icon-512.png':
        assert (width, height) == (512, 512)
    else:
        assert 320 <= width <= 2160 and 320 <= height <= 3840
        hashes.append(digest)
assert len(set(hashes)) == 3, 'Expected three distinct app screens'
print('Verified three actual Android 16 screenshots, 512px icon and capture checksums')
