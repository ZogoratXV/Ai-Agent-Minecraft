#!/usr/bin/env bash
# Avvio di Tessera su Linux. Uso: ./start.sh [opzioni del launcher]
# Richiede Java 25 (https://adoptium.net/). Puoi indicare un JDK specifico con JAVA=/percorso/bin/java.
set -euo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA:-java}"
if ! command -v "$JAVA" >/dev/null 2>&1; then
  echo "Java non trovato. Installa Java 25 (es. 'sudo apt install openjdk-25-jre-headless')." >&2
  exit 1
fi
VERSION=$("$JAVA" -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d. -f1)
if [ "${VERSION:-0}" -lt 25 ]; then
  echo "Serve Java 25 o superiore (trovato: ${VERSION})." >&2
  exit 1
fi

# Il launcher usa poca memoria: quella del server si imposta in tessera-launcher.properties (memory.max).
exec "$JAVA" -Xmx256M -jar tessera-launcher.jar "$@"
