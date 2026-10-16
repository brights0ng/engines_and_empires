package dev.brights0ng.sparseores.chips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
            JsonObject recipe = file("engines_and_empires/recipe/" + item + "_from_chips.json");
            assertEquals("engines_and_empires:" + chip.name(), recipe.getAsJsonObject("key").getAsJsonObject("#").get("item").getAsString());
            assertEquals(4, String.join("", recipe.getAsJsonArray("pattern").asList().stream().map(JsonElement::getAsString).toList()).length());
            assertEquals(chip.item(), recipe.getAsJsonObject("result").get("id").getAsString());
        }
        assertEquals(400, file("neoforge/data_maps/item/furnace_fuels.json").getAsJsonObject("values")
                .getAsJsonObject("engines_and_empires:coal_chip").get("burn_time").getAsInt());
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

    private static JsonObject file(String path) {
        JsonObject json = FILES.get(path);
        assertNotNull(json, "no generated file " + path);
        return json;
    }
}
