package dev.brights0ng.sparseores.rich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import dev.brights0ng.sparseores.chips.Chips;

/** The rich ores' own furnace and crushing recipes (split step 4c). */
class RichOreRecipesTest {

    private static final Map<String, JsonObject> FILES = RichOreRecipes.files();

    @Test
    void everyOreHasItsOrdinaryBlocksNumbers() {
        Set<String> furnace = new HashSet<>();
        for (RichOreRecipes.Furnace entry : RichOreRecipes.FURNACE) {
            furnace.add(entry.oreId());
        }
        Set<String> ores = new HashSet<>();
        Set<String> blocks = new HashSet<>();
        for (RichOres.Spec spec : RichOres.ALL) {
            ores.add(spec.oreId());
            for (RichOres.Variant variant : spec.variants()) {
                blocks.add(variant.counterpart());
            }
        }
        assertEquals(ores, furnace);
        Set<String> crushing = new HashSet<>();
        for (RichOreRecipes.Crushing entry : RichOreRecipes.CRUSHING) {
            crushing.add(entry.block());
        }
        assertEquals(blocks, crushing);
    }

    /** A rich block smelts into five of what its ordinary block smelts into (chips for the chip ores), with its experience. */
    @Test
    void richBlocksSmeltIntoFiveTimesTheirOrdinaryBlock() {
        for (RichOres.Spec spec : RichOres.ALL) {
            RichOreRecipes.Furnace furnace = RichOreRecipes.furnace(spec.oreId());
            String item = Chips.forItem(furnace.item()) == null ? furnace.item() : Chips.forItem(furnace.item()).id();
            for (RichOres.Variant variant : spec.variants()) {
                for (String kind : new String[] {"smelting", "blasting"}) {
                    JsonObject recipe = file("sparse_ores/recipe/" + kind + "/" + variant.name() + ".json");
                    assertEquals("minecraft:" + kind, recipe.get("type").getAsString());
                    assertEquals("sparse_ores:" + variant.name(), recipe.getAsJsonObject("ingredient").get("item").getAsString());
                    assertEquals(item, recipe.getAsJsonObject("result").get("id").getAsString(), variant.name());
                    assertEquals(RichOres.RICHNESS, recipe.getAsJsonObject("result").get("count").getAsInt(), variant.name());
                    assertEquals(furnace.xp(), recipe.get("experience").getAsDouble(), 1e-9, variant.name());
                    assertEquals(kind.equals("smelting") ? 200 : 100, recipe.get("cookingtime").getAsInt());
                }
            }
        }
        assertEquals("minecraft:iron_ingot", file("sparse_ores/recipe/smelting/rich_iron_ore.json")
                .getAsJsonObject("result").get("id").getAsString());
        assertEquals("sparse_ores:coal_chip", file("sparse_ores/recipe/blasting/rich_deepslate_coal_ore.json")
                .getAsJsonObject("result").get("id").getAsString());
    }

    /** Crushing a rich block gives five times what Create gives for its ordinary block, and needs Create to load. */
    @Test
    void richBlocksCrushIntoFiveTimesTheirOrdinaryBlock() {
        for (RichOres.Variant variant : RichOres.allVariants()) {
            RichOreRecipes.Crushing ordinary = RichOreRecipes.crushing(variant.counterpart());
            JsonObject recipe = file("sparse_ores/recipe/crushing/" + variant.name() + ".json");
            assertTrue(needs(recipe, "create"), variant.name() + " must load only with Create");
            assertEquals("create:crushing", recipe.get("type").getAsString());
            assertEquals(ordinary.time(), recipe.get("processing_time").getAsInt(), variant.name());
            JsonArray results = recipe.getAsJsonArray("results");
            assertTrue(results.size() <= 7, variant.name() + ": Create's crushing allows 7 results");
            for (JsonElement result : results) {
                int count = result.getAsJsonObject().has("count") ? result.getAsJsonObject().get("count").getAsInt() : 1;
                assertTrue(count <= 64, variant.name() + ": a result is at most a stack");
            }
            assertEquals(ordinary.mean() * RichOres.RICHNESS, mean(results, ordinary.item()), 0.025, variant.name());
            assertEquals(ordinary.xpNuggets() * RichOreRecipes.XP_CHANCE * RichOres.RICHNESS,
                    mean(results, "create:experience_nugget"), 0.025, variant.name());
        }
        // 18 gold nuggets from nether gold ore make 90 from the rich one: a stack and 26.
        assertEquals(90, mean(file("sparse_ores/recipe/crushing/rich_nether_gold_ore.json").getAsJsonArray("results"),
                "minecraft:gold_nugget"), 1e-9);
        assertEquals(8.75, mean(file("sparse_ores/recipe/crushing/rich_iron_ore.json").getAsJsonArray("results"),
                "create:crushed_raw_iron"), 1e-9);
    }

    /**
     * Whatever names Create's content needs Create to load (rich zinc ore exists only with it), and nothing else needs
     * anything: the furnace recipes of vanilla's ores' rich blocks load in a game with Sparse Ores alone.
     */
    @Test
    void onlyWhatNamesCreatesContentWaitsForCreate() {
        for (Map.Entry<String, JsonObject> file : FILES.entrySet()) {
            String path = file.getKey();
            boolean create = file.getValue().toString().contains("\"create:");
            assertEquals(create, needs(file.getValue(), "create"), path);
            if (!create) {
                assertFalse(file.getValue().has("neoforge:conditions"), path);
            }
        }
        assertTrue(needs(file("sparse_ores/recipe/smelting/rich_zinc_ore.json"), "create"));
        assertFalse(needs(file("sparse_ores/recipe/smelting/rich_iron_ore.json"), "create"));
    }

    /**
     * Create's own zinc ore furnace recipes match {@code #c:ores/zinc}, which holds the rich zinc ores too, so a furnace
     * would choose between two recipes for rich zinc ore. Sparse Ores replaces them, naming the ordinary blocks, with
     * Create's numbers.
     */
    @Test
    void createsZincOreRecipesNameOnlyTheOrdinaryBlocks() {
        for (String kind : new String[] {"smelting", "blasting"}) {
            JsonObject recipe = file("create/recipe/" + kind + "/zinc_ingot_from_ore.json");
            assertTrue(needs(recipe, "create"));
            assertFalse(recipe.toString().contains("\"tag\""), kind);
            JsonArray ingredients = recipe.getAsJsonArray("ingredient");
            assertEquals(2, ingredients.size());
            assertEquals("create:zinc_ore", ingredients.get(0).getAsJsonObject().get("item").getAsString());
            assertEquals("create:deepslate_zinc_ore", ingredients.get(1).getAsJsonObject().get("item").getAsString());
            assertEquals("create:zinc_ingot", recipe.getAsJsonObject("result").get("id").getAsString());
            assertEquals(1, recipe.getAsJsonObject("result").get("count").getAsInt());
            assertEquals(1.0, recipe.get("experience").getAsDouble(), 1e-9);
        }
    }

    /** What the data generator wrote is what the rules say today. If this fails, run the Data run configuration. */
    @Test
    void theGeneratedFilesAreUpToDate() throws IOException {
        for (Map.Entry<String, JsonObject> file : FILES.entrySet()) {
            String resource = "/data/" + file.getKey();
            try (InputStream in = RichOreRecipesTest.class.getResourceAsStream(resource)) {
                assertNotNull(in, "missing generated file " + resource + " (run the Data run configuration)");
                JsonElement written = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(file.getValue(), written, resource + " is stale (run the Data run configuration)");
            }
        }
    }

    // ---- helpers ----

    private static JsonObject file(String path) {
        JsonObject json = FILES.get(path);
        assertNotNull(json, "no generated file " + path);
        return json;
    }

    /** Whether the recipe has a {@code neoforge:mod_loaded} condition on the mod. */
    private static boolean needs(JsonObject recipe, String mod) {
        if (!recipe.has("neoforge:conditions")) {
            return false;
        }
        for (JsonElement condition : recipe.getAsJsonArray("neoforge:conditions")) {
            JsonObject json = condition.getAsJsonObject();
            if (json.get("type").getAsString().equals("neoforge:mod_loaded") && json.get("modid").getAsString().equals(mod)) {
                return true;
            }
        }
        return false;
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
