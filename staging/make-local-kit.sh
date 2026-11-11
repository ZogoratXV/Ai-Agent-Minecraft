#!/usr/bin/env bash
# Builds AgentCraft-test-locale-<version>.zip: start scripts + the plugin jar, ready for a PC.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
(cd "$ROOT/plugin" && ./gradlew --quiet build -x test)
JAR="$(ls -t "$ROOT"/plugin/build/libs/agentcraft-server-*.jar | head -1)"
VERSION="$(basename "$JAR" .jar | sed 's/agentcraft-server-//')"
OUT="${1:-$ROOT/build}"
STAGE="$(mktemp -d)/AgentCraft-test-locale"
mkdir -p "$STAGE/plugins" "$OUT"
cp "$ROOT/local-test/start.sh" "$ROOT/local-test/start.ps1" "$ROOT/local-test/start.bat" "$ROOT/local-test/LEGGIMI.txt" "$STAGE/"
cp "$JAR" "$STAGE/plugins/AgentCraftServer-$VERSION.jar"
ZIP="$OUT/AgentCraft-test-locale-$VERSION.zip"
rm -f "$ZIP"
(cd "$(dirname "$STAGE")" && zip -q -r -X "$ZIP" AgentCraft-test-locale)
echo "$ZIP"
