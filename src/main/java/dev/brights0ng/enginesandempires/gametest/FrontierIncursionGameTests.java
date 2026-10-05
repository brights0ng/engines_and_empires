package dev.brights0ng.enginesandempires.gametest;

import java.util.HashSet;
import java.util.Set;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.FrontierContent;
import dev.brights0ng.enginesandempires.frontier.deep.DeepRoster;
import dev.brights0ng.enginesandempires.frontier.incursion.Incursion;
import dev.brights0ng.enginesandempires.frontier.incursion.Incursions;
import dev.brights0ng.enginesandempires.frontier.incursion.LoudMachines;
import dev.brights0ng.enginesandempires.frontier.incursion.Settlement;
import dev.brights0ng.enginesandempires.frontier.incursion.SettlementClusters;
import dev.brights0ng.enginesandempires.frontier.tier.SectionKey;
import dev.brights0ng.enginesandempires.geophone.ChokedThumperBlock;
import dev.brights0ng.enginesandempires.geophone.ChokedThumpers;
import dev.brights0ng.enginesandempires.geophone.CombustiveThumperBlock;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game checks of incursions (Frontier phase 6): choking thumpers, snuffing torches, the wave rosters and loud machines
 * loading, and a thumper incursion bringing its first wave up. Each runs in its own batch.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class FrontierIncursionGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final BlockPos PLATE = new BlockPos(3, 1, 3);
    private static final BlockPos THUMPER = new BlockPos(3, 2, 3);

    @GameTest(template = SCRATCH, batch = "incursion_choke_mechanical")
    public static void aChokedMechanicalThumperKeepsItsAxis(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(PLATE, SeismicContent.STRIKE_PLATE.get());
        helper.setBlock(THUMPER, SeismicContent.MECHANICAL_THUMPER.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock.HORIZONTAL_AXIS, Direction.Axis.X));
        helper.assertTrue(ChokedThumpers.choke(helper.getLevel(), helper.absolutePos(THUMPER)), "there was a thumper to choke");
        helper.assertBlockPresent(SeismicContent.CHOKED_MECHANICAL_THUMPER.get(), THUMPER);
        helper.assertTrue(helper.getBlockState(THUMPER).getValue(ChokedThumperBlock.HORIZONTAL_AXIS) == Direction.Axis.X,
                "it keeps the axis the thumper was turned to");
        helper.assertFalse(ChokedThumpers.choke(helper.getLevel(), helper.absolutePos(THUMPER)), "a choked one can't choke again");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "incursion_choke_combustive")
    public static void aChokedCombustiveThumperLosesItsColumn(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(PLATE, SeismicContent.STRIKE_PLATE.get());
        helper.setBlock(THUMPER, SeismicContent.COMBUSTIVE_THUMPER.get());
        CombustiveThumperBlock.placeColumn(helper.getLevel(), helper.absolutePos(THUMPER),
                helper.getBlockState(THUMPER).getValue(CombustiveThumperBlock.HORIZONTAL_AXIS));
        // Choked from the top of the column: it finds the base.
        helper.assertTrue(ChokedThumpers.choke(helper.getLevel(), helper.absolutePos(THUMPER.above(2))), "found the base");
        helper.assertBlockPresent(SeismicContent.CHOKED_COMBUSTIVE_THUMPER.get(), THUMPER);
        helper.assertBlockNotPresent(SeismicContent.COMBUSTIVE_THUMPER_COLUMN.get(), THUMPER.above());
        helper.assertBlockNotPresent(SeismicContent.COMBUSTIVE_THUMPER_COLUMN.get(), THUMPER.above(2));
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "incursion_torches")
    public static void incursionMobsSnuffTorchesWithinTwoBlocks(GameTestHelper helper) {
        floor(helper);
        BlockPos near = new BlockPos(3, 1, 5);   // two blocks from the middle
        BlockPos far = new BlockPos(6, 1, 6);    // three out
        helper.setBlock(near, Blocks.TORCH);
        helper.setBlock(far, Blocks.TORCH);
        Incursion.snuffTorches(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 3)));
        helper.assertBlockPresent(FrontierContent.DRY_TORCH.get(), near);
        helper.assertBlockPresent(Blocks.TORCH, far);
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "incursion_data")
    public static void theWaveRostersAndLoudMachinesLoad(GameTestHelper helper) {
        helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.LESSER).isPresent(), "the lesser roster");
        for (int wave = 1; wave <= Incursions.GREATER_WAVES; wave++) {
            helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.greater(wave)).isPresent(), "greater wave " + wave);
        }
        if (ModList.get().isLoaded("deeperdarker")) {
            DeepRoster last = DeepRoster.get(helper.getLevel(), DeepRoster.greater(5)).orElseThrow();
            helper.assertTrue(last.fixed().stream().anyMatch(f -> f.entity().getPath().equals("stalker")
                            && f.roll(helper.getLevel().random) == 1),
                    "the stalker comes only in the last wave, alone");
            helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.greater(4)).orElseThrow().fixed().stream()
                    .noneMatch(f -> f.entity().getPath().equals("stalker")), "and not before");
            for (int wave = 1; wave <= Incursions.GREATER_WAVES; wave++) {
                helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.greater(wave)).orElseThrow().fixed().stream()
                        .noneMatch(f -> f.entity().getPath().equals("sludge")), "no sludge in wave " + wave);
            }
            helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.LESSER).orElseThrow().entries().stream()
                    .noneMatch(e -> e.entity().getPath().equals("sludge")), "no sludge in lesser waves");
        }
        helper.assertTrue(LoudMachines.weight(block("create:mechanical_drill")) == 10.0, "a drill weighs 10");
        helper.assertTrue(LoudMachines.weight(block("create:mechanical_press")) == 5.0, "a press weighs 5");
        helper.assertTrue(LoudMachines.weight(block("create:mechanical_saw")) == 1.0, "a saw weighs 1");
        helper.assertTrue(LoudMachines.weight(Blocks.STONE.defaultBlockState()) == 0.0, "stone is quiet");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "incursion_settlement")
    public static void aSettlementCountsTheSubchunksAboveAndBelowItsOwn(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 3));
        Set<Long> sections = new HashSet<>();
        sections.add(SectionKey.of(at.getX() >> 4, at.getY() >> 4, at.getZ() >> 4));
        Settlement settlement = new Settlement(sections, SettlementClusters.columns(sections), false);
        helper.assertTrue(settlement.contains(at), "its own subchunk");
        helper.assertTrue(settlement.contains(at.above(16)), "the one above");
        helper.assertFalse(settlement.contains(at.above(40)), "not two above");
        helper.assertFalse(settlement.contains(at.east(40)), "not beside it");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "incursion_waves", timeoutTicks = 800)
    public static void aThumperIncursionBringsItsFirstWaveUp(GameTestHelper helper) {
        if (!ModList.get().isLoaded("deeperdarker")) {
            helper.succeed();
            return;
        }
        floor(helper);
        helper.setBlock(PLATE, SeismicContent.STRIKE_PLATE.get());
        helper.setBlock(THUMPER, SeismicContent.MECHANICAL_THUMPER.get());
        Incursions incursions = Incursions.of(helper.getLevel());
        incursions.stopAll();
        int minDistance = dev.brights0ng.enginesandempires.frontier.FrontierConfig.THUMPER_WAVE_MIN_DISTANCE.get();
        int maxDistance = dev.brights0ng.enginesandempires.frontier.FrontierConfig.THUMPER_WAVE_MAX_DISTANCE.get();
        // Inside the test's own walled space, where there is always room.
        dev.brights0ng.enginesandempires.frontier.FrontierConfig.THUMPER_WAVE_MIN_DISTANCE.set(1);
        dev.brights0ng.enginesandempires.frontier.FrontierConfig.THUMPER_WAVE_MAX_DISTANCE.set(2);
        Incursion incursion = incursions.startThumper(helper.absolutePos(THUMPER));
        helper.assertTrue(incursion != null, "it starts");
        helper.assertTrue(incursion.thumpers().contains(helper.absolutePos(THUMPER)), "it goes after the thumper that called it");
        helper.succeedWhen(() -> {
            helper.assertTrue(incursion.wave() >= 1 || incursion.isOver(), "waiting for the first wave");
            dev.brights0ng.enginesandempires.frontier.FrontierConfig.THUMPER_WAVE_MIN_DISTANCE.set(minDistance);
            dev.brights0ng.enginesandempires.frontier.FrontierConfig.THUMPER_WAVE_MAX_DISTANCE.set(maxDistance);
            helper.assertTrue(incursion.wave() >= 1, "the first wave came up (" + incursion.describe(helper.getLevel()) + ")");
            int tagged = helper.getLevel().getEntitiesOfClass(Mob.class, new AABB(helper.absolutePos(THUMPER)).inflate(64),
                    mob -> mob.getTags().contains(Incursion.TAG)).size();
            helper.assertTrue(tagged > 0, "its mobs are tagged");
            incursions.stopAll();
            helper.getLevel().getEntitiesOfClass(Mob.class, new AABB(helper.absolutePos(THUMPER)).inflate(64),
                    mob -> mob.getTags().contains(Incursion.TAG)).forEach(Mob::discard);
        });
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    @GameTest(template = SCRATCH, batch = "incursion_heart")
    public static void aWardenDropsASquelchingHeartThatOnlyWorksAtNight(GameTestHelper helper) {
        helper.assertTrue(dev.brights0ng.enginesandempires.frontier.incursion.NightHold.isNight(14000), "14000 is night");
        helper.assertTrue(dev.brights0ng.enginesandempires.frontier.incursion.NightHold.isNight(24000 * 3 + 22999), "just before dawn");
        helper.assertFalse(dev.brights0ng.enginesandempires.frontier.incursion.NightHold.isNight(24000 * 3 + 23000), "dawn is not");
        helper.assertFalse(dev.brights0ng.enginesandempires.frontier.incursion.NightHold.isNight(6000), "noon is not");
        floor(helper);
        net.minecraft.world.entity.monster.warden.Warden warden = helper.spawn(net.minecraft.world.entity.EntityType.WARDEN,
                new BlockPos(3, 1, 3));
        warden.hurt(helper.getLevel().damageSources().genericKill(), Float.MAX_VALUE);
        helper.succeedWhen(() -> helper.assertItemEntityPresent(
                dev.brights0ng.enginesandempires.frontier.FrontierContent.SQUELCHING_HEART.get(), new BlockPos(3, 1, 3), 4.0));
    }

    private static net.minecraft.world.level.block.state.BlockState block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }

    @GameTest(template = SCRATCH, batch = "tier_map")
    public static void theMapsAnswerCarriesEveryColumnAndRemembersUnloadedOnes(GameTestHelper helper) {
        var frontier = dev.brights0ng.enginesandempires.frontier.tier.FrontierLevels.of(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 3));
        var answer = dev.brights0ng.enginesandempires.frontier.map.TierMapNetwork.build(frontier, at.getX() >> 4, at.getZ() >> 4, 2);
        helper.assertTrue(answer.size() == 5, "a radius of 2 is 5 across");
        helper.assertTrue(dev.brights0ng.enginesandempires.frontier.map.TierGrid.unpack(answer.packed(), 25).length == 25, "25 columns");
        // A column far off, never loaded here: whatever was last remembered for it comes back.
        var memory = dev.brights0ng.enginesandempires.frontier.map.TierMemory.of(helper.getLevel());
        int far = 1_000_000 >> 4;
        memory.remember(far, far, dev.brights0ng.enginesandempires.frontier.Tier.SETTLED);
        helper.assertTrue(memory.groundTier(frontier, far, far) == dev.brights0ng.enginesandempires.frontier.Tier.SETTLED,
                "remembered as Settled");
        memory.remember(far, far, dev.brights0ng.enginesandempires.frontier.Tier.FRONTIER);
        helper.assertTrue(memory.groundTier(frontier, far, far) == dev.brights0ng.enginesandempires.frontier.Tier.FRONTIER,
                "and forgotten once Frontier");
        helper.succeed();
    }

    private FrontierIncursionGameTests() {
    }
}
