package dev.brights0ng.sparseores.rich;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.brights0ng.sparseores.SparseOres;
import dev.brights0ng.sparseores.chips.ChipRecipes;
import dev.brights0ng.sparseores.chips.Chips;

/**
 * The rich ore blocks' own recipes (split step 4c; until then only the Engines and Empires core gave them any): a rich
 * block gives {@link RichOres#RICHNESS} times what its ordinary block gives, in the furnace and blast furnace, and,
 * with Create, on crushing wheels. The ordinary blocks' numbers are vanilla's (and Create's), mirrored in the tables
 * below as {@link RichOres} mirrors their loot, with Sparse Ores' one rule on top: the chip ores smelt and crush into
 * chips ({@link Chips#chipped}; their ordinary blocks' recipes are {@link ChipRecipes}, and so is the chips' crushing
 * lead, since split step 4d). A pack that refines ore differently replaces these by id, as the core does with its metal
 * and group-drop numbers.
 *
 * <p>Built as plain JSON, so unit tests check it; {@code RichOreDataGen.Recipes} writes it out. Everything that names
 * Create's content carries a {@code neoforge:mod_loaded} condition on Create (an optional dependency), so without Create
 * those files are skipped quietly instead of failing to parse: the crushing recipes (the chip ores' are
 * {@link ChipRecipes#crushing}: chips, not whole items), rich zinc ore's furnace recipes,
 * and Create's own zinc ore furnace recipes, which Sparse Ores replaces because they match {@code #c:ores/zinc}, where
 * rich zinc ore is too (with both, a furnace would pick either recipe for rich zinc ore).
 */
public final class RichOreRecipes {

    private static final String MODID = SparseOres.MODID;

    /** Create's chance of its experience nuggets when crushing an ore. */
    public static final double XP_CHANCE = 0.75;

    /** Create's chance of the rock itself (cobblestone, cobbled deepslate, netherrack) when crushing an ore. */
    public static final double ROCK_CHANCE = 0.125;

    /** The most of one item a single recipe result holds: a stack. Larger amounts are split over several results. */
    private static final int STACK = 64;

    /**
     * What an ordinary ore block smelts into, as vanilla's furnace recipes have it (Create's for zinc): one {@code item},
     * with {@code xp} experience, in the recipe book group {@code group} (null for none).
     */
    public record Furnace(String oreId, String item, double xp, String group) {
    }

    public static final List<Furnace> FURNACE = List.of(
            new Furnace("coal", "minecraft:coal", 0.1, "coal"),
            new Furnace("iron", "minecraft:iron_ingot", 0.7, "iron_ingot"),
            new Furnace("copper", "minecraft:copper_ingot", 0.7, "copper_ingot"),
            new Furnace("zinc", "create:zinc_ingot", 1.0, null),
            new Furnace("redstone", "minecraft:redstone", 0.7, "redstone"),
            new Furnace("lapis", "minecraft:lapis_lazuli", 0.2, "lapis_lazuli"),
            new Furnace("gold", "minecraft:gold_ingot", 1.0, "gold_ingot"),
            new Furnace("emerald", "minecraft:emerald", 1.0, "emerald"),
            new Furnace("diamond", "minecraft:diamond", 1.0, "diamond"),
            new Furnace("nether_gold", "minecraft:gold_ingot", 1.0, "gold_ingot"),
            new Furnace("nether_quartz", "minecraft:quartz", 0.2, null));

    /**
     * What Create's crushing wheels make of an ordinary ore block ({@code block}): {@code mean} of {@code item} on
     * average, {@code xpNuggets} experience nuggets at {@link #XP_CHANCE}, the rock at {@link #ROCK_CHANCE}, in
     * {@code time} ticks. From Create 6.0.10's {@code create:crushing/<block>} recipes.
     */
    public record Crushing(String block, String item, double mean, int xpNuggets, int time) {
    }

    public static final List<Crushing> CRUSHING = List.of(
            new Crushing("minecraft:coal_ore", "minecraft:coal", 1.75, 1, 150),
            new Crushing("minecraft:deepslate_coal_ore", "minecraft:coal", 2.25, 1, 300),
            new Crushing("minecraft:iron_ore", "create:crushed_raw_iron", 1.75, 1, 250),
            new Crushing("minecraft:deepslate_iron_ore", "create:crushed_raw_iron", 2.25, 1, 350),
            new Crushing("minecraft:copper_ore", "create:crushed_raw_copper", 5.25, 1, 250),
            new Crushing("minecraft:deepslate_copper_ore", "create:crushed_raw_copper", 7.25, 1, 350),
            new Crushing("create:zinc_ore", "create:crushed_raw_zinc", 1.75, 1, 250),
            new Crushing("create:deepslate_zinc_ore", "create:crushed_raw_zinc", 2.25, 1, 350),
            new Crushing("minecraft:redstone_ore", "minecraft:redstone", 6.5, 1, 250),
            new Crushing("minecraft:deepslate_redstone_ore", "minecraft:redstone", 7.5, 1, 350),
            new Crushing("minecraft:lapis_ore", "minecraft:lapis_lazuli", 10.5, 1, 250),
            new Crushing("minecraft:deepslate_lapis_ore", "minecraft:lapis_lazuli", 12.5, 1, 350),
            new Crushing("minecraft:gold_ore", "create:crushed_raw_gold", 1.75, 2, 250),
            new Crushing("minecraft:deepslate_gold_ore", "create:crushed_raw_gold", 2.25, 2, 350),
            new Crushing("minecraft:emerald_ore", "minecraft:emerald", 1.75, 1, 350),
            new Crushing("minecraft:deepslate_emerald_ore", "minecraft:emerald", 2.25, 1, 450),
            new Crushing("minecraft:diamond_ore", "minecraft:diamond", 1.75, 1, 350),
            new Crushing("minecraft:deepslate_diamond_ore", "minecraft:diamond", 2.25, 1, 450),
            new Crushing("minecraft:nether_gold_ore", "minecraft:gold_nugget", 18, 1, 350),
            new Crushing("minecraft:nether_quartz_ore", "minecraft:quartz", 2.25, 1, 350));

    /** The furnace entry of an ore. */
    public static Furnace furnace(String oreId) {
        for (Furnace furnace : FURNACE) {
            if (furnace.oreId().equals(oreId)) {
                return furnace;
            }
        }
        throw new IllegalArgumentException("No furnace entry for ore '" + oreId + "'");
    }

    /** Create's crushing of an ordinary ore block. */
    public static Crushing crushing(String block) {
        for (Crushing crushing : CRUSHING) {
            if (crushing.block().equals(block)) {
                return crushing;
            }
        }
        throw new IllegalArgumentException("No crushing entry for block '" + block + "'");
    }

    /**
     * What an ordinary ore block smelts into in Sparse Ores, and so what a rich block gives a fifth of: vanilla's item,
     * or its chip for the chip ores (one chip, as mining the block gives one).
     */
    public static String smeltedItem(String oreId) {
        String item = furnace(oreId).item();
        Chips.Chip chip = Chips.forItem(item);
        return chip == null ? item : chip.id();
    }

    /** The mod an ore's blocks or items come from, if not vanilla (zinc: Create), else null. */
    public static String otherMod(String oreId) {
        RichOres.Spec spec = RichOres.forOre(oreId);
        String mod = spec.stone().substring(0, spec.stone().indexOf(':'));
        return mod.equals("minecraft") ? null : mod;
    }

    /** Every file, keyed by its path under {@code data/} (such as {@code sparse_ores/recipe/smelting/rich_iron_ore.json}). */
    public static Map<String, JsonObject> files() {
        Map<String, JsonObject> files = new LinkedHashMap<>();
        for (RichOres.Spec spec : RichOres.ALL) {
            String mod = otherMod(spec.oreId());
            for (RichOres.Variant variant : spec.variants()) {
                String rich = MODID + ":" + variant.name();
                for (boolean blasting : new boolean[] {false, true}) {
                    JsonObject recipe = cooking(blasting, item(rich), smeltedItem(spec.oreId()), RichOres.RICHNESS,
                            furnace(spec.oreId()).xp(), null);
                    put(files, MODID + "/recipe/" + kind(blasting) + "/" + variant.name() + ".json",
                            mod == null ? recipe : withModLoaded(recipe, mod));
                }
                // The chip ores crush into chips (ChipRecipes), the rest into five times Create's numbers.
                JsonObject crushing = Chips.forItem(spec.drop()) != null
                        ? ChipRecipes.crushing(spec, variant, RichOres.RICHNESS)
                        : crushing(variant, rich);
                put(files, MODID + "/recipe/crushing/" + variant.name() + ".json", withModLoaded(crushing, "create"));
            }
        }

        // Create smelts both zinc ores with one recipe matching #c:ores/zinc, which holds the rich zinc ores too: it now
        // names the ordinary ones, as Create's own numbers otherwise (rich zinc ore has the recipe above).
        RichOres.Spec zinc = RichOres.forOre("zinc");
        JsonArray both = new JsonArray();
        both.add(item(zinc.stone()));
        both.add(item(zinc.deepslate()));
        for (boolean blasting : new boolean[] {false, true}) {
            put(files, "create/recipe/" + kind(blasting) + "/zinc_ingot_from_ore.json", withModLoaded(
                    cooking(blasting, both, furnace("zinc").item(), 1, furnace("zinc").xp(), null), "create"));
        }
        return files;
    }

    /** Crushing a rich block: {@link RichOres#RICHNESS} times what Create gives for its ordinary block. */
    private static JsonObject crushing(RichOres.Variant variant, String rich) {
        Crushing ordinary = crushing(variant.counterpart());
        return crushing(rich, ordinary, ordinary.item(), ordinary.mean(), variant.ground(), RichOres.RICHNESS);
    }

    /**
     * A crushing recipe for one block, {@code times} as rich as an ordinary block that Create's {@code ordinary} entry
     * describes, except that the main product is {@code item} and its average from the ordinary block {@code mean} (Create's
     * own, or the chips' in {@link ChipRecipes#crushing}). With {@code times} 1 the experience nuggets are given as Create
     * writes them (a count at {@link #XP_CHANCE}); more than that, as an average amount. The rock's chance is not multiplied.
     */
    public static JsonObject crushing(String input, Crushing ordinary, String item, double mean, RichOres.Ground ground,
                               int times) {
        JsonArray results = new JsonArray();
        addAmount(results, item, mean * times);
        if (times == 1) {
            results.add(result("create:experience_nugget", ordinary.xpNuggets(), XP_CHANCE));
        } else {
            addAmount(results, "create:experience_nugget", ordinary.xpNuggets() * XP_CHANCE * times);
        }
        results.add(result(rock(ground), 1, ROCK_CHANCE));
        JsonObject json = new JsonObject();
        json.addProperty("type", "create:crushing");
        JsonArray ingredients = new JsonArray();
        ingredients.add(item(input));
        json.add("ingredients", ingredients);
        json.addProperty("processing_time", ordinary.time());
        json.add("results", results);
        return json;
    }

    /** Create's own crushing of an ordinary ore block, as it ships it (the twin of a recipe that replaces it). */
    public static JsonObject createsCrushing(RichOres.Variant variant) {
        Crushing ordinary = crushing(variant.counterpart());
        return crushing(variant.counterpart(), ordinary, ordinary.item(), ordinary.mean(), variant.ground(), 1);
    }

    /**
     * An average amount as a crushing recipe gives it: whole stacks always given, then a chance of one more, rounded to
     * 5% (2.3 is 2, and a 30% chance of a third).
     */
    private static void addAmount(JsonArray results, String id, double mean) {
        int count = (int) Math.floor(mean);
        double chance = Math.round((mean - count) * 20) / 20.0;
        if (chance >= 1) {
            count++;
            chance = 0;
        }
        for (int left = count; left > 0; left -= STACK) {
            results.add(result(id, Math.min(left, STACK), 0));
        }
        if (chance > 0) {
            results.add(result(id, 1, chance));
        }
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

    /** A furnace or blast furnace recipe, as vanilla writes them (category misc, 200 or 100 ticks). */
    public static JsonObject cooking(boolean blasting, JsonElement ingredient, String result, int count, double xp,
                                     String group) {
        JsonObject json = new JsonObject();
        json.addProperty("type", blasting ? "minecraft:blasting" : "minecraft:smelting");
        json.addProperty("category", "misc");
        json.addProperty("cookingtime", blasting ? 100 : 200);
        json.addProperty("experience", xp);
        if (group != null) {
            json.addProperty("group", group);
        }
        json.add("ingredient", ingredient);
        JsonObject stack = new JsonObject();
        stack.addProperty("count", count);
        stack.addProperty("id", result);
        json.add("result", stack);
        return json;
    }

    /** The recipe with a condition that the mod {@code modId} is loaded, ahead of everything else in it. */
    public static JsonObject withModLoaded(JsonObject recipe, String modId) {
        JsonObject condition = new JsonObject();
        condition.addProperty("type", "neoforge:mod_loaded");
        condition.addProperty("modid", modId);
        JsonArray conditions = new JsonArray();
        conditions.add(condition);
        return withConditions(recipe, conditions);
    }

    /** The recipe with these {@code neoforge:conditions}, ahead of everything else in it. */
    public static JsonObject withConditions(JsonObject recipe, JsonArray conditions) {
        JsonObject json = new JsonObject();
        json.add("neoforge:conditions", conditions);
        recipe.entrySet().forEach(entry -> json.add(entry.getKey(), entry.getValue()));
        return json;
    }

    public static JsonObject item(String id) {
        JsonObject json = new JsonObject();
        json.addProperty("item", id);
        return json;
    }

    private static String kind(boolean blasting) {
        return blasting ? "blasting" : "smelting";
    }

    public static String rock(RichOres.Ground ground) {
        return switch (ground) {
            case STONE -> "minecraft:cobblestone";
            case DEEPSLATE -> "minecraft:cobbled_deepslate";
            case NETHERRACK -> "minecraft:netherrack";
        };
    }

    private static void put(Map<String, JsonObject> files, String path, JsonObject json) {
        if (files.put(path, json) != null) {
            throw new IllegalStateException("Two recipes at " + path);
        }
    }

    private RichOreRecipes() {
    }
}
