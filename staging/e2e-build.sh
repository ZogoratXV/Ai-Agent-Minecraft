#!/usr/bin/env bash
# Phase 6 end-to-end on a real Purpur 26.2 server, sim backend (no API usage): create a region,
# ask for a house, approve the ghost preview, let the builder place every module, then undo it.
# Usage: ACCEPT_EULA=true staging/e2e-build.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DIR="$HERE/server"
LOG="$DIR/e2e-build.log"
PDIR="$DIR/plugins/AgentCraftServer"
unset AGENTCRAFT_TOKEN

"$HERE/fetch-purpur.sh" >/dev/null
(cd "$ROOT/plugin" && ./gradlew --quiet build -x test)
mkdir -p "$DIR/plugins" "$PDIR"
rm -f "$DIR"/plugins/agentcraft-server-*.jar
cp "$(ls -t "$ROOT"/plugin/build/libs/agentcraft-server-*.jar | head -1)" "$DIR/plugins/"
rm -rf "$PDIR/jobs" "$PDIR/regions.yml" "$PDIR/audit.jsonl"
sed "s/  extra-args: \[\]/  extra-args: ['--step-ms', '300']/" "$ROOT/plugin/src/main/resources/config.yml" > "$PDIR/config.yml"
grep -qs "eula=true" "$DIR/eula.txt" || { [ "${ACCEPT_EULA:-}" = "true" ] && echo "eula=true" > "$DIR/eula.txt"; } || { echo "Set ACCEPT_EULA=true" >&2; exit 1; }
[ -f "$DIR/server.properties" ] || printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=25566\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\n' > "$DIR/server.properties"

cleanup() { kill "${JAVA_PID:-}" 2>/dev/null || true; rm -f "${FIFO:-}"; }
trap cleanup EXIT
FIFO="$(mktemp -u)"; mkfifo "$FIFO"
(cd "$DIR" && exec timeout 900 java -Xms1G -Xmx2G -jar purpur.jar --nogui) < "$FIFO" > "$LOG" 2>&1 &
JAVA_PID=$!
exec 3> "$FIFO"
plain() { sed 's/\x1b\[[0-9;]*m//g' "$LOG"; }
wait_for() { local end=$((SECONDS + $2)); until plain | grep -qE "$1"; do kill -0 "$JAVA_PID" 2>/dev/null || return 1; [ $SECONDS -lt $end ] || return 1; sleep 1; done; }
fail() { echo "E2E FAILED: $1" >&2; plain | grep -iE "agentcraft|job |exception" | tail -40 >&2; tail -15 "$PDIR/foreman/foreman.log" >&2 || true; exit 1; }

wait_for 'Done \(' 600 || fail "server did not start"
wait_for 'connected to the foreman' 90 || fail "plugin did not connect"
# flat world: grass at y=-61, so the region starts at y=-60
echo "agent region create piazza world 10 -60 10 29 -46 29" >&3; sleep 2
plain | grep -q "Regione creata: piazza" || fail "region not created"
echo "agent goal costruisci una casa medievale" >&3
wait_for 'Matita \(d[0-9]+, build\)' 120 || fail "no build decision"
D=$(plain | grep -oE 'Matita \(d[0-9]+, build\)' | head -1 | grep -oE 'd[0-9]+')
plain | grep -q "Costruire \"Casa medievale\" nella regione piazza" || fail "wrong decision text"
echo "agent answer $D 1" >&3
wait_for 'job t[0-9]+-interni done' 300 || fail "the house was not finished"
for m in fondamenta pavimento muri tetto interni; do
  plain | grep -qE "job t[0-9]+-$m done: ([0-9]+)/\1 blocks" || fail "module $m not complete"
done
FOUND=$(plain | grep -oE 'job t[0-9]+-fondamenta done: [0-9]+' | grep -oE '[0-9]+$')
[ "$FOUND" = "81" ] || fail "foundation has $FOUND blocks, expected 81"
T=$(plain | grep -oE 'job (t[0-9]+)-fondamenta' | head -1 | sed -E 's/job (t[0-9]+)-.*/\1/')
# visible speed: the house must take a while to build (not all in the same second)
FIRST=$(plain | grep -E "job $T-fondamenta done" | grep -oE '^\[[0-9:]+' | tr -d '[' )
LAST=$(plain | grep -E "job $T-interni done" | grep -oE '^\[[0-9:]+' | tr -d '[' )
secs() { IFS=: read -r h m s <<<"$1"; echo $((10#$h*3600 + 10#$m*60 + 10#$s)); }
[ $(( $(secs "$LAST") - $(secs "$FIRST") )) -ge 15 ] || fail "built too fast to watch ($FIRST -> $LAST)"
echo "agent undo $T" >&3
wait_for "job $T-fondamenta:undo undone" 240 || fail "undo did not finish"
plain | grep -q "Annullo $T-interni, $T-tetto, $T-muri, $T-pavimento, $T-fondamenta" || fail "undo not newest-first"
echo "agent jobs" >&3; sleep 2
echo "stop" >&3
wait "$JAVA_PID" || true
grep -q '"action":"world.place".*"outcome":"done"' "$PDIR/audit.jsonl" || fail "no world.place in the audit"
grep -q '"action":"world.undo".*"outcome":"undone"' "$PDIR/audit.jsonl" || fail "no undo in the audit"
plain | grep -v -E "minecraftservices|authlib" | grep -qE "^[^ ]*Exception|Exception in|\[AgentCraftServer\].*(ERROR|SEVERE)" && fail "exceptions in the log"
echo "E2E PASSED: region, design, approval, 5 modules built block by block, undone"
