/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.compat;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Decides what happens when a Fabric/mod mixin cannot be applied to Purpur's patched classes.
 *
 * <p>Known incompatibilities are listed in {@code rpgbridge-mixin-compat.properties} as
 * {@code <mixin class name>=<reason>}; each one is either irrelevant on a dedicated server or
 * replaced by an RPG Bridge mixin. Those are downgraded to a warning. Anything else stays a hard
 * error, unless {@code -Drpgbridge.mixinSurvey=true} is set (used to list all failures at once:
 * they are appended to {@code rpgbridge-mixin-report.txt}).
 */
public final class MixinCompat implements IMixinErrorHandler {
	private static final boolean SURVEY = Boolean.getBoolean("rpgbridge.mixinSurvey");
	private static final Path REPORT = Path.of("rpgbridge-mixin-report.txt");
	private static final Map<String, String> KNOWN = load();

	@Override
	public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
		return decide("prepare", mixin, th, action);
	}

	@Override
	public ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin, ErrorAction action) {
		return decide("apply to " + targetClassName, mixin, th, action);
	}

	private static ErrorAction decide(String phase, IMixinInfo mixin, Throwable th, ErrorAction action) {
		String name = mixin.getClassName();
		String reason = KNOWN.get(name);
		if (reason != null) {
			System.out.println("[RPGBridge] Skipping incompatible mixin " + name + " (" + reason + ")");
			return ErrorAction.NONE;
		}
		if (SURVEY) {
			String line = name + "\t" + mixin.getConfig().getName() + "\t" + phase + "\t" + rootMessage(th) + "\n";
			try {
				Files.writeString(REPORT, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException ignored) {
			}
			return ErrorAction.WARN;
		}
		return action;
	}

	private static String rootMessage(Throwable th) {
		Throwable t = th;
		while (t.getCause() != null && t.getCause() != t) t = t.getCause();
		String msg = String.valueOf(t.getMessage()).replace('\n', ' ').replace('\t', ' ');
		return t.getClass().getSimpleName() + ": " + (msg.length() > 400 ? msg.substring(0, 400) + "..." : msg);
	}

	private static Map<String, String> load() {
		return MixinBlocker.blocked();
	}
}
