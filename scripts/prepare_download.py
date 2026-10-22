#!/usr/bin/env python3
"""Reassemble an already signed public APK for release upload in GitHub Actions.

Only public APK/material blobs are transported. No signing key or API credential
is included in this process. Input IDs are validated before calling the CLI.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def blob(sha):
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise SystemExit('Invalid public blob ID')
    response = json.loads(subprocess.check_output([
        'gh', 'api', f'repos/{repository}/git/blobs/{sha}',
    ]))
    if response.get('encoding') != 'base64':
        raise SystemExit('Unexpected blob encoding')
    data = base64.b64decode(response['content'])
    digest = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    if digest != sha:
        raise SystemExit('Public blob checksum mismatch')
    return data


repository = os.environ['GITHUB_REPOSITORY']
if repository != 'rrrazaqe-eng/tool':
    raise SystemExit('Unexpected repository')
expected = os.environ['APK_SHA256']
if not re.fullmatch(r'[0-9a-f]{64}', expected):
    raise SystemExit('Invalid APK checksum')
parts = os.environ['APK_BLOBS'].split(',')
if not 1 <= len(parts) <= 16:
    raise SystemExit('Unexpected APK part count')
out = Path('out')
out.mkdir(exist_ok=True)
apk = out / 'Tools-Strong-1.0.0-release.apk'
digest = hashlib.sha256()
size = 0
with apk.open('wb') as output:
    for sha in parts:
        data = blob(sha)
        size += len(data)
        if size > 100 * 1024 * 1024:
            raise SystemExit('APK transport exceeds size limit')
        digest.update(data)
        output.write(data)
if digest.hexdigest() != expected:
    apk.unlink()
    raise SystemExit('Signed APK SHA-256 mismatch')
with zipfile.ZipFile(apk) as package:
    assert 'AndroidManifest.xml' in package.namelist()
    assert 'classes.dex' in package.namelist()
    assert not any(name.lower().endswith(('.p12', '.jks', '.keystore', '.pem', '.key')) for name in package.namelist())
apk.with_suffix('.apk.sha256').write_text(f'{expected}  {apk.name}\n')
materials = os.environ.get('MATERIALS_BLOB', '')
if materials:
    data = blob(materials)
    if len(data) > 5 * 1024 * 1024:
        raise SystemExit('Unexpected store material size')
    target = out / 'Tools-Strong-RuStore-materials.zip'
    target.write_bytes(data)
    with zipfile.ZipFile(target) as archive:
        assert not any(name.lower().endswith(('.apk', '.p12', '.jks', '.keystore', '.pem', '.key')) or 'password' in name.lower() or 'private' in name.lower() for name in archive.namelist())
print(f'Public signed APK verified: {size} bytes, SHA-256 {expected}')
