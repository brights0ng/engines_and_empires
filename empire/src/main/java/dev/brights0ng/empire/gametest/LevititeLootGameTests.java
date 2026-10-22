// Written by Claude.
package dev.brights0ng.empire.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Levitite and pearlescent levitite drop themselves only to a silk touch pickaxe of diamond or better, mined the way a
 * survival player mines (Aeronautics mixes into blocks and registers both without loot tables, so only real drops count).
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class LevititeLootGameTests {

    private static final BlockPos SPOT = new BlockPos(2, 2, 2);
    private static final List<String> LEVITITE = List.of("aeronautics:levitite", "aeronautics:pearlescent_levitite");

    @GameTest(template = GameTestStructures.EMPTY)
    public static void silkTouchDiamondPickaxeDropsLevitite(GameTestHelper helper) {
        for (String id : LEVITITE) {
            Block block = block(helper, id);
            helper.assertTrue(block.defaultBlockState().is(BlockTags.NEEDS_DIAMOND_TOOL), id + " needs a diamond tool");
            helper.assertTrue(block.defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE), id + " is mined with a pickaxe");
            List<ItemStack> drops = mine(helper, block, silkTouch(helper, Items.DIAMOND_PICKAXE));
            helper.assertTrue(drops.size() == 1 && drops.get(0).is(block.asItem()) && drops.get(0).getCount() == 1,
                    id + " should drop itself to a silk touch diamond pickaxe, dropped " + drops);
        }
        // Netherite is diamond or better too
        Block levitite = block(helper, LEVITITE.get(0));
        List<ItemStack> drops = mine(helper, levitite, silkTouch(helper, Items.NETHERITE_PICKAXE));
        helper.assertTrue(drops.size() == 1 && drops.get(0).is(levitite.asItem()),
                "levitite should drop itself to a silk touch netherite pickaxe, dropped " + drops);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void silkTouchIronPickaxeDropsNothing(GameTestHelper helper) {
        for (String id : LEVITITE) {
            List<ItemStack> drops = mine(helper, block(helper, id), silkTouch(helper, Items.IRON_PICKAXE));
            helper.assertTrue(drops.isEmpty(), id + " should drop nothing to a silk touch iron pickaxe, dropped " + drops);
        }
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void diamondPickaxeWithoutSilkTouchDropsNothing(GameTestHelper helper) {
        for (String id : LEVITITE) {
            List<ItemStack> drops = mine(helper, block(helper, id), new ItemStack(Items.DIAMOND_PICKAXE));
            helper.assertTrue(drops.isEmpty(), id + " should drop nothing to a plain diamond pickaxe, dropped " + drops);
        }
        helper.succeed();
    }

    // ---- helpers ----

    private static Block block(GameTestHelper helper, String id) {
        ResourceLocation rl = ResourceLocation.parse(id);
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(rl), id + " is not registered (is Create Aeronautics loaded?)");
        return BuiltInRegistries.BLOCK.get(rl);
    }

    private static ItemStack silkTouch(GameTestHelper helper, Item pickaxe) {
        ItemStack stack = new ItemStack(pickaxe);
        stack.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH), 1);
        return stack;
    }

    /** Places the block, has a survival player break it with {@code tool}, and returns (and removes) what it dropped. */
    private static List<ItemStack> mine(GameTestHelper helper, Block block, ItemStack tool) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(SPOT);
        AABB area = new AABB(pos).inflate(2);
        level.getEntitiesOfClass(ItemEntity.class, area).forEach(ItemEntity::discard);
        helper.setBlock(SPOT, block);

        FakePlayer miner = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "empire_levitite_miner"));
        miner.setGameMode(GameType.SURVIVAL);
        miner.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        miner.setItemInHand(InteractionHand.MAIN_HAND, tool);
        boolean broken = miner.gameMode.destroyBlock(pos);
        helper.assertTrue(broken && level.getBlockState(pos).isAir(), "the miner should break " + block + " with " + tool);

        List<ItemEntity> entities = level.getEntitiesOfClass(ItemEntity.class, area);
        List<ItemStack> drops = entities.stream().map(e -> e.getItem().copy()).toList();
        entities.forEach(ItemEntity::discard);
        helper.setBlock(SPOT, Blocks.AIR);
        return drops;
    }
}
