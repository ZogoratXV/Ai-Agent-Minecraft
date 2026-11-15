/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.loader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Writes the Java argument file used by start.sh / run.bat:
 * {@code java -cp rpgbridge-loader.jar dev.rpgbridge.loader.LaunchArgs <purpur.jar> <loader libraries dir> <out file>}.
 * <p>
 * The class path is Fabric Loader's libraries, this jar, then exactly the libraries listed in Purpur's
 * {@code META-INF/libraries.list} (never everything under {@code libraries/}: Paper's plugin library loader
 * downloads plugin libraries there too, e.g. an old Netty for ItemsAdder, which would clash with the server's).
 * Paper's ASM is left out (Fabric Loader brings a newer one). One jar per line ("\" continues a quoted
 * argument), so the file has no length limit, unlike a Windows command line.
 */
public final class LaunchArgs {
	private LaunchArgs() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 3) {
			System.err.println("usage: LaunchArgs <purpur.jar> <loader libraries dir> <out file>");
			System.exit(2);
		}
		File self = new File(LaunchArgs.class.getProtectionDomain().getCodeSource().getLocation().getPath());
		List<String> classPath = new ArrayList<>();
		File[] loaderLibs = new File(args[1]).listFiles((dir, name) -> name.endsWith(".jar"));
		if (loaderLibs == null || loaderLibs.length == 0) fail("no Fabric Loader libraries in " + args[1]);
		java.util.Arrays.sort(loaderLibs);
		for (File f : loaderLibs) classPath.add(f.getPath());
		classPath.add(self.getPath());
		String gameJar = null;
		try (ZipFile purpur = new ZipFile(args[0])) {
			for (String[] line : list(purpur, "META-INF/libraries.list")) {
				String path = "libraries/" + line[2];
				if (line[2].startsWith("org/ow2/asm/")) continue;
				if (!new File(path).isFile()) fail("missing " + path + " (delete versions/ and start again to let paperclip recreate it)");
				classPath.add(path);
			}
			for (String[] line : list(purpur, "META-INF/versions.list")) gameJar = "versions/" + line[2];
		}
		if (gameJar == null || !new File(gameJar).isFile()) fail("missing the patched server jar in versions/");
		StringBuilder out = new StringBuilder();
		out.append("-Dfabric.gameJarPath=").append(quote(gameJar)).append('\n');
		out.append("-cp \"\\\n");
		for (String entry : classPath) out.append(entry.replace('\\', '/')).append(File.pathSeparatorChar).append("\\\n");
		out.append("\"\n");
		Path file = Path.of(args[2]);
		if (file.getParent() != null) Files.createDirectories(file.getParent());
		Files.writeString(file, out, StandardCharsets.UTF_8);
	}

	private static List<String[]> list(ZipFile jar, String name) throws IOException {
		ZipEntry entry = jar.getEntry(name);
		if (entry == null) fail(jar.getName() + " has no " + name + " (is it a Purpur/Paper paperclip jar?)");
		List<String[]> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
			for (String line; (line = reader.readLine()) != null; ) {
				String[] parts = line.split("\t");
				if (parts.length == 3) lines.add(parts);
			}
		}
		return lines;
	}

	private static String quote(String s) {
		return "\"" + s.replace('\\', '/') + "\"";
	}

	private static void fail(String message) {
		System.err.println("[RPG Bridge] " + message);
		System.exit(1);
	}
}
