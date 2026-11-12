#!/usr/bin/env bash
# Phase 5 end-to-end on a real Purpur 26.2 server: a scripted foreman asks for the server tools,
# installs a new version of a small plugin after an approved deploy decision and restarts the
# server; the start loop (like local-test/start.sh) brings it back with the new version.
# Usage: ACCEPT_EULA=true staging/e2e-deploy.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DIR="$HERE/server"
LOG="$DIR/e2e-deploy.log"
PDIR="$DIR/plugins/AgentCraftServer"
WORK="$(mktemp -d)"
RESULTS="$WORK/results.jsonl"
PORT=7979
export AGENTCRAFT_TOKEN="e2e-deploy-$RANDOM$RANDOM"

"$HERE/fetch-purpur.sh" >/dev/null
(cd "$ROOT/plugin" && ./gradlew --quiet build -x test)
API="$(find ~/.gradle -name 'purpur-api-26.2*.jar' ! -name '*sources*' | head -1)"
[ -n "$API" ] || { echo "purpur-api jar not found in the Gradle cache" >&2; exit 1; }

# a tiny plugin, version 1.0 installed by hand and 2.0 built "by the agents"
mkdir -p "$WORK/src/hello" "$WORK/classes"
cat > "$WORK/src/hello/HelloAgent.java" <<'JAVA'
package hello;
public class HelloAgent extends org.bukkit.plugin.java.JavaPlugin {
    @Override public void onEnable() { getLogger().info("HelloAgent running version " + getPluginMeta().getVersion()); }
}
JAVA
javac -nowarn -cp "$API:$(find ~/.gradle -name 'adventure-api-*.jar' | head -1)" -d "$WORK/classes" "$WORK/src/hello/HelloAgent.java" 2>/dev/null || \
  javac -nowarn -cp "$(find ~/.gradle -name '*.jar' -path '*modules-2*' | tr '\n' ':')" -d "$WORK/classes" "$WORK/src/hello/HelloAgent.java"
mkjar() { printf 'name: HelloAgent\nversion: "%s"\nmain: hello.HelloAgent\napi-version: "26.2"\n' "$1" > "$WORK/classes/plugin.yml"; (cd "$WORK/classes" && jar cf "$2" plugin.yml hello); }

mkdir -p "$DIR/plugins" "$PDIR/foreman/home/deploy"
rm -f "$DIR"/plugins/agentcraft-server-*.jar "$DIR"/plugins/HelloAgent*.jar "$DIR/plugins/update/"* "$DIR/.agentcraft-restart"
cp "$(ls -t "$ROOT"/plugin/build/libs/agentcraft-server-*.jar | head -1)" "$DIR/plugins/"
mkjar 1.0 "$DIR/plugins/HelloAgent.jar"
mkjar 2.0 "$PDIR/foreman/home/deploy/t1-helloagent-2.0.jar"
rm -f "$PDIR/audit.jsonl"
sed -e "s/  managed: true/  managed: false/" -e "s#ws://127.0.0.1:7878#ws://127.0.0.1:$PORT#" \
  "$ROOT/plugin/src/main/resources/config.yml" > "$PDIR/config.yml"
grep -qs "eula=true" "$DIR/eula.txt" || { [ "${ACCEPT_EULA:-}" = "true" ] && echo "eula=true" > "$DIR/eula.txt"; } || { echo "Set ACCEPT_EULA=true" >&2; exit 1; }
[ -f "$DIR/server.properties" ] || printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=25566\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\n' > "$DIR/server.properties"

cleanup() { kill "${FOREMAN_PID:-}" "${LOOP_PID:-}" 2>/dev/null || true; pkill -f "agentcraft.e2e=deploy" 2>/dev/null || true; rm -f "${FIFO:-}"; rm -rf "$WORK"; }
trap cleanup EXIT
RESULTS="$RESULTS" node "$HERE/fake-foreman.mjs" "$PORT" "$PDIR/foreman/home/deploy/t1-helloagent-2.0.jar" &
FOREMAN_PID=$!
FIFO="$(mktemp -u)"; mkfifo "$FIFO"
# the same restart loop as local-test/start.sh
(cd "$DIR" && while :; do
  timeout 600 java -Dagentcraft.e2e=deploy -Xms1G -Xmx2G -jar purpur.jar --nogui
  [ -f .agentcraft-restart ] || break
  rm -f .agentcraft-restart
  echo "E2E: restarting"
done) < "$FIFO" > "$LOG" 2>&1 &
LOOP_PID=$!
exec 3> "$FIFO"
plain() { sed 's/\x1b\[[0-9;]*m//g' "$LOG"; }
wait_for() { local end=$((SECONDS + $2)); until plain | grep -qE "$1"; do kill -0 "$LOOP_PID" 2>/dev/null || return 1; [ $SECONDS -lt $end ] || return 1; sleep 1; done; }
fail() { echo "E2E FAILED: $1" >&2; plain | grep -iE "agentcraft|helloagent|exception|E2E" | tail -40 >&2; cat "$RESULTS" >&2 2>/dev/null || true; exit 1; }
res() { grep "\"tool\":\"$1\"" "$RESULTS" | sed -n "${2:-1}p"; }

wait_for 'HelloAgent running version 1.0' 600 || fail "server did not start with HelloAgent 1.0"
wait_for 'E2E: restarting' 180 || fail "the server was not restarted"
wait_for 'HelloAgent running version 2.0' 600 || fail "HelloAgent 2.0 was not active after the restart"
wait_for 'connected to the foreman' 120 || fail "plugin did not reconnect after the restart"

res server.metrics | grep -q '"ok":true' || fail "server.metrics"
res server.metrics | grep -q '"HelloAgent","version":"1.0"' || fail "metrics lack the plugin list"
res server.logs | grep -q 'HelloAgent running version 1.0' || fail "server.logs"
res server.command 1 | grep -q '"ok":true' || fail "server.command list"
res server.command 2 | grep -q '"code":"forbidden"' || fail "op was not refused"
res player.simulate | grep -q '"code":"not_found"' || fail "player.simulate without Citizens should say so"
res server.deploy 1 | grep -q '"code":"forbidden"' || fail "qa may not deploy"
res server.deploy 2 | grep -q '"code":"needs_approval"' || fail "an open decision must not allow a deploy"
res server.deploy 3 | grep -q '"code":"forbidden"' || fail "a file outside the deploy folder was accepted"
res server.deploy 4 | grep -q '"ok":true' || fail "approved deploy failed"
res server.deploy 4 | grep -q '"previousVersion":"1.0"' || fail "deploy did not find the installed version"
res server.restart | grep -q '"method":"script"' || fail "restart method"
plain | grep -q "Il server si riavvia tra 5 secondi" || fail "players were not warned"
grep -q '"action":"server.deploy".*"outcome":"ok"' "$PDIR/audit.jsonl" || fail "deploy not audited"
echo "stop" >&3
wait "$LOOP_PID" || true
echo "E2E PASSED: server tools, refusals, approved install of HelloAgent 2.0, restart and reconnection"
