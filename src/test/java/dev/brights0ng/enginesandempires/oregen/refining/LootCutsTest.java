package dev.brights0ng.enginesandempires.oregen.refining;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import dev.brights0ng.sparseores.rich.RichOres;

/** The pack's loot cuts ({@link LootCuts}), generated from Sparse Ores' {@link RichOres} table (Sparse Ores' until split step 2f). */
class LootCutsTest {

    /** The ordinary blocks of the ores the pack cuts get a loot table generated from the same entry, once over. */
    @Test
    void cutOresReplaceTheOrdinaryBlocksLootTables() throws IOException {
        for (String oreId : RichOres.PACK_LOOT) {
            RichOres.Spec spec = RichOres.forOre(oreId);
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

    private static String read(String resource) throws IOException {
        try (InputStream in = LootCutsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource " + resource + " (run the Data run configuration)");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
