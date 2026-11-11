#!/usr/bin/env bash
# Starts a local Purpur staging server with the freshly built AgentCraft plugin.
# Usage: staging/run-local.sh            (foreground; type "stop" to quit)
#        staging/run-local.sh --smoke    (boot, check the plugin enabled, stop; exit code = result)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DIR="$HERE/server"
MEMORY="${STAGING_MEMORY:-2G}"

"$HERE/fetch-purpur.sh" >/dev/null
(cd "$ROOT/plugin" && ./gradlew --quiet build -x test)

mkdir -p "$DIR/plugins"
rm -f "$DIR"/plugins/agentcraft-server-*.jar
cp "$ROOT"/plugin/build/libs/agentcraft-server-*.jar "$DIR/plugins/"

# Accepting the Minecraft EULA is the operator's decision: https://aka.ms/MinecraftEULA
if [ ! -f "$DIR/eula.txt" ] || ! grep -q "eula=true" "$DIR/eula.txt"; then
  if [ "${ACCEPT_EULA:-}" = "true" ]; then
    echo "eula=true" > "$DIR/eula.txt"
  else
    echo "Set ACCEPT_EULA=true to accept the Minecraft EULA (https://aka.ms/MinecraftEULA)." >&2
    exit 1
  fi
fi
if [ ! -f "$DIR/server.properties" ]; then
  printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=25566\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\n' > "$DIR/server.properties"
fi

cd "$DIR"
if [ "${1:-}" != "--smoke" ]; then
  exec java -Xms"$MEMORY" -Xmx"$MEMORY" -jar purpur.jar --nogui
fi

LOG="$DIR/smoke.log"
: > "$LOG"
( sleep 1; while ! grep -qE 'Done \(|Failed to start|Exception in server tick' "$LOG"; do sleep 2; done; echo stop ) \
  | timeout 600 java -Xms"$MEMORY" -Xmx"$MEMORY" -jar purpur.jar --nogui > "$LOG" 2>&1 || true

if grep -q "AgentCraft ready" "$LOG" && grep -q 'Done (' "$LOG"; then
  echo "smoke test passed: plugin enabled on $(grep -oE 'Purpur [^ ]+' "$LOG" | head -1 || echo Purpur)"
  grep "AgentCraft" "$LOG"
else
  echo "smoke test FAILED, see $LOG" >&2
  grep -iE "agentcraft|error|exception" "$LOG" | head -40 >&2
  exit 1
fi
