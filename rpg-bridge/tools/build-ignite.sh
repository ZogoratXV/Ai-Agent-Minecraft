#!/bin/bash
# Builds Ignite (MIT, https://github.com/vectrix-space/ignite) from source with plain javac,
# reproducing its shadow jar (dependencies merged, gson relocated). Output: tools/build/ignite.jar
set -euo pipefail
cd "$(dirname "$0")"
JAVA_HOME=${RPGB_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}
COMMIT=ffb5feaefec95a3416b31d0e87bdb9fc7c68c017
W=build/ignite
mkdir -p "$W/deps" "$W/classes"

if [ ! -d "$W/src/.git" ]; then
  git clone -q https://github.com/vectrix-space/ignite "$W/src"
fi
git -C "$W/src" fetch -q --depth 1 origin "$COMMIT" 2>/dev/null || true
git -C "$W/src" checkout -q -f "$COMMIT"
# Local fix: upstream formats the Blackboard key object instead of its value in the game jar path.
sed -i 's|version, PAPER_BRANCH, version)|version, Blackboard.raw(PaperGameLocator.PAPER_BRANCH), version)|' \
  "$W/src/launcher/src/main/java/space/vectrix/ignite/game/PaperGameLocator.java"

CENTRAL=https://repo1.maven.org/maven2
FABRIC=https://maven.fabricmc.net
fetch() { # repo group artifact version
  local f="$W/deps/$2-$3.jar"
  [ -f "$f" ] || curl -sSfLo "$f" "$1/${2//.//}/$2/$3/$2-$3.jar"
}
fetch_ga() { local f="$W/deps/$3-$4.jar"; [ -f "$f" ] || curl -sSfLo "$f" "$1/${2//.//}/$3/$4/$3-$4.jar"; }
for a in asm asm-analysis asm-commons asm-tree asm-util; do fetch_ga $CENTRAL org.ow2.asm $a 9.11; done
fetch_ga $CENTRAL org.tinylog tinylog-api 2.8.1
fetch_ga $CENTRAL org.tinylog tinylog-impl 2.8.1
fetch_ga $FABRIC net.fabricmc sponge-mixin 0.17.5+mixin.0.8.7
fetch_ga $CENTRAL io.github.llamalad7 mixinextras-common 0.5.5
fetch_ga $FABRIC net.fabricmc access-widener 2.1.0
fetch_ga $CENTRAL com.google.code.gson gson 2.14.0
fetch_ga $CENTRAL org.jetbrains annotations 26.1.0
# compile-only for sponge-mixin's optional references
fetch_ga $CENTRAL com.google.guava guava 33.4.8-jre

CP=$(find "$W/deps" -name '*.jar' | tr '\n' ':')
rm -rf "$W/classes" && mkdir -p "$W/classes"
"$JAVA_HOME/bin/javac" --release 17 -nowarn -Xlint:none -proc:none -cp "$CP" -d "$W/classes" \
  $(find "$W/src/api/src/main/java" "$W/src/launcher/src/main/java" -name '*.java')
cp -r "$W/src/launcher/src/main/resources/." "$W/classes/"

# Merge runtime dependencies (not annotations/guava), like shadowJar.
for j in "$W"/deps/*.jar; do
  case "$(basename "$j")" in annotations-*|guava-*) continue;; esac
  (cd "$W/classes" && unzip -qo "../../../$j" -x 'META-INF/MANIFEST.MF' 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'module-info.class' 'META-INF/versions/*/module-info.class')
done
# Ignite's own service files must win over the ones merged from dependencies.
cp -r "$W/src/launcher/src/main/resources/META-INF/services/." "$W/classes/META-INF/services/"
"$JAVA_HOME/bin/java" -cp "$CP" Relocate.java "$W/classes" com/google/gson/ space/vectrix/ignite/libs/gson/

cat > "$W/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Premain-Class: space.vectrix.ignite.agent.IgniteAgent
Agent-Class: space.vectrix.ignite.agent.IgniteAgent
Launcher-Agent-Class: space.vectrix.ignite.agent.IgniteAgent
Main-Class: space.vectrix.ignite.IgniteBootstrap
Multi-Release: true
Specification-Title: ignite
Specification-Version: 1.2
Implementation-Title: ignite-launcher
Implementation-Version: 1.2.2-SNAPSHOT+$COMMIT

Name: org/objectweb/asm/
Implementation-Version: 9.11
EOF
rm -f build/ignite.jar
"$JAVA_HOME/bin/jar" --create --file build/ignite.jar --manifest "$W/MANIFEST.MF" -C "$W/classes" .
echo "built tools/build/ignite.jar"
