package dev.tessera.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prepara la cartella del server senza passare da meta.fabricmc.net:
 * <ul>
 *   <li>server vanilla dal manifest ufficiale Mojang (con verifica SHA-1)</li>
 *   <li>Fabric Loader e le sue librerie da maven.fabricmc.net (profilo JSON del loader)</li>
 *   <li>Fabric API in mods/ e la mod Tessera (inclusa nel launcher) in mods/</li>
 * </ul>
 */
final class Installer {

    static final String MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";

    private final Path dir;
    private final LauncherConfig config;
    private final Downloader dl = new Downloader();

    Installer(Path dir, LauncherConfig config) {
        this.dir = dir;
        this.config = config;
    }

    record Installation(Path serverJar, List<Path> classpath) {
    }

    Installation install() throws IOException {
        for (String d : List.of("mods", "plugins", "config", "libraries")) {
            Files.createDirectories(dir.resolve(d));
        }
        Path serverJar = installMinecraft(config.get("minecraft.version"));
        List<Path> classpath = installLoader(config.get("fabric.loader.version"));
        installFabricApi(config.get("fabric.api.version"));
        installTessera();
        return new Installation(serverJar, classpath);
    }

    private Path installMinecraft(String version) throws IOException {
        Path target = dir.resolve("libraries/com/mojang/minecraft/" + version + "/minecraft-" + version + "-server.jar");
        Path marker = target.resolveSibling("server.sha1");
        if (Files.exists(target) && Files.exists(marker)
                && Files.readString(marker).strip().equalsIgnoreCase(Downloader.sha1(target))) {
            return target;
        }
        Map<String, Object> manifest = Json.obj(Json.parse(dl.text(MOJANG_MANIFEST)));
        String versionUrl = null;
        for (Object v : Json.arr(manifest.get("versions"))) {
            Map<String, Object> m = Json.obj(v);
            if (version.equals(m.get("id"))) versionUrl = (String) m.get("url");
        }
        if (versionUrl == null) throw new IOException("Versione Minecraft " + version + " non trovata nel manifest Mojang");
        Map<String, Object> versionJson = Json.obj(Json.parse(dl.text(versionUrl)));
        Map<String, Object> server = Json.obj(Json.obj(versionJson.get("downloads")).get("server"));
        String sha1 = (String) server.get("sha1");
        dl.file((String) server.get("url"), target, sha1);
        Files.writeString(marker, sha1);
        return target;
    }

    private List<Path> installLoader(String loaderVersion) throws IOException {
        List<Path> cp = new ArrayList<>();
        String coord = "net.fabricmc:fabric-loader:" + loaderVersion;
        cp.add(maven(FABRIC_MAVEN, coord, null));
        Path profileFile = dir.resolve("libraries/net/fabricmc/fabric-loader/" + loaderVersion + "/fabric-loader-" + loaderVersion + ".json");
        if (!Files.exists(profileFile)) {
            dl.file(FABRIC_MAVEN + path(coord, "json"), profileFile, null);
        }
        Map<String, Object> profile = Json.obj(Json.parse(Files.readString(profileFile)));
        Map<String, Object> libraries = Json.obj(profile.get("libraries"));
        for (String side : List.of("common", "server")) {
            for (Object o : Json.arr(libraries.get(side))) {
                Map<String, Object> lib = Json.obj(o);
                String url = (String) lib.getOrDefault("url", FABRIC_MAVEN);
                cp.add(maven(url.endsWith("/") ? url : url + "/", (String) lib.get("name"), (String) lib.get("sha1")));
            }
        }
        return cp;
    }

    private void installFabricApi(String version) throws IOException {
        if (version.isBlank() || version.equalsIgnoreCase("none")) return;
        String name = "fabric-api-" + version + ".jar";
        Path target = dir.resolve("mods").resolve(name);
        removeOld("fabric-api-", name);
        dl.file(FABRIC_MAVEN + path("net.fabricmc.fabric-api:fabric-api:" + version, "jar"), target, null);
    }

    private void installTessera() throws IOException {
        String version = Main.version();
        String name = "tessera-core-" + version + ".jar";
        removeOld("tessera-core-", name);
        try (InputStream in = Installer.class.getResourceAsStream("/tessera/tessera-core.jar")) {
            if (in == null) throw new IOException("tessera-core.jar non incluso nel launcher (build incompleta?)");
            Files.copy(in, dir.resolve("mods").resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void removeOld(String prefix, String keep) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir.resolve("mods"), prefix + "*.jar")) {
            for (Path p : stream) {
                if (!p.getFileName().toString().equals(keep)) {
                    System.out.println("[Tessera] Rimuovo la versione precedente " + p.getFileName());
                    Files.delete(p);
                }
            }
        }
    }

    private Path maven(String repo, String coordinate, String sha1) throws IOException {
        Path target = dir.resolve("libraries").resolve(path(coordinate, "jar"));
        dl.file(repo + path(coordinate, "jar"), target, sha1);
        return target;
    }

    /** gruppo:artefatto:versione -> gruppo/artefatto/versione/artefatto-versione.ext */
    static String path(String coordinate, String ext) {
        String[] p = coordinate.split(":");
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + "." + ext;
    }
}
