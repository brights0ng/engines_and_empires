package dev.brights0ng.corelesstests.gametest;

import dev.brights0ng.corelesstests.CorelessTests;
import dev.brights0ng.frontier.incursion.Incursion;
import dev.brights0ng.frontier.incursion.Incursions;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.seismicsurveyance.SeismicContent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The in-game check of incursions that needs both Frontier and Seismic Surveyance (Frontier phase 6): a thumper incursion
 * bringing its first wave up.
 *
 * <p>It needs both mods (and Create, which Seismic requires), and no code of the core, so since split step 4c it is this
 * test-only mod's and runs without the core as well as in the aggregate; until then it was the core's. The checks that
 * need only one of the two moved to that mod in split step 4d, under the same class and test names: snuffing torches,
 * settlements, the squelching heart and the tier map's answer are Frontier's; choking thumpers is Seismic Surveyance's.
 * The one check that needs the core's data (Create's machines as loud machines) stays in the core's
 * {@code enginesandempires.gametest.FrontierIncursionGameTests}, under the same class name so every test keeps its name.
 */
@GameTestHolder(CorelessTests.MODID)
@PrefixGameTestTemplate(false)
public final class FrontierIncursionGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final BlockPos PLATE = new BlockPos(3, 1, 3);
    private static final BlockPos THUMPER = new BlockPos(3, 2, 3);

    @GameTest(template = SCRATCH, batch = "incursion_waves", timeoutTicks = 800)
    public static void aThumperIncursionBringsItsFirstWaveUp(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(PLATE, SeismicContent.STRIKE_PLATE.get());
        helper.setBlock(THUMPER, SeismicContent.MECHANICAL_THUMPER.get());
        Incursions incursions = Incursions.of(helper.getLevel());
        incursions.stopAll();
        int minDistance = dev.brights0ng.frontier.FrontierConfig.THUMPER_WAVE_MIN_DISTANCE.get();
        int maxDistance = dev.brights0ng.frontier.FrontierConfig.THUMPER_WAVE_MAX_DISTANCE.get();
        // Inside the test's own walled space, where there is always room.
        dev.brights0ng.frontier.FrontierConfig.THUMPER_WAVE_MIN_DISTANCE.set(1);
        dev.brights0ng.frontier.FrontierConfig.THUMPER_WAVE_MAX_DISTANCE.set(2);
        Incursion incursion = incursions.startThumper(helper.absolutePos(THUMPER));
        helper.assertTrue(incursion != null, "it starts");
        helper.assertTrue(incursion.thumpers().contains(helper.absolutePos(THUMPER)), "it goes after the thumper that called it");
        helper.succeedWhen(() -> {
            helper.assertTrue(incursion.wave() >= 1 || incursion.isOver(), "waiting for the first wave");
            dev.brights0ng.frontier.FrontierConfig.THUMPER_WAVE_MIN_DISTANCE.set(minDistance);
            dev.brights0ng.frontier.FrontierConfig.THUMPER_WAVE_MAX_DISTANCE.set(maxDistance);
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

    private FrontierIncursionGameTests() {
    }
}
