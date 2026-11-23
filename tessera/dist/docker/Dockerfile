# Immagine di Tessera. Build (dalla cartella tessera/):
#   ./gradlew build && docker build -f dist/docker/Dockerfile -t tessera:26.2 .
FROM eclipse-temurin:25-jre

RUN useradd -r -u 1000 -m -d /data minecraft
COPY tessera-launcher/build/libs/tessera-launcher-*.jar /opt/tessera/tessera-launcher.jar

ENV TESSERA_MEMORY_MIN=1G \
    TESSERA_MEMORY_MAX=4G \
    TESSERA_AUTO_RESTART=false \
    TESSERA_EULA=false

USER minecraft
WORKDIR /data
VOLUME ["/data"]
EXPOSE 25565/tcp

# Il launcher è PID 1: inoltra SIGTERM al server (docker stop salva i mondi).
ENTRYPOINT ["java", "-Xmx256M", "-jar", "/opt/tessera/tessera-launcher.jar", "--dir", "/data"]
