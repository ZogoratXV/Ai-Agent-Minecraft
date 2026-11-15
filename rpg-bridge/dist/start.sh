#!/bin/bash
# RPG Bridge launcher: starts an unmodified Purpur server through Fabric Loader, so the Fabric mods
# in ./mods (Fabric API, RPG Series, Better Combat, ...) run on Purpur next to the Bukkit plugins.
#
# Put this script, rpgbridge-loader.jar and rpgbridge-log4j2.xml in the server directory, next to
# purpur.jar. Put rpgbridge-core.jar and the same mod jars the players use into ./mods.
# Usage: ./start.sh [server arguments, e.g. nogui]      Env: JAVA (default: java), MEMORY (default: 4G)
#    or: ./start.sh [java options] -jar <server jar> [server arguments]
#        the same command line as "java ... -jar server.jar ...": replace "java" with "bash start.sh"
#        (e.g. Pelican/Pterodactyl startup commands). With -Xmx or -XX:MaxRAMPercentage among the
#        options, MEMORY is not applied.
set -euo pipefail
cd "$(dirname "$0")"

JAVA=${JAVA:-java}
MEMORY=${MEMORY:-4G}
PURPUR_JAR=${PURPUR_JAR:-purpur.jar}
JVM_OPTS=()
if printf '%s\n' "$@" | grep -qx -- '-jar'; then
  while [ $# -gt 0 ]; do
    if [ "$1" = "-jar" ]; then PURPUR_JAR=$2; shift 2; break; fi
    JVM_OPTS+=("$1"); shift
  done
fi
HEAP=("-Xmx$MEMORY")
for o in "${JVM_OPTS[@]}" ${JVM_ARGS:-}; do
  case "$o" in -Xmx*|-XX:MaxRAMPercentage=*|-XX:MaxRAM=*) HEAP=();; esac
done
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

# 2. Fabric Loader and its libraries (Apache-2.0 / MIT / BSD), downloaded once.
mkdir -p "$LIB"
fetch() { # url
  local f="$LIB/$(basename "$1")"
  [ -s "$f" ] || curl -fsSLo "$f" "$1"
}
# Same list as the "libraries" of fabric-loader-$LOADER_VERSION.json (no python/jq needed, e.g. in Pelican containers).
for path in \
  "net/fabricmc/fabric-loader/$LOADER_VERSION/fabric-loader-$LOADER_VERSION.jar" \
  org/ow2/asm/asm/9.10.1/asm-9.10.1.jar \
  org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar \
  org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar \
  org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar \
  org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar \
  "net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar"; do
  fetch "$FABRIC_MAVEN/$path"
done

# 3. Class path in a Java argument file: Fabric Loader, then only the libraries Purpur lists (plugin libraries
#    downloaded into libraries/ by Paper must stay out), without Paper's own ASM (Fabric's replaces it).
ARGS=.rpgbridge/launch.txt
"$JAVA" -cp rpgbridge-loader.jar dev.rpgbridge.loader.LaunchArgs "$PURPUR_JAR" "$LIB" "$ARGS"

exec "$JAVA" "${HEAP[@]}" "${JVM_OPTS[@]}" ${JVM_ARGS:-} \
  -Dlog4j.configurationFile=rpgbridge-log4j2.xml \
  -Dfabric.skipMcProvider=true \
  @"$ARGS" net.fabricmc.loader.impl.launch.knot.KnotServer "$@"
