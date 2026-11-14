#!/bin/bash
# RPG Bridge launcher: starts an unmodified Purpur server through Fabric Loader, so the Fabric mods
# in ./mods (Fabric API, RPG Series, Better Combat, ...) run on Purpur next to the Bukkit plugins.
#
# Put this script, rpgbridge-loader.jar and rpgbridge-log4j2.xml in the server directory, next to
# purpur.jar. Put rpgbridge-core.jar and the same mod jars the players use into ./mods.
# Usage: ./start.sh [server arguments, e.g. nogui]      Env: JAVA (default: java), MEMORY (default: 4G)
set -euo pipefail
cd "$(dirname "$0")"

JAVA=${JAVA:-java}
MEMORY=${MEMORY:-4G}
PURPUR_JAR=${PURPUR_JAR:-purpur.jar}
LOADER_VERSION=0.19.5
FABRIC_MAVEN=https://maven.fabricmc.net
LIB=.rpgbridge/libraries

[ -f "$PURPUR_JAR" ] || { echo "Missing $PURPUR_JAR"; exit 1; }
[ -f rpgbridge-loader.jar ] || { echo "Missing rpgbridge-loader.jar"; exit 1; }
ls mods/rpgbridge-core*.jar >/dev/null 2>&1 || { echo "Missing mods/rpgbridge-core.jar"; exit 1; }

# 1. Let paperclip apply Purpur's patches (creates versions/ and libraries/), without starting.
if ! ls versions/*/purpur-*.jar >/dev/null 2>&1; then
  "$JAVA" -Dpaperclip.patchonly=true -jar "$PURPUR_JAR"
fi
GAME_JAR=$(ls versions/*/purpur-*.jar | head -1)

# 2. Fabric Loader and its libraries (Apache-2.0 / MIT / BSD), downloaded once.
mkdir -p "$LIB"
fetch() { # url
  local f="$LIB/$(basename "$1")"
  [ -s "$f" ] || curl -fsSLo "$f" "$1"
}
fetch "$FABRIC_MAVEN/net/fabricmc/fabric-loader/$LOADER_VERSION/fabric-loader-$LOADER_VERSION.jar"
LOADER_JSON="$LIB/fabric-loader-$LOADER_VERSION.json"
[ -s "$LOADER_JSON" ] || curl -fsSLo "$LOADER_JSON" "$FABRIC_MAVEN/net/fabricmc/fabric-loader/$LOADER_VERSION/fabric-loader-$LOADER_VERSION.json"
# Library coordinates from the loader's own metadata ("name": "group:artifact:version", "url": repo).
python3 - "$LOADER_JSON" <<'EOF' | while read -r url; do fetch "$url"; done
import json, sys
d = json.load(open(sys.argv[1]))
libs = d["libraries"]["common"] + d["libraries"].get("server", [])
for l in libs:
    g, a, v = l["name"].split(":")[:3]
    print(f'{l["url"].rstrip("/")}/{g.replace(".", "/")}/{a}/{v}/{a}-{v}.jar')
EOF

# 3. Class path: Fabric Loader first, then Purpur's libraries (Paper's own ASM is replaced by Fabric's).
CP="$(find "$LIB" -name '*.jar' | tr '\n' ':')rpgbridge-loader.jar:$(find libraries -name '*.jar' | grep -v '/org/ow2/asm/' | tr '\n' ':')"

exec "$JAVA" -Xmx"$MEMORY" ${JVM_ARGS:-} \
  -Dlog4j.configurationFile=rpgbridge-log4j2.xml \
  -Dfabric.skipMcProvider=true \
  -Dfabric.gameJarPath="$GAME_JAR" \
  -cp "$CP" net.fabricmc.loader.impl.launch.knot.KnotServer "$@"
