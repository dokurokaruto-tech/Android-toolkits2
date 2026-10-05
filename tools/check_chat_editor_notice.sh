#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SOURCE=app/src/main/java/com/example/kennys_dokidoki_wallpaper
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
"${KOTLINC:-kotlinc}" "$SOURCE"/{ConciergeEditorPolicy,ChatReplyNoticePolicy,ChatReplyStreamState}.kt \
    tools/chat_editor_notice_check.kt -include-runtime -d "$TMP/check.jar"
"${JAVA:-java}" -jar "$TMP/check.jar"
