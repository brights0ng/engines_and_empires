package dev.brights0ng.frontier.gametest;

import java.util.HashSet;
import java.util.Set;

import dev.brights0ng.frontier.Frontier;
import dev.brights0ng.frontier.FrontierContent;
import dev.brights0ng.frontier.deep.DeepRoster;
import dev.brights0ng.frontier.incursion.Incursion;
import dev.brights0ng.frontier.incursion.Incursions;
import dev.brights0ng.frontier.incursion.Settlement;
import dev.brights0ng.frontier.incursion.SettlementClusters;
import dev.brights0ng.frontier.tier.SectionKey;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game checks of incursions that need Frontier and nothing else (Frontier phase 6): snuffing torches, settlements, the
 * squelching heart, the tier map's answer, and the wave rosters loading. Each runs in its own batch.
 *
 * <p>They were in the core until split step 4c, then in the test-only mod {@code coreless_tests}; since step 4d they are
 * Frontier's, under the same class and test names, so they also run in Frontier's bare run. The checks that need
 * Seismic Surveyance as well (a thumper incursion bringing its first wave up) stay in {@code coreless_tests}, and the one
 * that needs the core's data (Create's machines as loud machines) in the core's {@code PackIncursionGameTests}.
 * The wave rosters check was the first half of the core's former {@code theWaveRostersAndLoudMachinesLoad}; split step 4e moved it
 * here as {@code theWaveRostersLoad}, since the rosters are Frontier's own data.
 */
@GameTestHolder(Frontier.MODID)
@PrefixGameTestTemplate(false)
public final class FrontierIncursionGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

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

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    @GameTest(template = SCRATCH, batch = "incursion_heart")
    public static void aWardenDropsASquelchingHeartThatOnlyWorksAtNight(GameTestHelper helper) {
        helper.assertTrue(dev.brights0ng.frontier.incursion.NightHold.isNight(14000), "14000 is night");
        helper.assertTrue(dev.brights0ng.frontier.incursion.NightHold.isNight(24000 * 3 + 22999), "just before dawn");
        helper.assertFalse(dev.brights0ng.frontier.incursion.NightHold.isNight(24000 * 3 + 23000), "dawn is not");
        helper.assertFalse(dev.brights0ng.frontier.incursion.NightHold.isNight(6000), "noon is not");
        floor(helper);
        net.minecraft.world.entity.monster.warden.Warden warden = helper.spawn(net.minecraft.world.entity.EntityType.WARDEN,
                new BlockPos(3, 1, 3));
        warden.hurt(helper.getLevel().damageSources().genericKill(), Float.MAX_VALUE);
        helper.succeedWhen(() -> helper.assertItemEntityPresent(
                dev.brights0ng.frontier.FrontierContent.SQUELCHING_HEART.get(), new BlockPos(3, 1, 3), 4.0));
    }

    @GameTest(template = SCRATCH, batch = "tier_map")
    public static void theMapsAnswerCarriesEveryColumnAndRemembersUnloadedOnes(GameTestHelper helper) {
        var frontier = dev.brights0ng.frontier.tier.FrontierLevels.of(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(3, 1, 3));
        var answer = dev.brights0ng.frontier.map.TierMapNetwork.build(frontier, at.getX() >> 4, at.getZ() >> 4, 2);
        helper.assertTrue(answer.size() == 5, "a radius of 2 is 5 across");
        helper.assertTrue(dev.brights0ng.frontier.map.TierGrid.unpack(answer.packed(), 25).length == 25, "25 columns");
        // A column far off, never loaded here: whatever was last remembered for it comes back.
        var memory = dev.brights0ng.frontier.map.TierMemory.of(helper.getLevel());
        int far = 1_000_000 >> 4;
        memory.remember(far, far, dev.brights0ng.frontier.Tier.SETTLED);
        helper.assertTrue(memory.groundTier(frontier, far, far) == dev.brights0ng.frontier.Tier.SETTLED,
                "remembered as Settled");
        memory.remember(far, far, dev.brights0ng.frontier.Tier.FRONTIER);
        helper.assertTrue(memory.groundTier(frontier, far, far) == dev.brights0ng.frontier.Tier.FRONTIER,
                "and forgotten once Frontier");
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "incursion_data")
    public static void theWaveRostersLoad(GameTestHelper helper) {
        helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.LESSER).isPresent(), "the lesser roster");
        for (int wave = 1; wave <= Incursions.GREATER_WAVES; wave++) {
            helper.assertTrue(DeepRoster.get(helper.getLevel(), DeepRoster.greater(wave)).isPresent(), "greater wave " + wave);
        }
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
        helper.succeed();
    }

    private FrontierIncursionGameTests() {
    }
}
