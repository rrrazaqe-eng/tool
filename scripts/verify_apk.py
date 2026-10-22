#!/usr/bin/env python3
"""Verify packaged native ABI and 16 KB ELF load alignment without unpacking to disk."""
import struct
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    native = [name for name in apk.namelist() if name.startswith('lib/') and name.endswith('.so')]
    assert native, 'APK contains no native libraries'
    assert {name.split('/')[1] for name in native} == {'arm64-v8a'}, 'Unexpected APK ABI'
    for name in native:
        data = apk.read(name)
        assert data[:6] == b'\x7fELF\x02\x01', f'Not a 64-bit little-endian ELF: {name}'
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        for index in range(count):
            entry = offset + size * index
            if struct.unpack_from('<I', data, entry)[0] == 1:
                alignment = struct.unpack_from('<Q', data, entry + 48)[0]
                assert alignment >= 16384, f'ELF is not 16 KB aligned: {name}'
    assert 'AndroidManifest.xml' in apk.namelist() and 'classes.dex' in apk.namelist()
print(f'APK structure and 16 KB alignment verified ({len(native)} ARM64 native libraries)')
