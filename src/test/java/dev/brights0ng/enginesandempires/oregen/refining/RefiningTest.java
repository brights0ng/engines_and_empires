package dev.brights0ng.enginesandempires.oregen.refining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.brights0ng.enginesandempires.oregen.rich.RichOres;

class RefiningTest {

    private static final Map<String, JsonObject> FILES = RefiningRecipes.files();

    // ---- the rules ----

    @Test
    void createLinesBeatTheFurnaceByTheSameMarginForEveryOre() {
        assertEquals(2.1875, Refining.crushingLead(false), 1e-9);  // 1.75 crushed x 2.5 nuggets / 2
        assertEquals(2.8125, Refining.crushingLead(true), 1e-9);   // 2.25 crushed x 2.5 nuggets / 2
        assertEquals(2.5, Refining.washedNuggets(), 1e-9);
        assertTrue(Refining.washedNuggets() > Refining.NUGGETS_PER_RAW, "washing must beat the furnace");
    }

    @Test
    void amountsSplitIntoACountAndAChance() {
        assertEquals(new Refining.Amount(2, 0.2), Refining.Amount.of(2.1875));
        assertEquals(new Refining.Amount(2, 0.8), Refining.Amount.of(2.8125));
        assertEquals(new Refining.Amount(3, 0.3), Refining.Amount.of(1.5 * 2.1875));
        assertEquals(new Refining.Amount(5, 0), Refining.Amount.of(4.99));
        assertEquals(new Refining.Amount(0, 0.75), Refining.Amount.of(0.75));
    }

    @Test
    void aSilkTouchedOreSmeltsIntoWhatMiningItGives() {
        assertEquals(2, Refining.ore("iron").furnaceCount());      // 1 raw iron = 2 nuggets
        assertEquals(2, Refining.ore("gold").furnaceCount());
        assertEquals(2, Refining.ore("zinc").furnaceCount());
        assertEquals(7, Refining.ore("copper").furnaceCount());    // 3.5 raw copper = 7 nuggets
        assertEquals(1, Refining.ore("coal").furnaceCount());      // 1 chip
        assertEquals(1, Refining.ore("diamond").furnaceCount());
        assertEquals(1, Refining.ore("nether_quartz").furnaceCount());
        assertEquals(1, Refining.ore("nether_gold").furnaceCount()); // 1 nugget
        assertEquals(1, Refining.ore("emerald").furnaceCount());
    }

    @Test
    void everyOreWithRichBlocksHasARefiningEntry() {
        Set<String> refined = new HashSet<>();
        for (Refining.Ore ore : Refining.ORES) {
            refined.add(ore.oreId());
        }
        Set<String> rich = new HashSet<>();
        for (RichOres.Spec spec : RichOres.ALL) {
            rich.add(spec.oreId());
        }
        assertEquals(rich, refined);
    }

    @Test
    void otherSourcesAreCutLikeRawOre() {
        assertEquals(0.03, Refining.otherSourceChance(0.125), 1e-9);  // gravel's iron nugget
        assertEquals(0.085, Refining.otherSourceChance(0.375), 1e-9); // red sand's gold nuggets
        assertEquals(400, Refining.COAL_CHIP_BURN_TIME);
    }

    // ---- the recipes ----

    @Test
    void rawAndCrushedOreSmeltIntoTwoNuggets() {
        assertCooking("minecraft/recipe/iron_ingot_from_smelting_raw_iron.json", "minecraft:iron_nugget", 2);
        assertCooking("minecraft/recipe/copper_ingot_from_blasting_raw_copper.json", "create:copper_nugget", 2);
        assertCooking("minecraft/recipe/gold_ingot_from_smelting_raw_gold.json", "minecraft:gold_nugget", 2);
        assertCooking("create/recipe/smelting/zinc_ingot_from_raw_ore.json", "create:zinc_nugget", 2);
        assertCooking("create/recipe/blasting/iron_ingot_from_crushed.json", "minecraft:iron_nugget", 2);
        assertCooking("create/recipe/smelting/zinc_ingot_from_crushed.json", "create:zinc_nugget", 2);
    }

    @Test
    void silkTouchedOreBlocksSmeltLikeTheirDrops() {
        assertCooking("minecraft/recipe/iron_ingot_from_smelting_deepslate_iron_ore.json", "minecraft:iron_nugget", 2);
        assertCooking("minecraft/recipe/copper_ingot_from_smelting_copper_ore.json", "create:copper_nugget", 7);
        assertCooking("minecraft/recipe/coal_from_blasting_coal_ore.json", "engines_and_empires:coal_chip", 1);
        assertCooking("minecraft/recipe/diamond_from_smelting_deepslate_diamond_ore.json", "engines_and_empires:diamond_chip", 1);
        assertCooking("minecraft/recipe/quartz.json", "engines_and_empires:quartz_chip", 1);
        assertCooking("minecraft/recipe/quartz_from_blasting.json", "engines_and_empires:quartz_chip", 1);
        assertCooking("minecraft/recipe/gold_ingot_from_smelting_nether_gold_ore.json", "minecraft:gold_nugget", 1);
        // Create's zinc ore recipe no longer matches the rich zinc ores through #c:ores/zinc.
        JsonObject zinc = file("create/recipe/smelting/zinc_ingot_from_ore.json");
        assertFalse(zinc.toString().contains("\"tag\""), "the zinc ore recipe should name the ordinary blocks");
        assertEquals(2, zinc.getAsJsonArray("ingredient").size());
    }

    @Test
    void washingCrushedOreGivesTwoNuggetsAndAChanceOfAThird() {
        for (Refining.Metal metal : Refining.METALS) {
            JsonArray results = file("create/recipe/splashing/crushed_raw_" + metal.name() + ".json").getAsJsonArray("results");
            assertEquals(Refining.WASHED_NUGGETS + Refining.WASH_BONUS_CHANCE, mean(results, metal.nugget()), 1e-9, metal.name());
            assertEquals(metal.byproductChance(), mean(results, metal.byproduct()), 1e-9, metal.name() + " byproduct");
        }
    }

    @Test
    void crushingChipAndGroupOresKeepsTheCreateLead() {
        assertEquals(2.2, mean(crushing("create/recipe/crushing/coal_ore.json"), "engines_and_empires:coal_chip"), 1e-9);
        assertEquals(2.8, mean(crushing("create/recipe/crushing/deepslate_diamond_ore.json"), "engines_and_empires:diamond_chip"), 1e-9);
        assertEquals(2.8, mean(crushing("create/recipe/crushing/nether_quartz_ore.json"), "engines_and_empires:quartz_chip"), 1e-9);
        assertEquals(2.2, mean(crushing("create/recipe/crushing/redstone_ore.json"), "minecraft:redstone"), 1e-9);
        assertEquals(3.3, mean(crushing("create/recipe/crushing/lapis_ore.json"), "minecraft:lapis_lazuli"), 1e-9);
        assertEquals(4.2, mean(crushing("create/recipe/crushing/deepslate_lapis_ore.json"), "minecraft:lapis_lazuli"), 1e-9);
        assertEquals(2.8, mean(crushing("create/recipe/crushing/nether_gold_ore.json"), "minecraft:gold_nugget"), 1e-9);
        // Metals keep Create's own ore crushing.
        assertFalse(FILES.containsKey("create/recipe/crushing/iron_ore.json"));
        assertFalse(FILES.containsKey("create/recipe/crushing/emerald_ore.json"));
    }

    @Test
    void richBlocksRefineIntoFiveTimesTheirOrdinaryBlock() {
        for (Refining.Ore ore : Refining.ORES) {
            for (RichOres.Variant variant : ore.spec().variants()) {
                boolean deep = variant.ground() != RichOres.Ground.STONE;
                String name = variant.name();
                assertCooking("engines_and_empires/recipe/smelting/" + name + ".json", ore.furnaceItem(), ore.furnaceCount() * 5);
                assertCooking("engines_and_empires/recipe/blasting/" + name + ".json", ore.furnaceItem(), ore.furnaceCount() * 5);
                JsonArray crushed = crushing("engines_and_empires/recipe/crushing/" + name + ".json");
                assertEquals(Refining.Amount.of(ore.crushedMean(deep) * 5).mean(), mean(crushed, ore.crushedItem()), 1e-9, name);
                assertEquals(ore.crushedMean(deep) * 5, mean(crushed, ore.crushedItem()), 0.05, name);
            }
        }
    }

    @Test
    void fourChipsCraftIntoTheWholeItem() {
        for (Refining.Chip chip : Refining.CHIPS) {
            String item = chip.item().substring("minecraft:".length());
            JsonObject recipe = file("engines_and_empires/recipe/" + item + "_from_chips.json");
            assertEquals("engines_and_empires:" + chip.name(), recipe.getAsJsonObject("key").getAsJsonObject("#").get("item").getAsString());
            assertEquals(4, String.join("", recipe.getAsJsonArray("pattern").asList().stream().map(JsonElement::getAsString).toList()).length());
            assertEquals(chip.item(), recipe.getAsJsonObject("result").get("id").getAsString());
        }
        assertEquals(400, file("neoforge/data_maps/item/furnace_fuels.json").getAsJsonObject("values")
                .getAsJsonObject("engines_and_empires:coal_chip").get("burn_time").getAsInt());
    }

    @Test
    void createResultsNeverExceedWhatCrushingAllows() {
        for (Map.Entry<String, JsonObject> file : FILES.entrySet()) {
            JsonObject json = file.getValue();
            if (json.has("type") && json.get("type").getAsString().equals("create:crushing")) {
                assertTrue(json.getAsJsonArray("results").size() <= 7, file.getKey());
            }
        }
    }

    /** What the data generator wrote is what the rules say today. If this fails, run the Data run configuration. */
    @Test
    void theGeneratedFilesAreUpToDate() throws IOException {
        for (Map.Entry<String, JsonObject> file : FILES.entrySet()) {
            String resource = "/data/" + file.getKey();
            try (InputStream in = RefiningTest.class.getResourceAsStream(resource)) {
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

    // ---- helpers ----

    private static JsonObject file(String path) {
        JsonObject json = FILES.get(path);
        assertNotNull(json, "no generated file " + path);
        return json;
    }

    private static JsonArray crushing(String path) {
        JsonObject json = file(path);
        assertEquals("create:crushing", json.get("type").getAsString(), path);
        return json.getAsJsonArray("results");
    }

    private static void assertCooking(String path, String item, int count) {
        JsonObject result = file(path).getAsJsonObject("result");
        assertEquals(item, result.get("id").getAsString(), path);
        assertEquals(count, result.get("count").getAsInt(), path);
    }

    /** The average number of an item a Create recipe gives. */
    private static double mean(JsonArray results, String item) {
        double total = 0;
        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            if (result.get("id").getAsString().equals(item)) {
                int count = result.has("count") ? result.get("count").getAsInt() : 1;
                double chance = result.has("chance") ? result.get("chance").getAsDouble() : 1;
                total += count * chance;
            }
        }
        return total;
    }
}
