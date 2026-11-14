/*
 * RPG Bridge - Fabric Loader game provider for Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 * Extends Fabric Loader's MinecraftGameProvider (Apache-2.0).
 */
package dev.rpgbridge.loader;

import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * Lets Fabric Loader run a (paperclip-patched) Purpur server jar.
 *
 * <p>Purpur is the vanilla dedicated server plus Paper/Purpur patches, so everything vanilla
 * Fabric needs is there: {@code net.minecraft.server.Main} still bootstraps the registries and
 * reads {@code server.properties}, which is where Fabric's {@code EntrypointPatch} initialises
 * the mods. The only difference is the process entry point: Purpur starts from
 * {@code org.bukkit.craftbukkit.Main#main(String[])}, which parses the Bukkit options and then
 * calls {@code net.minecraft.server.Main#main(OptionSet)}.
 */
public final class PurpurGameProvider extends MinecraftGameProvider {
	private static final String BUKKIT_MAIN = "org.bukkit.craftbukkit.Main";
	private String[] launchArgs = new String[0];
	private boolean purpur;

	public PurpurGameProvider() {
		// Replace Fabric's EntrypointPatch (which needs main(String[])) with the Paper-aware one.
		try {
			java.lang.reflect.Field f = MinecraftGameProvider.class.getDeclaredField("transformer");
			f.setAccessible(true);
			f.set(this, new net.fabricmc.loader.impl.game.patch.GameTransformer(
					new PurpurEntrypointPatch(), new net.fabricmc.loader.impl.game.minecraft.patch.BrandingPatch()));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unsupported Fabric Loader version", e);
		}
	}

	@Override
	public String getGameName() {
		return purpur ? "Minecraft (Purpur)" : super.getGameName();
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	@Override
	public boolean locateGame(FabricLauncher launcher, String[] args) {
		this.launchArgs = args.clone();
		if (!super.locateGame(launcher, args)) return false;
		for (Path jar : gameJars()) {
			if (hasClass(jar, BUKKIT_MAIN)) {
				purpur = true;
				break;
			}
		}
		if (!purpur) {
			Log.warn(LogCategory.GAME_PROVIDER, "Game jar has no %s: launching as vanilla", BUKKIT_MAIN);
		}
		return true;
	}

	@Override
	public void launch(ClassLoader loader) {
		if (!purpur) {
			super.launch(loader);
			return;
		}
		MethodHandle invoker;
		try {
			Class<?> c = loader.loadClass(BUKKIT_MAIN);
			invoker = MethodHandles.lookup().findStatic(c, "main", MethodType.methodType(void.class, String[].class));
		} catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
			throw FormattedException.ofLocalized("exception.minecraft.invokeFailure", e);
		}
		try {
			invoker.invokeExact(launchArgs);
		} catch (Throwable t) {
			throw FormattedException.ofLocalized("exception.minecraft.generic", t);
		}
	}

	@SuppressWarnings("unchecked")
	private java.util.List<Path> gameJars() {
		try {
			java.lang.reflect.Field f = MinecraftGameProvider.class.getDeclaredField("gameJars");
			f.setAccessible(true);
			return (java.util.List<Path>) f.get(this);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean hasClass(Path jar, String className) {
		if (!Files.isRegularFile(jar)) return Files.exists(jar.resolve(className.replace('.', '/') + ".class"));
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return zip.getEntry(className.replace('.', '/') + ".class") != null;
		} catch (Exception e) {
			return false;
		}
	}
}
