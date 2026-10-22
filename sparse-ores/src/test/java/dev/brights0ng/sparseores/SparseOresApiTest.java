// Written by Gemini.
package dev.brights0ng.sparseores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.brights0ng.sparseores.shape.SeamShape;
import dev.brights0ng.sparseores.worldgen.OreBlocks;
import dev.brights0ng.sparseores.worldgen.OreMaps;

class SparseOresApiTest {

    @AfterEach
    void tearDown() {
        OreTypes.resetForTesting();
        OreBlocks.resetForTesting();
        OreMaps.clearCache();
    }

    @Test
    void registerOreAddsOreToRegistryAndPalette() {
        String testId = "api_test_ore";
        OreLayer layer = new OreLayer(testId, 200);
        SizeProfile size = new SizeProfile(300, 0.35, 100, 400);
        DepthProfile depth = new DepthProfile(-32, 64);
        OreType custom = new OreType(layer, Realm.OVERWORLD, SeamShape.COAL, size, depth, 0.20);

        SparseOresApi.registerOre(custom, "minecraft:stone", "minecraft:deepslate");

        OreType retrieved = OreTypes.byId(testId);
        assertNotNull(retrieved);
        assertEquals(testId, retrieved.id());
        assertEquals(200, retrieved.layer().scale());
        assertTrue(OreTypes.ALL.contains(retrieved));
        assertTrue(OreTypes.forRealm(Realm.OVERWORLD).contains(retrieved));

        assertThrows(IllegalStateException.class, () ->
                SparseOresApi.registerOre(custom, "minecraft:stone", "minecraft:deepslate"));
    }
}
