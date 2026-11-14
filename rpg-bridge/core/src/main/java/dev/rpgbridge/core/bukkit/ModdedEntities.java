/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.bukkit;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftAbstractArrow;
import org.bukkit.craftbukkit.entity.CraftAnimals;
import org.bukkit.craftbukkit.entity.CraftCreature;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftGolem;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftMob;
import org.bukkit.craftbukkit.entity.CraftMonster;
import org.bukkit.craftbukkit.entity.CraftProjectile;
import org.bukkit.craftbukkit.entity.CraftTameableAnimal;
import org.bukkit.craftbukkit.entity.CraftWaterMob;

/**
 * Bukkit wrappers for entities added by mods. CraftBukkit only knows the vanilla entity types; a mod
 * entity gets the most specific CraftBukkit class matching its vanilla superclass, so plugins see it as
 * a LivingEntity/Mob/Monster/Projectile/... with type {@code EntityType.UNKNOWN}.
 */
public final class ModdedEntities {
	private ModdedEntities() {
	}

	public static CraftEntity wrap(CraftServer server, Entity entity) {
		if (entity instanceof TamableAnimal e) return new CraftTameableAnimal(server, e);
		if (entity instanceof Animal e) return new CraftAnimals(server, e);
		if (entity instanceof Monster e) return new CraftMonster(server, e);
		if (entity instanceof AbstractGolem e) return new CraftGolem(server, e);
		if (entity instanceof WaterAnimal e) return new CraftWaterMob(server, e);
		if (entity instanceof PathfinderMob e) return new CraftCreature(server, e);
		if (entity instanceof Mob e) return new ModdedMob(server, e);
		if (entity instanceof LivingEntity e) return new CraftLivingEntity(server, e);
		if (entity instanceof AbstractArrow e) return new ModdedArrow(server, e);
		if (entity instanceof Projectile e) return new ModdedProjectile(server, e);
		return new ModdedEntity(server, entity);
	}

	public static final class ModdedMob extends CraftMob {
		public ModdedMob(CraftServer server, Mob entity) {
			super(server, entity);
		}
	}

	public static final class ModdedArrow extends CraftAbstractArrow {
		public ModdedArrow(CraftServer server, AbstractArrow entity) {
			super(server, entity);
		}
	}

	public static final class ModdedProjectile extends CraftProjectile {
		public ModdedProjectile(CraftServer server, Projectile entity) {
			super(server, entity);
		}
	}

	public static final class ModdedEntity extends CraftEntity {
		public ModdedEntity(CraftServer server, Entity entity) {
			super(server, entity);
		}
	}
}
