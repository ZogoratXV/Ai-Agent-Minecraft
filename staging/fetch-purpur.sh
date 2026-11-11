#!/usr/bin/env bash
# Downloads a Purpur server jar and checks its MD5 against the Purpur API.
# Usage: staging/fetch-purpur.sh [mc-version] [build]   (default: 26.2, latest build)
set -euo pipefail

MC_VERSION="${1:-26.2}"
BUILD="${2:-latest}"
DIR="$(cd "$(dirname "$0")" && pwd)/server"
API="https://api.purpurmc.org/v2/purpur/${MC_VERSION}"

mkdir -p "$DIR"
if [ "$BUILD" = "latest" ]; then
  BUILD="$(curl -fsS "$API" | sed -E 's/.*"latest":"([0-9]+)".*/\1/')"
fi
EXPECTED_MD5="$(curl -fsS "$API/$BUILD" | sed -E 's/.*"md5":"([0-9a-f]+)".*/\1/')"
JAR="$DIR/purpur-${MC_VERSION}-${BUILD}.jar"

if [ ! -f "$JAR" ]; then
  echo "downloading Purpur $MC_VERSION build $BUILD"
  curl -fsSL -o "$JAR.part" "$API/$BUILD/download"
  mv "$JAR.part" "$JAR"
fi
ACTUAL_MD5="$(md5sum "$JAR" | cut -d' ' -f1)"
if [ "$ACTUAL_MD5" != "$EXPECTED_MD5" ]; then
  echo "checksum mismatch for $JAR (expected $EXPECTED_MD5, got $ACTUAL_MD5)" >&2
  rm -f "$JAR"
  exit 1
fi
ln -sf "$(basename "$JAR")" "$DIR/purpur.jar"
echo "$JAR"
