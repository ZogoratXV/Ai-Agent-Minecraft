#!/bin/bash
# Static check of every mod mixin against Purpur (see MixinAudit.java). Run after updating Purpur or a mod.
# Output: tools/build/mixin-audit.txt. Relevant lines: MISSING, NOPOINT [present in vanilla], ORDINAL,
# SHADOW, INVOKER, DESCRIPTOR. Vanilla server jar: deps/purpur/cache/mojang_<version>.jar (from paperclip).
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME=${RPGB_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}
mkdir -p tools/build
CP="$(ls deps/loader/asm*.jar | tr '\n' ':')deps/tools/gson.jar"
"$JAVA_HOME/bin/java" -Xmx3G -cp "$CP" tools/MixinAudit.java deps/purpur/cache/mojang_*.jar deps/purpur/versions/*/purpur-*.jar jars > tools/build/mixin-audit.txt
grep -E '^(MISSING|ORDINAL|SHADOW|INVOKER|DESCRIPTOR)|present in vanilla' tools/build/mixin-audit.txt || true
