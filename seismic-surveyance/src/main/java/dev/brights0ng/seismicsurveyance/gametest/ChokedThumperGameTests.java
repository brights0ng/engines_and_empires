package dev.brights0ng.seismicsurveyance.gametest;

import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.seismicsurveyance.block.ChokedThumperBlock;
import dev.brights0ng.seismicsurveyance.logic.ChokedThumpers;
import dev.brights0ng.seismicsurveyance.block.CombustiveThumperBlock;
import dev.brights0ng.seismicsurveyance.SeismicContent;
import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game checks of choking thumpers (what a Frontier incursion does to them, {@link ChokedThumpers}), which need
 * Seismic Surveyance and nothing else: no Frontier code runs. A choked mechanical thumper keeps its axis, a choked
 * combustive one loses its column.
 *
 * <p>They were in the core until split step 4c, then in the test-only mod {@code coreless_tests}; since step 4d they are
 * Seismic Surveyance's, so they also run in its bare run. Step 4e renamed the class (it was {@code FrontierIncursionGameTests},
 * which these two never tested); the test names are unchanged. The check that has a Frontier incursion go after the
 * thumpers stays in {@code coreless_tests}, where both mods are.
 */
@GameTestHolder(SeismicSurveyance.MODID)
@PrefixGameTestTemplate(false)
public final class ChokedThumperGameTests {

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

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private ChokedThumperGameTests() {
    }
}
