package dev.tessera.bridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;

/** Corrispondenza Material Bukkit <-> blocchi vanilla tramite l'identificatore (es. minecraft:stone). */
public final class Materials {

    public static Material fromBlock(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        Material material = Material.matchMaterial(id.toString());
        return material != null ? material : Material.AIR;
    }

    public static Material fromState(BlockState state) {
        return fromBlock(state.getBlock());
    }

    public static Block toBlock(Material material) {
        return BuiltInRegistries.BLOCK.getOptional(Identifier.parse(material.getKey().toString()))
                .orElseThrow(() -> new IllegalArgumentException(material + " non è un blocco"));
    }

    private Materials() {
    }
}
