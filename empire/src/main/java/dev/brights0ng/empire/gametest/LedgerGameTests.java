// Written by Gemini.
package dev.brights0ng.empire.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.region.RegionManager;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Game tests for the Administrative Ledger item, crafting recipe, and bed right-click {@code HOME} registration.
 */
@GameTestHolder(Empire.MODID)
@PrefixGameTestTemplate(false)
public final class LedgerGameTests {

    private static BlockPos mainPos(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(4, 2, 4));
    }

    private static EmpireRecord found(GameTestHelper helper, Player founder) {
        BlockPos main = mainPos(helper);
        return EmpireSavedData.of(helper.getLevel()).createEmpire(founder.getUUID(),
                ChunkKeys.pack(main.getX() >> 4, main.getZ() >> 4), main, helper.getLevel().getGameTime());
    }

    private static void disband(GameTestHelper helper, EmpireRecord empire) {
        EmpireSavedData data = EmpireSavedData.of(helper.getLevel());
        data.disbandEmpire(empire.id(), helper.getLevel().getServer());
        data.onBonfireRemoved(mainPos(helper), null);
    }

    private static BlockPos buildRoom(ServerLevel level, BlockPos origin, int innerW, int innerH, int innerD,
                                      boolean lit, List<BlockPos> placed) {
        for (int dx = -1; dx <= innerW; dx++) {
            for (int dy = -1; dy <= innerH; dy++) {
                for (int dz = -1; dz <= innerD; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    boolean isShell = dx == -1 || dx == innerW || dy == -1 || dy == innerH || dz == -1 || dz == innerD;
                    BlockState state = isShell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(p, state, Block.UPDATE_ALL);
                    placed.add(p);
                }
            }
        }
        if (lit) {
            BlockPos torch = origin.offset(innerW / 2, 0, innerD / 2);
            level.setBlock(torch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
            placed.add(torch);
        }
        return origin;
    }

    private static BlockPos placeBed(ServerLevel level, BlockPos foot, Direction facing, List<BlockPos> placed) {
        BlockPos head = foot.relative(facing);
        BlockState footState = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.FOOT)
                .setValue(BedBlock.OCCUPIED, false);
        BlockState headState = footState.setValue(BedBlock.PART, BedPart.HEAD);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        level.setBlock(foot, footState, flags);
        level.setBlock(head, headState, flags);
        placed.add(foot);
        placed.add(head);
        return head;
    }

    private static void clearBlocks(ServerLevel level, List<BlockPos> placed) {
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos pos : placed) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        }
    }

    private static PlayerInteractEvent.RightClickBlock rightClick(Player player, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        return CommonHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "ledger_recipe")
    public static void ledgerItemRegisteredAndCraftableWithInkPaperSign(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack ledger = new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get());
        helper.assertTrue(
                Empire.id("administrative_ledger").equals(BuiltInRegistries.ITEM.getKey(ledger.getItem())),
                "item id is empire:administrative_ledger");
        helper.assertTrue(ledger.getMaxStackSize() == 1, "ledger is unstackable");

        // Top center: ink sac, middle: paper, bottom center: any sign (#minecraft:signs).
        CraftingInput spruceInput = CraftingInput.of(3, 3, List.of(
                ItemStack.EMPTY, new ItemStack(Items.INK_SAC), ItemStack.EMPTY,
                ItemStack.EMPTY, new ItemStack(Items.PAPER), ItemStack.EMPTY,
                ItemStack.EMPTY, new ItemStack(Items.SPRUCE_SIGN), ItemStack.EMPTY));
        Optional<RecipeHolder<CraftingRecipe>> spruceMatch =
                level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, spruceInput, level);
        helper.assertTrue(spruceMatch.isPresent(), "recipe matches spruce sign in center column");
        ItemStack assembled = spruceMatch.get().value().assemble(spruceInput, level.registryAccess());
        helper.assertTrue(assembled.is(EmpireContent.ADMINISTRATIVE_LEDGER.get()) && assembled.getCount() == 1,
                "recipe produces 1 Administrative Ledger");

        CraftingInput oakInput = CraftingInput.of(3, 3, List.of(
                ItemStack.EMPTY, new ItemStack(Items.INK_SAC), ItemStack.EMPTY,
                ItemStack.EMPTY, new ItemStack(Items.PAPER), ItemStack.EMPTY,
                ItemStack.EMPTY, new ItemStack(Items.OAK_SIGN), ItemStack.EMPTY));
        Optional<RecipeHolder<CraftingRecipe>> oakMatch =
                level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, oakInput, level);
        helper.assertTrue(oakMatch.isPresent()
                        && oakMatch.get().value().assemble(oakInput, level.registryAccess())
                                .is(EmpireContent.ADMINISTRATIVE_LEDGER.get()),
                "recipe accepts any wooden sign via #minecraft:signs");

        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "ledger_home_create", timeoutTicks = 25)
    public static void rightClickBedWithLedgerCreatesAutoHomeAndCancelsVanillaBedUse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos room = buildRoom(level, mainPos(helper).offset(2, 0, -2), 5, 2, 5, true, placed);
        BlockPos bedFoot = room.offset(0, 0, 0);
        BlockPos bedHead = placeBed(level, bedFoot, Direction.EAST, placed);

        helper.runAtTickTime(4, () -> {
            try {
                // Clicking a non-bed block passes through uncanceled.
                PlayerInteractEvent.RightClickBlock wallClick = rightClick(founder, room.offset(-1, 0, 0));
                helper.assertFalse(wallClick.isCanceled(), "clicking non-bed block does not cancel");

                // Right-clicking the bed FOOT creates an AUTO HOME seeded at the bed HEAD and cancels bed use.
                PlayerInteractEvent.RightClickBlock footClick = rightClick(founder, bedFoot);
                helper.assertTrue(footClick.isCanceled(), "bed right-click canceled so vanilla sleep never runs");

                RegionRecord created = data.regionAt(bedHead);
                helper.assertTrue(created != null, "HOME region created at bed head");
                helper.assertTrue(created.type() == RegionType.HOME, "region type is HOME");
                helper.assertTrue(created.origin() == RegionOrigin.AUTO, "region origin is AUTO");
                helper.assertTrue(created.valid(), "HOME region is valid");
                helper.assertTrue(created.seed() == bedHead.asLong(), "seed normalized from foot to bed head");
                helper.assertTrue(created.effectiveVolume() == 50, "effective volume is 50, got " + created.effectiveVolume());
                helper.assertTrue(RegionRules.homeCapacity(created.effectiveVolume()) == 1, "capacity is 1");
                helper.assertTrue(data.regionsOf(empire.id()).size() == 1, "exactly 1 region registered");

                // Clicking the bed HEAD of an existing HOME checks status without duplicating the region.
                PlayerInteractEvent.RightClickBlock headClick = rightClick(founder, bedHead);
                helper.assertTrue(headClick.isCanceled(), "re-clicking existing home bed cancels vanilla sleep");
                helper.assertTrue(data.regionsOf(empire.id()).size() == 1, "no duplicate region created");
            } finally {
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "ledger_home_validation", timeoutTicks = 25)
    public static void ledgerReportsFailuresAndRestoresRepairedHomeOnClick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        Player founder = helper.makeMockPlayer(GameType.SURVIVAL);
        founder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        Player outsider = helper.makeMockPlayer(GameType.SURVIVAL);
        outsider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(EmpireContent.ADMINISTRATIVE_LEDGER.get()));
        EmpireRecord empire = found(helper, founder);
        EmpireSavedData data = EmpireSavedData.of(level);

        BlockPos main = mainPos(helper);
        // Place rooms/beds outside the 7x7 GameTest barrier roof (x >= 8 from structure origin) so sky checks are real,
        // while staying within the empire's initial 3x3 staked chunks around main.
        BlockPos darkRoom = buildRoom(level, main.offset(4, 0, -2), 5, 2, 5, false, placed);
        BlockPos darkBed = placeBed(level, darkRoom.offset(0, 0, 0), Direction.EAST, placed);

        // Outdoor bed on staked land, outside the 7x7 barrier roof (truly exposed to sky).
        BlockPos skyBed = placeBed(level, main.offset(4, 0, 5), Direction.EAST, placed);

        // Unstaked bed in chunk (cx - 2, cz), strictly outside the empire's initial 3x3 staked chunks.
        int unstakedCx = (main.getX() >> 4) - 2;
        BlockPos farRoom = buildRoom(level, new BlockPos(unstakedCx * 16 + 4, main.getY(), main.getZ()), 5, 2, 5, true, placed);
        BlockPos farBed = placeBed(level, farRoom.offset(0, 0, 0), Direction.EAST, placed);

        helper.runAtTickTime(4, () -> {
            // Dark room fails -> no region created.
            helper.assertTrue(rightClick(founder, darkBed).isCanceled(), "dark bed click canceled");
            helper.assertTrue(data.regionAt(darkBed) == null, "dark room did not create HOME");

            // Outdoor bed fails -> no region created.
            helper.assertTrue(rightClick(founder, skyBed).isCanceled(), "sky bed click canceled");
            helper.assertTrue(data.regionAt(skyBed) == null, "sky-exposed bed did not create HOME");

            // Unstaked bed fails -> no region created.
            helper.assertTrue(rightClick(founder, farBed).isCanceled(), "unstaked bed click canceled");
            helper.assertTrue(data.regionAt(farBed) == null, "unstaked bed did not create HOME");

            // Outsider on unprotected land is canceled directly by LedgerInteractions with no region created.
            helper.assertTrue(rightClick(outsider, farBed).isCanceled(), "outsider click on wild bed canceled");
            helper.assertTrue(data.regionAt(farBed) == null, "outsider created no region on wild bed");

            // Light the room now; wait a few ticks for block light to propagate before clicking again.
            BlockPos torch = darkRoom.offset(2, 0, 2);
            level.setBlock(torch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
            placed.add(torch);
        });

        helper.runAtTickTime(10, () -> {
            try {
                // Outsider on divinely protected land is blocked by DivineProtection.
                PlayerInteractEvent.RightClickBlock protectedClick = rightClick(outsider, darkBed);
                helper.assertTrue(protectedClick.isCanceled() || protectedClick.getUseBlock() == TriState.FALSE,
                        "outsider click inside protected land blocked");
                helper.assertTrue(data.regionAt(darkBed) == null, "outsider cannot create HOME");

                // Founder now succeeds!
                helper.assertTrue(rightClick(founder, darkBed).isCanceled(), "founder click canceled");
                RegionRecord home = data.regionAt(darkBed);
                helper.assertTrue(home != null && home.valid(), "lit room created valid HOME");

                // Break a roof block (outside the 7x7 barrier roof) and force invalidation; repair and click to restore.
                BlockPos roofHole = darkRoom.offset(2, 2, 2);
                level.setBlock(roofHole, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                RegionManager.updateRegion(level, data, home, level.getGameTime());
                helper.assertFalse(home.valid(), "broken roof invalidated HOME");

                level.setBlock(roofHole, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                rightClick(founder, darkBed);
                helper.assertTrue(home.valid(), "re-clicking repaired HOME bed restored validity immediately");
            } finally {
                disband(helper, empire);
                clearBlocks(level, placed);
            }
            helper.succeed();
        });
    }

    private LedgerGameTests() {
    }
}
