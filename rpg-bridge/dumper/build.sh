#!/bin/bash
# Compiles the dumper mod with plain javac (26.x is unobfuscated: no Loom/remapping needed).
# Needs: ../jars (mods + fabric-api), run/server.jar (vanilla bundler jar), run/libs (fabric-loader).
set -euo pipefail
cd "$(dirname "$0")"
JAVA_HOME=${RPGB_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}
CP_DIR=build/cp
mkdir -p "$CP_DIR/fapi" build/classes
(cd "$CP_DIR" && unzip -qo ../../run/server.jar 'META-INF/versions/*' 'META-INF/libraries/*')
(cd "$CP_DIR/fapi" && unzip -qo ../../../../jars/fabric-api-*.jar 'META-INF/jars/*')
CP=$(find "$CP_DIR" run/libs ../jars -name '*.jar' | tr '\n' ':')
"$JAVA_HOME/bin/javac" --release 25 -nowarn -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/* build/classes/
(cd build/classes && "$JAVA_HOME/bin/jar" --create --file ../rpgbridge-dumper.jar .)
echo "built build/rpgbridge-dumper.jar"
