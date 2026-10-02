#!/usr/bin/env bash
# Android SDK不要。JAVA_HOMEとKOTLINC（2.2以降）を指定する。
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT
SOURCE="$ROOT/app/src/main/java/com/example/kennys_dokidoki_wallpaper"
"${KOTLINC:-kotlinc}" "$SOURCE"/{GeneratedImageIdentity,ImageSelection}.kt \
    "$ROOT/tools/image_selection_check.kt" -include-runtime -d "$TEMP/check.jar"
"${JAVA_HOME:?Set JAVA_HOME}/bin/java" -jar "$TEMP/check.jar"
