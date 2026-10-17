#!/usr/bin/env bash
# Android SDK不要。KOTLINC（2.2以降）とJAVAを指定するかPATHへ。
# 再生成のシード/バッチ方針と、閲覧画面→ビルダーのカード受け渡しを検証する。
set -euo pipefail
cd "$(dirname "$0")/.."
SOURCE=app/src/main/java/com/example/kennys_dokidoki_wallpaper
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

"${KOTLINC:-kotlinc}" \
    "$SOURCE"/{AgentGenerationRequest,GeneratedImageReplayPolicy,GeneratedImageLifecycle,BuilderGenSettingPolicy}.kt \
    tools/replay_seed_batch_check.kt -include-runtime -d "$TMP/replay.jar"
"${JAVA:-java}" -cp "$TMP/replay.jar" com.example.kennys_dokidoki_wallpaper.Replay_seed_batch_checkKt

"${KOTLINC:-kotlinc}" \
    "$SOURCE"/{Preset,PresetSavePolicy,GeneratedImagePresetPolicy,BuilderCardSetApplyPolicy,BuilderCardSetHandoff}.kt \
    tools/image_cache_stubs/Uri.kt tools/card_set_apply_check.kt -include-runtime -d "$TMP/apply.jar"
"${JAVA:-java}" -cp "$TMP/apply.jar" com.example.kennys_dokidoki_wallpaper.Card_set_apply_checkKt
