package dev.brights0ng.enginesandempires.gametest;

import java.util.List;
import java.util.Optional;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.refining.BoreheadCrushing;
import dev.brights0ng.enginesandempires.oregen.refining.RefiningItems;
import dev.brights0ng.enginesandempires.oregen.rich.RichOreBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game checks of ore refining: that the pack's recipes really replace vanilla's and Create's once everything is
 * loaded (the files could exist and still lose to theirs), that the cut ores drop what they should, and that the
 * borehead bearing crushes ore.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class RefiningGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(template = SCRATCH)
    public static void rawIronSmeltsIntoTwoNuggets(GameTestHelper helper) {
        ItemStack result = cook(helper, RecipeType.SMELTING, Items.RAW_IRON);
        helper.assertTrue(result.is(Items.IRON_NUGGET) && result.getCount() == 2, "raw iron smelted into " + result);
        ItemStack blasted = cook(helper, RecipeType.BLASTING, Items.DEEPSLATE_IRON_ORE);
        helper.assertTrue(blasted.is(Items.IRON_NUGGET) && blasted.getCount() == 2, "deepslate iron ore blasted into " + blasted);
        ItemStack coal = cook(helper, RecipeType.SMELTING, Items.COAL_ORE);
        helper.assertTrue(coal.is(RefiningItems.chip("coal_chip")) && coal.getCount() == 1, "coal ore smelted into " + coal);
        ItemStack richIron = cook(helper, RecipeType.SMELTING, RichOreBlocks.block("rich_iron_ore").asItem());
        helper.assertTrue(richIron.is(Items.IRON_NUGGET) && richIron.getCount() == 10, "rich iron ore smelted into " + richIron);
        helper.succeed();
    }

    /** Create's own recipe ids are replaced: this only passes if the pack's data loads after Create's. */
    @GameTest(template = SCRATCH)
    public static void createRecipesAreReplaced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack zinc = cook(helper, RecipeType.SMELTING, item("create:crushed_raw_zinc"));
        helper.assertTrue(zinc.is(item("create:zinc_nugget")) && zinc.getCount() == 2, "crushed zinc smelted into " + zinc);

        List<ItemStack> netherGold = crushingOutputs(level, Items.NETHER_GOLD_ORE);
        int nuggets = count(netherGold, Items.GOLD_NUGGET);
        helper.assertTrue(nuggets >= 2 && nuggets <= 3, "nether gold ore crushed into " + nuggets + " nuggets (Create's is 18)");

        Optional<RecipeHolder<Recipe<SingleRecipeInput>>> washing = AllRecipeTypes.SPLASHING
                .find(new SingleRecipeInput(new ItemStack(item("create:crushed_raw_iron"))), level);
        helper.assertTrue(washing.isPresent(), "no washing recipe for crushed iron");
        List<ItemStack> washed = ((ProcessingRecipe<?, ?>) washing.get().value()).getRollableResultsAsItemStacks();
        helper.assertTrue(count(washed, Items.IRON_NUGGET) == 3, "washing crushed iron lists " + washed);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void cutOresDropChipsAndLess(GameTestHelper helper) {
        assertDrops(helper, Blocks.COAL_ORE, RefiningItems.chip("coal_chip"), 1, 1);
        assertDrops(helper, Blocks.DEEPSLATE_DIAMOND_ORE, RefiningItems.chip("diamond_chip"), 1, 1);
        assertDrops(helper, Blocks.NETHER_QUARTZ_ORE, RefiningItems.chip("quartz_chip"), 1, 1);
        assertDrops(helper, Blocks.REDSTONE_ORE, Items.REDSTONE, 1, 1);
        assertDrops(helper, Blocks.LAPIS_ORE, Items.LAPIS_LAZULI, 1, 2);
        assertDrops(helper, Blocks.NETHER_GOLD_ORE, Items.GOLD_NUGGET, 1, 1);
        assertDrops(helper, Blocks.IRON_ORE, Items.RAW_IRON, 1, 1);
        assertDrops(helper, RichOreBlocks.block("rich_coal_ore"), RefiningItems.chip("coal_chip"), 5, 5);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void boreheadCrushesOresButNotStone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ItemStack> iron = BoreheadCrushing.crush(level, Blocks.IRON_ORE.defaultBlockState());
        helper.assertTrue(iron != null && count(iron, item("create:crushed_raw_iron")) >= 1, "iron ore gave " + iron);
        helper.assertTrue(count(iron, Items.RAW_IRON) == 0, "the borehead should not drop raw iron");

        List<ItemStack> rich = BoreheadCrushing.crush(level, RichOreBlocks.state("rich_iron_ore"));
        helper.assertTrue(rich != null && count(rich, item("create:crushed_raw_iron")) >= 8, "rich iron ore gave " + rich);

        List<ItemStack> coal = BoreheadCrushing.crush(level, Blocks.DEEPSLATE_COAL_ORE.defaultBlockState());
        helper.assertTrue(coal != null && count(coal, RefiningItems.chip("coal_chip")) >= 2, "deepslate coal ore gave " + coal);

        helper.assertTrue(BoreheadCrushing.crush(level, Blocks.STONE.defaultBlockState()) == null, "stone should drop as normal");
        helper.assertTrue(BoreheadCrushing.crush(level, Blocks.GRAVEL.defaultBlockState()) == null, "gravel should drop as normal");
        helper.succeed();
    }

    /**
     * Loads the class the borehead mixin changes. The mixin is applied as it loads, and must match (defaultRequire), so
     * this fails if an Aeronautics update moves the call it wraps, instead of the borehead quietly dropping raw ore.
     */
    @GameTest(template = SCRATCH)
    public static void boreheadMixinApplies(GameTestHelper helper) {
        try {
            Class<?> target = Class.forName("dev.ryanhcode.offroad.handlers.server.MultiMiningServerManager$BlockBreakingData");
            boolean mixed = false;
            for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
                mixed |= method.getName().contains("crushOres");
            }
            helper.assertTrue(mixed, "the borehead mixin did not apply");
        } catch (ClassNotFoundException | LinkageError e) {
            helper.fail("could not load the borehead's block breaking: " + e);
        }
        helper.succeed();
    }

    // ---- helpers ----

    private static ItemStack cook(GameTestHelper helper, RecipeType<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> type, Item input) {
        ServerLevel level = helper.getLevel();
        @SuppressWarnings("unchecked")
        RecipeType<net.minecraft.world.item.crafting.AbstractCookingRecipe> cooking =
                (RecipeType<net.minecraft.world.item.crafting.AbstractCookingRecipe>) type;
        return level.getRecipeManager().getRecipeFor(cooking, new SingleRecipeInput(new ItemStack(input)), level)
                .map(holder -> holder.value().getResultItem(level.registryAccess()).copy())
                .orElse(ItemStack.EMPTY);
    }

    private static List<ItemStack> crushingOutputs(ServerLevel level, Item input) {
        Optional<RecipeHolder<Recipe<SingleRecipeInput>>> recipe = AllRecipeTypes.CRUSHING.find(new SingleRecipeInput(new ItemStack(input)), level);
        if (recipe.isEmpty()) {
            return List.of();
        }
        return ((ProcessingRecipe<?, ?>) recipe.get().value()).rollResults(level.random);
    }

    /** Mines the block 20 times with a plain iron pickaxe (no Fortune or Silk Touch), checking every drop. */
    private static void assertDrops(GameTestHelper helper, Block block, Item expected, int min, int max) {
        BlockState state = block.defaultBlockState();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        for (int i = 0; i < 20; i++) {
            List<ItemStack> drops = Block.getDrops(state, helper.getLevel(), pos, null, null, new ItemStack(Items.IRON_PICKAXE));
            int n = count(drops, expected);
            helper.assertTrue(n >= min && n <= max, BuiltInRegistries.BLOCK.getKey(block) + " dropped " + drops);
        }
    }

    private static int count(List<ItemStack> stacks, Item item) {
        int n = 0;
        for (ItemStack stack : stacks) {
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    private RefiningGameTests() {
    }
}
