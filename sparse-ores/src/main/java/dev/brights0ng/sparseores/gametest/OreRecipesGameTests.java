package dev.brights0ng.sparseores.gametest;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.sparseores.SparseOres;
import dev.brights0ng.sparseores.SparseOresConfig;
import dev.brights0ng.sparseores.chips.ChipRecipes;
import dev.brights0ng.sparseores.chips.Chips;
import dev.brights0ng.sparseores.rich.RichOreBlocks;
import dev.brights0ng.sparseores.rich.RichOres;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.conditions.WithConditions;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Sparse Ores' own recipes, in game (split step 4c): every rich ore smelts and blasts into five times what its ordinary
 * block gives, the chip ores smelt into what mining them drops (one chip), the chip recipes give way to vanilla's whole
 * items when the startup config switches chip drops off, and the rich ores' crushing recipes load exactly when Create
 * does.
 *
 * <p>In Sparse Ores' bare run the ordinary blocks' recipes are vanilla's, apart from the
 * chip ores', so "five times its ordinary block" is five times vanilla; with Create there is crushing too. The checks
 * hold in any run that loads Sparse Ores: a pack that refines ore differently (the Engines and Empires core) replaces
 * the ordinary and the rich blocks' recipes alike.
 */
@GameTestHolder(SparseOres.MODID)
@PrefixGameTestTemplate(false)
public final class OreRecipesGameTests {

    @GameTest(template = GameTestStructures.EMPTY)
    public static void richOresSmeltIntoFiveTimesTheirOrdinaryBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (RecipeType<? extends AbstractCookingRecipe> type : List.of(RecipeType.SMELTING, RecipeType.BLASTING)) {
            for (RichOres.Variant variant : RichOres.allVariants()) {
                if (!RichOreBlocks.exists(variant.name())) {
                    continue; // rich zinc ore, without Create
                }
                List<ItemStack> ordinary = cooked(level, type, item(variant.counterpart()));
                List<ItemStack> rich = cooked(level, type, RichOreBlocks.block(variant.name()).asItem());
                if (ordinary.size() != 1 || rich.size() != 1) {
                    wrong.add(type + " " + variant.name() + ": " + ordinary.size() + " recipes take the ordinary block, "
                            + rich.size() + " the rich one (expected one each)");
                } else if (!ItemStack.isSameItemSameComponents(ordinary.get(0), rich.get(0))
                        || rich.get(0).getCount() != ordinary.get(0).getCount() * RichOres.RICHNESS) {
                    wrong.add(type + " " + variant.name() + " gives " + rich.get(0) + ", its ordinary block " + ordinary.get(0));
                }
                checked++;
            }
        }
        helper.assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        helper.assertTrue(checked >= 36, "only " + checked + " rich ore recipes checked");
        helper.succeed();
    }

    /** A silk-touched chip ore, ordinary or rich, smelts and blasts into exactly what mining it with a pickaxe drops. */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void chipOresSmeltIntoWhatMiningThemDrops(GameTestHelper helper) {
        helper.assertTrue(SparseOresConfig.chipDrops(), "chip drops are on by default");
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        for (RichOres.Spec spec : Chips.ores()) {
            Item chip = item(Chips.chipped(spec).drop());
            for (RichOres.Variant variant : spec.variants()) {
                for (Block block : List.of(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(variant.counterpart())),
                        RichOreBlocks.block(variant.name()))) {
                    List<ItemStack> drops = Block.getDrops(block.defaultBlockState(), level, pos, null, null,
                            new ItemStack(Items.DIAMOND_PICKAXE));
                    helper.assertTrue(drops.size() == 1 && drops.get(0).is(chip), block + " dropped " + drops);
                    for (RecipeType<? extends AbstractCookingRecipe> type : List.of(RecipeType.SMELTING, RecipeType.BLASTING)) {
                        List<ItemStack> cooked = cooked(level, type, block.asItem());
                        helper.assertTrue(cooked.size() == 1 && cooked.get(0).is(chip)
                                && cooked.get(0).getCount() == drops.get(0).getCount(),
                                type + " " + block + " gives " + cooked + ", mining it " + drops);
                    }
                }
            }
        }
        helper.succeed();
    }

    /**
     * The chip ores' smelting follows {@code chipDrops}: the chip recipes (under vanilla's ids) load only with it on, and
     * their whole-item twins only with it off, read from the recipe files the server has and tested as the recipe
     * manager tests them while loading. The setting is put back before the test returns, all on the server thread.
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void chipOreSmeltingFollowsTheChipDropsSwitch(GameTestHelper helper) {
        helper.assertTrue(SparseOresConfig.SPEC.isLoaded(), "Sparse Ores' startup config is not loaded");
        ServerLevel level = helper.getLevel();
        List<String> chipFiles = new ArrayList<>();
        for (String path : ChipRecipes.files().keySet()) {
            if (path.startsWith("minecraft/recipe/")) {
                chipFiles.add(path);
            }
        }
        helper.assertTrue(chipFiles.size() == 10, "expected the 10 chip ore furnace recipes, found " + chipFiles);
        boolean before = SparseOresConfig.CHIP_DROPS.get();
        try {
            for (boolean chipDrops : new boolean[] {true, false}) {
                SparseOresConfig.setChipDropsForTests(chipDrops);
                for (String path : chipFiles) {
                    String twin = SparseOres.MODID + "/recipe/" + ChipRecipes.WHOLE_ITEMS + "/"
                            + path.substring("minecraft/recipe/".length());
                    Optional<Recipe<?>> chipRecipe = load(level, path);
                    Optional<Recipe<?>> wholeRecipe = load(level, twin);
                    String what = path + " with chip drops " + (chipDrops ? "on" : "off");
                    helper.assertTrue(chipRecipe.isPresent() == chipDrops, what + ": the chip recipe loads " + chipRecipe.isPresent());
                    helper.assertTrue(wholeRecipe.isPresent() != chipDrops, what + ": the whole-item one loads " + wholeRecipe.isPresent());
                    ItemStack result = (chipRecipe.isPresent() ? chipRecipe : wholeRecipe).orElseThrow()
                            .getResultItem(level.registryAccess());
                    Chips.Chip chip = Chips.forChipId(BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
                    helper.assertTrue(chipDrops == (chip != null) && result.getCount() == 1, what + " gives " + result);
                }
            }
        } finally {
            SparseOresConfig.setChipDropsForTests(before);
        }
        helper.succeed();
    }

    /**
     * Rich ores' crushing recipes load when Create does, one for each rich ore, giving five times what crushing its
     * ordinary block gives; without Create they are skipped quietly (they name Create's recipe type and items).
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void richOresCrushOnlyWithCreate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        boolean create = ModList.get().isLoaded("create");
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (RichOres.Variant variant : RichOres.allVariants()) {
            ResourceLocation id = SparseOres.id("crushing/" + variant.name());
            helper.assertTrue(level.getServer().getResourceManager().getResource(
                    ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "recipe/" + id.getPath() + ".json")).isPresent(),
                    "Sparse Ores ships no recipe file for " + id);
            Optional<RecipeHolder<?>> rich = level.getRecipeManager().byKey(id);
            if (!create) {
                if (rich.isPresent()) {
                    wrong.add(id + " loaded without Create");
                }
                continue;
            }
            List<JsonObject> ordinary = crushing(level, item(variant.counterpart()));
            List<JsonObject> richRecipes = crushing(level, RichOreBlocks.block(variant.name()).asItem());
            if (rich.isEmpty() || ordinary.size() != 1 || richRecipes.size() != 1) {
                wrong.add(variant.name() + ": " + id + " loaded " + rich.isPresent() + ", " + ordinary.size()
                        + " crushing recipes take the ordinary block, " + richRecipes.size() + " the rich one");
                continue;
            }
            String main = mainItem(ordinary.get(0));
            double expected = mean(ordinary.get(0), main) * RichOres.RICHNESS;
            double actual = mean(richRecipes.get(0), main);
            // Each recipe rounds its chance of one more to 5%, so five ordinary ones and a rich one differ by up to
            // 6 x 0.025.
            if (!main.equals(mainItem(richRecipes.get(0))) || Math.abs(actual - expected) > 0.151) {
                wrong.add(variant.name() + " crushes into " + actual + " " + mainItem(richRecipes.get(0)) + ", expected "
                        + expected + " " + main);
            }
            checked++;
        }
        helper.assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        helper.assertTrue(!create || checked == 20, "only " + checked + " rich ores' crushing checked");
        helper.succeed();
    }


    /**
     * With Create, coal, diamond and nether quartz ore, ordinary and rich, crush into chips and no whole item: 2.2 chips
     * from the stone ore and 2.8 from the deepslate and nether ore (docs/design/ore-refining.md), five times that from the
     * rich block. Without Create there is no crushing recipe at all.
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void chipOresCrushIntoChips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        boolean create = ModList.get().isLoaded("create");
        helper.assertTrue(SparseOresConfig.chipDrops(), "chip drops are on by default");
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (RichOres.Spec spec : Chips.ores()) {
            Chips.Chip chip = Chips.forItem(spec.drop());
            for (RichOres.Variant variant : spec.variants()) {
                boolean deep = variant.ground() != RichOres.Ground.STONE;
                ResourceLocation ordinaryId = ResourceLocation.fromNamespaceAndPath("create",
                        "crushing/" + variant.counterpart().substring(variant.counterpart().indexOf(':') + 1));
                ResourceLocation richId = SparseOres.id("crushing/" + variant.name());
                if (!create) {
                    if (level.getRecipeManager().byKey(ordinaryId).isPresent() || level.getRecipeManager().byKey(richId).isPresent()) {
                        wrong.add(variant.name() + " crushes without Create");
                    }
                    continue;
                }
                List<JsonObject> ordinary = crushing(level, item(variant.counterpart()));
                List<JsonObject> rich = crushing(level, RichOreBlocks.block(variant.name()).asItem());
                if (ordinary.size() != 1 || rich.size() != 1 || level.getRecipeManager().byKey(ordinaryId).isEmpty()
                        || level.getRecipeManager().byKey(richId).isEmpty()) {
                    wrong.add(variant.name() + ": " + ordinary.size() + " crushing recipes take the ordinary block, "
                            + rich.size() + " the rich one, " + ordinaryId + " loaded "
                            + level.getRecipeManager().byKey(ordinaryId).isPresent());
                    continue;
                }
                for (JsonObject recipe : List.of(ordinary.get(0), rich.get(0))) {
                    boolean isRich = recipe == rich.get(0);
                    String what = (isRich ? "rich " : "ordinary ") + variant.name();
                    if (!mainItem(recipe).equals(chip.id())) {
                        wrong.add(what + " crushes into " + mainItem(recipe) + ", not " + chip.id());
                    }
                    if (mean(recipe, chip.item()) != 0) {
                        wrong.add(what + " also gives the whole " + chip.item());
                    }
                    // 2.2 or 2.8 chips; the rich block's recipe rounds five times the lead once, not five times the rounded one.
                    double expected = isRich ? (deep ? 14.05 : 10.95) : (deep ? 2.8 : 2.2);
                    if (Math.abs(mean(recipe, chip.id()) - expected) > 1e-6) {
                        wrong.add(what + " gives " + mean(recipe, chip.id()) + " chips, expected " + expected);
                    }
                }
                checked++;
            }
        }
        helper.assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        helper.assertTrue(!create || checked == 5, "only " + checked + " chip ores' crushing checked");
        helper.succeed();
    }

    /**
     * The ordinary chip ores' crushing follows {@code chipDrops} like their smelting: the chip recipes (under Create's
     * ids) load only with it on, and their whole-item twins only with it off, read from the recipe files the server has and
     * tested as the recipe manager tests them while loading. Without Create neither loads. The twins are Create's own
     * recipes: the same results, time and ingredient as the file in Create's jar. Rich ores crush into chips either way.
     * The setting is put back before the test returns, all on the server thread.
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void chipOreCrushingFollowsTheChipDropsSwitch(GameTestHelper helper) {
        helper.assertTrue(SparseOresConfig.SPEC.isLoaded(), "Sparse Ores' startup config is not loaded");
        ServerLevel level = helper.getLevel();
        boolean create = ModList.get().isLoaded("create");
        List<String> chipFiles = new ArrayList<>();
        for (String path : ChipRecipes.files().keySet()) {
            if (path.startsWith("create/recipe/crushing/")) {
                chipFiles.add(path);
            }
        }
        helper.assertTrue(chipFiles.size() == 5, "expected the 5 chip ore crushing recipes, found " + chipFiles);
        boolean before = SparseOresConfig.CHIP_DROPS.get();
        try {
            for (boolean chipDrops : new boolean[] {true, false}) {
                SparseOresConfig.setChipDropsForTests(chipDrops);
                for (String path : chipFiles) {
                    String twin = SparseOres.MODID + "/recipe/" + ChipRecipes.WHOLE_ITEMS + "/"
                            + path.substring("create/recipe/".length());
                    String what = path + " with chip drops " + (chipDrops ? "on" : "off") + (create ? "" : ", without Create");
                    Optional<Recipe<?>> chipRecipe = create ? load(level, path) : Optional.empty();
                    Optional<Recipe<?>> wholeRecipe = create ? load(level, twin) : Optional.empty();
                    if (!create) {
                        helper.assertTrue(!conditionsHold(level, path) && !conditionsHold(level, twin),
                                what + ": neither loads without Create");
                        continue;
                    }
                    helper.assertTrue(chipRecipe.isPresent() == chipDrops, what + ": the chip recipe loads " + chipRecipe.isPresent());
                    helper.assertTrue(wholeRecipe.isPresent() != chipDrops, what + ": the whole-item one loads " + wholeRecipe.isPresent());
                    JsonObject loaded = encode(level, (chipRecipe.isPresent() ? chipRecipe : wholeRecipe).orElseThrow());
                    String block = path.substring(path.lastIndexOf('/') + 1, path.length() - ".json".length());
                    Chips.Chip chip = chipOfOre(block);
                    helper.assertTrue(mainItem(loaded).equals(chipDrops ? chip.id() : chip.item()),
                            what + " crushes into " + mainItem(loaded));
                    helper.assertTrue(mean(loaded, chipDrops ? chip.item() : chip.id()) == 0, what + " gives both kinds");
                }
            }
        } finally {
            SparseOresConfig.setChipDropsForTests(before);
        }
        if (create) {
            for (String path : chipFiles) {
                theTwinIsCreatesOwnRecipe(helper, level, path);
            }
            helper.assertTrue(SparseOresConfig.chipDrops() == before, "the setting is back");
        }
        helper.succeed();
    }

    /** The whole-item twin of a chip ore's crushing is the recipe Create ships under that id (its jar's file, which Sparse Ores' replaces). */
    private static void theTwinIsCreatesOwnRecipe(GameTestHelper helper, ServerLevel level, String chipPath) {
        String name = chipPath.substring("create/recipe/".length());
        ResourceLocation createFile = ResourceLocation.fromNamespaceAndPath("create", "recipe/" + name);
        ResourceLocation twinFile = ResourceLocation.fromNamespaceAndPath(SparseOres.MODID,
                "recipe/" + ChipRecipes.WHOLE_ITEMS + "/" + name);
        JsonObject createsOwn = null;
        for (Resource resource : level.getServer().getResourceManager().getResourceStack(createFile)) {
            if (resource.sourcePackId().equals("mod/create")) {
                createsOwn = json(resource);
            }
        }
        helper.assertTrue(createsOwn != null, "Create ships no " + createFile);
        JsonObject twin = json(level.getServer().getResourceManager().getResource(twinFile)
                .orElseThrow(() -> new IllegalStateException("no file " + twinFile)));
        for (String key : new String[] {"type", "ingredients", "processing_time", "results"}) {
            helper.assertTrue(createsOwn.get(key).equals(twin.get(key)),
                    name + ": " + key + " is " + twin.get(key) + ", Create's own is " + createsOwn.get(key));
        }
    }

    // ---- helpers ----

    /** The results of every furnace recipe of this type that takes the item. */
    private static List<ItemStack> cooked(ServerLevel level, RecipeType<? extends AbstractCookingRecipe> type, Item input) {
        List<ItemStack> results = new ArrayList<>();
        SingleRecipeInput in = new SingleRecipeInput(new ItemStack(input));
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            if (holder.value().getType() == type && holder.value() instanceof AbstractCookingRecipe cooking
                    && cooking.matches(in, level)) {
                results.add(cooking.getResultItem(level.registryAccess()).copy());
            }
        }
        return results;
    }

    /** Every Create crushing recipe that takes the item, as the game encodes it (no Create classes needed). */
    private static List<JsonObject> crushing(ServerLevel level, Item input) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        ResourceLocation type = ResourceLocation.fromNamespaceAndPath("create", "crushing");
        List<JsonObject> recipes = new ArrayList<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (type.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                    && recipe.getIngredients().stream().anyMatch(i -> i.test(new ItemStack(input)))) {
                recipes.add(Recipe.CODEC.encodeStart(ops, recipe).getOrThrow().getAsJsonObject());
            }
        }
        return recipes;
    }

    /** The item of a crushing recipe's first result: its main product. */
    private static String mainItem(JsonObject recipe) {
        return recipe.getAsJsonArray("results").get(0).getAsJsonObject().get("id").getAsString();
    }

    /** How many of the item a crushing recipe gives on average. */
    private static double mean(JsonObject recipe, String item) {
        double mean = 0;
        for (JsonElement element : recipe.getAsJsonArray("results")) {
            JsonObject result = element.getAsJsonObject();
            if (result.get("id").getAsString().equals(item)) {
                int count = result.has("count") ? result.get("count").getAsInt() : 1;
                double chance = result.has("chance") ? result.get("chance").getAsDouble() : 1;
                mean += count * chance;
            }
        }
        return mean;
    }

    /**
     * The recipe in the server's file at {@code data/<path>}, decoded as the recipe manager decodes recipes while
     * loading: empty if its conditions fail now.
     */
    private static Optional<Recipe<?>> load(ServerLevel level, String path) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(path.substring(0, path.indexOf('/')),
                path.substring(path.indexOf('/') + 1));
        Resource resource = level.getServer().getResourceManager().getResource(location)
                .orElseThrow(() -> new IllegalStateException("no file " + location));
        JsonElement json;
        try (Reader reader = resource.openAsReader()) {
            json = JsonParser.parseReader(reader);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + location, e);
        }
        ConditionalOps<JsonElement> ops = new ConditionalOps<>(
                RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), ICondition.IContext.EMPTY);
        return Recipe.CONDITIONAL_CODEC.parse(ops, json).getOrThrow().map(WithConditions::carrier);
    }

    /** The recipe as the game encodes it. */
    private static JsonObject encode(ServerLevel level, Recipe<?> recipe) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        return Recipe.CODEC.encodeStart(ops, recipe).getOrThrow().getAsJsonObject();
    }

    private static JsonObject json(Resource resource) {
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + resource.sourcePackId(), e);
        }
    }

    /** Whether the conditions of the recipe file at {@code data/<path>} hold now. */
    private static boolean conditionsHold(ServerLevel level, String path) {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(path.substring(0, path.indexOf('/')),
                path.substring(path.indexOf('/') + 1));
        JsonObject json = json(level.getServer().getResourceManager().getResource(location)
                .orElseThrow(() -> new IllegalStateException("no file " + location)));
        return ICondition.LIST_CODEC.parse(JsonOps.INSTANCE, json.get("neoforge:conditions")).getOrThrow().stream()
                .allMatch(condition -> condition.test(ICondition.IContext.EMPTY));
    }

    /** The chip of the chip ore block named by the last part of a crushing recipe's path, such as {@code deepslate_coal_ore}. */
    private static Chips.Chip chipOfOre(String block) {
        for (RichOres.Spec spec : Chips.ores()) {
            for (RichOres.Variant variant : spec.variants()) {
                if (variant.counterpart().equals("minecraft:" + block)) {
                    return Chips.forItem(spec.drop());
                }
            }
        }
        throw new IllegalArgumentException(block);
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    private OreRecipesGameTests() {
    }
}
