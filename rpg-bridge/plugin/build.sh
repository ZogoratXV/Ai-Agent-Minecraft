#!/bin/bash
# Compiles the plugin with plain javac against the Purpur server jar extracted by paperclip
# (26.x is unobfuscated: no paperweight/remapping needed). Needs ../run (see CLAUDE.md).
set -euo pipefail
cd "$(dirname "$0")"
JAVA_HOME=${RPGB_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}
RUN=../run
CP=$(find "$RUN/versions" "$RUN/libraries" -name '*.jar' | tr '\n' ':')
rm -rf build/classes && mkdir -p build/classes
"$JAVA_HOME/bin/javac" --release 25 -Xlint:-unchecked -Xlint:-rawtypes -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/* build/classes/
(cd build/classes && "$JAVA_HOME/bin/jar" --create --file ../RPGBridge.jar .)
echo "built build/RPGBridge.jar"
