// Written by Gemini; Overworld-only registration and campfire-tag tests added by Claude.
package dev.brights0ng.empire.gametest;

import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.bonfire.BonfireBlockEntity;
import dev.brights0ng.empire.bonfire.BonfireEvents;
import dev.brights0ng.empire.bonfire.BonfireItem;
import dev.brights0ng.empire.bonfire.BonfirePartBlock;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class BonfireGameTests {

    private static final BlockPos SPOT = new BlockPos(3, 1, 3);

    @GameTest(template = GameTestStructures.EMPTY)
    public static void placeNeedsClear3x3x3(GameTestHelper helper) {
        clearArea(helper, SPOT);

        // Put an obstacle at (1, 1, 0)
        helper.setBlock(SPOT.offset(1, 1, 0), Blocks.STONE);
        helper.assertFalse(BonfireItem.canPlaceBonfire(helper.getLevel(), helper.absolutePos(SPOT), null),
                "Cannot place when an obstacle is present in the 3x3x3 area");

        // Clear the obstacle
        helper.setBlock(SPOT.offset(1, 1, 0), Blocks.AIR);
        helper.assertTrue(BonfireItem.canPlaceBonfire(helper.getLevel(), helper.absolutePos(SPOT), null),
                "Can place when all 27 positions are clear");

        // Place main and parts
        helper.setBlock(SPOT, EmpireContent.BONFIRE.get());
        BonfireItem.placeParts(helper.getLevel(), helper.absolutePos(SPOT));

        // Verify main block and all 26 parts
        helper.assertBlockPresent(EmpireContent.BONFIRE.get(), SPOT);
        for (int i = 0; i < 26; i++) {
            BlockPos partSpot = SPOT.offset(BonfirePartBlock.PART_OFFSETS[i]);
            helper.assertBlockPresent(EmpireContent.BONFIRE_PART.get(), partSpot);
            BlockState partState = helper.getBlockState(partSpot);
            helper.assertTrue(partState.getValue(BonfirePartBlock.OFFSET) == i,
                    "Part offset property matches index " + i);
        }

        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void breakingPartRemovesAll27AndDrops1(GameTestHelper helper) {
        clearArea(helper, SPOT);

        // Place main and parts
        helper.setBlock(SPOT, EmpireContent.BONFIRE.get());
        BonfireItem.placeParts(helper.getLevel(), helper.absolutePos(SPOT));

        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockPos partSpot = SPOT.offset(1, 0, 0);
        BlockState partState = helper.getBlockState(partSpot);

        // Break the part block
        partState.getBlock().playerWillDestroy(helper.getLevel(), helper.absolutePos(partSpot), partState, player);
        helper.destroyBlock(partSpot);

        // Assert all 27 blocks are now air
        helper.assertBlockPresent(Blocks.AIR, SPOT);
        for (BlockPos offset : BonfirePartBlock.PART_OFFSETS) {
            helper.assertBlockPresent(Blocks.AIR, SPOT.offset(offset));
        }

        // Assert exactly 1 bonfire item dropped
        helper.assertItemEntityPresent(EmpireContent.BONFIRE_ITEM.get(), SPOT, 4.0);

        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void litRoaringLitAfter600Ticks(GameTestHelper helper) {
        clearArea(helper, SPOT);

        helper.setBlock(SPOT, EmpireContent.BONFIRE.get().defaultBlockState()
                .setValue(BonfireBlock.LIT, true)
                .setValue(BonfireBlock.ROARING, true));

        BonfireBlockEntity be = (BonfireBlockEntity) helper.getBlockEntity(SPOT);
        helper.assertTrue(be != null, "BE exists");
        be.setRoarTicks(600);
        helper.assertTrue(helper.getBlockState(SPOT).getValue(BonfireBlock.ROARING), "Initial state is roaring");

        // Tick 599 times
        for (int i = 0; i < 599; i++) {
            BonfireBlockEntity.serverTick(helper.getLevel(), helper.absolutePos(SPOT), helper.getBlockState(SPOT), be);
        }
        helper.assertTrue(be.roarTicks() == 1, "1 tick remaining before roaring ends");
        helper.assertTrue(helper.getBlockState(SPOT).getValue(BonfireBlock.ROARING), "Still roaring at 1 tick");

        // Tick 600th time
        BonfireBlockEntity.serverTick(helper.getLevel(), helper.absolutePos(SPOT), helper.getBlockState(SPOT), be);
        helper.assertTrue(be.roarTicks() == 0, "Roar timer finished (0 ticks)");
        helper.assertFalse(helper.getBlockState(SPOT).getValue(BonfireBlock.ROARING), "Roaring is now false");
        helper.assertTrue(helper.getBlockState(SPOT).getValue(BonfireBlock.LIT), "Still lit");

        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void noBreakProgressWithoutIronAxe(GameTestHelper helper) {
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockState state = EmpireContent.BONFIRE.get().defaultBlockState();
        BlockPos pos = helper.absolutePos(SPOT);

        // Empty hand
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        PlayerEvent.BreakSpeed fistEvent = new PlayerEvent.BreakSpeed(player, state, 1.0F, pos);
        BonfireEvents.onBreakSpeed(fistEvent);
        helper.assertTrue(fistEvent.getNewSpeed() == 0.0F, "Fist break speed is 0");

        // Wooden axe
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WOODEN_AXE));
        PlayerEvent.BreakSpeed woodAxeEvent = new PlayerEvent.BreakSpeed(player, state, 1.0F, pos);
        BonfireEvents.onBreakSpeed(woodAxeEvent);
        helper.assertTrue(woodAxeEvent.getNewSpeed() == 0.0F, "Wooden axe break speed is 0");

        // Stone axe
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_AXE));
        PlayerEvent.BreakSpeed stoneAxeEvent = new PlayerEvent.BreakSpeed(player, state, 1.0F, pos);
        BonfireEvents.onBreakSpeed(stoneAxeEvent);
        helper.assertTrue(stoneAxeEvent.getNewSpeed() == 0.0F, "Stone axe break speed is 0");

        // Iron pickaxe
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        PlayerEvent.BreakSpeed ironPickEvent = new PlayerEvent.BreakSpeed(player, state, 1.0F, pos);
        BonfireEvents.onBreakSpeed(ironPickEvent);
        helper.assertTrue(ironPickEvent.getNewSpeed() == 0.0F, "Iron pickaxe break speed is 0");

        // Iron axe
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        PlayerEvent.BreakSpeed ironAxeEvent = new PlayerEvent.BreakSpeed(player, state, 1.0F, pos);
        BonfireEvents.onBreakSpeed(ironAxeEvent);
        helper.assertTrue(ironAxeEvent.getNewSpeed() > 0.0F, "Iron axe break speed > 0");

        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void firstLightingRefusedIfNeighbourChunkOwned(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        UUID founderId = UUID.randomUUID();

        // Empire in chunk (50, 50) stakes 49..51
        long centerChunk = ChunkKeys.pack(50, 50);
        BlockPos centerPos = new BlockPos(800, 64, 800);
        EmpireRecord empire = data.createEmpire(founderId, centerChunk, centerPos, server.overworld().getGameTime());

        // Try to light an unclaimed bonfire in chunk (52, 50) whose 3x3 includes chunk (51, 50)
        BlockPos foreignPos = new BlockPos(52 * 16 + 8, 64, 50 * 16 + 8);
        var player = helper.makeMockPlayer(GameType.SURVIVAL);

        boolean canLight = BonfireBlock.canLight(server.overworld(), foreignPos, player);
        helper.assertFalse(canLight, "Cannot light when a neighbour chunk is owned by another empire");

        data.disbandEmpire(empire.id(), server);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void mainReselectionPicksClosestBonfire(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        UUID founderId = UUID.randomUUID();

        long centerChunk = ChunkKeys.pack(10, 10);
        BlockPos posMain = new BlockPos(160, 64, 160);
        EmpireRecord record = data.createEmpire(founderId, centerChunk, posMain, server.overworld().getGameTime());

        BlockPos posCloser = new BlockPos(165, 64, 160);
        BlockPos posFarther = new BlockPos(175, 64, 160);
        data.onBonfirePlaced(posCloser, server);
        data.onBonfirePlaced(posFarther, server);

        helper.assertTrue(posMain.equals(record.mainBonfire()), "posMain is initially main");

        // Remove main bonfire
        data.onBonfireRemoved(posMain, server);

        // Closer bonfire becomes new main
        helper.assertTrue(posCloser.equals(record.mainBonfire()), "Closest bonfire became new main");

        data.disbandEmpire(record.id(), server);
        data.onBonfireRemoved(posCloser, server);
        data.onBonfireRemoved(posFarther, server);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void nullMainPlusNewBonfireInLandBecomesMain(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        EmpireSavedData data = EmpireSavedData.of(server);
        UUID founderId = UUID.randomUUID();

        long centerChunk = ChunkKeys.pack(20, 20);
        BlockPos posInitial = new BlockPos(320, 64, 320);
        EmpireRecord record = data.createEmpire(founderId, centerChunk, posInitial, server.overworld().getGameTime());

        // Remove initial main bonfire
        data.onBonfireRemoved(posInitial, server);
        helper.assertTrue(record.mainBonfire() == null, "Empire is unanchored (mainBonfire is null)");

        // Place a new bonfire in land staked by this empire
        BlockPos posNew = new BlockPos(325, 64, 325);
        data.onBonfirePlaced(posNew, server);

        helper.assertTrue(posNew.equals(record.mainBonfire()), "New bonfire placed in land became main");

        data.disbandEmpire(record.id(), server);
        data.onBonfireRemoved(posNew, server);
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void bonfireLetsLightThroughAndProvidesCloneItem(GameTestHelper helper) {
        BlockState mainState = EmpireContent.BONFIRE.get().defaultBlockState();
        BlockState partState = EmpireContent.BONFIRE_PART.get().defaultBlockState();

        // 1. Light and shadow properties: must let light through completely
        helper.assertTrue(mainState.propagatesSkylightDown(helper.getLevel(), SPOT),
                "Main bonfire propagates skylight down");
        helper.assertTrue(partState.propagatesSkylightDown(helper.getLevel(), SPOT),
                "Part block propagates skylight down");
        helper.assertTrue(mainState.getShadeBrightness(helper.getLevel(), SPOT) == 1.0F,
                "Main bonfire shade brightness is 1.0 (no shadow)");
        helper.assertTrue(partState.getShadeBrightness(helper.getLevel(), SPOT) == 1.0F,
                "Part block shade brightness is 1.0 (no shadow)");
        helper.assertTrue(mainState.getVisualShape(helper.getLevel(), SPOT, net.minecraft.world.phys.shapes.CollisionContext.empty()).isEmpty(),
                "Main bonfire visual shape is empty (no AO face shadow)");
        helper.assertTrue(partState.getVisualShape(helper.getLevel(), SPOT, net.minecraft.world.phys.shapes.CollisionContext.empty()).isEmpty(),
                "Part block visual shape is empty (no AO face shadow)");

        // 2. Clone item: middle clicking either block yields the bonfire item
        ItemStack mainClone = mainState.getBlock().getCloneItemStack(helper.getLevel(), helper.absolutePos(SPOT), mainState);
        ItemStack partClone = partState.getBlock().getCloneItemStack(helper.getLevel(), helper.absolutePos(SPOT), partState);
        helper.assertTrue(mainClone.is(EmpireContent.BONFIRE_ITEM.get()), "Main block drops bonfire item on pick");
        helper.assertTrue(partClone.is(EmpireContent.BONFIRE_ITEM.get()), "Part block drops bonfire item on pick");

        helper.succeed();
    }

    /** A bonfire outside the Overworld is never added to the registry, so it can never become an empire's main bonfire. */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void bonfireOutsideOverworldIsNotRegistered(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "the Nether exists");
        BlockPos overworldPos = helper.absolutePos(SPOT);
        BlockPos pos = new BlockPos(overworldPos.getX(), 64, overworldPos.getZ());
        BlockState before = nether.getBlockState(pos);
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel().getServer());

        nether.setBlock(pos, EmpireContent.BONFIRE.get().defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        boolean registered = data.bonfires().contains(pos);
        nether.setBlock(pos, before, net.minecraft.world.level.block.Block.UPDATE_ALL);

        helper.assertFalse(registered, "a Nether bonfire is not in the bonfire registry");
        helper.succeed();
    }

    /** The bonfire counts as a campfire (vanilla's splash-water and smoke checks), and an unlit bonfire stops roaring. */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void bonfireIsCampfireAndStopsRoaringWhenUnlit(GameTestHelper helper) {
        BlockState lit = EmpireContent.BONFIRE.get().defaultBlockState().setValue(BonfireBlock.LIT, true);
        helper.assertTrue(lit.is(BlockTags.CAMPFIRES), "the bonfire is in #minecraft:campfires");
        helper.assertTrue(CampfireBlock.isLitCampfire(lit), "vanilla sees a lit bonfire as a lit campfire");

        clearArea(helper, SPOT);
        // As a splash water potion leaves it: unlit, but still roaring.
        helper.setBlock(SPOT, lit.setValue(BonfireBlock.LIT, false).setValue(BonfireBlock.ROARING, true));
        helper.succeedWhen(() -> helper.assertFalse(helper.getBlockState(SPOT).getValue(BonfireBlock.ROARING),
                "an unlit bonfire is not roaring"));
    }

    private static void clearArea(GameTestHelper helper, BlockPos center) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    helper.setBlock(center.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
    }
}
