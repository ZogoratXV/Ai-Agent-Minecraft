#!/usr/bin/env bash
# Test end-to-end: installa un server 26.2 con il launcher, carica il plugin di prova,
# esegue i suoi controlli dalla console e verifica il log. Avviandolo accetti la EULA di
# Minecraft per il server di test (https://aka.ms/MinecraftEULA).
# Uso (dalla cartella tessera/, dopo ./gradlew build): ./scripts/smoke-test.sh [cartella]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-$ROOT/build/smoke-server}"
LAUNCHER=$(ls "$ROOT"/tessera-launcher/build/libs/tessera-launcher-*.jar | head -1)
PLUGIN=$(ls "$ROOT"/test-plugin/build/libs/test-plugin-*.jar | head -1)

rm -rf "$DIR/plugins" && mkdir -p "$DIR/plugins"
cp "$PLUGIN" "$DIR/plugins/"
printf 'memory.max=2G\n' > "$DIR/tessera-launcher.properties"
LOG="$DIR/smoke.log"
FIFO="$DIR/console.fifo"
rm -f "$FIFO" "$LOG" && mkfifo "$FIFO"

sleep infinity > "$FIFO" &
HOLDER=$!
trap 'kill $HOLDER 2>/dev/null || true' EXIT
java -jar "$LAUNCHER" --dir "$DIR" --accept-eula --no-restart < "$FIFO" > "$LOG" 2>&1 &
SERVER=$!

for _ in $(seq 1 300); do
  grep -q "Tessera pronto" "$LOG" && break
  kill -0 $SERVER 2>/dev/null || { cat "$LOG"; echo "Il server è terminato prima di essere pronto" >&2; exit 1; }
  sleep 1
done
for cmd in "tesseratest" "hello smoke" "plugins" "tessera unsupported"; do
  echo "$cmd" > "$FIFO"; sleep 2
done
sleep 3
echo "stop" > "$FIFO"
wait $SERVER

fail=0
for expect in "Tessera pronto: 1 plugin attivi" "TESSERA-CHECK-OK" "Ciao smoke!" "runTaskLater eseguito" "task asincrono" "Plugin Bukkit (1)" "onDisable: arrivederci" "Server fermato correttamente"; do
  if grep -q "$expect" "$LOG"; then echo "OK   $expect"; else echo "FAIL $expect"; fail=1; fi
done
if grep -q "ERROR\].*Tessera\|Errore durante l'abilitazione" "$LOG"; then echo "FAIL errori di Tessera nel log"; fail=1; fi
[ $fail -eq 0 ] || { echo "--- log ---"; tail -80 "$LOG"; exit 1; }
echo "Smoke test superato."
