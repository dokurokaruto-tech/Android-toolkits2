#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SOURCE=app/src/main/java/com/example/kennys_dokidoki_wallpaper
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
"${KOTLINC:-kotlinc}" "$SOURCE"/{OriginalDiskCache,ImageMemoryPressurePolicy}.kt \
    tools/image_cache_check.kt -include-runtime -d "$TMP/policy.jar"
"${JAVA:-java}" -jar "$TMP/policy.jar"
if [[ -n "${COROUTINES_JAR:-}" ]]; then
    "${KOTLINC:-kotlinc}" -cp "$COROUTINES_JAR" \
        "$SOURCE"/{OriginalDiskCache,OriginalImageMemoryCache,ImageMemoryPressurePolicy}.kt \
        tools/image_cache_stubs/*.kt tools/image_transfer_check.kt \
        -include-runtime -d "$TMP/transfer.jar"
    "${JAVA:-java}" -cp "$TMP/transfer.jar:$COROUTINES_JAR" \
        com.example.kennys_dokidoki_wallpaper.Image_transfer_checkKt
fi
