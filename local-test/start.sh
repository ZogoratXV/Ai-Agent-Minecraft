#!/usr/bin/env bash
# AgentCraft: server Minecraft di prova sul tuo PC (macOS / Linux).
# Doppio clic non basta su macOS: apri il Terminale in questa cartella e scrivi ./start.sh
set -euo pipefail
cd "$(dirname "$0")"

PURPUR_BUILD=2633
PURPUR_MD5=3f3c933f2d518af98571c7461fa42278
PURPUR_URL="https://api.purpurmc.org/v2/purpur/26.2/$PURPUR_BUILD/download"
RAM="${RAM:-2G}"

# 1. Java 25
if ! command -v java >/dev/null 2>&1; then
  echo "Java non trovato. Installa Java 25 (Temurin): https://adoptium.net/temurin/releases/?version=25"
  exit 1
fi
JMAJOR="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)"
if [ -z "$JMAJOR" ] || [ "$JMAJOR" -lt 25 ]; then
  echo "Serve Java 25 o più recente (trovato: ${JMAJOR:-?})."
  echo "Installa Temurin 25: https://adoptium.net/temurin/releases/?version=25"
  exit 1
fi

# 2. Purpur 26.2 (scaricato una volta e verificato)
md5of() { if command -v md5sum >/dev/null; then md5sum "$1" | cut -d' ' -f1; else md5 -q "$1"; fi; }
if [ ! -f purpur.jar ] || [ "$(md5of purpur.jar)" != "$PURPUR_MD5" ]; then
  echo "Scarico Purpur 26.2 build $PURPUR_BUILD..."
  curl -fL --progress-bar -o purpur.jar.part "$PURPUR_URL"
  if [ "$(md5of purpur.jar.part)" != "$PURPUR_MD5" ]; then
    rm -f purpur.jar.part
    echo "Il file scaricato non corrisponde all'impronta ufficiale: interrotto."
    exit 1
  fi
  mv purpur.jar.part purpur.jar
fi

# 3. EULA di Minecraft: la accetti tu
if ! grep -qs "eula=true" eula.txt; then
  echo "Per avviare un server Minecraft devi accettare l'EULA: https://aka.ms/MinecraftEULA"
  printf "La accetti? [s/N] "
  read -r risposta
  case "$risposta" in
    s|S|si|sì|SI|y|Y) echo "eula=true" > eula.txt ;;
    *) echo "Senza EULA il server non parte."; exit 1 ;;
  esac
fi

# 4. Impostazioni di un mondo di prova (solo la prima volta)
if [ ! -f server.properties ]; then
  cat > server.properties <<'PROPS'
motd=AgentCraft - server di prova
online-mode=true
server-port=25565
level-name=mondo-prova
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
difficulty=peaceful
view-distance=8
simulation-distance=6
PROPS
fi

echo
echo "Avvio il server. Quando vedi 'Done', entra in Minecraft (26.2) su: localhost"
echo "Per darti i permessi scrivi qui:  op <il tuo nome>      Per fermarlo:  stop"
echo
while :; do
  java -Xms"$RAM" -Xmx"$RAM" -jar purpur.jar --nogui
  # AgentCraft leaves this file when it restarts the server to install an updated plugin
  [ -f .agentcraft-restart ] || break
  rm -f .agentcraft-restart
  echo "Riavvio richiesto da AgentCraft: riparto..."
done
