package dev.agentcraft.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.server.build.BlueprintCompiler;
import dev.agentcraft.server.build.BlueprintCompiler.BlueprintException;
import dev.agentcraft.server.build.BlueprintCompiler.Planned;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlueprintCompilerTest {

    private static JsonObject house() throws Exception {
        Path p = Path.of(System.getProperty("agentcraft.protocolDir", "../protocol"))
                .resolve("../blueprints/examples/casa-medievale.json");
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    private static JsonObject bp(String modulesJson) {
        return JsonParser.parseString("{\"format\":\"agentcraft-blueprint\",\"version\":1,\"name\":\"t\","
                        + "\"size\":[5,3,4],\"palette\":{\"a\":\"minecraft:stone\",\"b\":\"minecraft:oak_planks\"},"
                        + "\"modules\":" + modulesJson + "}")
                .getAsJsonObject();
    }

    @Test
    void compilesTheMedievalHouseBottomUpInDependencyOrder() throws Exception {
        BlueprintCompiler.Result r = BlueprintCompiler.compile(house(), null, 0, 100_000);
        assertEquals(List.of("fondamenta", "pavimento", "muri", "tetto", "interni"), r.modules());
        assertTrue(r.blocks().size() > 250, "got " + r.blocks().size());
        for (int i = 1; i < r.blocks().size(); i++) {
            assertTrue(r.blocks().get(i - 1).y() <= r.blocks().get(i).y(), "not bottom-up");
        }
        // the floor replaced the foundation inside: later modules win at the same position
        assertTrue(r.blocks().stream()
                .anyMatch(
                        b -> b.x() == 4 && b.y() == 0 && b.z() == 4 && b.block().contains("spruce_planks")));
        assertTrue(r.blocks().stream().allMatch(b -> b.x() >= 0 && b.x() < 9 && b.z() >= 0 && b.z() < 9));
    }

    @Test
    void buildsOneModuleAlone() throws Exception {
        BlueprintCompiler.Result r = BlueprintCompiler.compile(house(), "fondamenta", 0, 100_000);
        assertEquals(List.of("fondamenta"), r.modules());
        assertEquals(81, r.blocks().size());
        assertThrows(BlueprintException.class, () -> BlueprintCompiler.compile(house(), "cantina", 0, 100_000));
    }

    @Test
    void rotationKeepsEveryBlockInsideTheRotatedBox() throws Exception {
        for (int rot : new int[] {90, 180, 270}) {
            BlueprintCompiler.Result r = BlueprintCompiler.compile(
                    bp("[{\"id\":\"m\",\"ops\":["
                            + "{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[4,2,3],\"block\":\"a\"}]}]"),
                    null,
                    rot,
                    1000);
            assertEquals(60, r.blocks().size());
            for (Planned b : r.blocks()) {
                assertTrue(b.x() >= 0 && b.x() < r.sizeX() && b.z() >= 0 && b.z() < r.sizeZ(), rot + ": " + b);
            }
        }
        // north (-z) turns east (+x) at 90 degrees, like Minecraft's CLOCKWISE_90
        int[] a = BlueprintCompiler.rotate(2, 1, 5, 4, 90);
        int[] b = BlueprintCompiler.rotate(2, 0, 5, 4, 90);
        assertEquals(1, b[0] - a[0]);
        assertArrayEquals(new int[] {3, 3}, BlueprintCompiler.rotate(1, 0, 5, 4, 180));
        assertArrayEquals(new int[] {0, 3}, BlueprintCompiler.rotate(1, 0, 5, 4, 270));
    }

    @Test
    void fillModes() {
        int solid = BlueprintCompiler.compile(
                        bp(
                                "[{\"id\":\"m\",\"ops\":[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[4,2,3],\"block\":\"a\",\"mode\":\"solid\"}]}]"),
                        null,
                        0,
                        1000)
                .blocks()
                .size();
        var hollow = BlueprintCompiler.compile(
                        bp(
                                "[{\"id\":\"m\",\"ops\":[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[4,2,3],\"block\":\"a\",\"mode\":\"hollow\"}]}]"),
                        null,
                        0,
                        1000)
                .blocks();
        int walls = BlueprintCompiler.compile(
                        bp(
                                "[{\"id\":\"m\",\"ops\":[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[4,2,3],\"block\":\"a\",\"mode\":\"walls\"}]}]"),
                        null,
                        0,
                        1000)
                .blocks()
                .size();
        assertEquals(60, solid);
        assertEquals(60, hollow.size());
        assertEquals(
                6,
                hollow.stream()
                        .filter(p -> p.block().equals(BlueprintCompiler.AIR))
                        .count()); // 3x1x2 inside
        assertEquals(3 * 14, walls); // perimeter of 5x4 = 14, three layers high
    }

    @Test
    void layersAndAir() {
        var r = BlueprintCompiler.compile(
                        bp("[{\"id\":\"m\",\"ops\":[{\"op\":\"layers\",\"origin\":[0,0,0],"
                                + "\"rows\":[[\"ab.\",\"_a\"]]}]}]"),
                        null,
                        0,
                        1000)
                .blocks();
        assertEquals(4, r.size());
        assertTrue(r.contains(new Planned(0, 0, 1, BlueprintCompiler.AIR)));
    }

    @Test
    void refusesBadBlueprints() {
        assertThrows(
                BlueprintException.class,
                () -> BlueprintCompiler.compile(
                        bp("[{\"id\":\"m\",\"ops\":[{\"op\":\"set\",\"at\":[9,0,0],\"block\":\"a\"}]}]"),
                        null,
                        0,
                        1000));
        assertThrows(
                BlueprintException.class,
                () -> BlueprintCompiler.compile(
                        bp("[{\"id\":\"m\",\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"z\"}]}]"),
                        null,
                        0,
                        1000));
        assertThrows(
                BlueprintException.class,
                () -> BlueprintCompiler.compile(
                        bp(
                                "[{\"id\":\"a\",\"dependsOn\":[\"b\"],\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"a\"}]},{\"id\":\"b\",\"dependsOn\":[\"a\"],\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"a\"}]}]"),
                        null,
                        0,
                        1000));
        assertThrows(
                BlueprintException.class,
                () -> BlueprintCompiler.compile(
                        bp(
                                "[{\"id\":\"m\",\"ops\":[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[4,2,3],\"block\":\"a\"}]}]"),
                        null,
                        0,
                        10));
        assertThrows(
                BlueprintException.class,
                () -> BlueprintCompiler.compile(
                        bp("[{\"id\":\"m\",\"ops\":[{\"op\":\"set\",\"at\":[0,0,0],\"block\":\"a\"}]}]"),
                        null,
                        45,
                        10));
    }
}
