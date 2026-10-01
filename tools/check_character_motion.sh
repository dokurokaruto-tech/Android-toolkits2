#!/usr/bin/env bash
# Android SDKなしでモーションを検証。JAVA_HOMEとKOTLINC（2.2以降）を指定する。
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT
SOURCE="$ROOT/app/src/main/java/com/example/kennys_dokidoki_wallpaper"
python3 - "$SOURCE" "$TEMP/R.kt" <<'PY'
import re
import sys
from pathlib import Path
source, output = map(Path, sys.argv[1:])
names = {}
for name in ('HomeCharacter.kt', 'HomeSection.kt'):
    for kind, key in re.findall(r'R\.(\w+)\.(\w+)', (source / name).read_text()):
        names.setdefault(kind, set()).add(key)
text = 'package com.example.kennys_dokidoki_wallpaper\nobject R {\n'
for kind, keys in names.items():
    text += f'object {kind} {{\n'
    text += ''.join(f'const val {key} = {i}\n' for i, key in enumerate(sorted(keys), 1))
    text += '}\n'
output.write_text(text + '}\n')
PY
"${KOTLINC:-kotlinc}" "$SOURCE"/{HomeCharacter,HomeSection,BocchiLive2dPolicy}.kt \
    "$TEMP/R.kt" "$ROOT/tools/character_motion_check.kt" -include-runtime -d "$TEMP/check.jar"
"${JAVA_HOME:?Set JAVA_HOME}/bin/java" -jar "$TEMP/check.jar"
