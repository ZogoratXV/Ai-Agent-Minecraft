#!/usr/bin/env bash
# End-to-end check of phase 3: foreman (sim backend) + Purpur staging server + AgentCraft plugin.
# Drives a whole goal from the server console (as an admin would) and checks that NPCs were moved
# and spoke through world tools, that decisions were answered and the goal completed.
# Usage: ACCEPT_EULA=true staging/e2e-sim.sh               foreman started by this script
#        ACCEPT_EULA=true MODE=managed staging/e2e-sim.sh  the plugin starts the bundled foreman
#                                                          and generates the token by itself
set -euo pipefail
MODE="${MODE:-external}"

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DIR="$HERE/server"
LOG="$DIR/e2e.log"
FLOG="$DIR/e2e-foreman.log"
AUDIT="$DIR/plugins/AgentCraftServer/audit.jsonl"
if [ "$MODE" = "external" ]; then
  export AGENTCRAFT_TOKEN="${AGENTCRAFT_TOKEN:-$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')}"
else
  unset AGENTCRAFT_TOKEN
fi

"$HERE/fetch-purpur.sh" >/dev/null
(cd "$ROOT/plugin" && ./gradlew --quiet build -x test)
mkdir -p "$DIR/plugins"
rm -f "$DIR"/plugins/agentcraft-server-*.jar "$AUDIT"
cp "$(ls -t "$ROOT"/plugin/build/libs/agentcraft-server-*.jar | head -1)" "$DIR/plugins/"
PDIR="$DIR/plugins/AgentCraftServer"
mkdir -p "$PDIR"
rm -rf "$PDIR/token.txt" "$PDIR/foreman"
# a fast sim for the managed foreman too
sed "s/  extra-args: \[\]/  extra-args: ['--step-ms', '400']/" "$ROOT/plugin/src/main/resources/config.yml" > "$PDIR/config.yml"
if ! grep -qs "eula=true" "$DIR/eula.txt"; then
  [ "${ACCEPT_EULA:-}" = "true" ] || { echo "Set ACCEPT_EULA=true (https://aka.ms/MinecraftEULA)" >&2; exit 1; }
  echo "eula=true" > "$DIR/eula.txt"
fi
[ -f "$DIR/server.properties" ] || printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=25566\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\n' > "$DIR/server.properties"

cleanup() { kill "${FOREMAN_PID:-}" "${JAVA_PID:-}" 2>/dev/null || true; rm -f "${FIFO:-}"; }
trap cleanup EXIT

if [ "$MODE" = "external" ]; then
  (cd "$ROOT/foreman" && exec node --import tsx src/main.ts --backend sim --step-ms 400) > "$FLOG" 2>&1 &
  FOREMAN_PID=$!
else
  FLOG="$PDIR/foreman/foreman.log"
fi

# NO_NODE=1: like the Java container of Pelican/Pterodactyl, the server sees no Node at all
SERVER_PATH="$PATH"
if [ "${NO_NODE:-}" = "1" ]; then
  NB="$(mktemp -d)"
  for t in java timeout tar gzip git sh bash env ls cat mkdir rm cp mv sed grep head tail dirname basename uname id; do
    p="$(command -v "$t" || true)"; [ -n "$p" ] && ln -s "$p" "$NB/$t"
  done
  SERVER_PATH="$NB"
  rm -rf "$PDIR/runtime"
fi
FIFO="$(mktemp -u)"; mkfifo "$FIFO"
(cd "$DIR" && PATH="$SERVER_PATH" exec timeout 900 java -Xms1G -Xmx2G -jar purpur.jar --nogui) < "$FIFO" > "$LOG" 2>&1 &
JAVA_PID=$!
exec 3> "$FIFO"

# console output carries ANSI colors and may print emoji as '?': match on plain text
plain() { sed 's/\x1b\[[0-9;]*m//g' "$1"; }
wait_for() { # pattern file timeout-seconds
  local end=$((SECONDS + $3))
  until plain "$2" | grep -qE "$1"; do
    kill -0 "$JAVA_PID" 2>/dev/null || { echo "server died" >&2; return 1; }
    [ $SECONDS -lt $end ] || { echo "timeout waiting for: $1" >&2; return 1; }
    sleep 1
  done
}
cmd() { echo "$1" >&3; sleep "${2:-1}"; }
fail() { echo "E2E FAILED: $1" >&2; grep -iE "agentcraft|exception" "$LOG" | tail -40 >&2; tail -20 "$FLOG" >&2; exit 1; }

wait_for 'Done \(' "$LOG" 600 || fail "server did not start"
wait_for 'connected to the foreman' "$LOG" 90 || fail "plugin did not connect"
if [ "$MODE" = "managed" ]; then
  plain "$LOG" | grep -q "foreman started (pid" || fail "plugin did not start the bundled foreman"
  if [ "${NO_NODE:-}" = "1" ]; then
    plain "$LOG" | grep -q "Node 22.23.4 ready" || fail "Node was not downloaded"
  fi
  [ -s "$PDIR/token.txt" ] || fail "no generated token"
else
  plain "$LOG" | grep -q "already running" || fail "plugin did not reuse the running foreman"
fi
cmd "agent status" 2
cmd "agent goal Prova end-to-end del backend simulato" 2
plain "$LOG" | grep -q "Obiettivo inviato (g1)" || fail "goal not accepted"
wait_for 'Briciola \(d1, question\)' "$LOG" 60 || fail "no question from Briciola"
cmd "agent answer d1 1" 2
wait_for 'Bussola \(d2, merge\)' "$LOG" 90 || fail "no merge decision"
cmd "agent decisions" 1
cmd "agent approve d2" 2
true
sleep 6
cmd "agent tasks" 2
cmd "agent agents" 2
cmd "stop" 0
wait "$JAVA_PID" || true
if [ "$MODE" = "managed" ] && pgrep -f "$PDIR/foreman/foreman.mjs" >/dev/null; then
  fail "the managed foreman is still running after the server stopped"
fi

plain "$LOG" | grep -q "t1 \[done\]" || fail "t1 is not done"
plain "$LOG" | grep -q "t2 \[done\]" || fail "t2 is not done"
MOVES=$(grep -c '"action":"body.move".*"outcome":"ok"' "$AUDIT" || true)
SAYS=$(grep -c '"action":"body.say".*"outcome":"ok"' "$AUDIT" || true)
for tool in server.metrics server.logs server.command; do
  grep -q "\"action\":\"$tool\".*\"outcome\":\"ok\"" "$AUDIT" || fail "$tool was not executed"
done

[ "$MOVES" -ge 5 ] || fail "only $MOVES successful body.move calls"
[ "$SAYS" -ge 3 ] || fail "only $SAYS successful body.say calls"
# the sandbox has no Mojang auth services: ignore that one, fail on anything else
plain "$LOG" | grep -v -E "minecraftservices|authlib" | grep -qE "^[^ ]*Exception|Exception in|\[AgentCraftServer\].*(ERROR|SEVERE)" && fail "exceptions in the server log"
echo "E2E PASSED ($MODE foreman${NO_NODE:+, no Node on the machine}): goal g1 done, $MOVES body.move and $SAYS body.say calls executed and audited"
