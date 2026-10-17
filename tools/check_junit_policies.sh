#!/usr/bin/env bash
# Android SDK不要。KOTLINC（2.2以降）とJAVAを指定するかPATHへ。
# 生成プロンプト系ポリシーのJUnitテストをスタブでローカル実行する。
set -euo pipefail
cd "$(dirname "$0")/.."
SOURCE=app/src/main/java/com/example/kennys_dokidoki_wallpaper
TESTS=app/src/test/java/com/example/kennys_dokidoki_wallpaper
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

"${KOTLINC:-kotlinc}" \
    tools/junit_stubs/Junit.kt tools/junit_stubs/Runner.kt \
    tools/org_json_stubs/Json.kt tools/image_cache_stubs/Uri.kt \
    "$SOURCE"/{GeneratedImageTagBinding,GeneratedImagePresetPolicy,Preset,PresetSavePolicy,BuilderCardSetApplyPolicy,BuilderCardSetHandoff,PromptCard}.kt \
    "$TESTS"/{GeneratedImageTagBindingTest,GeneratedImagePresetPolicyTest,BuilderCardSetApplyPolicyTest,BuilderCardSetHandoffTest}.kt \
    -include-runtime -d "$TMP/junit.jar"
"${JAVA:-java}" -jar "$TMP/junit.jar" \
    com.example.kennys_dokidoki_wallpaper.GeneratedImageTagBindingTest \
    com.example.kennys_dokidoki_wallpaper.GeneratedImagePresetPolicyTest \
    com.example.kennys_dokidoki_wallpaper.BuilderCardSetApplyPolicyTest \
    com.example.kennys_dokidoki_wallpaper.BuilderCardSetHandoffTest
