package dev.brights0ng.sparseores.chips;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.brights0ng.sparseores.SparseOres;
import dev.brights0ng.sparseores.rich.RichOreRecipes;
import dev.brights0ng.sparseores.rich.RichOres;

/**
 * The chips' recipes (four craft back into the whole item), the coal chip's burn time, and the ordinary chip ores'
 * smelting, built as plain JSON from {@link Chips} (the first two moved from the core's {@code RefiningRecipes} in split
 * seam S15, the smelting in split step 4c). It needs no running game, so unit tests check it; {@link ChipDataGen} writes
 * it out.
 *
 * <p>A silk-touched coal, diamond or nether quartz ore smelts into what mining it drops: one chip while the server config
 * gives chips ({@code chipDrops}), the whole item when it does not. The chip recipes replace vanilla's, under vanilla's
 * ids, so they carry the condition {@link ChipDropsCondition sparse_ores:chip_drops}. A recipe whose condition fails is
 * skipped, and that does not bring vanilla's back (Sparse Ores' file hides vanilla's at the same id), so each one has a
 * twin under {@code sparse_ores:whole_items/<vanilla's name>}: vanilla's own recipe, with the opposite condition. Exactly
 * one of the pair loads, either way.
 */
public final class ChipRecipes {

    private static final String MODID = SparseOres.MODID;

    /** The folder of the whole-item recipes that stand in for vanilla's when chip drops are off. */
    public static final String WHOLE_ITEMS = "whole_items";

    /**
     * Every file, keyed by its path under {@code data/} (such as {@code sparse_ores/recipe/coal_from_chips.json}).
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

        // A silk-touched chip ore smelts into what mining it gives: chips under vanilla's ids while chip drops are on,
        // vanilla's whole item under Sparse Ores' ids while they are off.
        for (RichOres.Spec spec : Chips.ores()) {
            RichOreRecipes.Furnace furnace = RichOreRecipes.furnace(spec.oreId());
            RichOres.Spec chipped = Chips.chipped(spec);
            for (RichOres.Variant variant : spec.variants()) {
                for (boolean blasting : new boolean[] {false, true}) {
                    String vanilla = vanillaFurnacePath(spec.oreId(), furnace, variant.counterpart(), blasting);
                    files.put("minecraft/recipe/" + vanilla + ".json", RichOreRecipes.withConditions(
                            RichOreRecipes.cooking(blasting, RichOreRecipes.item(variant.counterpart()), chipped.drop(),
                                    chipped.minDrop(), furnace.xp(), furnace.group()),
                            conditions(true)));
                    files.put(MODID + "/recipe/" + WHOLE_ITEMS + "/" + vanilla + ".json", RichOreRecipes.withConditions(
                            RichOreRecipes.cooking(blasting, RichOreRecipes.item(variant.counterpart()), furnace.item(),
                                    spec.minDrop(), furnace.xp(), furnace.group()),
                            conditions(false)));
                }
            }
        }
        return files;
    }

    /**
     * The path of vanilla's recipe for smelting or blasting a chip ore block, such as
     * {@code coal_from_blasting_deepslate_coal_ore}, or {@code quartz} and {@code quartz_from_blasting} for nether quartz
     * ore.
     */
    static String vanillaFurnacePath(String oreId, RichOreRecipes.Furnace furnace, String block, boolean blasting) {
        if (oreId.equals("nether_quartz")) {
            return blasting ? "quartz_from_blasting" : "quartz";
        }
        String item = furnace.item().substring(furnace.item().indexOf(':') + 1);
        return item + "_from_" + (blasting ? "blasting" : "smelting") + "_" + block.substring(block.indexOf(':') + 1);
    }

    /** {@code sparse_ores:chip_drops}, or {@code neoforge:not} of it. */
    private static JsonArray conditions(boolean chipDrops) {
        JsonObject condition = new JsonObject();
        condition.addProperty("type", Chips.CHIP_DROPS_CONDITION);
        JsonArray conditions = new JsonArray();
        if (chipDrops) {
            conditions.add(condition);
        } else {
            JsonObject not = new JsonObject();
            not.addProperty("type", "neoforge:not");
            not.add("value", condition);
            conditions.add(not);
        }
        return conditions;
    }

    private ChipRecipes() {
    }
}
