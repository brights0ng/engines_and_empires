package dev.brights0ng.sparseores.chips;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.brights0ng.sparseores.SparseOres;

/**
 * The chips' recipes (four craft back into the whole item) and the coal chip's burn time, built as plain JSON from
 * {@link Chips} (moved from the core's {@code RefiningRecipes} in split seam S15). It needs no running game, so unit tests
 * check it; {@link ChipDataGen} writes it out.
 */
public final class ChipRecipes {

    private static final String MODID = SparseOres.MODID;

    /**
     * Every file, keyed by its path under {@code data/} (such as {@code engines_and_empires/recipe/coal_from_chips.json}).
     */
    public static Map<String, JsonObject> files() {
        Map<String, JsonObject> files = new LinkedHashMap<>();
        // Four chips craft back into the whole item.
        for (Chips.Chip chip : Chips.ALL) {
            JsonObject json = new JsonObject();
            json.addProperty("type", "minecraft:crafting_shaped");
            json.addProperty("category", "misc");
            JsonObject key = new JsonObject();
            JsonObject ingredient = new JsonObject();
            ingredient.addProperty("item", MODID + ":" + chip.name());
            key.add("#", ingredient);
            json.add("key", key);
            JsonArray pattern = new JsonArray();
            pattern.add("##");
            pattern.add("##");
            json.add("pattern", pattern);
            JsonObject result = new JsonObject();
            result.addProperty("count", 1);
            result.addProperty("id", chip.item());
            json.add("result", result);
            String item = chip.item().substring(chip.item().indexOf(':') + 1);
            files.put(MODID + "/recipe/" + item + "_from_chips.json", json);
        }

        JsonObject coalChip = new JsonObject();
        coalChip.addProperty("burn_time", Chips.COAL_CHIP_BURN_TIME);
        JsonObject values = new JsonObject();
        values.add(MODID + ":coal_chip", coalChip);
        JsonObject fuels = new JsonObject();
        fuels.add("values", values);
        files.put("neoforge/data_maps/item/furnace_fuels.json", fuels);
        return files;
    }

    private ChipRecipes() {
    }
}
