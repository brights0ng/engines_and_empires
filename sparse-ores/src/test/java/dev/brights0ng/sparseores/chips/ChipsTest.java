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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.brights0ng.sparseores.rich.RichOreRecipes;
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

    /**
     * A silk-touched chip ore smelts and blasts into what mining it drops (split step 4c): one chip under vanilla's
     * recipe id while chip drops are on, vanilla's own recipe (one whole item) under {@code sparse_ores:whole_items/}
     * while they are off.
     */
    @Test
    void chipOresSmeltIntoWhatMiningThemDrops() {
        int pairs = 0;
        for (RichOres.Spec spec : Chips.ores()) {
            Chips.Chip chip = Chips.forItem(spec.drop());
            for (RichOres.Variant variant : spec.variants()) {
                for (boolean blasting : new boolean[] {false, true}) {
                    String vanilla = ChipRecipes.vanillaFurnacePath(spec.oreId(),
                            RichOreRecipes.furnace(spec.oreId()), variant.counterpart(), blasting);
                    JsonObject chipRecipe = file("minecraft/recipe/" + vanilla + ".json");
                    JsonObject whole = file("sparse_ores/recipe/whole_items/" + vanilla + ".json");
                    assertEquals("[{\"type\":\"sparse_ores:chip_drops\"}]", chipRecipe.get("neoforge:conditions").toString());
                    assertEquals("[{\"type\":\"neoforge:not\",\"value\":{\"type\":\"sparse_ores:chip_drops\"}}]",
                            whole.get("neoforge:conditions").toString());
                    assertEquals(chip.id(), chipRecipe.getAsJsonObject("result").get("id").getAsString(), vanilla);
                    assertEquals(chip.item(), whole.getAsJsonObject("result").get("id").getAsString(), vanilla);
                    assertEquals(1, chipRecipe.getAsJsonObject("result").get("count").getAsInt(), vanilla);
                    assertEquals(1, whole.getAsJsonObject("result").get("count").getAsInt(), vanilla);
                    // Apart from the result and the condition, the pair is vanilla's recipe.
                    for (String key : new String[] {"type", "category", "cookingtime", "experience", "ingredient"}) {
                        assertEquals(chipRecipe.get(key), whole.get(key), vanilla + " " + key);
                    }
                    assertEquals(variant.counterpart(), chipRecipe.getAsJsonObject("ingredient").get("item").getAsString());
                    pairs++;
                }
            }
        }
        assertEquals(10, pairs);
        assertEquals("coal", file("minecraft/recipe/coal_from_smelting_coal_ore.json").get("group").getAsString());
        assertEquals(0.1, file("minecraft/recipe/coal_from_blasting_deepslate_coal_ore.json").get("experience").getAsDouble(), 1e-9);
        assertFalse(file("minecraft/recipe/quartz.json").has("group"), "vanilla's quartz recipe has no group");
        assertEquals("minecraft:diamond", file("sparse_ores/recipe/whole_items/diamond_from_smelting_diamond_ore.json")
                .getAsJsonObject("result").get("id").getAsString());
    }

    /**
     * Every recipe that carries {@code sparse_ores:chip_drops}, either way round, has a twin with the opposite condition
     * that takes the same block: a failed condition skips a recipe without bringing back the one it replaced, so the
     * block must still smelt (and, with Create, crush), into the other kind of drop. The furnace recipes replace vanilla's
     * ({@code minecraft/recipe/<name>}), the crushing recipes Create's ({@code create/recipe/crushing/<name>}); the twin of
     * either is {@code sparse_ores/recipe/whole_items/<name>}. The condition is on recipes only, never on a loot table (a
     * skipped table would leave the block dropping nothing).
     */
    @Test
    void whereverTheChipDropsConditionFailsItsTwinLoads() {
        int uses = 0;
        int crushing = 0;
        for (Map.Entry<String, JsonObject> file : FILES.entrySet()) {
            JsonElement conditions = file.getValue().get("neoforge:conditions");
            if (conditions == null || !conditions.toString().contains("sparse_ores:chip_drops")) {
                continue;
            }
            uses++;
            String path = file.getKey();
            assertTrue(path.contains("/recipe/"), path + ": the condition belongs on recipes only");
            boolean chips = !conditions.toString().contains("neoforge:not");
            String whole = "sparse_ores/recipe/whole_items/";
            String twin;
            if (chips) {
                twin = whole + path.substring(path.indexOf("/recipe/") + "/recipe/".length());
            } else {
                String name = path.substring(whole.length());
                twin = (name.startsWith("crushing/") ? "create/recipe/" : "minecraft/recipe/") + name;
            }
            JsonObject other = file(twin);
            assertEquals(!chips, !other.get("neoforge:conditions").toString().contains("neoforge:not"), path + " and " + twin);
            assertEquals(file.getValue().get("ingredient"), other.get("ingredient"), path + " and " + twin);
            assertEquals(file.getValue().get("ingredients"), other.get("ingredients"), path + " and " + twin);
            assertEquals(file.getValue().get("type"), other.get("type"), path + " and " + twin);
            if (path.contains("crushing/")) {
                crushing++;
                assertTrue(conditions.toString().contains("\"modid\":\"create\""), path + " names Create's content");
                assertTrue(other.get("neoforge:conditions").toString().contains("\"modid\":\"create\""), twin + " too");
            }
        }
        assertEquals(30, uses, "10 furnace pairs and 5 crushing pairs, each both ways");
        assertEquals(10, crushing);
    }

    /**
     * Crushing a chip ore block with Create (split step 4d): chips, 2.2 times what mining it gives from stone ore and 2.8
     * from deepslate and nether ore (docs/design/ore-refining.md), Create's experience nuggets, rock and time, under
     * Create's own recipe ids while chip drops are on. The twin, under Sparse Ores' id while they are off, is Create's
     * recipe as it ships.
     */
    @Test
    void chipOresCrushIntoChipsAndTheirTwinsAreCreatesOwn() {
        int blocks = 0;
        for (RichOres.Spec spec : Chips.ores()) {
            Chips.Chip chip = Chips.forItem(spec.drop());
            for (RichOres.Variant variant : spec.variants()) {
                boolean deep = variant.ground() != RichOres.Ground.STONE;
                String name = variant.counterpart().substring("minecraft:".length());
                RichOreRecipes.Crushing create = RichOreRecipes.crushing(variant.counterpart());
                JsonObject chips = file("create/recipe/crushing/" + name + ".json");
                JsonObject whole = file("sparse_ores/recipe/whole_items/crushing/" + name + ".json");

                assertEquals("create:crushing", chips.get("type").getAsString(), name);
                assertEquals(variant.counterpart(), chips.getAsJsonArray("ingredients").get(0).getAsJsonObject().get("item").getAsString());
                assertEquals(create.time(), chips.get("processing_time").getAsInt(), name);
                JsonArray results = chips.getAsJsonArray("results");
                assertEquals(deep ? 2.8 : 2.2, mean(results, chip.id()), 1e-9, name);
                assertEquals(0, mean(results, chip.item()), 1e-9, name + " gives no whole item");
                assertEquals(create.xpNuggets() * 0.75, mean(results, "create:experience_nugget"), 1e-9, name);
                assertEquals(0.125, mean(results, RichOreRecipes.rock(variant.ground())), 1e-9, name);

                // The twin: what Create gives for this block (Create 6.0.10's coal_ore: 1 + 75% of 1 coal, and so on).
                assertEquals("create:crushing", whole.get("type").getAsString(), name);
                assertEquals(create.time(), whole.get("processing_time").getAsInt(), name);
                JsonArray wholeResults = whole.getAsJsonArray("results");
                assertEquals(create.mean(), mean(wholeResults, chip.item()), 1e-9, name);
                assertEquals(0, mean(wholeResults, chip.id()), 1e-9, name + " gives no chip");
                assertEquals(results.size(), wholeResults.size(), name + " has the same results, in whole items");
                blocks++;
            }
        }
        assertEquals(5, blocks);
        // Coal ore: 1 chip always and a 20% chance of a second (2.2); deepslate coal ore 2 and 80%.
        assertEquals("[{\"id\":\"sparse_ores:coal_chip\",\"count\":2},{\"id\":\"sparse_ores:coal_chip\",\"chance\":0.2},"
                + "{\"id\":\"create:experience_nugget\",\"chance\":0.75},{\"id\":\"minecraft:cobblestone\",\"chance\":0.125}]",
                file("create/recipe/crushing/coal_ore.json").getAsJsonArray("results").toString());
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

    /** How many of the item a crushing recipe's results give on average. */
    private static double mean(JsonArray results, String item) {
        double mean = 0;
        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            if (result.get("id").getAsString().equals(item)) {
                int count = result.has("count") ? result.get("count").getAsInt() : 1;
                double chance = result.has("chance") ? result.get("chance").getAsDouble() : 1;
                mean += count * chance;
            }
        }
        return mean;
    }

    private static JsonObject file(String path) {
        JsonObject json = FILES.get(path);
        assertNotNull(json, "no generated file " + path);
        return json;
    }
}