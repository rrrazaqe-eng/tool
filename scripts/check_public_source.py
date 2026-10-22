#!/usr/bin/env python3
"""Fail on accidentally tracked signing keys or recognizable credentials; never echo values."""
import pathlib
import re
import subprocess

root = pathlib.Path(__file__).resolve().parents[1]
paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
blocked = []
patterns = [
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
    re.compile(rb'gh[pousr]_[A-Za-z0-9_]{30,}'),
    re.compile(rb'github_pat_[A-Za-z0-9_]{30,}'),
    re.compile(rb'AKIA[0-9A-Z]{16}'),
    re.compile(rb'AIza[0-9A-Za-z_-]{35}'),
    re.compile(rb'xox[baprs]-[A-Za-z0-9-]{20,}'),
    re.compile(rb'(?i)(?:storePassword|keyPassword|api_key|access_token)\s*[:=]\s*[\'\"][^\'\"\r\n]{6,}[\'\"]'),
]
for name in filter(None, paths):
    path = root / name
    if path.suffix.lower() in {'.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.apk', '.aab'} or name.startswith('signing/') or path.name in {'.env', 'local.properties', 'keystore.properties', 'key.properties'}:
        blocked.append(name)
    elif path.is_file() and any(pattern.search(path.read_bytes()) for pattern in patterns):
        blocked.append(name)
if blocked:
    raise SystemExit('Unsafe public files: ' + ', '.join(sorted(set(blocked))))
print(f'Public source check passed ({len(list(filter(None, paths)))} tracked files)')
