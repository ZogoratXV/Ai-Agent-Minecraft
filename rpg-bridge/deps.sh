#!/bin/bash
# Prepares everything needed to build and test RPG Bridge (nothing of this is committed):
#   deps/purpur/   paperclip-patched Purpur 26.2 (versions/, libraries/) - compile classpath
#   deps/loader/   Fabric Loader + its libraries                          - compile classpath, dev runs
#   deps/compile/  MixinExtras (nested in Fabric Loader)                  - compile classpath only
#   deps/tools/    gson for tools/MixinAudit.java
#   jars/          the client modpack from Modrinth (Fabric API, RPG Series, Better Combat, libraries)
# Needs: JDK 25 (RPGB_JAVA_HOME), curl, python3, network access to the hosts below.
set -euo pipefail
cd "$(dirname "$0")"
JAVA_HOME=${RPGB_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}
MC=26.2
PURPUR_BUILD=${PURPUR_BUILD:-2633}
LOADER=0.19.5
mkdir -p deps/purpur deps/loader deps/tools jars

# Purpur (api.purpurmc.org), patched by paperclip without starting the server.
if ! ls deps/purpur/versions/*/purpur-*.jar >/dev/null 2>&1; then
  curl -fsSLo deps/purpur/purpur.jar "https://api.purpurmc.org/v2/purpur/$MC/$PURPUR_BUILD/download"
  (cd deps/purpur && "$JAVA_HOME/bin/java" -Dpaperclip.patchonly=true -jar purpur.jar)
fi

# Fabric Loader (maven.fabricmc.net).
curl -fsSLo deps/loader/loader.json "https://maven.fabricmc.net/net/fabricmc/fabric-loader/$LOADER/fabric-loader-$LOADER.json"
{ echo "https://maven.fabricmc.net/net/fabricmc/fabric-loader/$LOADER/fabric-loader-$LOADER.jar"
  python3 - deps/loader/loader.json <<'EOF'
import json, sys
d = json.load(open(sys.argv[1]))
for l in d["libraries"]["common"] + d["libraries"].get("server", []):
    g, a, v = l["name"].split(":")[:3]
    print(f'{l["url"].rstrip("/")}/{g.replace(".", "/")}/{a}/{v}/{a}-{v}.jar')
EOF
} | while read -r url; do f="deps/loader/$(basename "$url")"; [ -s "$f" ] || curl -fsSLo "$f" "$url"; done

# MixinExtras is nested in Fabric Loader (META-INF/jars); extract it for the compile classpath only
# (at runtime the loader provides it, a second copy on the classpath would clash).
mkdir -p deps/compile
unzip -qjo "deps/loader/fabric-loader-$LOADER.jar" 'META-INF/jars/mixinextras-*.jar' -d deps/compile

[ -s deps/tools/gson.jar ] || curl -fsSLo deps/tools/gson.jar https://repo1.maven.org/maven2/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar

# The modpack (api.modrinth.com / cdn.modrinth.com): latest Fabric build for this Minecraft version.
for p in fabric-api spell-engine spell-power better-combat wizards paladins-and-priests rogues-and-warriors archers runes \
         cloth-config player-animation-library armor-model-api structure-pool-api ranged-weapon-api; do
  url=$(curl -fsS "https://api.modrinth.com/v2/project/$p/version?game_versions=%5B%22$MC%22%5D&loaders=%5B%22fabric%22%5D" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(next((f["url"] for f in d[0]["files"] if f.get("primary")), d[0]["files"][0]["url"]) if d else "")')
  [ -n "$url" ] || { echo "no $MC build for $p"; exit 1; }
  f="jars/$(basename "$url" | sed 's/%2B/+/g')"
  [ -s "$f" ] || curl -fsSLo "$f" "$url"
done
echo "dependencies ready"
