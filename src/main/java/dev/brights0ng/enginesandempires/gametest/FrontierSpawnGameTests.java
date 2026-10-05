package dev.brights0ng.enginesandempires.gametest;

import java.util.UUID;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.FrontierContent;
import dev.brights0ng.enginesandempires.frontier.Tier;
import dev.brights0ng.enginesandempires.frontier.spawn.FrontierSpawnEvents;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevels;
import dev.brights0ng.enginesandempires.frontier.tier.TierParams;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game checks of spawning by tier and of torches burning out.
 *
 * <p>The game test world lies below y 0, where everything is Frontier. Tests that need Settled land lower the depth limits in
 * the config for their duration and put them back afterwards. Each test runs in a batch of its own, since tiers look further
 * than the space between tests.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class FrontierSpawnGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final BlockPos SPOT = new BlockPos(3, 1, 3);

    @GameTest(template = SCRATCH, batch = "frontier_dark")
    public static void torchlightDoesNotKeepMonstersAwayBelowZero(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(SPOT);
        helper.setBlock(SPOT.above(), Blocks.GLOWSTONE);
        helper.setBlock(SPOT.east(), Blocks.GLOWSTONE);
        if (at.getY() < 0) {
            for (int i = 0; i < 20; i++) {
                helper.assertTrue(Monster.isDarkEnoughToSpawn(helper.getLevel(), at, helper.getLevel().random),
                        "below y 0 in Frontier land, light should not matter");
            }
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "frontier_neutral")
    public static void neutralMobsAreProvokedInFrontierLand(GameTestHelper helper) {
        Wolf wolf = helper.spawn(EntityType.WOLF, SPOT);
        Llama llama = helper.spawn(EntityType.LLAMA, SPOT.east(2));
        helper.assertTrue(hasProvokedGoal(wolf), "a wolf should have learnt to attack players in Frontier land");
        helper.assertTrue(FrontierSpawnEvents.isProvoked(wolf), "a wild wolf in Frontier land should be provoked");
        wolf.setOwnerUUID(UUID.randomUUID());
        helper.assertFalse(FrontierSpawnEvents.isProvoked(wolf), "somebody's dog should never be");
        helper.assertFalse(hasProvokedGoal(llama), "llamas are left out (the never_provoked tag)");
        wolf.discard();
        llama.discard();
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "frontier_spawnrules")
    public static void settledLandTurnsSpawnsAway(GameTestHelper helper) {
        FrontierLevel frontier = FrontierLevels.of(helper.getLevel());
        BlockPos at = helper.absolutePos(SPOT);
        withoutDepthLimits(frontier, () -> {
            settle(helper, frontier, at);
            helper.assertTrue(frontier.tierAt(at) == Tier.SETTLED, "the spot should be Settled, got " + frontier.tierAt(at));
            helper.assertTrue(FrontierSpawnEvents.denies(frontier, EntityType.CREEPER, at, 0.0), "no creepers in Settled land");
            helper.assertTrue(FrontierSpawnEvents.denies(frontier, EntityType.POLAR_BEAR, at, 0.0), "no polar bears");
            helper.assertFalse(FrontierSpawnEvents.denies(frontier, EntityType.PIG, at, 0.99), "pigs are welcome");
            boolean surface = helper.getLevel().canSeeSky(at);
            helper.assertTrue(FrontierSpawnEvents.denies(frontier, EntityType.ZOMBIE, at, 0.99) == surface,
                    "half of the surface's zombies are turned away (and none underground)");
            helper.assertFalse(FrontierSpawnEvents.denies(frontier, EntityType.ZOMBIE, at, 0.1), "the other half still come");

            helper.setBlock(SPOT, Blocks.AIR);
            frontier.refreshAround(at, 4);
            // Earlier tests leave their blocks behind (furnaces, crafting tables...), which may still anchor the land here.
            if (frontier.countsAround(FrontierLevel.keyOf(at)).anchors() == 0) {
                helper.assertTrue(frontier.tierAt(at).ordinal() < Tier.SETTLED.ordinal(),
                        "without its anchor the spot should not be Settled any more, got " + frontier.tierAt(at));
                helper.assertFalse(FrontierSpawnEvents.denies(frontier, EntityType.CREEPER, at, 0.0), "wild land turns nothing away");
            }
            frontier.clearHabitation(at, 8);
        });
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "frontier_torches")
    public static void torchesBurnOutAndCanBeLitAgain(GameTestHelper helper) {
        FrontierLevel frontier = FrontierLevels.of(helper.getLevel());
        BlockPos standing = helper.absolutePos(SPOT);
        BlockPos wallRel = new BlockPos(1, 1, 1);
        helper.setBlock(SPOT.below(), Blocks.STONE);
        helper.setBlock(SPOT, Blocks.TORCH);
        helper.setBlock(wallRel.south(), Blocks.STONE);
        helper.setBlock(wallRel, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH));
        helper.assertTrue(frontier.torches().litAt(standing) != null, "a placed torch should start its clock");

        frontier.torches().age(standing, 4, TierParams.DAY / 2);
        frontier.torches().sweep();
        helper.assertBlockPresent(Blocks.TORCH, SPOT);

        frontier.torches().age(standing, 4, TierParams.DAY / 2);
        frontier.torches().sweep();
        helper.assertBlockPresent(FrontierContent.DRY_TORCH.get(), SPOT);
        BlockState wall = helper.getBlockState(wallRel);
        helper.assertTrue(wall.is(FrontierContent.DRY_WALL_TORCH.get()) && wall.getValue(WallTorchBlock.FACING) == Direction.NORTH,
                "the wall torch should go out facing the same way, got " + wall);
        helper.assertTrue(frontier.torches().litAt(standing) == null, "a dry torch has no clock");

        helper.useBlock(SPOT);
        helper.assertBlockPresent(Blocks.TORCH, SPOT);
        Long lit = frontier.torches().litAt(standing);
        helper.assertTrue(lit != null && lit == helper.getLevel().getGameTime(), "lighting it again should restart its clock");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "frontier_torches_settled")
    public static void torchesInSettledLandAreLookedAfter(GameTestHelper helper) {
        FrontierLevel frontier = FrontierLevels.of(helper.getLevel());
        BlockPos at = helper.absolutePos(SPOT);
        BlockPos torchRel = SPOT.west(2);
        withoutDepthLimits(frontier, () -> {
            settle(helper, frontier, at);
            helper.setBlock(torchRel.below(), Blocks.STONE);
            helper.setBlock(torchRel, Blocks.TORCH);
            frontier.torches().age(at, 4, TierParams.DAY * 3);
            frontier.torches().sweep();
            helper.assertBlockPresent(Blocks.TORCH, torchRel);
            Long lit = frontier.torches().litAt(helper.absolutePos(torchRel));
            helper.assertTrue(lit != null && lit == helper.getLevel().getGameTime(), "a looked-after torch's day starts again");
            helper.setBlock(SPOT, Blocks.AIR);
            frontier.clearHabitation(at, 8);
        });
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "frontier_guards")
    public static void aPennedGolemIsNotFreeButALooseOneIs(GameTestHelper helper) {
        FrontierLevel frontier = FrontierLevels.of(helper.getLevel());
        // The test's 7x7 space is walled in, so a smaller yard stands in for the usual 64 spots.
        int area = FrontierConfig.GUARD_FREE_AREA.get();
        FrontierConfig.GUARD_FREE_AREA.set(20);
        try {
            pennedAndLoose(helper, frontier);
        } finally {
            FrontierConfig.GUARD_FREE_AREA.set(area);
        }
        helper.succeed();
    }

    private static void pennedAndLoose(GameTestHelper helper, FrontierLevel frontier) {
        BlockPos pen = new BlockPos(4, 0, 4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    helper.setBlock(pen.offset(dx, 0, dz), Blocks.OAK_FENCE);
                }
            }
        }
        IronGolem penned = helper.spawn(EntityType.IRON_GOLEM, pen);
        IronGolem loose = helper.spawn(EntityType.IRON_GOLEM, new BlockPos(1, 0, 1));
        int needed = FrontierConfig.guardFreeArea();
        int pennedReach = frontier.guards().checkNow(penned);
        int looseReach = frontier.guards().checkNow(loose);
        helper.assertTrue(pennedReach < needed, "a golem in a 1x1 pen should not be free, it reached " + pennedReach);
        helper.assertTrue(looseReach >= needed, "a golem on open ground should be free, it reached " + looseReach);
        penned.discard();
        loose.discard();
    }

    @GameTest(template = SCRATCH, batch = "frontier_civilized")
    public static void fiveFreeGuardsMakeSettledLandCivilized(GameTestHelper helper) {
        FrontierLevel frontier = FrontierLevels.of(helper.getLevel());
        BlockPos at = helper.absolutePos(SPOT);
        int area = FrontierConfig.GUARD_FREE_AREA.get();
        FrontierConfig.GUARD_FREE_AREA.set(20); // the test's 7x7 space is walled in
        withoutDepthLimits(frontier, () -> {
            settle(helper, frontier, at);
            helper.assertTrue(frontier.tierAt(at) == Tier.SETTLED, "the spot should be Settled first, got " + frontier.tierAt(at));
            java.util.List<IronGolem> golems = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, new BlockPos(1 + i, 0, i % 2 == 0 ? 1 : 5));
                frontier.guards().checkNow(golem);
                golems.add(golem);
            }
            frontier.refreshAround(at, 4);
            helper.assertTrue(frontier.countsAround(FrontierLevel.keyOf(at)).freeGuards() >= 5, "five free golems should count");
            helper.assertTrue(frontier.tierAt(at) == Tier.CIVILIZED, "Settled land with 5 free guards should be Civilized, got "
                    + frontier.tierAt(at));
            helper.assertTrue(FrontierSpawnEvents.denies(frontier, EntityType.ZOMBIE, at, 0.0), "no monsters in Civilized land");
            helper.assertTrue(FrontierSpawnEvents.denies(frontier, EntityType.ZOMBIE, at.below(3), 0.0), "not even underground");

            golems.get(0).discard();
            frontier.guards().tick();
            frontier.refreshAround(at, 4);
            if (frontier.countsAround(FrontierLevel.keyOf(at)).freeGuards() < 5) {
                helper.assertTrue(frontier.tierAt(at) == Tier.SETTLED, "with a guard gone it should be Settled again, got "
                        + frontier.tierAt(at));
            }
            golems.forEach(IronGolem::discard);
            helper.setBlock(SPOT, Blocks.AIR);
            frontier.clearHabitation(at, 8);
        });
        FrontierConfig.GUARD_FREE_AREA.set(area);
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "frontier_colonists")
    public static void colonistsAreWatchedButOnlyGuardsCount(GameTestHelper helper) {
        if (!net.neoforged.fml.ModList.get().isLoaded("minecolonies")) {
            helper.succeed();
            return;
        }
        EntityType<?> citizenType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .get(net.minecraft.resources.ResourceLocation.parse("minecolonies:citizen"));
        net.minecraft.world.entity.Entity citizen = citizenType.create(helper.getLevel());
        helper.assertTrue(citizen != null, "MineColonies should have a citizen entity");
        helper.assertTrue(dev.brights0ng.enginesandempires.frontier.guard.GuardTypes.isCandidate(citizen),
                "colonists should be watched (they may be given a guard job)");
        helper.assertFalse(dev.brights0ng.enginesandempires.frontier.guard.GuardTypes.isGuard(citizen),
                "a colonist with no job is not a guard");
        citizen.discard();
        helper.succeed();
    }

    /** Settles the land at {@code at}: an anchor there and enough inhabited time. */
    private static void settle(GameTestHelper helper, FrontierLevel frontier, BlockPos at) {
        frontier.clearHabitation(at, 8);
        helper.setBlock(helper.relativePos(at), Blocks.LANTERN);
        frontier.addHabitation(at, frontier.params().settleTicks());
        frontier.refreshAround(at, 4);
    }

    /** Runs {@code body} with the depth limits out of the way, so land below y 0 can be Settled, then puts them back. */
    private static void withoutDepthLimits(FrontierLevel frontier, Runnable body) {
        int frontierBelow = FrontierConfig.FRONTIER_BELOW_Y.get();
        int uninhabitedBelow = FrontierConfig.UNINHABITED_BELOW_Y.get();
        FrontierConfig.FRONTIER_BELOW_Y.set(-2048);
        FrontierConfig.UNINHABITED_BELOW_Y.set(-2048);
        frontier.reloadParams();
        try {
            body.run();
        } finally {
            FrontierConfig.FRONTIER_BELOW_Y.set(frontierBelow);
            FrontierConfig.UNINHABITED_BELOW_Y.set(uninhabitedBelow);
            frontier.reloadParams();
        }
    }

    private static boolean hasProvokedGoal(Mob mob) {
        return mob.targetSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.getGoal() instanceof FrontierSpawnEvents.ProvokedTargetGoal);
    }

    private FrontierSpawnGameTests() {
    }
}
