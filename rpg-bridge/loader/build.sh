#!/bin/bash
# Builds the Fabric Loader game provider for Purpur (run ../deps.sh first).
set -euo pipefail
cd "$(dirname "$0")"
JAVA_HOME=${RPGB_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}
CP=$(find ../deps/loader -name '*.jar' | tr '\n' ':')
rm -rf build/classes && mkdir -p build/classes
"$JAVA_HOME/bin/javac" --release 21 -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/. build/classes/
"$JAVA_HOME/bin/jar" --create --file build/rpgbridge-loader.jar -C build/classes .
echo "built loader/build/rpgbridge-loader.jar"
