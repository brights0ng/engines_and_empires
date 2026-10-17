package dev.brights0ng.sparseores.chips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.brights0ng.sparseores.rich.RichOres;

/** The chips' rules, recipes and textures (moved with the chips from the core's {@code RefiningTest}, split seam S15). */
class ChipsTest {

    private static final Map<String, JsonObject> FILES = ChipRecipes.files();

    @Test
    void aCoalChipBurnsAQuarterAsLongAsCoal() {
        assertEquals(400, Chips.COAL_CHIP_BURN_TIME);
        assertEquals(4, Chips.CHIPS_PER_ITEM);
    }

    @Test
    void fourChipsCraftIntoTheWholeItem() {
        for (Chips.Chip chip : Chips.ALL) {
            String item = chip.item().substring("minecraft:".length());
            JsonObject recipe = file("sparse_ores/recipe/" + item + "_from_chips.json");
            assertEquals("sparse_ores:" + chip.name(), recipe.getAsJsonObject("key").getAsJsonObject("#").get("item").getAsString());
            assertEquals(4, String.join("", recipe.getAsJsonArray("pattern").asList().stream().map(JsonElement::getAsString).toList()).length());
            assertEquals(chip.item(), recipe.getAsJsonObject("result").get("id").getAsString());
        }
        assertEquals(400, file("neoforge/data_maps/item/furnace_fuels.json").getAsJsonObject("values")
                .getAsJsonObject("sparse_ores:coal_chip").get("burn_time").getAsInt());
    }

    /**
     * Sparse Ores' own chip rule (split step 4b, Bright's option A): coal, diamond and nether quartz drop one chip for
     * each whole item vanilla drops, everything else about the drop kept; every other ore keeps vanilla's drop.
     */
    @Test
    void coalDiamondAndQuartzOresDropOneChipAndNothingElseDoes() {
        List<String> chipOres = new ArrayList<>();
        for (RichOres.Spec spec : Chips.ores()) {
            chipOres.add(spec.oreId());
        }
        assertEquals(List.of("coal", "diamond", "nether_quartz"), chipOres);
        for (RichOres.Spec vanilla : RichOres.ALL) {
            RichOres.Spec chipped = Chips.chipped(vanilla);
            if (chipOres.contains(vanilla.oreId())) {
                Chips.Chip chip = Chips.forItem(vanilla.drop());
                assertEquals(chip.id(), chipped.drop(), vanilla.oreId());
                assertEquals(1, chipped.minDrop(), vanilla.oreId());
                assertEquals(1, chipped.maxDrop(), vanilla.oreId());
                assertEquals(5, chipped.richMinDrop(), vanilla.oreId());
                assertEquals(5, chipped.richMaxDrop(), vanilla.oreId());
                assertEquals(vanilla.withDrop(chip.id(), vanilla.minDrop(), vanilla.maxDrop()), chipped, vanilla.oreId());
            } else {
                assertEquals(vanilla, chipped, vanilla.oreId());
            }
        }
        assertEquals("sparse_ores:quartz_chip", Chips.forItem("minecraft:quartz").id());
        assertEquals("minecraft:diamond", Chips.forChipId("sparse_ores:diamond_chip").item());
        assertNull(Chips.forItem("minecraft:emerald"));
        assertNull(Chips.forChipId("minecraft:coal"));
    }

    /**
     * Sparse Ores replaces the loot tables of vanilla's five chip ore blocks: Silk Touch gives the block, otherwise one
     * chip with Fortune's ore formula, through the {@code sparse_ores:chip_drop} function the config switch works by.
     * Only those five: every other ordinary ore keeps vanilla's table. If this fails, run the Data run configuration.
     */
    @Test
    void theOrdinaryChipOresLootTablesDropOneChip() throws IOException {
        int tables = 0;
        for (RichOres.Spec spec : Chips.ores()) {
            Chips.Chip chip = Chips.forItem(spec.drop());
            for (RichOres.Variant variant : spec.variants()) {
                String block = variant.counterpart().substring("minecraft:".length());
                String loot = read("/data/minecraft/loot_table/blocks/" + block + ".json");
                assertTrue(loot.contains("\"name\": \"" + chip.id() + "\""), block + " drops its chip");
                assertFalse(loot.contains("\"name\": \"" + chip.item() + "\""), block + " drops no whole " + chip.item());
                assertTrue(loot.contains("\"name\": \"" + variant.counterpart() + "\""), block + " silk touch");
                assertTrue(loot.contains("minecraft:silk_touch"), block + " silk touch");
                assertTrue(loot.contains("\"count\": 1.0"), block + " count");
                assertTrue(loot.contains("minecraft:ore_drops"), block + " fortune formula");
                assertTrue(loot.contains("minecraft:explosion_decay"), block + " explosion decay");
                assertTrue(loot.contains("\"function\": \"sparse_ores:chip_drop\""), block + " goes through the config switch");
                tables++;
            }
        }
        assertEquals(5, tables);
        for (RichOres.Spec spec : RichOres.ALL) {
            if (Chips.forItem(spec.drop()) == null) {
                for (RichOres.Variant variant : spec.variants()) {
                    String path = "/data/" + variant.counterpart().replace(":", "/loot_table/blocks/") + ".json";
                    assertNull(ChipsTest.class.getResource(path), spec.oreId() + " keeps its own mod's table: " + path);
                }
            }
        }
    }

    /** What the data generator wrote is what the rules say today. If this fails, run the Data run configuration. */
    @Test
    void theGeneratedFilesAreUpToDate() throws IOException {
        for (Map.Entry<String, JsonObject> file : FILES.entrySet()) {
            String resource = "/data/" + file.getKey();
            try (InputStream in = ChipsTest.class.getResourceAsStream(resource)) {
                assertNotNull(in, "missing generated file " + resource + " (run the Data run configuration)");
                JsonElement written = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(file.getValue(), written, resource + " is stale (run the Data run configuration)");
            }
        }
    }

    // ---- the chip textures ----

    @Test
    void aChipIsAShardOfTheItemWithADarkRim() {
        BufferedImage item = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 14; y++) {
            for (int x = 2; x < 14; x++) {
                item.setRGB(x, y, 0xFFC08040);
            }
        }
        BufferedImage chip = ChipTextures.chip(item);
        int opaque = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int alpha = chip.getRGB(x, y) >>> 24;
                boolean inShape = ChipTextures.inShape(x - ChipTextures.LEFT, y - ChipTextures.TOP);
                assertEquals(inShape ? 255 : 0, alpha, "pixel " + x + "," + y);
                if (inShape) {
                    opaque++;
                    int expected = ChipTextures.onRim(x - ChipTextures.LEFT, y - ChipTextures.TOP)
                            ? ChipTextures.shade(0xFFC08040, ChipTextures.RIM_SHADE) : 0xFFC08040;
                    assertEquals(expected, chip.getRGB(x, y));
                }
            }
        }
        assertTrue(opaque > 20 && opaque < 60, "a chip is small: " + opaque + " pixels");
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = ChipsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource " + resource + " (run the Data run configuration)");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JsonObject file(String path) {
        JsonObject json = FILES.get(path);
        assertNotNull(json, "no generated file " + path);
        return json;
    }
}
