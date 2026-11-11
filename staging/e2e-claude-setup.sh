#!/usr/bin/env bash
# Checks the real-agent setup without spending anything: backend claude, managed foreman, NO API key.
# The plugin must install the Claude Agent SDK with npm, start the foreman, show in /agent status
# that the agents are stopped for the missing key, and register a repository from the console.
# No agent turn can run (the foreman refuses to work without API authentication).
# Usage: ACCEPT_EULA=true staging/e2e-claude-setup.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DIR="$HERE/server"
LOG="$DIR/e2e-claude.log"
PDIR="$DIR/plugins/AgentCraftServer"
unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN CLAUDE_CODE_OAUTH_TOKEN CLAUDE_CODE_USE_BEDROCK CLAUDE_CODE_USE_VERTEX AGENTCRAFT_TOKEN

"$HERE/fetch-purpur.sh" >/dev/null
(cd "$ROOT/plugin" && ./gradlew --quiet build -x test)
mkdir -p "$DIR/plugins" "$PDIR"
rm -f "$DIR"/plugins/agentcraft-server-*.jar
cp "$(ls -t "$ROOT"/plugin/build/libs/agentcraft-server-*.jar | head -1)" "$DIR/plugins/"
rm -rf "$PDIR/foreman/home" "$PDIR/token.txt"
sed "s/^  backend: sim$/  backend: claude/" "$ROOT/plugin/src/main/resources/config.yml" > "$PDIR/config.yml"
# LOGIN=1: subscription mode with a fake token (no goal is submitted, so nothing is called)
if [ "${LOGIN:-}" = "1" ]; then
  echo "fake-subscription-token-for-tests" > "$PDIR/claude.token"
  sed -i "s/^  claude-token-file: ''$/  claude-token-file: claude.token/" "$PDIR/config.yml"
  grep -q "^  claude-token-file: claude.token" "$PDIR/config.yml"
fi
grep -q "^  backend: claude" "$PDIR/config.yml"
grep -qs "eula=true" "$DIR/eula.txt" || { [ "${ACCEPT_EULA:-}" = "true" ] && echo "eula=true" > "$DIR/eula.txt"; } || { echo "Set ACCEPT_EULA=true" >&2; exit 1; }
[ -f "$DIR/server.properties" ] || printf 'online-mode=false\nserver-ip=127.0.0.1\nserver-port=25566\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\n' > "$DIR/server.properties"

# a small git repository to register
REPO="$(mktemp -d)"
git -C "$REPO" init -q -b main && git -C "$REPO" -c user.name=t -c user.email=t@t commit -q --allow-empty -m init

cleanup() { kill "${JAVA_PID:-}" 2>/dev/null || true; rm -f "${FIFO:-}"; rm -rf "$REPO"; }
trap cleanup EXIT
# NO_NODE=1: like the Java container of Pelican/Pterodactyl (no Node, no npm, no Maven)
SERVER_PATH="$PATH"
if [ "${NO_NODE:-}" = "1" ]; then
  NB="$(mktemp -d)"
  for t in java timeout tar gzip git sh bash env ls cat mkdir rm cp mv sed grep head tail dirname basename uname id; do
    p="$(command -v "$t" || true)"; [ -n "$p" ] && ln -s "$p" "$NB/$t"
  done
  SERVER_PATH="$NB"
  rm -rf "$PDIR/runtime" "$PDIR/foreman/node_modules"
fi
FIFO="$(mktemp -u)"; mkfifo "$FIFO"
(cd "$DIR" && PATH="$SERVER_PATH" exec timeout 900 java -Xms1G -Xmx2G -jar purpur.jar --nogui) < "$FIFO" > "$LOG" 2>&1 &
JAVA_PID=$!
exec 3> "$FIFO"
plain() { sed 's/\x1b\[[0-9;]*m//g' "$1"; }
wait_for() { local end=$((SECONDS + $2)); until plain "$LOG" | grep -qE "$1"; do kill -0 "$JAVA_PID" 2>/dev/null || return 1; [ $SECONDS -lt $end ] || return 1; sleep 1; done; }
fail() { echo "E2E FAILED: $1" >&2; plain "$LOG" | grep -iE "agentcraft|exception" | tail -30 >&2; tail -20 "$PDIR/foreman/foreman.log" >&2 || true; exit 1; }

wait_for 'Done \(' 600 || fail "server did not start"
wait_for 'Claude Agent SDK (installed|already)|connected to the foreman' 300 || fail "SDK not installed"
wait_for 'connected to the foreman' 120 || fail "plugin did not connect"
if [ "${NO_NODE:-}" = "1" ]; then
  plain "$LOG" | grep -q "Node 22.23.4 ready" || fail "Node was not downloaded"
  plain "$LOG" | grep -q "Maven 3.9.16 ready" || fail "Maven was not downloaded"
fi
echo "agent status" >&3; sleep 2
if [ "${LOGIN:-}" = "1" ]; then
  plain "$LOG" | grep -q "personal use" || fail "no personal-use warning"
  plain "$LOG" | grep -q "Agenti fermi" && fail "subscription mode refused a token"
  grep -q -- "--use-claude-login" <(ps -ef) || fail "foreman not in subscription mode"
  echo "stop" >&3; wait "$JAVA_PID" || true
  echo "E2E PASSED: subscription mode, warning shown, agents ready, no call made"
  exit 0
fi
plain "$LOG" | grep -q "Agenti fermi: manca ANTHROPIC_API_KEY.*imposta la chiave" || fail "/agent status does not say that the key is missing"
echo "agent repo add $REPO" >&3; sleep 3
plain "$LOG" | grep -q "Repository registrato" || fail "repo add failed"
echo "agent repos" >&3; sleep 2
echo "agent goal prova senza chiave" >&3; sleep 3
echo "stop" >&3
wait "$JAVA_PID" || true
[ -f "$PDIR/foreman/node_modules/@anthropic-ai/claude-agent-sdk/package.json" ] || fail "SDK not in the foreman folder"
if ls "$PDIR/foreman/home/claude-config/projects" >/dev/null 2>&1; then fail "an agent session was started without an API key"; fi
echo "E2E PASSED${NO_NODE:+ (no Node/npm/Maven on the machine)}: SDK installed by the plugin, agents stopped for the missing key, repository registered, no agent turn"
