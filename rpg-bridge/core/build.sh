#!/bin/bash
# Builds the RPG Bridge core Fabric mod against Purpur + Fabric Loader + Fabric API + mods.
# Purpur is unobfuscated (Mojang names), so plain javac is enough: no Loom, no remapping.
set -euo pipefail
cd "$(dirname "$0")"
JAVA_HOME=${RPGB_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}
B=..
mkdir -p build/fapi
(cd build/fapi && unzip -qo ../../$B/jars/fabric-api-*.jar 'META-INF/jars/*')
CP=$(find "$B/dumper/run/libs" "$B/run/versions" "$B/run/libraries" build/fapi "$B/jars" -name '*.jar' | grep -v '/org/ow2/asm/' | tr '\n' ':')
rm -rf build/classes && mkdir -p build/classes
"$JAVA_HOME/bin/javac" --release 25 -nowarn -Xlint:none -proc:none -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
"$JAVA_HOME/bin/jar" --create --file build/rpgbridge-core.jar -C build/classes .
echo "built core/build/rpgbridge-core.jar"
