/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.compat;

/** Holds Paper's registry bootstrap callback until the mods have registered their content. */
public final class DelayedFreeze {
	public static Runnable paperBootstrap;

	private DelayedFreeze() {
	}
}
