#!/bin/bash
# Dev launcher: Purpur through Fabric Loader in ../run-hybrid. Extra args are passed to the server.
set -euo pipefail
B=$(cd "$(dirname "$0")/.." && pwd)
cd "$B/run-hybrid"
CP="$(find $B/dumper/run/libs -name '*.jar' | tr '\n' ':')$B/loader/build/rpgbridge-loader.jar:$(find libraries -name '*.jar' | grep -v '/org/ow2/asm/' | tr '\n' ':')"
exec ${RPGB_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}/bin/java -Xmx4G ${RPGB_JVM_ARGS:-} \
  -Dlog4j.configurationFile=rpgbridge-log4j2.xml -Dfabric.skipMcProvider=true \
  -Dfabric.gameJarPath=versions/26.2/purpur-26.2.jar -cp "$CP" net.fabricmc.loader.impl.launch.knot.KnotServer "$@"
