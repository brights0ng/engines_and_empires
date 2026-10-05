package dev.brights0ng.enginesandempires.oregen.refining;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.brights0ng.enginesandempires.oregen.rich.RichOres;

/**
 * Every recipe the pack adds or replaces for ore refining, and the coal chip's burn time, built as plain JSON from
 * {@link Refining}. It needs no running game, so unit tests check it; {@link RefiningDataGen} writes it out.
 *
 * <p>Recipes that replace vanilla's or Create's are written under <em>their</em> ids (for example
 * {@code minecraft:iron_ingot_from_smelting_raw_iron}), so the pack's file is loaded instead of theirs. This mod loads
 * after Create, so its data wins.
 */
public final class RefiningRecipes {

    private static final String MODID = "engines_and_empires";

    /** Every file, keyed by its path under {@code data/} (such as {@code minecraft/recipe/x.json}). */
    public static Map<String, JsonObject> files() {
        Map<String, JsonObject> files = new LinkedHashMap<>();
        BiConsumer<String, JsonObject> recipe = (id, json) -> {
            String path = namespace(id) + "/recipe/" + path(id) + ".json";
            if (files.put(path, json) != null) {
                throw new IllegalStateException("Two recipes with the id " + id);
            }
        };

        metals(recipe);
        ordinaryOres(recipe);
        richOres(recipe);
        chips(recipe);
        otherSources(recipe);

        JsonObject coalChip = new JsonObject();
        coalChip.addProperty("burn_time", Refining.COAL_CHIP_BURN_TIME);
        JsonObject values = new JsonObject();
        values.add(MODID + ":coal_chip", coalChip);
        JsonObject fuels = new JsonObject();
        fuels.add("values", values);
        files.put("neoforge/data_maps/item/furnace_fuels.json", fuels);
        return files;
    }

    /** Raw ore and crushed ore in the furnace, and crushed ore in the washer. */
    private static void metals(BiConsumer<String, JsonObject> recipe) {
        for (Refining.Metal metal : Refining.METALS) {
            for (boolean blasting : new boolean[] {false, true}) {
                String kind = blasting ? "blasting" : "smelting";
                String rawId = metal.create()
                        ? "create:" + kind + "/" + metal.name() + "_ingot_from_raw_ore"
                        : "minecraft:" + metal.name() + "_ingot_from_" + kind + "_raw_" + metal.name();
                JsonElement rawIngredient = metal.create()
                        ? tag("c:raw_materials/" + metal.name())
                        : item(metal.raw());
                recipe.accept(rawId, cooking(blasting, rawIngredient, metal.nugget(), Refining.NUGGETS_PER_RAW,
                        metal.rawXp(), metal.create() ? null : metal.name() + "_ingot"));

                recipe.accept("create:" + kind + "/" + metal.name() + "_ingot_from_crushed",
                        cooking(blasting, item(metal.crushed()), metal.nugget(), Refining.NUGGETS_PER_RAW, 0.1, null));
            }

            JsonArray results = new JsonArray();
            results.add(result(metal.nugget(), Refining.WASHED_NUGGETS, 0));
            results.add(result(metal.nugget(), 1, Refining.WASH_BONUS_CHANCE));
            results.add(result(metal.byproduct(), 1, metal.byproductChance()));
            recipe.accept("create:splashing/crushed_raw_" + metal.name(), processing("create:splashing", metal.crushed(), results, 0));
        }
    }

    /** The ordinary ore blocks: the furnace (every metal and chip, and nether gold), and crushing (chips and group drops). */
    private static void ordinaryOres(BiConsumer<String, JsonObject> recipe) {
        for (Refining.Ore ore : Refining.ORES) {
            RichOres.Spec spec = ore.spec();
            if (ore.overridesFurnace()) {
                if (ore.oreId().equals("zinc")) {
                    // Create smelts both zinc ores with one recipe. It matched #c:ores/zinc, which holds the rich zinc
                    // ores too; it now names the ordinary ones, and rich zinc gets its own recipe.
                    JsonArray both = new JsonArray();
                    both.add(item(spec.stone()));
                    both.add(item(spec.deepslate()));
                    for (boolean blasting : new boolean[] {false, true}) {
                        recipe.accept("create:" + (blasting ? "blasting" : "smelting") + "/zinc_ingot_from_ore",
                                cooking(blasting, both, ore.furnaceItem(), ore.furnaceCount(), ore.smeltXp(), null));
                    }
                } else {
                    for (RichOres.Variant variant : spec.variants()) {
                        for (boolean blasting : new boolean[] {false, true}) {
                            recipe.accept(vanillaFurnaceId(ore, variant.counterpart(), blasting),
                                    cooking(blasting, item(variant.counterpart()), ore.furnaceItem(), ore.furnaceCount(),
                                            ore.smeltXp(), ore.oreId().equals("nether_quartz") ? null : vanillaGroup(ore)));
                        }
                    }
                }
            }
            if (ore.overridesCrushing()) {
                for (RichOres.Variant variant : spec.variants()) {
                    recipe.accept("create:crushing/" + path(variant.counterpart()), crushing(ore, variant, 1));
                }
            }
        }
    }

    /** Rich ore blocks, which had no furnace or crushing recipes of their own: five times their ordinary block. */
    private static void richOres(BiConsumer<String, JsonObject> recipe) {
        for (Refining.Ore ore : Refining.ORES) {
            for (RichOres.Variant variant : ore.spec().variants()) {
                String rich = MODID + ":" + variant.name();
                for (boolean blasting : new boolean[] {false, true}) {
                    recipe.accept(MODID + ":" + (blasting ? "blasting" : "smelting") + "/" + variant.name(),
                            cooking(blasting, item(rich), ore.furnaceItem(), ore.furnaceCount() * RichOres.RICHNESS,
                                    ore.smeltXp(), null));
                }
                recipe.accept(MODID + ":crushing/" + variant.name(), crushing(ore, variant, RichOres.RICHNESS));
            }
        }
    }

    /** Four chips craft back into the whole item. */
    private static void chips(BiConsumer<String, JsonObject> recipe) {
        for (Refining.Chip chip : Refining.CHIPS) {
            JsonObject json = new JsonObject();
            json.addProperty("type", "minecraft:crafting_shaped");
            json.addProperty("category", "misc");
            JsonObject key = new JsonObject();
            key.add("#", item(MODID + ":" + chip.name()));
            json.add("key", key);
            JsonArray pattern = new JsonArray();
            pattern.add("##");
            pattern.add("##");
            json.add("pattern", pattern);
            json.add("result", stack(chip.item(), 1));
            recipe.accept(MODID + ":" + path(chip.item()) + "_from_chips", json);
        }
    }

    /**
     * Create's washing recipes that make metal out of plain sand and gravel, cut like raw ore. Soul sand's quartz
     * becomes quartz chips; everything else they give is kept.
     */
    private static void otherSources(BiConsumer<String, JsonObject> recipe) {
        JsonArray gravel = new JsonArray();
        gravel.add(result("minecraft:flint", 1, 0.25));
        gravel.add(result("minecraft:iron_nugget", 1, Refining.otherSourceChance(0.125)));
        recipe.accept("create:splashing/gravel", processing("create:splashing", "minecraft:gravel", gravel, 0));

        JsonArray redSand = new JsonArray();
        redSand.add(result("minecraft:gold_nugget", 1, Refining.otherSourceChance(3 * 0.125)));
        redSand.add(result("minecraft:dead_bush", 1, 0.05));
        recipe.accept("create:splashing/red_sand", processing("create:splashing", "minecraft:red_sand", redSand, 0));

        // Create gives four quartz an eighth of the time: half a quartz, or two chips, on average.
        JsonArray soulSand = new JsonArray();
        soulSand.add(result(MODID + ":quartz_chip", 1,
                Refining.otherSourceChance(4 * 0.125 * Refining.CHIPS_PER_ITEM)));
        soulSand.add(result("minecraft:gold_nugget", 1, Refining.otherSourceChance(0.02)));
        recipe.accept("create:splashing/soul_sand", processing("create:splashing", "minecraft:soul_sand", soulSand, 0));
    }

    // ---- recipe pieces ----

    /** A crushing recipe for one ore block, {@code times} as rich as the ordinary block. */
    private static JsonObject crushing(Refining.Ore ore, RichOres.Variant variant, int times) {
        boolean deep = variant.ground() != RichOres.Ground.STONE;
        String input = times == 1 ? variant.counterpart() : MODID + ":" + variant.name();
        JsonArray results = new JsonArray();
        addAmount(results, ore.crushedItem(), Refining.Amount.of(ore.crushedMean(deep) * times));
        if (times == 1) {
            results.add(result("create:experience_nugget", ore.xpNuggets(), Refining.XP_CHANCE));
        } else {
            addAmount(results, "create:experience_nugget", Refining.Amount.of(ore.xpNuggets() * Refining.XP_CHANCE * times));
        }
        results.add(result(rock(variant.ground()), 1, Refining.ROCK_CHANCE));
        return processing("create:crushing", input, results, ore.crushingTime(deep));
    }

    private static void addAmount(JsonArray results, String id, Refining.Amount amount) {
        if (amount.count() > 0) {
            results.add(result(id, amount.count(), 0));
        }
        if (amount.chance() > 0) {
            results.add(result(id, 1, amount.chance()));
        }
    }

    private static JsonObject processing(String type, String input, JsonArray results, int time) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        JsonArray ingredients = new JsonArray();
        ingredients.add(item(input));
        json.add("ingredients", ingredients);
        if (time > 0) {
            json.addProperty("processing_time", time);
        }
        json.add("results", results);
        return json;
    }

    /** One of a Create recipe's results: {@code count} of an item, given with {@code chance} (0 means always). */
    private static JsonObject result(String id, int count, double chance) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        if (count != 1) {
            json.addProperty("count", count);
        }
        if (chance > 0 && chance < 1) {
            json.addProperty("chance", chance);
        }
        return json;
    }

    private static JsonObject cooking(boolean blasting, JsonElement ingredient, String result, int count, double xp, String group) {
        JsonObject json = new JsonObject();
        json.addProperty("type", blasting ? "minecraft:blasting" : "minecraft:smelting");
        json.addProperty("category", "misc");
        json.addProperty("cookingtime", blasting ? 100 : 200);
        json.addProperty("experience", xp);
        if (group != null) {
            json.addProperty("group", group);
        }
        json.add("ingredient", ingredient);
        json.add("result", stack(result, count));
        return json;
    }

    private static JsonObject stack(String id, int count) {
        JsonObject json = new JsonObject();
        json.addProperty("count", count);
        json.addProperty("id", id);
        return json;
    }

    private static JsonObject item(String id) {
        JsonObject json = new JsonObject();
        json.addProperty("item", id);
        return json;
    }

    private static JsonObject tag(String id) {
        JsonObject json = new JsonObject();
        json.addProperty("tag", id);
        return json;
    }

    /** Vanilla's id for smelting or blasting an ore block, such as {@code minecraft:iron_ingot_from_blasting_deepslate_iron_ore}. */
    static String vanillaFurnaceId(Refining.Ore ore, String block, boolean blasting) {
        if (ore.oreId().equals("nether_quartz")) {
            return blasting ? "minecraft:quartz_from_blasting" : "minecraft:quartz";
        }
        return "minecraft:" + vanillaGroup(ore) + "_from_" + (blasting ? "blasting" : "smelting") + "_" + path(block);
    }

    /** The name vanilla's furnace recipes give their output: {@code iron_ingot}, {@code coal}, {@code diamond}. */
    private static String vanillaGroup(Refining.Ore ore) {
        return switch (ore.oreId()) {
            case "iron", "gold", "copper", "zinc" -> ore.oreId() + "_ingot";
            case "nether_gold" -> "gold_ingot";
            case "nether_quartz" -> "quartz";
            case "lapis" -> "lapis_lazuli";
            default -> ore.oreId();
        };
    }

    private static String rock(RichOres.Ground ground) {
        return switch (ground) {
            case STONE -> "minecraft:cobblestone";
            case DEEPSLATE -> "minecraft:cobbled_deepslate";
            case NETHERRACK -> "minecraft:netherrack";
        };
    }

    /** {@code minecraft} of {@code minecraft:iron_ore}. */
    static String namespace(String id) {
        return id.substring(0, id.indexOf(':'));
    }

    /** {@code iron_ore} of {@code minecraft:iron_ore}. */
    static String path(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    private RefiningRecipes() {
    }
}
