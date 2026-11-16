/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.bukkit;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.block.CraftBlockEntityState;

/**
 * BlockState di Bukkit generico (TileState) per i blocchi delle mod con block entity (es. il tavolo Spell Binding).
 * CraftBukkit non ha una classe per loro e la sua factory predefinita accetta solo blocchi senza block entity:
 * {@code Block.getState()} lanciava "Unexpected BlockState for ..." (WorldGuard, plugin di serrature, ...).
 */
public final class ModdedBlockEntityState extends CraftBlockEntityState<BlockEntity> {
	public ModdedBlockEntityState(World world, BlockEntity blockEntity) {
		super(world, blockEntity);
	}

	private ModdedBlockEntityState(ModdedBlockEntityState state, Location location) {
		super(state, location);
	}

	@Override
	public ModdedBlockEntityState copy() {
		return new ModdedBlockEntityState(this, null);
	}

	@Override
	public ModdedBlockEntityState copy(Location location) {
		return new ModdedBlockEntityState(this, location);
	}
}
