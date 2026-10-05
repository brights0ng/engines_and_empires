package dev.brights0ng.enginesandempires.gametest;

import java.util.ArrayList;
import java.util.List;

import com.khofonyx.encumbered.common.events.PlayerWeightHandler;
import com.khofonyx.encumbered.datamaps.EncumberedDataMaps;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.refining.RefiningItems;
import dev.brights0ng.enginesandempires.oregen.rich.RichOreBlocks;
import dev.brights0ng.enginesandempires.weight.ItemWeights;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game checks of item carry weights: that Encumbered really asks the pack's rules, that blocks match their Create
 * Aeronautics mass (ore blocks now 2), that each group and the tool/armour materials weigh what was chosen, and that
 * every item in the game lands on one of the allowed steps.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class WeightGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(template = SCRATCH)
    public static void blocksWeighTheirAeronauticsMass(GameTestHelper helper) {
        // Wood is "light" in Aeronautics, wool and leaves "super light"
        expect(helper, Items.OAK_PLANKS, 0.5f);
        expect(helper, Items.OAK_LOG, 0.5f);
        expect(helper, Items.WHITE_WOOL, 0.25f);
        expect(helper, Items.DIRT, 1f);
        expect(helper, Items.STONE, 2f);
        expect(helper, Items.COBBLESTONE, 2f);
        expect(helper, Items.ANDESITE, 2f);
        expect(helper, Items.STONE_SLAB, 1f);
        expect(helper, Items.IRON_ORE, 2f);
        expect(helper, Items.DEEPSLATE_IRON_ORE, 2f);
        expect(helper, Items.NETHER_QUARTZ_ORE, 2f);
        expect(helper, RichOreBlocks.block("rich_iron_ore").asItem(), 2f);
        expect(helper, Items.IRON_BLOCK, 4f);
        expect(helper, Items.RAW_IRON_BLOCK, 4f);
        expect(helper, Items.TORCH, ItemWeights.SMALL_PLACEABLE);
        expect(helper, Items.RAIL, ItemWeights.SMALL_PLACEABLE);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void groupsHaveTheirWeights(GameTestHelper helper) {
        expect(helper, Items.IRON_INGOT, 0.5f);
        expect(helper, Items.RAW_IRON, 0.5f);
        expect(helper, item("create:crushed_raw_iron"), 0.5f);
        expect(helper, item("create:brass_ingot"), 0.5f);
        expect(helper, Items.DIAMOND, 0.5f);
        expect(helper, Items.LAPIS_LAZULI, 0.5f);
        expect(helper, Items.REDSTONE, 0.5f);
        expect(helper, Items.COAL, 0.5f);
        expect(helper, Items.IRON_NUGGET, 0.05f);
        expect(helper, RefiningItems.chip("coal_chip"), 0.1f);
        expect(helper, Items.BREAD, 0.1f);
        expect(helper, Items.WHEAT, 0.1f);
        expect(helper, Items.WHEAT_SEEDS, 0.05f);
        expect(helper, Items.OAK_SAPLING, 0.05f);
        expect(helper, Items.STRING, 0.05f);
        expect(helper, Items.STICK, 0.05f);
        expect(helper, Items.BLUE_DYE, 0.05f);
        expect(helper, Items.BONE_MEAL, 0.05f);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void toolsAndArmourWeighByMaterial(GameTestHelper helper) {
        expect(helper, Items.WOODEN_PICKAXE, 0.5f);
        expect(helper, Items.STONE_AXE, 1f);
        expect(helper, Items.IRON_PICKAXE, 1f);
        expect(helper, Items.GOLDEN_SWORD, 1f);
        expect(helper, Items.DIAMOND_SWORD, 2f);
        expect(helper, Items.NETHERITE_SHOVEL, 2f);
        expect(helper, Items.LEATHER_HELMET, 0.5f);
        expect(helper, Items.CHAINMAIL_LEGGINGS, 1f);
        expect(helper, Items.IRON_CHESTPLATE, 1f);
        expect(helper, Items.DIAMOND_BOOTS, 2f);
        expect(helper, Items.BOW, 1f);
        expect(helper, Items.SHIELD, 1f);
        expect(helper, Items.ELYTRA, 1f);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void unsortedItemsGetTheFallback(GameTestHelper helper) {
        expect(helper, Items.BUCKET, ItemWeights.FALLBACK);
        expect(helper, item("create:precision_mechanism"), ItemWeights.FALLBACK);
        helper.succeed();
    }

    /** Every item weighs one of the allowed steps (bedrock, at Aeronautics' 1000, is the one exception). */
    @GameTest(template = SCRATCH)
    public static void everyWeightIsAnAllowedStep(GameTestHelper helper) {
        List<String> off = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.asHolderIdMap()) {
            if (holder.value() == Items.BEDROCK || holder.value() == Items.AIR) {
                continue;
            }
            float w = ItemWeights.of(holder);
            if (!isStep(w)) {
                off.add(BuiltInRegistries.ITEM.getKey(holder.value()) + "=" + w);
            }
        }
        helper.assertTrue(off.isEmpty(), off.size() + " items off the allowed steps: " + String.join(", ", off.subList(0, Math.min(20, off.size()))));
        helper.succeed();
    }

    /** Encumbered's own total for a player carrying the target load comes out in our weights. */
    @GameTest(template = SCRATCH)
    public static void encumberedCountsOurWeights(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getInventory().add(new ItemStack(Items.STONE, 64));
        player.getInventory().add(new ItemStack(Items.RAW_IRON, 64));
        player.getInventory().add(new ItemStack(Items.RAW_IRON, 64));
        player.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        // 64 x 2 + 128 x 0.5 + 1
        float total = PlayerWeightHandler.calculateWeight(player);
        helper.assertTrue(Math.abs(total - 193f) < 0.01f, "Encumbered totalled " + total + " kpg, expected 193");
        helper.assertTrue(EncumberedDataMaps.getWeight(Items.IRON_INGOT.builtInRegistryHolder()) == 0.5f,
                "Encumbered's lookup did not go through the pack's weights");
        helper.succeed();
    }

    private static void expect(GameTestHelper helper, Item item, float kpg) {
        float actual = ItemWeights.of(item);
        helper.assertTrue(Math.abs(actual - kpg) < 1e-4f,
                BuiltInRegistries.ITEM.getKey(item) + " weighs " + actual + " kpg (" + ItemWeights.resolve(item.builtInRegistryHolder()).source()
                        + "), expected " + kpg);
    }

    private static boolean isStep(float w) {
        for (float step : ItemWeights.STEPS) {
            if (Math.abs(step - w) < 1e-4f) {
                return true;
            }
        }
        return false;
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    private WeightGameTests() {
    }
}
