#!/usr/bin/env bash
# Android SDK不要でホームキャラクター周りのJUnitテストを実行する。
# JAVA_HOME と KOTLINC（2.2以降）を指定する。
set -euo pipefail
cd "$(dirname "$0")/.."
SOURCE=app/src/main/java/com/example/kennys_dokidoki_wallpaper
TESTS=app/src/test/java/com/example/kennys_dokidoki_wallpaper
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Android 生成リソースIDのみスタブ化する
python3 tools/r_stub.py "$SOURCE" "$TMP/R.kt"

"${KOTLINC:-kotlinc}" \
    tools/junit_stubs/Junit.kt tools/junit_stubs/Runner.kt \
    "$SOURCE"/{HomeCharacter,HomeSection,BocchiLive2dPolicy}.kt "$TMP/R.kt" \
    "$TESTS"/{HomeCharacterTest,BocchiLive2dPolicyTest}.kt \
    -include-runtime -d "$TMP/character.jar"
"${JAVA_HOME:?Set JAVA_HOME}/bin/java" -jar "$TMP/character.jar" \
    com.example.kennys_dokidoki_wallpaper.HomeCharacterTest \
    com.example.kennys_dokidoki_wallpaper.BocchiLive2dPolicyTest
