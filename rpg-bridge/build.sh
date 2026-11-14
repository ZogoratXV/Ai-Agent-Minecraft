#!/bin/bash
# Builds RPG Bridge into dist/out: rpgbridge-loader.jar, rpgbridge-core.jar, start.sh, rpgbridge-log4j2.xml.
# Compile-time inputs (not committed): run ./deps.sh first.
set -euo pipefail
cd "$(dirname "$0")"
./loader/build.sh
./core/build.sh
mkdir -p dist/out
cp loader/build/rpgbridge-loader.jar core/build/rpgbridge-core.jar dist/start.sh loader/src/main/resources/rpgbridge-log4j2.xml dist/out/
chmod +x dist/out/start.sh
echo "dist/out ready:"; ls -la dist/out
