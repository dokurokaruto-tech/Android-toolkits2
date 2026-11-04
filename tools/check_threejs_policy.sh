#!/usr/bin/env bash
# Android SDK不要。KOTLINC（2.2以降）とJAVAを指定するかPATHへ。
# three.js セットの純粋ロジックと組み込み目録のJUnitテストをスタブでローカル実行する。
set -euo pipefail
cd "$(dirname "$0")/.."
SOURCE=app/src/main/java/com/example/kennys_dokidoki_wallpaper
TESTS=app/src/test/java/com/example/kennys_dokidoki_wallpaper
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

"${KOTLINC:-kotlinc}" \
    tools/junit_stubs/Junit.kt tools/junit_stubs/Runner.kt \
    "$SOURCE"/{ThreeJsSetPolicy,ThreeJsWork}.kt \
    "$TESTS"/ThreeJsSetPolicyTest.kt \
    -include-runtime -d "$TMP/junit.jar"
"${JAVA:-java}" -jar "$TMP/junit.jar" \
    com.example.kennys_dokidoki_wallpaper.ThreeJsSetPolicyTest
