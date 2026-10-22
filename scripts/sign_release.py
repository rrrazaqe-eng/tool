#!/usr/bin/env python3
"""Sign the production APK using an owner's external keystore; never log passwords."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    required = (
        'TOOLS_STRONG_KEYSTORE', 'TOOLS_STRONG_KEY_ALIAS',
        'TOOLS_STRONG_KEYSTORE_PASSWORD', 'TOOLS_STRONG_KEY_PASSWORD',
    )
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise SystemExit('Signing not configured. Required environment variables: ' + ', '.join(missing))
    keystore = Path(os.environ['TOOLS_STRONG_KEYSTORE']).expanduser().resolve()
    if not keystore.is_file():
        raise SystemExit('External keystore file not found')
    if keystore.is_relative_to(root):
        raise SystemExit('Keep the signing keystore outside the public repository')
    if keystore.name == 'debug.keystore' or os.environ['TOOLS_STRONG_KEY_ALIAS'].lower() == 'androiddebugkey':
        raise SystemExit('Use the permanent release key, not the Android debug key')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise SystemExit('ANDROID_HOME is required')
    tools = Path(sdk) / 'build-tools' / '36.0.0'
    source = root / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    if not source.is_file():
        raise SystemExit('Build the production APK first: ./gradlew :app:assembleRelease')
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(source)], text=True)
    if "package: name='com.michastrong.toolsstrong'" not in badging or 'application-debuggable' in badging:
        raise SystemExit('Expected the non-debug production package')
    if "versionName='1.0.0'" not in badging or "targetSdkVersion:'36'" not in badging:
        raise SystemExit('Unexpected release version or target SDK')
    out = root / 'out'
    out.mkdir(exist_ok=True)
    aligned = out / 'Tools-Strong-1.0.0-aligned-unsigned.apk'
    release = out / 'Tools-Strong-1.0.0-release.apk'
    subprocess.run([str(tools / 'zipalign'), '-f', '-P', '16', '4', str(source), str(aligned)], check=True)
    try:
        subprocess.run([
            str(tools / 'apksigner'), 'sign', '--ks', str(keystore),
            '--ks-key-alias', os.environ['TOOLS_STRONG_KEY_ALIAS'],
            '--ks-pass', 'env:TOOLS_STRONG_KEYSTORE_PASSWORD',
            '--key-pass', 'env:TOOLS_STRONG_KEY_PASSWORD',
            '--out', str(release), str(aligned),
        ], check=True)
        subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', str(release)], check=True)
        subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(release)], check=True)
        subprocess.run([sys.executable, str(root / 'scripts/verify_apk.py'), str(release)], check=True)
    except subprocess.CalledProcessError:
        release.unlink(missing_ok=True)
        raise SystemExit('Release signing or verification failed') from None
    finally:
        aligned.unlink(missing_ok=True)
    digest = hashlib.sha256(release.read_bytes()).hexdigest()
    release.with_suffix('.apk.sha256').write_text(f'{digest}  {release.name}\n')
    print('Signed and verified release: ' + str(release))


if __name__ == '__main__':
    main()
