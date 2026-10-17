package dev.brights0ng.enginesandempires.oregen.refining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.sparseores.chips.Chips;
import dev.brights0ng.sparseores.rich.RichOres;

/**
 * The pack's loot cuts ({@link LootCuts}), generated from Sparse Ores' {@link RichOres} table (Sparse Ores' until split
 * step 2f): the ordinary blocks' tables since 2f, and the rich blocks' since step 4, when Sparse Ores' own rich ores went
 * to vanilla's drops five times over. Since step 4b only the group drops: the chip ores' tables are Sparse Ores' own.
 */
class LootCutsTest {

    /** The ordinary blocks of the ores the pack cuts get a loot table generated from the same entry, once over. */
    @Test
    void cutOresReplaceTheOrdinaryBlocksLootTables() throws IOException {
        for (Refining.Cut cut : Refining.CUTS) {
            RichOres.Spec spec = Refining.packSpec(cut.oreId());
            for (RichOres.Variant variant : spec.variants()) {
                String path = variant.counterpart().substring("minecraft:".length());
                String loot = read("/data/minecraft/loot_table/blocks/" + path + ".json");
                assertTrue(loot.contains("\"name\": \"" + spec.drop() + "\""), path + " drop item");
                assertTrue(loot.contains("\"name\": \"" + variant.counterpart() + "\""), path + " silk touch");
                if (spec.minDrop() == spec.maxDrop()) {
                    assertTrue(loot.contains("\"count\": " + spec.minDrop() + ".0"), path + " count");
                } else {
                    assertTrue(loot.contains("\"min\": " + spec.minDrop() + ".0"), path + " min count");
                    assertTrue(loot.contains("\"max\": " + spec.maxDrop() + ".0"), path + " max count");
                }
            }
        }
        assertNull(LootCutsTest.class.getResource("/data/minecraft/loot_table/blocks/iron_ore.json"),
                "iron keeps vanilla's loot table: it is cut in the furnace instead");
        assertNull(LootCutsTest.class.getResource("/data/minecraft/loot_table/blocks/emerald_ore.json"),
                "emerald is left alone");
    }

    /** The rich blocks of the cut ores get the core's own table too, the same entry five times over (split step 4). */
    @Test
    void cutOresReplaceTheRichBlocksLootTables() throws IOException {
        for (Refining.Cut cut : Refining.CUTS) {
            RichOres.Spec spec = Refining.packSpec(cut.oreId());
            for (RichOres.Variant variant : spec.variants()) {
                String loot = readCores("/data/sparse_ores/loot_table/blocks/" + variant.name() + ".json");
                String label = variant.name();
                assertTrue(loot.contains("\"name\": \"" + spec.drop() + "\""), label + " drop item");
                assertTrue(loot.contains("\"name\": \"sparse_ores:" + variant.name() + "\""), label + " silk touch");
                if (spec.richMinDrop() == spec.richMaxDrop()) {
                    assertTrue(loot.contains("\"count\": " + spec.richMinDrop() + ".0"), label + " count");
                } else {
                    assertTrue(loot.contains("\"min\": " + spec.richMinDrop() + ".0"), label + " min count");
                    assertTrue(loot.contains("\"max\": " + spec.richMaxDrop() + ".0"), label + " max count");
                }
            }
        }
    }

    /**
     * The pack's amounts, as Sparse Ores' {@code RichOresTest} pinned them before split step 4 moved them here: the cut
     * ores' ordinary blocks drop a chip or about a quarter of vanilla, and their rich blocks five times that.
     */
    @Test
    void thePacksCutAmountsAreUnchanged() {
        assertEquals(5, Refining.packSpec("lapis").richMinDrop());     // the pack's lapis ore drops 1-2
        assertEquals(10, Refining.packSpec("lapis").richMaxDrop());
        assertEquals(5, Refining.packSpec("redstone").richMinDrop());  // the pack's redstone ore drops 1
        assertEquals(5, Refining.packSpec("redstone").richMaxDrop());
        assertEquals(5, Refining.packSpec("nether_gold").richMinDrop()); // the pack's nether gold ore drops 1 nugget
        assertEquals(5, Refining.packSpec("nether_gold").richMaxDrop());
        assertEquals("sparse_ores:coal_chip", Refining.packSpec("coal").drop());
        assertEquals("sparse_ores:diamond_chip", Refining.packSpec("diamond").drop());
        assertEquals("sparse_ores:quartz_chip", Refining.packSpec("nether_quartz").drop());
        // The ores the pack leaves alone keep Sparse Ores' entry, which is vanilla's.
        for (String kept : List.of("iron", "copper", "zinc", "gold", "emerald")) {
            assertNull(Refining.cutOf(kept), kept);
            assertEquals(RichOres.forOre(kept), Refining.packSpec(kept), kept);
        }
        assertEquals("minecraft:emerald", Refining.packSpec("emerald").drop());
    }

    /**
     * The chip ores (coal, diamond, nether quartz) drop chips by Sparse Ores' own rule since split step 4b, so the core
     * cuts none of them and writes none of their tables: the pack's drops of them are Sparse Ores' tables, which the core's
     * game test {@code PackOreDropsGameTests} pins at the amounts the core's tables had.
     */
    @Test
    void theChipOresTablesAreSparseOresNotTheCores() throws IOException {
        for (RichOres.Spec spec : Chips.ores()) {
            assertNull(Refining.cutOf(spec.oreId()), spec.oreId() + " is not one of the core's cuts");
            for (RichOres.Variant variant : spec.variants()) {
                for (String resource : List.of(
                        "data/minecraft/loot_table/blocks/" + variant.counterpart().substring("minecraft:".length()) + ".json",
                        "data/sparse_ores/loot_table/blocks/" + variant.name() + ".json")) {
                    assertNotNull(LootCutsTest.class.getClassLoader().getResource(resource), "Sparse Ores has " + resource);
                    assertFalse(coresOwn(resource), "the core still writes " + resource + " (run the Data run configuration)");
                }
            }
        }
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = LootCutsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource " + resource + " (run the Data run configuration)");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Whether the core's resources folder has a copy of this resource (a file, not in a jar). */
    private static boolean coresOwn(String resource) throws IOException {
        for (URL url : Collections.list(LootCutsTest.class.getClassLoader().getResources(resource))) {
            if (url.getProtocol().equals("file")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The core's own copy of a resource Sparse Ores' jar has too: the one in the core's resources folder, not in a jar
     * (in the game the core's wins, as it loads after Sparse Ores).
     */
    private static String readCores(String resource) throws IOException {
        for (URL url : Collections.list(LootCutsTest.class.getClassLoader().getResources(resource.substring(1)))) {
            if (url.getProtocol().equals("file")) {
                try (InputStream in = url.openStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("the core has no " + resource + " (run the Data run configuration)");
    }
}
