#!/usr/bin/env bash
# Android SDK不要で BocchiLive2dStageView の型検査だけを行う。
# 描画は実行しない。JAVA_HOME と KOTLINC（2.2以降）を指定する。
set -euo pipefail
cd "$(dirname "$0")/.."
SOURCE=app/src/main/java/com/example/kennys_dokidoki_wallpaper
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

python3 tools/r_stub.py "$SOURCE" "$TMP/R.kt"

"${KOTLINC:-kotlinc}" \
    tools/graphics_stubs/*.kt \
    "$SOURCE"/{HomeCharacter,HomeSection,BocchiLive2dPolicy,BocchiLive2dStageView}.kt \
    "$TMP/R.kt" \
    -d "$TMP/stage"
