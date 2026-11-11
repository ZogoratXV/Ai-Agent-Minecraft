package dev.agentcraft.server.body;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Where each station of the base is (desk, library, podium...). Set in game with
 * {@code /agent base set <station>}; unset stations are placed around the base.
 */
public final class Stations {
    public static final List<String> NAMES =
            List.of("base", "desk", "library", "terminal", "testbench", "podium", "worksite", "lounge");

    /** Default offsets from the base, in blocks (x, z). */
    private static final Map<String, int[]> OFFSETS = Map.of(
            "desk", new int[] {3, 0},
            "library", new int[] {-3, 0},
            "terminal", new int[] {0, 3},
            "testbench", new int[] {0, -3},
            "podium", new int[] {4, 4},
            "worksite", new int[] {-4, 4},
            "lounge", new int[] {-4, -4});

    private final File file;
    private final Logger log;
    private final YamlConfiguration yaml;

    public Stations(File file, Logger log) {
        this.file = file;
        this.log = log;
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    public Location base() {
        Location set = yaml.getLocation("base");
        if (set != null && set.getWorld() != null) {
            return set;
        }
        World w = Bukkit.getWorlds().getFirst();
        return w.getSpawnLocation();
    }

    public Location get(String station) {
        Location set = yaml.getLocation(station);
        if (set != null && set.getWorld() != null) {
            return set;
        }
        int[] off = OFFSETS.get(station);
        Location b = base();
        return off == null ? b : b.clone().add(off[0], 0, off[1]);
    }

    public Optional<Location> explicit(String station) {
        return Optional.ofNullable(yaml.getLocation(station));
    }

    public void set(String station, Location at) {
        yaml.set(station, at.toBlockLocation().add(0.5, 0, 0.5));
        try {
            yaml.save(file);
        } catch (IOException e) {
            log.warning("could not save stations: " + e.getMessage());
        }
    }
}
