package dev.brights0ng.enginesandempires.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.enginesandempires.oregen.refining.Refining;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.sparseores.SparseOres;
import dev.brights0ng.sparseores.chips.Chips;
import dev.brights0ng.sparseores.rich.RichOreBlocks;
import dev.brights0ng.sparseores.rich.RichOres;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Every recipe in the pack that takes an ore block, a rich ore block, a raw or crushed metal, a chip, or the sand and
 * gravel the pack's washing recipes cut, read from the recipe manager the server actually loaded (every mod's data, so
 * the core's overrides win over Sparse Ores', vanilla's and Create's where they share an id), against the recipes
 * recorded before split step 4c. Step 4c moved the rich ores' recipes and the chip ores' smelting from the core into
 * Sparse Ores, which now ships them for itself; the pack's resolved recipes must not change, and this pins all of them:
 * for each input and recipe type, which recipes take it, and each one as the game encodes it (results, counts, chances,
 * experience, cooking and processing times, groups and categories).
 *
 * <p>A recipe that only moved to another id is listed in {@link #MOVED}. On a mismatch the test writes what it resolved
 * to a file in the temporary folder (named in the failure) in the same format as the recorded lines.
 */
@GameTestHolder(EnginesAndEmpires.MODID)
@PrefixGameTestTemplate(false)
public final class PackRecipesGameTests {

    private static final Gson GSON = new Gson();

    /** Ids a recipe has had since step 4c, by the id it had before: none yet. */
    private static final Map<String, String> MOVED = Map.of();

    @GameTest(template = GameTestStructures.EMPTY)
    public static void everyOreRecipeResolvesAsItDidBeforeStep4c(GameTestHelper helper) {
        Set<String> resolved = resolved(helper.getLevel());
        Set<String> before = new TreeSet<>();
        // Not BEFORE_VANILLA_TYPES + BEFORE_CREATE_TYPES: javac would fold that into one constant, too long for a class file.
        for (String half : List.of(BEFORE_VANILLA_TYPES, BEFORE_CREATE_TYPES)) {
            for (String line : half.split("\n")) {
                if (!line.isBlank()) {
                    before.add(renamed(line.strip()));
                }
            }
        }
        Set<String> missing = new TreeSet<>(before);
        missing.removeAll(resolved);
        Set<String> extra = new TreeSet<>(resolved);
        extra.removeAll(before);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            Path dump = dump(resolved);
            helper.fail(missing.size() + " recipe lines missing, " + extra.size() + " new (all resolved lines: " + dump
                    + "); first missing: " + first(missing) + "; first new: " + first(extra));
        }
        helper.succeed();
    }

    /** One line per (recipe type, input, recipe): {@code type input id json}, sorted. */
    private static Set<String> resolved(ServerLevel level) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        Set<String> lines = new TreeSet<>();
        List<Item> inputs = inputs();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            String json = null;
            for (Item input : inputs) {
                if (!takes(recipe, new ItemStack(input))) {
                    continue;
                }
                if (json == null) {
                    json = GSON.toJson(sorted(Recipe.CODEC.encodeStart(ops, recipe).getOrThrow()));
                }
                lines.add(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()) + " "
                        + BuiltInRegistries.ITEM.getKey(input) + " " + holder.id() + " " + json);
            }
        }
        return lines;
    }

    private static boolean takes(Recipe<?> recipe, ItemStack stack) {
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty() && ingredient.test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** The items whose recipes the pack's ore refining touches. */
    private static List<Item> inputs() {
        Set<String> ids = new LinkedHashSet<>();
        for (RichOres.Spec spec : RichOres.ALL) {
            for (RichOres.Variant variant : spec.variants()) {
                ids.add(variant.counterpart());
                if (RichOreBlocks.exists(variant.name())) {
                    ids.add(SparseOres.MODID + ":" + variant.name());
                }
            }
        }
        for (Refining.Metal metal : Refining.METALS) {
            ids.add(metal.raw());
            ids.add(metal.crushed());
        }
        for (Chips.Chip chip : Chips.ALL) {
            ids.add(chip.id());
        }
        ids.add("minecraft:gravel");
        ids.add("minecraft:red_sand");
        ids.add("minecraft:soul_sand");
        List<Item> items = new ArrayList<>();
        for (String id : ids) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            if (item != Items.AIR) {
                items.add(item);
            }
        }
        return items;
    }

    /** A recorded line with the recipe's id as it is now, if it has moved. */
    private static String renamed(String line) {
        String[] parts = line.split(" ", 4);
        String id = MOVED.getOrDefault(parts[2], parts[2]);
        return parts[0] + " " + parts[1] + " " + id + " " + parts[3];
    }

    /** The JSON with every object's keys in order, so two encodings of the same recipe print the same. */
    private static JsonElement sorted(JsonElement element) {
        if (element.isJsonObject()) {
            Map<String, JsonElement> keys = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                keys.put(entry.getKey(), sorted(entry.getValue()));
            }
            JsonObject object = new JsonObject();
            keys.forEach(object::add);
            return object;
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                array.add(sorted(child));
            }
            return array;
        }
        return element;
    }

    private static Path dump(Set<String> lines) {
        try {
            Path file = Files.createTempFile("pack-recipes-", ".txt");
            Files.write(file, lines, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            return Path.of("(could not write the resolved lines: " + e + ")");
        }
    }

    private static String first(Set<String> lines) {
        return lines.isEmpty() ? "none" : lines.iterator().next();
    }

    // Recorded from the aggregate run on branch split-4c before any recipe moved (the recipes of split step 4b), in
    // two halves: one string constant may not hold them all.

    /** Recorded before step 4c: the furnace, blast furnace and crafting recipes. */
    private static final String BEFORE_VANILLA_TYPES = """
            minecraft:blasting create:crushed_raw_copper create:blasting/copper_ingot_from_crushed {"category":"misc","cookingtime":100,"experience":0.1,"ingredient":{"item":"create:crushed_raw_copper"},"result":{"count":2,"id":"create:copper_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting create:crushed_raw_gold create:blasting/gold_ingot_from_crushed {"category":"misc","cookingtime":100,"experience":0.1,"ingredient":{"item":"create:crushed_raw_gold"},"result":{"count":2,"id":"minecraft:gold_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting create:crushed_raw_iron create:blasting/iron_ingot_from_crushed {"category":"misc","cookingtime":100,"experience":0.1,"ingredient":{"item":"create:crushed_raw_iron"},"result":{"count":2,"id":"minecraft:iron_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting create:crushed_raw_zinc create:blasting/zinc_ingot_from_crushed {"category":"misc","cookingtime":100,"experience":0.1,"ingredient":{"item":"create:crushed_raw_zinc"},"result":{"count":2,"id":"create:zinc_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting create:deepslate_zinc_ore create:blasting/zinc_ingot_from_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":[{"item":"create:zinc_ore"},{"item":"create:deepslate_zinc_ore"}],"result":{"count":2,"id":"create:zinc_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting create:raw_zinc create:blasting/zinc_ingot_from_raw_ore {"category":"misc","cookingtime":100,"experience":0.7,"ingredient":{"tag":"c:raw_materials/zinc"},"result":{"count":2,"id":"create:zinc_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting create:zinc_ore create:blasting/zinc_ingot_from_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":[{"item":"create:zinc_ore"},{"item":"create:deepslate_zinc_ore"}],"result":{"count":2,"id":"create:zinc_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:coal_ore minecraft:coal_from_blasting_coal_ore {"category":"misc","cookingtime":100,"experience":0.1,"group":"coal","ingredient":{"item":"minecraft:coal_ore"},"result":{"count":1,"id":"sparse_ores:coal_chip"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:copper_ore minecraft:copper_ingot_from_blasting_copper_ore {"category":"misc","cookingtime":100,"experience":0.7,"group":"copper_ingot","ingredient":{"item":"minecraft:copper_ore"},"result":{"count":7,"id":"create:copper_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:deepslate_coal_ore minecraft:coal_from_blasting_deepslate_coal_ore {"category":"misc","cookingtime":100,"experience":0.1,"group":"coal","ingredient":{"item":"minecraft:deepslate_coal_ore"},"result":{"count":1,"id":"sparse_ores:coal_chip"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:deepslate_copper_ore minecraft:copper_ingot_from_blasting_deepslate_copper_ore {"category":"misc","cookingtime":100,"experience":0.7,"group":"copper_ingot","ingredient":{"item":"minecraft:deepslate_copper_ore"},"result":{"count":7,"id":"create:copper_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:deepslate_diamond_ore minecraft:diamond_from_blasting_deepslate_diamond_ore {"category":"misc","cookingtime":100,"experience":1.0,"group":"diamond","ingredient":{"item":"minecraft:deepslate_diamond_ore"},"result":{"count":1,"id":"sparse_ores:diamond_chip"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:deepslate_emerald_ore minecraft:emerald_from_blasting_deepslate_emerald_ore {"category":"misc","cookingtime":100,"experience":1.0,"group":"emerald","ingredient":{"item":"minecraft:deepslate_emerald_ore"},"result":{"count":1,"id":"minecraft:emerald"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:deepslate_gold_ore minecraft:gold_ingot_from_blasting_deepslate_gold_ore {"category":"misc","cookingtime":100,"experience":1.0,"group":"gold_ingot","ingredient":{"item":"minecraft:deepslate_gold_ore"},"result":{"count":2,"id":"minecraft:gold_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:deepslate_iron_ore minecraft:iron_ingot_from_blasting_deepslate_iron_ore {"category":"misc","cookingtime":100,"experience":0.7,"group":"iron_ingot","ingredient":{"item":"minecraft:deepslate_iron_ore"},"result":{"count":2,"id":"minecraft:iron_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:deepslate_lapis_ore minecraft:lapis_lazuli_from_blasting_deepslate_lapis_ore {"category":"misc","cookingtime":100,"experience":0.2,"group":"lapis_lazuli","ingredient":{"item":"minecraft:deepslate_lapis_ore"},"result":{"count":1,"id":"minecraft:lapis_lazuli"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:deepslate_redstone_ore minecraft:redstone_from_blasting_deepslate_redstone_ore {"category":"blocks","cookingtime":100,"experience":0.7,"group":"redstone","ingredient":{"item":"minecraft:deepslate_redstone_ore"},"result":{"count":1,"id":"minecraft:redstone"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:diamond_ore minecraft:diamond_from_blasting_diamond_ore {"category":"misc","cookingtime":100,"experience":1.0,"group":"diamond","ingredient":{"item":"minecraft:diamond_ore"},"result":{"count":1,"id":"sparse_ores:diamond_chip"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:emerald_ore minecraft:emerald_from_blasting_emerald_ore {"category":"misc","cookingtime":100,"experience":1.0,"group":"emerald","ingredient":{"item":"minecraft:emerald_ore"},"result":{"count":1,"id":"minecraft:emerald"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:gold_ore minecraft:gold_ingot_from_blasting_gold_ore {"category":"misc","cookingtime":100,"experience":1.0,"group":"gold_ingot","ingredient":{"item":"minecraft:gold_ore"},"result":{"count":2,"id":"minecraft:gold_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:iron_ore minecraft:iron_ingot_from_blasting_iron_ore {"category":"misc","cookingtime":100,"experience":0.7,"group":"iron_ingot","ingredient":{"item":"minecraft:iron_ore"},"result":{"count":2,"id":"minecraft:iron_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:lapis_ore minecraft:lapis_lazuli_from_blasting_lapis_ore {"category":"misc","cookingtime":100,"experience":0.2,"group":"lapis_lazuli","ingredient":{"item":"minecraft:lapis_ore"},"result":{"count":1,"id":"minecraft:lapis_lazuli"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:nether_gold_ore minecraft:gold_ingot_from_blasting_nether_gold_ore {"category":"misc","cookingtime":100,"experience":1.0,"group":"gold_ingot","ingredient":{"item":"minecraft:nether_gold_ore"},"result":{"count":1,"id":"minecraft:gold_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:nether_quartz_ore minecraft:quartz_from_blasting {"category":"misc","cookingtime":100,"experience":0.2,"ingredient":{"item":"minecraft:nether_quartz_ore"},"result":{"count":1,"id":"sparse_ores:quartz_chip"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:raw_copper minecraft:copper_ingot_from_blasting_raw_copper {"category":"misc","cookingtime":100,"experience":0.7,"group":"copper_ingot","ingredient":{"item":"minecraft:raw_copper"},"result":{"count":2,"id":"create:copper_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:raw_gold minecraft:gold_ingot_from_blasting_raw_gold {"category":"misc","cookingtime":100,"experience":1.0,"group":"gold_ingot","ingredient":{"item":"minecraft:raw_gold"},"result":{"count":2,"id":"minecraft:gold_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:raw_iron minecraft:iron_ingot_from_blasting_raw_iron {"category":"misc","cookingtime":100,"experience":0.7,"group":"iron_ingot","ingredient":{"item":"minecraft:raw_iron"},"result":{"count":2,"id":"minecraft:iron_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting minecraft:redstone_ore minecraft:redstone_from_blasting_redstone_ore {"category":"blocks","cookingtime":100,"experience":0.7,"group":"redstone","ingredient":{"item":"minecraft:redstone_ore"},"result":{"count":1,"id":"minecraft:redstone"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_coal_ore engines_and_empires:blasting/rich_coal_ore {"category":"misc","cookingtime":100,"experience":0.1,"ingredient":{"item":"sparse_ores:rich_coal_ore"},"result":{"count":5,"id":"sparse_ores:coal_chip"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_copper_ore engines_and_empires:blasting/rich_copper_ore {"category":"misc","cookingtime":100,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_copper_ore"},"result":{"count":35,"id":"create:copper_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_deepslate_coal_ore engines_and_empires:blasting/rich_deepslate_coal_ore {"category":"misc","cookingtime":100,"experience":0.1,"ingredient":{"item":"sparse_ores:rich_deepslate_coal_ore"},"result":{"count":5,"id":"sparse_ores:coal_chip"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_deepslate_copper_ore engines_and_empires:blasting/rich_deepslate_copper_ore {"category":"misc","cookingtime":100,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_deepslate_copper_ore"},"result":{"count":35,"id":"create:copper_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_deepslate_diamond_ore engines_and_empires:blasting/rich_deepslate_diamond_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_deepslate_diamond_ore"},"result":{"count":5,"id":"sparse_ores:diamond_chip"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_deepslate_emerald_ore engines_and_empires:blasting/rich_deepslate_emerald_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_deepslate_emerald_ore"},"result":{"count":5,"id":"minecraft:emerald"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_deepslate_gold_ore engines_and_empires:blasting/rich_deepslate_gold_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_deepslate_gold_ore"},"result":{"count":10,"id":"minecraft:gold_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_deepslate_iron_ore engines_and_empires:blasting/rich_deepslate_iron_ore {"category":"misc","cookingtime":100,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_deepslate_iron_ore"},"result":{"count":10,"id":"minecraft:iron_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_deepslate_lapis_ore engines_and_empires:blasting/rich_deepslate_lapis_ore {"category":"misc","cookingtime":100,"experience":0.2,"ingredient":{"item":"sparse_ores:rich_deepslate_lapis_ore"},"result":{"count":5,"id":"minecraft:lapis_lazuli"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_deepslate_redstone_ore engines_and_empires:blasting/rich_deepslate_redstone_ore {"category":"misc","cookingtime":100,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_deepslate_redstone_ore"},"result":{"count":5,"id":"minecraft:redstone"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_deepslate_zinc_ore engines_and_empires:blasting/rich_deepslate_zinc_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_deepslate_zinc_ore"},"result":{"count":10,"id":"create:zinc_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_diamond_ore engines_and_empires:blasting/rich_diamond_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_diamond_ore"},"result":{"count":5,"id":"sparse_ores:diamond_chip"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_emerald_ore engines_and_empires:blasting/rich_emerald_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_emerald_ore"},"result":{"count":5,"id":"minecraft:emerald"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_gold_ore engines_and_empires:blasting/rich_gold_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_gold_ore"},"result":{"count":10,"id":"minecraft:gold_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_iron_ore engines_and_empires:blasting/rich_iron_ore {"category":"misc","cookingtime":100,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_iron_ore"},"result":{"count":10,"id":"minecraft:iron_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_lapis_ore engines_and_empires:blasting/rich_lapis_ore {"category":"misc","cookingtime":100,"experience":0.2,"ingredient":{"item":"sparse_ores:rich_lapis_ore"},"result":{"count":5,"id":"minecraft:lapis_lazuli"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_nether_gold_ore engines_and_empires:blasting/rich_nether_gold_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_nether_gold_ore"},"result":{"count":5,"id":"minecraft:gold_nugget"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_nether_quartz_ore engines_and_empires:blasting/rich_nether_quartz_ore {"category":"misc","cookingtime":100,"experience":0.2,"ingredient":{"item":"sparse_ores:rich_nether_quartz_ore"},"result":{"count":5,"id":"sparse_ores:quartz_chip"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_redstone_ore engines_and_empires:blasting/rich_redstone_ore {"category":"misc","cookingtime":100,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_redstone_ore"},"result":{"count":5,"id":"minecraft:redstone"},"type":"minecraft:blasting"}
            minecraft:blasting sparse_ores:rich_zinc_ore engines_and_empires:blasting/rich_zinc_ore {"category":"misc","cookingtime":100,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_zinc_ore"},"result":{"count":10,"id":"create:zinc_nugget"},"type":"minecraft:blasting"}
            minecraft:crafting create:raw_zinc create:crafting/materials/raw_zinc_block {"category":"misc","key":{"C":{"item":"create:raw_zinc"}},"pattern":["CCC","CCC","CCC"],"result":{"count":1,"id":"create:raw_zinc_block"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting minecraft:gravel createdieselgenerators:crafting/asphalt_block {"category":"misc","key":{"C":{"item":"createdieselgenerators:crude_oil_bucket"},"G":{"item":"minecraft:gravel"},"S":{"item":"minecraft:sand"}},"pattern":["SSS","SCG","GGG"],"result":{"count":8,"id":"createdieselgenerators:asphalt_block"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting minecraft:gravel domum_ornamentum:beige_bricks {"category":"equipment","ingredients":[{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:brick"},{"item":"minecraft:brick"}],"result":{"count":4,"id":"domum_ornamentum:beige_bricks"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel domum_ornamentum:beige_stone_bricks {"category":"equipment","ingredients":[{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:stone_bricks"},{"item":"minecraft:stone_bricks"}],"result":{"count":4,"id":"domum_ornamentum:beige_stone_bricks"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:black_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:black_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:black_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:blue_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:blue_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:blue_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:brown_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:brown_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:brown_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:coarse_dirt {"category":"building","key":{"D":{"item":"minecraft:dirt"},"G":{"item":"minecraft:gravel"}},"pattern":["DG","GD"],"result":{"count":4,"id":"minecraft:coarse_dirt"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting minecraft:gravel minecraft:cyan_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:cyan_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:cyan_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:gray_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:gray_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:gray_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:green_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:green_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:green_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:light_blue_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:light_blue_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:light_blue_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:light_gray_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:light_gray_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:light_gray_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:lime_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:lime_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:lime_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:magenta_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:magenta_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:magenta_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:orange_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:orange_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:orange_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:pink_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:pink_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:pink_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:purple_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:purple_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:purple_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:red_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:red_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:red_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:white_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:white_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:white_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:gravel minecraft:yellow_concrete_powder {"category":"building","group":"concrete_powder","ingredients":[{"item":"minecraft:yellow_dye"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:gravel"}],"result":{"count":8,"id":"minecraft:yellow_concrete_powder"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:raw_copper minecraft:raw_copper_block {"category":"building","key":{"#":{"item":"minecraft:raw_copper"}},"pattern":["###","###","###"],"result":{"count":1,"id":"minecraft:raw_copper_block"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting minecraft:raw_gold minecraft:raw_gold_block {"category":"building","key":{"#":{"item":"minecraft:raw_gold"}},"pattern":["###","###","###"],"result":{"count":1,"id":"minecraft:raw_gold_block"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting minecraft:raw_iron minecraft:raw_iron_block {"category":"building","key":{"#":{"item":"minecraft:raw_iron"}},"pattern":["###","###","###"],"result":{"count":1,"id":"minecraft:raw_iron_block"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting minecraft:red_sand create:crafting/materials/red_sand_paper {"category":"misc","ingredients":[{"item":"minecraft:paper"},{"tag":"c:sands/red"}],"result":{"count":1,"id":"create:red_sand_paper"},"type":"minecraft:crafting_shapeless"}
            minecraft:crafting minecraft:red_sand minecraft:red_sandstone {"category":"building","key":{"#":{"item":"minecraft:red_sand"}},"pattern":["##","##"],"result":{"count":1,"id":"minecraft:red_sandstone"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting minecraft:red_sand minecraft:tnt {"category":"redstone","key":{"#":[{"item":"minecraft:sand"},{"item":"minecraft:red_sand"}],"X":{"item":"minecraft:gunpowder"}},"pattern":["X#X","#X#","X#X"],"result":{"count":1,"id":"minecraft:tnt"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting minecraft:soul_sand cold_sweat:hearth {"category":"misc","key":{"#":{"item":"cold_sweat:smokestack"},"B":{"tag":"c:ingots/nether_brick"},"I":{"tag":"c:ingots/iron"},"S":{"tag":"minecraft:soul_fire_base_blocks"}},"pattern":[" # ","BBB","SIS"],"result":{"count":1,"id":"cold_sweat:hearth"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting minecraft:soul_sand minecraft:soul_campfire {"category":"misc","key":{"#":{"tag":"minecraft:soul_fire_base_blocks"},"L":{"tag":"minecraft:logs"},"S":{"tag":"c:rods/wooden"}},"pattern":[" S ","S#S","LLL"],"result":{"count":1,"id":"minecraft:soul_campfire"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting minecraft:soul_sand minecraft:soul_torch {"category":"misc","key":{"#":{"tag":"c:rods/wooden"},"S":{"tag":"minecraft:soul_fire_base_blocks"},"X":[{"item":"minecraft:coal"},{"item":"minecraft:charcoal"}]},"pattern":["X","#","S"],"result":{"count":4,"id":"minecraft:soul_torch"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting sparse_ores:coal_chip sparse_ores:coal_from_chips {"category":"misc","key":{"#":{"item":"sparse_ores:coal_chip"}},"pattern":["##","##"],"result":{"count":1,"id":"minecraft:coal"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting sparse_ores:diamond_chip sparse_ores:diamond_from_chips {"category":"misc","key":{"#":{"item":"sparse_ores:diamond_chip"}},"pattern":["##","##"],"result":{"count":1,"id":"minecraft:diamond"},"type":"minecraft:crafting_shaped"}
            minecraft:crafting sparse_ores:quartz_chip sparse_ores:quartz_from_chips {"category":"misc","key":{"#":{"item":"sparse_ores:quartz_chip"}},"pattern":["##","##"],"result":{"count":1,"id":"minecraft:quartz"},"type":"minecraft:crafting_shaped"}
            minecraft:smelting create:crushed_raw_copper create:smelting/copper_ingot_from_crushed {"category":"misc","cookingtime":200,"experience":0.1,"ingredient":{"item":"create:crushed_raw_copper"},"result":{"count":2,"id":"create:copper_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting create:crushed_raw_gold create:smelting/gold_ingot_from_crushed {"category":"misc","cookingtime":200,"experience":0.1,"ingredient":{"item":"create:crushed_raw_gold"},"result":{"count":2,"id":"minecraft:gold_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting create:crushed_raw_iron create:smelting/iron_ingot_from_crushed {"category":"misc","cookingtime":200,"experience":0.1,"ingredient":{"item":"create:crushed_raw_iron"},"result":{"count":2,"id":"minecraft:iron_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting create:crushed_raw_zinc create:smelting/zinc_ingot_from_crushed {"category":"misc","cookingtime":200,"experience":0.1,"ingredient":{"item":"create:crushed_raw_zinc"},"result":{"count":2,"id":"create:zinc_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting create:deepslate_zinc_ore create:smelting/zinc_ingot_from_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":[{"item":"create:zinc_ore"},{"item":"create:deepslate_zinc_ore"}],"result":{"count":2,"id":"create:zinc_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting create:raw_zinc create:smelting/zinc_ingot_from_raw_ore {"category":"misc","cookingtime":200,"experience":0.7,"ingredient":{"tag":"c:raw_materials/zinc"},"result":{"count":2,"id":"create:zinc_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting create:zinc_ore create:smelting/zinc_ingot_from_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":[{"item":"create:zinc_ore"},{"item":"create:deepslate_zinc_ore"}],"result":{"count":2,"id":"create:zinc_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:coal_ore minecraft:coal_from_smelting_coal_ore {"category":"misc","cookingtime":200,"experience":0.1,"group":"coal","ingredient":{"item":"minecraft:coal_ore"},"result":{"count":1,"id":"sparse_ores:coal_chip"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:copper_ore minecraft:copper_ingot_from_smelting_copper_ore {"category":"misc","cookingtime":200,"experience":0.7,"group":"copper_ingot","ingredient":{"item":"minecraft:copper_ore"},"result":{"count":7,"id":"create:copper_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:deepslate_coal_ore minecraft:coal_from_smelting_deepslate_coal_ore {"category":"misc","cookingtime":200,"experience":0.1,"group":"coal","ingredient":{"item":"minecraft:deepslate_coal_ore"},"result":{"count":1,"id":"sparse_ores:coal_chip"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:deepslate_copper_ore minecraft:copper_ingot_from_smelting_deepslate_copper_ore {"category":"misc","cookingtime":200,"experience":0.7,"group":"copper_ingot","ingredient":{"item":"minecraft:deepslate_copper_ore"},"result":{"count":7,"id":"create:copper_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:deepslate_diamond_ore minecraft:diamond_from_smelting_deepslate_diamond_ore {"category":"misc","cookingtime":200,"experience":1.0,"group":"diamond","ingredient":{"item":"minecraft:deepslate_diamond_ore"},"result":{"count":1,"id":"sparse_ores:diamond_chip"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:deepslate_emerald_ore minecraft:emerald_from_smelting_deepslate_emerald_ore {"category":"misc","cookingtime":200,"experience":1.0,"group":"emerald","ingredient":{"item":"minecraft:deepslate_emerald_ore"},"result":{"count":1,"id":"minecraft:emerald"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:deepslate_gold_ore minecraft:gold_ingot_from_smelting_deepslate_gold_ore {"category":"misc","cookingtime":200,"experience":1.0,"group":"gold_ingot","ingredient":{"item":"minecraft:deepslate_gold_ore"},"result":{"count":2,"id":"minecraft:gold_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:deepslate_iron_ore minecraft:iron_ingot_from_smelting_deepslate_iron_ore {"category":"misc","cookingtime":200,"experience":0.7,"group":"iron_ingot","ingredient":{"item":"minecraft:deepslate_iron_ore"},"result":{"count":2,"id":"minecraft:iron_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:deepslate_lapis_ore minecraft:lapis_lazuli_from_smelting_deepslate_lapis_ore {"category":"misc","cookingtime":200,"experience":0.2,"group":"lapis_lazuli","ingredient":{"item":"minecraft:deepslate_lapis_ore"},"result":{"count":1,"id":"minecraft:lapis_lazuli"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:deepslate_redstone_ore minecraft:redstone_from_smelting_deepslate_redstone_ore {"category":"blocks","cookingtime":200,"experience":0.7,"group":"redstone","ingredient":{"item":"minecraft:deepslate_redstone_ore"},"result":{"count":1,"id":"minecraft:redstone"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:diamond_ore minecraft:diamond_from_smelting_diamond_ore {"category":"misc","cookingtime":200,"experience":1.0,"group":"diamond","ingredient":{"item":"minecraft:diamond_ore"},"result":{"count":1,"id":"sparse_ores:diamond_chip"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:emerald_ore minecraft:emerald_from_smelting_emerald_ore {"category":"misc","cookingtime":200,"experience":1.0,"group":"emerald","ingredient":{"item":"minecraft:emerald_ore"},"result":{"count":1,"id":"minecraft:emerald"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:gold_ore minecraft:gold_ingot_from_smelting_gold_ore {"category":"misc","cookingtime":200,"experience":1.0,"group":"gold_ingot","ingredient":{"item":"minecraft:gold_ore"},"result":{"count":2,"id":"minecraft:gold_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:iron_ore minecraft:iron_ingot_from_smelting_iron_ore {"category":"misc","cookingtime":200,"experience":0.7,"group":"iron_ingot","ingredient":{"item":"minecraft:iron_ore"},"result":{"count":2,"id":"minecraft:iron_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:lapis_ore minecraft:lapis_lazuli_from_smelting_lapis_ore {"category":"misc","cookingtime":200,"experience":0.2,"group":"lapis_lazuli","ingredient":{"item":"minecraft:lapis_ore"},"result":{"count":1,"id":"minecraft:lapis_lazuli"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:nether_gold_ore minecraft:gold_ingot_from_smelting_nether_gold_ore {"category":"misc","cookingtime":200,"experience":1.0,"group":"gold_ingot","ingredient":{"item":"minecraft:nether_gold_ore"},"result":{"count":1,"id":"minecraft:gold_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:nether_quartz_ore minecraft:quartz {"category":"misc","cookingtime":200,"experience":0.2,"ingredient":{"item":"minecraft:nether_quartz_ore"},"result":{"count":1,"id":"sparse_ores:quartz_chip"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:raw_copper minecraft:copper_ingot_from_smelting_raw_copper {"category":"misc","cookingtime":200,"experience":0.7,"group":"copper_ingot","ingredient":{"item":"minecraft:raw_copper"},"result":{"count":2,"id":"create:copper_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:raw_gold minecraft:gold_ingot_from_smelting_raw_gold {"category":"misc","cookingtime":200,"experience":1.0,"group":"gold_ingot","ingredient":{"item":"minecraft:raw_gold"},"result":{"count":2,"id":"minecraft:gold_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:raw_iron minecraft:iron_ingot_from_smelting_raw_iron {"category":"misc","cookingtime":200,"experience":0.7,"group":"iron_ingot","ingredient":{"item":"minecraft:raw_iron"},"result":{"count":2,"id":"minecraft:iron_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:red_sand minecraft:glass {"category":"blocks","cookingtime":200,"experience":0.1,"ingredient":{"tag":"minecraft:smelts_to_glass"},"result":{"count":1,"id":"minecraft:glass"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:redstone_ore minecraft:redstone_from_smelting_redstone_ore {"category":"blocks","cookingtime":200,"experience":0.7,"group":"redstone","ingredient":{"item":"minecraft:redstone_ore"},"result":{"count":1,"id":"minecraft:redstone"},"type":"minecraft:smelting"}
            minecraft:smelting minecraft:soul_sand create:smelting/scoria {"category":"blocks","cookingtime":200,"experience":0.0,"ingredient":{"item":"minecraft:soul_sand"},"result":{"count":1,"id":"create:scoria"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_coal_ore engines_and_empires:smelting/rich_coal_ore {"category":"misc","cookingtime":200,"experience":0.1,"ingredient":{"item":"sparse_ores:rich_coal_ore"},"result":{"count":5,"id":"sparse_ores:coal_chip"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_copper_ore engines_and_empires:smelting/rich_copper_ore {"category":"misc","cookingtime":200,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_copper_ore"},"result":{"count":35,"id":"create:copper_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_deepslate_coal_ore engines_and_empires:smelting/rich_deepslate_coal_ore {"category":"misc","cookingtime":200,"experience":0.1,"ingredient":{"item":"sparse_ores:rich_deepslate_coal_ore"},"result":{"count":5,"id":"sparse_ores:coal_chip"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_deepslate_copper_ore engines_and_empires:smelting/rich_deepslate_copper_ore {"category":"misc","cookingtime":200,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_deepslate_copper_ore"},"result":{"count":35,"id":"create:copper_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_deepslate_diamond_ore engines_and_empires:smelting/rich_deepslate_diamond_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_deepslate_diamond_ore"},"result":{"count":5,"id":"sparse_ores:diamond_chip"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_deepslate_emerald_ore engines_and_empires:smelting/rich_deepslate_emerald_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_deepslate_emerald_ore"},"result":{"count":5,"id":"minecraft:emerald"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_deepslate_gold_ore engines_and_empires:smelting/rich_deepslate_gold_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_deepslate_gold_ore"},"result":{"count":10,"id":"minecraft:gold_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_deepslate_iron_ore engines_and_empires:smelting/rich_deepslate_iron_ore {"category":"misc","cookingtime":200,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_deepslate_iron_ore"},"result":{"count":10,"id":"minecraft:iron_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_deepslate_lapis_ore engines_and_empires:smelting/rich_deepslate_lapis_ore {"category":"misc","cookingtime":200,"experience":0.2,"ingredient":{"item":"sparse_ores:rich_deepslate_lapis_ore"},"result":{"count":5,"id":"minecraft:lapis_lazuli"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_deepslate_redstone_ore engines_and_empires:smelting/rich_deepslate_redstone_ore {"category":"misc","cookingtime":200,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_deepslate_redstone_ore"},"result":{"count":5,"id":"minecraft:redstone"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_deepslate_zinc_ore engines_and_empires:smelting/rich_deepslate_zinc_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_deepslate_zinc_ore"},"result":{"count":10,"id":"create:zinc_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_diamond_ore engines_and_empires:smelting/rich_diamond_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_diamond_ore"},"result":{"count":5,"id":"sparse_ores:diamond_chip"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_emerald_ore engines_and_empires:smelting/rich_emerald_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_emerald_ore"},"result":{"count":5,"id":"minecraft:emerald"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_gold_ore engines_and_empires:smelting/rich_gold_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_gold_ore"},"result":{"count":10,"id":"minecraft:gold_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_iron_ore engines_and_empires:smelting/rich_iron_ore {"category":"misc","cookingtime":200,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_iron_ore"},"result":{"count":10,"id":"minecraft:iron_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_lapis_ore engines_and_empires:smelting/rich_lapis_ore {"category":"misc","cookingtime":200,"experience":0.2,"ingredient":{"item":"sparse_ores:rich_lapis_ore"},"result":{"count":5,"id":"minecraft:lapis_lazuli"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_nether_gold_ore engines_and_empires:smelting/rich_nether_gold_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_nether_gold_ore"},"result":{"count":5,"id":"minecraft:gold_nugget"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_nether_quartz_ore engines_and_empires:smelting/rich_nether_quartz_ore {"category":"misc","cookingtime":200,"experience":0.2,"ingredient":{"item":"sparse_ores:rich_nether_quartz_ore"},"result":{"count":5,"id":"sparse_ores:quartz_chip"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_redstone_ore engines_and_empires:smelting/rich_redstone_ore {"category":"misc","cookingtime":200,"experience":0.7,"ingredient":{"item":"sparse_ores:rich_redstone_ore"},"result":{"count":5,"id":"minecraft:redstone"},"type":"minecraft:smelting"}
            minecraft:smelting sparse_ores:rich_zinc_ore engines_and_empires:smelting/rich_zinc_ore {"category":"misc","cookingtime":200,"experience":1.0,"ingredient":{"item":"sparse_ores:rich_zinc_ore"},"result":{"count":10,"id":"create:zinc_nugget"},"type":"minecraft:smelting"}
            """;

    /** Recorded before step 4c: Create's recipe types (crushing, washing, milling, mixing, compacting, haunting). */
    private static final String BEFORE_CREATE_TYPES = """
            create:compacting minecraft:gravel create:compacting/andesite_from_flint {"ingredients":[{"item":"minecraft:flint"},{"item":"minecraft:flint"},{"item":"minecraft:gravel"},{"amount":100,"fluid":"minecraft:lava","type":"neoforge:single"}],"results":[{"id":"minecraft:andesite"}],"type":"create:compacting"}
            create:compacting minecraft:red_sand create:compacting/granite_from_flint {"ingredients":[{"item":"minecraft:flint"},{"item":"minecraft:flint"},{"item":"minecraft:red_sand"},{"amount":100,"fluid":"minecraft:lava","type":"neoforge:single"}],"results":[{"id":"minecraft:granite"}],"type":"create:compacting"}
            create:crushing create:deepslate_zinc_ore create:crushing/deepslate_zinc_ore {"ingredients":[{"item":"create:deepslate_zinc_ore"}],"processing_time":350,"results":[{"count":2,"id":"create:crushed_raw_zinc"},{"chance":0.25,"id":"create:crushed_raw_zinc"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing create:raw_zinc create:crushing/raw_zinc {"ingredients":[{"tag":"c:raw_materials/zinc"}],"processing_time":400,"results":[{"id":"create:crushed_raw_zinc"},{"chance":0.75,"id":"create:experience_nugget"}],"type":"create:crushing"}
            create:crushing create:zinc_ore create:crushing/zinc_ore {"ingredients":[{"item":"create:zinc_ore"}],"processing_time":250,"results":[{"id":"create:crushed_raw_zinc"},{"chance":0.75,"id":"create:crushed_raw_zinc"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing minecraft:coal_ore create:crushing/coal_ore {"ingredients":[{"item":"minecraft:coal_ore"}],"processing_time":150,"results":[{"count":2,"id":"sparse_ores:coal_chip"},{"chance":0.2,"id":"sparse_ores:coal_chip"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing minecraft:copper_ore create:crushing/copper_ore {"ingredients":[{"item":"minecraft:copper_ore"}],"processing_time":250,"results":[{"count":5,"id":"create:crushed_raw_copper"},{"chance":0.25,"id":"create:crushed_raw_copper"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing minecraft:deepslate_coal_ore create:crushing/deepslate_coal_ore {"ingredients":[{"item":"minecraft:deepslate_coal_ore"}],"processing_time":300,"results":[{"count":2,"id":"sparse_ores:coal_chip"},{"chance":0.8,"id":"sparse_ores:coal_chip"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing minecraft:deepslate_copper_ore create:crushing/deepslate_copper_ore {"ingredients":[{"item":"minecraft:deepslate_copper_ore"}],"processing_time":350,"results":[{"count":7,"id":"create:crushed_raw_copper"},{"chance":0.25,"id":"create:crushed_raw_copper"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing minecraft:deepslate_diamond_ore create:crushing/deepslate_diamond_ore {"ingredients":[{"item":"minecraft:deepslate_diamond_ore"}],"processing_time":450,"results":[{"count":2,"id":"sparse_ores:diamond_chip"},{"chance":0.8,"id":"sparse_ores:diamond_chip"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing minecraft:deepslate_emerald_ore create:crushing/deepslate_emerald_ore {"ingredients":[{"item":"minecraft:deepslate_emerald_ore"}],"processing_time":450,"results":[{"count":2,"id":"minecraft:emerald"},{"chance":0.25,"id":"minecraft:emerald"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing minecraft:deepslate_gold_ore create:crushing/deepslate_gold_ore {"ingredients":[{"item":"minecraft:deepslate_gold_ore"}],"processing_time":350,"results":[{"count":2,"id":"create:crushed_raw_gold"},{"chance":0.25,"id":"create:crushed_raw_gold"},{"chance":0.75,"count":2,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing minecraft:deepslate_iron_ore create:crushing/deepslate_iron_ore {"ingredients":[{"item":"minecraft:deepslate_iron_ore"}],"processing_time":350,"results":[{"count":2,"id":"create:crushed_raw_iron"},{"chance":0.25,"id":"create:crushed_raw_iron"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing minecraft:deepslate_lapis_ore create:crushing/deepslate_lapis_ore {"ingredients":[{"item":"minecraft:deepslate_lapis_ore"}],"processing_time":350,"results":[{"count":4,"id":"minecraft:lapis_lazuli"},{"chance":0.2,"id":"minecraft:lapis_lazuli"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing minecraft:deepslate_redstone_ore create:crushing/deepslate_redstone_ore {"ingredients":[{"item":"minecraft:deepslate_redstone_ore"}],"processing_time":350,"results":[{"count":2,"id":"minecraft:redstone"},{"chance":0.8,"id":"minecraft:redstone"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing minecraft:diamond_ore create:crushing/diamond_ore {"ingredients":[{"item":"minecraft:diamond_ore"}],"processing_time":350,"results":[{"count":2,"id":"sparse_ores:diamond_chip"},{"chance":0.2,"id":"sparse_ores:diamond_chip"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing minecraft:emerald_ore create:crushing/emerald_ore {"ingredients":[{"item":"minecraft:emerald_ore"}],"processing_time":350,"results":[{"id":"minecraft:emerald"},{"chance":0.75,"id":"minecraft:emerald"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing minecraft:gold_ore create:crushing/gold_ore {"ingredients":[{"item":"minecraft:gold_ore"}],"processing_time":250,"results":[{"id":"create:crushed_raw_gold"},{"chance":0.75,"id":"create:crushed_raw_gold"},{"chance":0.75,"count":2,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing minecraft:gravel create:crushing/gravel {"ingredients":[{"item":"minecraft:gravel"}],"processing_time":250,"results":[{"id":"minecraft:sand"},{"chance":0.1,"id":"minecraft:flint"},{"chance":0.05,"id":"minecraft:clay_ball"}],"type":"create:crushing"}
            create:crushing minecraft:iron_ore create:crushing/iron_ore {"ingredients":[{"item":"minecraft:iron_ore"}],"processing_time":250,"results":[{"id":"create:crushed_raw_iron"},{"chance":0.75,"id":"create:crushed_raw_iron"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing minecraft:lapis_ore create:crushing/lapis_ore {"ingredients":[{"item":"minecraft:lapis_ore"}],"processing_time":250,"results":[{"count":3,"id":"minecraft:lapis_lazuli"},{"chance":0.3,"id":"minecraft:lapis_lazuli"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing minecraft:nether_gold_ore create:crushing/nether_gold_ore {"ingredients":[{"item":"minecraft:nether_gold_ore"}],"processing_time":350,"results":[{"count":2,"id":"minecraft:gold_nugget"},{"chance":0.8,"id":"minecraft:gold_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:netherrack"}],"type":"create:crushing"}
            create:crushing minecraft:nether_quartz_ore create:crushing/nether_quartz_ore {"ingredients":[{"item":"minecraft:nether_quartz_ore"}],"processing_time":350,"results":[{"count":2,"id":"sparse_ores:quartz_chip"},{"chance":0.8,"id":"sparse_ores:quartz_chip"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:netherrack"}],"type":"create:crushing"}
            create:crushing minecraft:raw_copper create:crushing/raw_copper {"ingredients":[{"tag":"c:raw_materials/copper"}],"processing_time":400,"results":[{"id":"create:crushed_raw_copper"},{"chance":0.75,"id":"create:experience_nugget"}],"type":"create:crushing"}
            create:crushing minecraft:raw_gold create:crushing/raw_gold {"ingredients":[{"tag":"c:raw_materials/gold"}],"processing_time":400,"results":[{"id":"create:crushed_raw_gold"},{"chance":0.75,"count":2,"id":"create:experience_nugget"}],"type":"create:crushing"}
            create:crushing minecraft:raw_iron create:crushing/raw_iron {"ingredients":[{"tag":"c:raw_materials/iron"}],"processing_time":400,"results":[{"id":"create:crushed_raw_iron"},{"chance":0.75,"id":"create:experience_nugget"}],"type":"create:crushing"}
            create:crushing minecraft:redstone_ore create:crushing/redstone_ore {"ingredients":[{"item":"minecraft:redstone_ore"}],"processing_time":250,"results":[{"count":2,"id":"minecraft:redstone"},{"chance":0.2,"id":"minecraft:redstone"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_coal_ore engines_and_empires:crushing/rich_coal_ore {"ingredients":[{"item":"sparse_ores:rich_coal_ore"}],"processing_time":150,"results":[{"count":10,"id":"sparse_ores:coal_chip"},{"chance":0.95,"id":"sparse_ores:coal_chip"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_copper_ore engines_and_empires:crushing/rich_copper_ore {"ingredients":[{"item":"sparse_ores:rich_copper_ore"}],"processing_time":250,"results":[{"count":26,"id":"create:crushed_raw_copper"},{"chance":0.25,"id":"create:crushed_raw_copper"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_deepslate_coal_ore engines_and_empires:crushing/rich_deepslate_coal_ore {"ingredients":[{"item":"sparse_ores:rich_deepslate_coal_ore"}],"processing_time":300,"results":[{"count":14,"id":"sparse_ores:coal_chip"},{"chance":0.05,"id":"sparse_ores:coal_chip"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_deepslate_copper_ore engines_and_empires:crushing/rich_deepslate_copper_ore {"ingredients":[{"item":"sparse_ores:rich_deepslate_copper_ore"}],"processing_time":350,"results":[{"count":36,"id":"create:crushed_raw_copper"},{"chance":0.25,"id":"create:crushed_raw_copper"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_deepslate_diamond_ore engines_and_empires:crushing/rich_deepslate_diamond_ore {"ingredients":[{"item":"sparse_ores:rich_deepslate_diamond_ore"}],"processing_time":450,"results":[{"count":14,"id":"sparse_ores:diamond_chip"},{"chance":0.05,"id":"sparse_ores:diamond_chip"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_deepslate_emerald_ore engines_and_empires:crushing/rich_deepslate_emerald_ore {"ingredients":[{"item":"sparse_ores:rich_deepslate_emerald_ore"}],"processing_time":450,"results":[{"count":11,"id":"minecraft:emerald"},{"chance":0.25,"id":"minecraft:emerald"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_deepslate_gold_ore engines_and_empires:crushing/rich_deepslate_gold_ore {"ingredients":[{"item":"sparse_ores:rich_deepslate_gold_ore"}],"processing_time":350,"results":[{"count":11,"id":"create:crushed_raw_gold"},{"chance":0.25,"id":"create:crushed_raw_gold"},{"count":7,"id":"create:experience_nugget"},{"chance":0.5,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_deepslate_iron_ore engines_and_empires:crushing/rich_deepslate_iron_ore {"ingredients":[{"item":"sparse_ores:rich_deepslate_iron_ore"}],"processing_time":350,"results":[{"count":11,"id":"create:crushed_raw_iron"},{"chance":0.25,"id":"create:crushed_raw_iron"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_deepslate_lapis_ore engines_and_empires:crushing/rich_deepslate_lapis_ore {"ingredients":[{"item":"sparse_ores:rich_deepslate_lapis_ore"}],"processing_time":350,"results":[{"count":21,"id":"minecraft:lapis_lazuli"},{"chance":0.1,"id":"minecraft:lapis_lazuli"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_deepslate_redstone_ore engines_and_empires:crushing/rich_deepslate_redstone_ore {"ingredients":[{"item":"sparse_ores:rich_deepslate_redstone_ore"}],"processing_time":350,"results":[{"count":14,"id":"minecraft:redstone"},{"chance":0.05,"id":"minecraft:redstone"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_deepslate_zinc_ore engines_and_empires:crushing/rich_deepslate_zinc_ore {"ingredients":[{"item":"sparse_ores:rich_deepslate_zinc_ore"}],"processing_time":350,"results":[{"count":11,"id":"create:crushed_raw_zinc"},{"chance":0.25,"id":"create:crushed_raw_zinc"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobbled_deepslate"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_diamond_ore engines_and_empires:crushing/rich_diamond_ore {"ingredients":[{"item":"sparse_ores:rich_diamond_ore"}],"processing_time":350,"results":[{"count":10,"id":"sparse_ores:diamond_chip"},{"chance":0.95,"id":"sparse_ores:diamond_chip"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_emerald_ore engines_and_empires:crushing/rich_emerald_ore {"ingredients":[{"item":"sparse_ores:rich_emerald_ore"}],"processing_time":350,"results":[{"count":8,"id":"minecraft:emerald"},{"chance":0.75,"id":"minecraft:emerald"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_gold_ore engines_and_empires:crushing/rich_gold_ore {"ingredients":[{"item":"sparse_ores:rich_gold_ore"}],"processing_time":250,"results":[{"count":8,"id":"create:crushed_raw_gold"},{"chance":0.75,"id":"create:crushed_raw_gold"},{"count":7,"id":"create:experience_nugget"},{"chance":0.5,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_iron_ore engines_and_empires:crushing/rich_iron_ore {"ingredients":[{"item":"sparse_ores:rich_iron_ore"}],"processing_time":250,"results":[{"count":8,"id":"create:crushed_raw_iron"},{"chance":0.75,"id":"create:crushed_raw_iron"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_lapis_ore engines_and_empires:crushing/rich_lapis_ore {"ingredients":[{"item":"sparse_ores:rich_lapis_ore"}],"processing_time":250,"results":[{"count":16,"id":"minecraft:lapis_lazuli"},{"chance":0.4,"id":"minecraft:lapis_lazuli"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_nether_gold_ore engines_and_empires:crushing/rich_nether_gold_ore {"ingredients":[{"item":"sparse_ores:rich_nether_gold_ore"}],"processing_time":350,"results":[{"count":14,"id":"minecraft:gold_nugget"},{"chance":0.05,"id":"minecraft:gold_nugget"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:netherrack"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_nether_quartz_ore engines_and_empires:crushing/rich_nether_quartz_ore {"ingredients":[{"item":"sparse_ores:rich_nether_quartz_ore"}],"processing_time":350,"results":[{"count":14,"id":"sparse_ores:quartz_chip"},{"chance":0.05,"id":"sparse_ores:quartz_chip"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:netherrack"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_redstone_ore engines_and_empires:crushing/rich_redstone_ore {"ingredients":[{"item":"sparse_ores:rich_redstone_ore"}],"processing_time":250,"results":[{"count":10,"id":"minecraft:redstone"},{"chance":0.95,"id":"minecraft:redstone"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:crushing sparse_ores:rich_zinc_ore engines_and_empires:crushing/rich_zinc_ore {"ingredients":[{"item":"sparse_ores:rich_zinc_ore"}],"processing_time":250,"results":[{"count":8,"id":"create:crushed_raw_zinc"},{"chance":0.75,"id":"create:crushed_raw_zinc"},{"count":3,"id":"create:experience_nugget"},{"chance":0.75,"id":"create:experience_nugget"},{"chance":0.125,"id":"minecraft:cobblestone"}],"type":"create:crushing"}
            create:haunting minecraft:red_sand create:haunting/soul_sand {"ingredients":[{"tag":"minecraft:sand"}],"results":[{"id":"minecraft:soul_sand"}],"type":"create:haunting"}
            create:milling minecraft:gravel create:milling/gravel {"ingredients":[{"item":"minecraft:gravel"}],"processing_time":250,"results":[{"id":"minecraft:flint"}],"type":"create:milling"}
            create:mixing minecraft:gravel createdieselgenerators:mixing/asphalt_block {"ingredients":[{"item":"minecraft:gravel"},{"item":"minecraft:gravel"},{"item":"minecraft:sand"},{"item":"minecraft:sand"},{"amount":100,"tag":"c:crude_oil","type":"neoforge:tag"}],"results":[{"count":4,"id":"createdieselgenerators:asphalt_block"}],"type":"create:mixing"}
            create:splashing create:crushed_raw_copper create:splashing/crushed_raw_copper {"ingredients":[{"item":"create:crushed_raw_copper"}],"results":[{"count":2,"id":"create:copper_nugget"},{"chance":0.5,"id":"create:copper_nugget"},{"chance":0.5,"id":"minecraft:clay_ball"}],"type":"create:splashing"}
            create:splashing create:crushed_raw_gold create:splashing/crushed_raw_gold {"ingredients":[{"item":"create:crushed_raw_gold"}],"results":[{"count":2,"id":"minecraft:gold_nugget"},{"chance":0.5,"id":"minecraft:gold_nugget"},{"chance":0.5,"id":"minecraft:quartz"}],"type":"create:splashing"}
            create:splashing create:crushed_raw_iron create:splashing/crushed_raw_iron {"ingredients":[{"item":"create:crushed_raw_iron"}],"results":[{"count":2,"id":"minecraft:iron_nugget"},{"chance":0.5,"id":"minecraft:iron_nugget"},{"chance":0.75,"id":"minecraft:redstone"}],"type":"create:splashing"}
            create:splashing create:crushed_raw_zinc create:splashing/crushed_raw_zinc {"ingredients":[{"item":"create:crushed_raw_zinc"}],"results":[{"count":2,"id":"create:zinc_nugget"},{"chance":0.5,"id":"create:zinc_nugget"},{"chance":0.25,"id":"minecraft:gunpowder"}],"type":"create:splashing"}
            create:splashing minecraft:gravel create:splashing/gravel {"ingredients":[{"item":"minecraft:gravel"}],"results":[{"chance":0.25,"id":"minecraft:flint"},{"chance":0.03,"id":"minecraft:iron_nugget"}],"type":"create:splashing"}
            create:splashing minecraft:red_sand create:splashing/red_sand {"ingredients":[{"item":"minecraft:red_sand"}],"results":[{"chance":0.085,"id":"minecraft:gold_nugget"},{"chance":0.05,"id":"minecraft:dead_bush"}],"type":"create:splashing"}
            create:splashing minecraft:soul_sand create:splashing/soul_sand {"ingredients":[{"item":"minecraft:soul_sand"}],"results":[{"chance":0.445,"id":"sparse_ores:quartz_chip"},{"chance":0.005,"id":"minecraft:gold_nugget"}],"type":"create:splashing"}
            """;

    private PackRecipesGameTests() {
    }
}
