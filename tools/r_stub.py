"""Emit a Kotlin stub for the Android generated R class.

Usage: python3 r_stub.py <source-dir> <output.kt>

Only the R.<kind>.<name> references found in the given .kt files are stubbed,
so policy sources can be compiled without the Android SDK.
"""
import re
import sys
from pathlib import Path


def main() -> None:
    source, output = map(Path, sys.argv[1:3])
    names: dict = {}
    for path in sorted(source.glob('*.kt')):
        for kind, key in re.findall(r'R\.(\w+)\.(\w+)', path.read_text()):
            names.setdefault(kind, set()).add(key)

    lines = ['package com.example.kennys_dokidoki_wallpaper', 'object R {']
    for kind, keys in names.items():
        lines.append(f'object {kind} {{')
        lines += [f'const val {key} = {i}' for i, key in enumerate(sorted(keys), 1)]
        lines.append('}')
    lines.append('}')
    output.write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
