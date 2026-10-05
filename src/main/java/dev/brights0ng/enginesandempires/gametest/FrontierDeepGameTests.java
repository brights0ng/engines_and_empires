package dev.brights0ng.enginesandempires.gametest;

import java.util.List;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.deep.Deep;
import dev.brights0ng.enginesandempires.frontier.deep.DeepRoster;
import dev.brights0ng.enginesandempires.frontier.deep.DeepSpawns;
import dev.brights0ng.enginesandempires.frontier.deep.ShotSource;
import dev.brights0ng.enginesandempires.frontier.deep.StirredDeposits;
import dev.brights0ng.enginesandempires.frontier.deep.ThumperIncursions;
import dev.brights0ng.enginesandempires.frontier.spawn.FrontierSpawnEvents;
import dev.brights0ng.enginesandempires.geophone.SeismicWave;
import dev.brights0ng.enginesandempires.geophone.WaveModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.fml.ModList;

/**
 * In-game checks of what thumper shots set off (Frontier phase 5): sculk hearing them, the disturbance calling up an
 * incursion, mobs coming up, and echoes stirring deposits. Each runs in its own batch, since they share the level's state.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class FrontierDeepGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final BlockPos SPOT = new BlockPos(3, 1, 3);

    @GameTest(template = SCRATCH, batch = "deep_sculk")
    public static void aThumperShotSetsOffASculkSensor(GameTestHelper helper) {
        helper.setBlock(SPOT.below(), Blocks.STONE);
        helper.setBlock(SPOT, Blocks.SCULK_SENSOR);
        Deep.of(helper.getLevel()).onThump(helper.absolutePos(new BlockPos(0, 1, 0)), ShotSource.COMBUSTIVE_DRY, 128);
        helper.succeedWhen(() -> helper.assertTrue(
                helper.getBlockState(SPOT).getValue(SculkSensorBlock.PHASE) == SculkSensorPhase.ACTIVE,
                "the sculk sensor should hear the shot"));
    }

    @GameTest(template = SCRATCH, batch = "deep_sculk_range")
    public static void sculkBeyondHalfTheRangeDoesNotHear(GameTestHelper helper) {
        helper.setBlock(SPOT.below(), Blocks.STONE);
        helper.setBlock(SPOT, Blocks.SCULK_SENSOR);
        Deep deep = Deep.of(helper.getLevel());
        int before = deep.pendingCount();
        // 4 blocks away with a range of 6: half the range is 3, so it is out of reach.
        deep.onThump(helper.absolutePos(SPOT.west(4)), ShotSource.COMBUSTIVE_DRY, 6);
        helper.assertTrue(deep.pendingCount() == before, "a sensor beyond half the shot's range should not hear it");
        deep.onThump(helper.absolutePos(SPOT.west(4)), ShotSource.COMBUSTIVE_DRY, 10);
        helper.assertTrue(deep.pendingCount() == before + 1, "within half the range it should");
        deep.hearAllNow();
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "deep_disturbance")
    public static void fiveShotsRollForAnIncursionThenTheAreaGoesQuiet(GameTestHelper helper) {
        Deep deep = Deep.of(helper.getLevel());
        BlockPos at = helper.absolutePos(SPOT);
        double mechanicalChance = FrontierConfig.MECHANICAL_INCURSION_CHANCE.get();
        FrontierConfig.MECHANICAL_INCURSION_CHANCE.set(1.0);
        withNoMobs(() -> {
            int before = ThumperIncursions.recent().size();
            int dry = 0;
            for (int i = 0; i < 6; i++) {
                deep.onThump(at, ShotSource.COMBUSTIVE_DRY, 128);
                dry++;
            }
            helper.assertTrue(ThumperIncursions.recent().size() == before, "dry fires should not count, even " + dry);
            int shots = 0;
            while (ThumperIncursions.recent().size() == before && shots < 10) {
                deep.onThump(at, ShotSource.MECHANICAL, 512);
                shots++;
            }
            helper.assertTrue(ThumperIncursions.recent().size() == before + 1 && shots <= 5,
                    "five shots should call up an incursion, took " + shots);
            ThumperIncursions.Call call = ThumperIncursions.recent().get(ThumperIncursions.recent().size() - 1);
            helper.assertTrue(call.incursionId() != null, "it starts a real incursion");
            var incursion = dev.brights0ng.enginesandempires.frontier.incursion.Incursions.of(helper.getLevel()).byId(call.incursionId());
            helper.assertTrue(incursion != null
                    && incursion.kind() == dev.brights0ng.enginesandempires.frontier.incursion.Incursion.Kind.THUMPER,
                    "a thumper incursion");
            for (int i = 0; i < 10; i++) {
                deep.onThump(at.east(16), ShotSource.MECHANICAL, 512);
            }
            helper.assertTrue(ThumperIncursions.recent().size() == before + 1, "the area around should be quiet for the cooldown");
        });
        FrontierConfig.MECHANICAL_INCURSION_CHANCE.set(mechanicalChance);
        dev.brights0ng.enginesandempires.frontier.incursion.Incursions.of(helper.getLevel()).stopAll();
        deep.hearAllNow();
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "deep_mobs")
    public static void aShotCanBringMobsUpFromBelow(GameTestHelper helper) {
        Deep deep = Deep.of(helper.getLevel());
        BlockPos at = helper.absolutePos(SPOT);
        double chance = FrontierConfig.MECHANICAL_MOB_CHANCE.get();
        FrontierConfig.MECHANICAL_MOB_CHANCE.set(1.0);
        int cooldown = FrontierConfig.DISTURBANCE_SHOTS.get();
        FrontierConfig.DISTURBANCE_SHOTS.set(1000);
        int minDistance = FrontierConfig.SHOT_MOB_MIN_DISTANCE.get();
        int maxDistance = FrontierConfig.SHOT_MOB_MAX_DISTANCE.get();
        // Inside the test's own walled space, where there is always room.
        FrontierConfig.SHOT_MOB_MIN_DISTANCE.set(0);
        FrontierConfig.SHOT_MOB_MAX_DISTANCE.set(2);
        int found;
        try {
            for (int i = 0; i < 20; i++) {
                deep.onThump(at, ShotSource.MECHANICAL, 512);
            }
            found = helper.getLevel().getEntitiesOfClass(Mob.class, new AABB(at).inflate(40),
                    mob -> mob.getTags().contains(DeepSpawns.TAG)).size();
        } finally {
            FrontierConfig.MECHANICAL_MOB_CHANCE.set(chance);
            FrontierConfig.DISTURBANCE_SHOTS.set(cooldown);
            FrontierConfig.SHOT_MOB_MIN_DISTANCE.set(minDistance);
            FrontierConfig.SHOT_MOB_MAX_DISTANCE.set(maxDistance);
        }
        BlockPos near = DeepSpawns.findSpot(helper.getLevel(), net.minecraft.world.entity.EntityType.ZOMBIE, at, 0, 2, true,
                helper.getLevel().random);
        BlockPos far = DeepSpawns.findSpot(helper.getLevel(), net.minecraft.world.entity.EntityType.ZOMBIE, at, 16, 32, true,
                helper.getLevel().random);
        int surface = helper.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                at.getX(), at.getZ());
        boolean roster = DeepRoster.get(helper.getLevel(), DeepRoster.THUMPER).flatMap(r -> r.pick(helper.getLevel().random)).isPresent();
        helper.assertTrue(found > 0, "twenty shots at a 100% chance should bring something up from the thumper roster"
                + " (spot near " + near + ", far " + far + ", surface y " + surface + " at y " + at.getY() + ", roster " + roster
                + ", chance " + FrontierConfig.MECHANICAL_MOB_CHANCE.get() + ")");
        helper.getLevel().getEntitiesOfClass(Mob.class, new AABB(at).inflate(40), mob -> mob.getTags().contains(DeepSpawns.TAG))
                .forEach(Mob::discard);
        deep.hearAllNow();
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "deep_stir")
    public static void echoesStirTheDepositsTheyCameBackOff(GameTestHelper helper) {
        Deep deep = Deep.of(helper.getLevel());
        BlockPos centre = helper.absolutePos(SPOT);
        WaveModel.OreCells cells = new WaveModel.OreCells(
                new double[] {centre.getX() - 1.5, centre.getX() + 2.5},
                new double[] {centre.getY() + 0.5, centre.getY() + 0.5},
                new double[] {centre.getZ() + 0.5, centre.getZ() + 0.5});
        long now = helper.getLevel().getGameTime();
        StirredDeposits stirred = StirredDeposits.of(helper.getLevel());

        deep.onEchoes(ShotSource.COMBUSTIVE_DRY, List.of(new SeismicWave.Echoer(987_654L, "iron", cells)));
        helper.assertTrue(stirred.near(centre, 2, now).stream().noneMatch(s -> s.seed() == 987_654L), "a dry fire stirs nothing");

        deep.onEchoes(ShotSource.MECHANICAL, List.of(new SeismicWave.Echoer(987_654L, "iron", cells)));
        List<StirredDeposits.Stirred> near = stirred.near(centre, 2, now);
        helper.assertTrue(near.stream().anyMatch(s -> s.seed() == 987_654L), "a real shot's echo should stir the deposit");
        StirredDeposits.Stirred s = near.stream().filter(x -> x.seed() == 987_654L).findFirst().orElseThrow();
        helper.assertTrue(s.until() >= now + FrontierConfig.deep().stirTicks(), "stirred for 3 days");
        helper.assertTrue(s.centre().closerThan(centre, 2), "remembered at the middle of its ore");
        helper.assertTrue(stirred.near(centre, 2, s.until()).isEmpty(), "and settled again once the time is up");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "deep_roster_dd")
    public static void theStirredRosterIsDeeperAndDarkersMobs(GameTestHelper helper) {
        if (!ModList.get().isLoaded("deeperdarker")) {
            helper.succeed();
            return;
        }
        DeepRoster roster = DeepRoster.get(helper.getLevel(), DeepRoster.STIRRED).orElseThrow();
        helper.assertTrue(roster.entries().size() == 5, "five Deeper and Darker mobs, got " + roster.entries().size());
        for (DeepRoster.Entry entry : roster.entries()) {
            helper.assertTrue(entry.entity().getNamespace().equals("deeperdarker"), entry.entity() + " should be Deeper and Darker's");
            helper.assertTrue(entry.type().isPresent(), entry.entity() + " should exist");
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "deep_provoked_dd")
    public static void snappersAndCentipedesFromBelowAttackPlayersAnywhere(GameTestHelper helper) {
        if (!ModList.get().isLoaded("deeperdarker")) {
            helper.succeed();
            return;
        }
        for (String id : List.of("sculk_snapper", "sculk_centipede")) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath("deeperdarker", id));

            Mob plain = (Mob) helper.spawn(type, SPOT);
            helper.assertFalse(FrontierSpawnEvents.isFromBelow(plain), id + " that did not come up from below is not");
            plain.discard();

            Mob mob = (Mob) type.create(helper.getLevel());
            BlockPos at = helper.absolutePos(SPOT);
            mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            mob.addTag(DeepSpawns.TAG);
            helper.getLevel().addFreshEntity(mob);
            helper.assertTrue(mob.targetSelector.getAvailableGoals().stream()
                    .anyMatch(g -> g.getGoal() instanceof FrontierSpawnEvents.ProvokedTargetGoal), id + " from below should hunt players");
            helper.assertTrue(FrontierSpawnEvents.isProvoked(mob), id + " from below should be provoked whatever the tier");
            if (mob instanceof TamableAnimal tamable) {
                tamable.setOwnerUUID(java.util.UUID.randomUUID());
                helper.assertFalse(FrontierSpawnEvents.isProvoked(mob), "a tamed " + id + " never is");
            }
            mob.discard();
        }
        helper.succeed();
    }

    /** Runs {@code body} with shots bringing no mobs up, so they don't get in the way. */
    private static void withNoMobs(Runnable body) {
        double mechanical = FrontierConfig.MECHANICAL_MOB_CHANCE.get();
        double combustive = FrontierConfig.COMBUSTIVE_MOB_CHANCE.get();
        FrontierConfig.MECHANICAL_MOB_CHANCE.set(0.0);
        FrontierConfig.COMBUSTIVE_MOB_CHANCE.set(0.0);
        try {
            body.run();
        } finally {
            FrontierConfig.MECHANICAL_MOB_CHANCE.set(mechanical);
            FrontierConfig.COMBUSTIVE_MOB_CHANCE.set(combustive);
        }
    }

    private FrontierDeepGameTests() {
    }
}
