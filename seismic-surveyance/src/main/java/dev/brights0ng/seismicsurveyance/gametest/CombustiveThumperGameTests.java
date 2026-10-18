package dev.brights0ng.seismicsurveyance.gametest;

import java.lang.reflect.Field;

import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.seismicsurveyance.CombustiveThumperBlock;
import dev.brights0ng.seismicsurveyance.CombustiveThumperBlockEntity;
import dev.brights0ng.seismicsurveyance.SeismicContent;
import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-world tests for the combustive thumper that need no fuel of Create: Diesel Generators, so Seismic Surveyance's bare
 * run covers the machine (split step 2d; they were in the core's thumper tests since 2e). The tests on Diesel
 * Generators' fuels stay core integration tests ({@code enginesandempires.gametest.CombustiveThumperFuelGameTests}).
 *
 * <p>Each test starts from an empty template ({@link GameTestStructures}) and builds what it needs. The head is lifted by
 * setting the block entity's armed flag directly: the tests are about what a shot does, not about the torque that lifts it.
 */
@GameTestHolder(SeismicSurveyance.MODID)
@PrefixGameTestTemplate(false)
public final class CombustiveThumperGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    private static final BlockPos PLATE = new BlockPos(3, 1, 3);
    private static final BlockPos THUMPER = PLATE.above();

    /** Taking away the top (not by a player: an explosion, a command) brings the whole machine down, and drops it. */
    @GameTest(template = SCRATCH, timeoutTicks = 60)
    public static void losingThePartsAboveDropsTheMachine(GameTestHelper helper) {
        build(helper);
        helper.setBlock(THUMPER.above(2), Blocks.AIR);
        helper.succeedWhen(() -> {
            helper.assertBlockNotPresent(SeismicContent.COMBUSTIVE_THUMPER.get(), THUMPER);
            helper.assertBlockNotPresent(SeismicContent.COMBUSTIVE_THUMPER_COLUMN.get(), THUMPER.above());
            helper.assertItemEntityPresent(SeismicContent.COMBUSTIVE_THUMPER.get().asItem(), THUMPER, 2.0);
        });
    }

    /** A water-filled tank: the ram just drops, nothing burns, nothing explodes. */
    @GameTest(template = SCRATCH)
    public static void waterShotIsADud(GameTestHelper helper) {
        CombustiveThumperBlockEntity thumper = build(helper);
        helper.setBlock(THUMPER.east(), Blocks.STONE);
        fill(thumper, Fluids.WATER, 1000);
        arm(thumper);
        thumper.fireIfArmed();
        helper.runAfterDelay(20, () -> {
            if (thumper.fluid().getAmount() != 1000) {
                helper.fail("water was used up: " + thumper.fluid().getAmount());
                return;
            }
            helper.assertBlockPresent(Blocks.STONE, THUMPER.east());
            helper.assertBlockPresent(SeismicContent.COMBUSTIVE_THUMPER.get(), THUMPER);
            helper.succeed();
        });
    }

    // ---- helpers ----

    /** Lays a stone floor and stands a whole thumper (base and column) on a strike plate in the middle of it. */
    private static CombustiveThumperBlockEntity build(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(PLATE, SeismicContent.STRIKE_PLATE.get());
        helper.setBlock(THUMPER, SeismicContent.COMBUSTIVE_THUMPER.get());
        CombustiveThumperBlock.placeColumn(helper.getLevel(), helper.absolutePos(THUMPER),
                helper.getBlockState(THUMPER).getValue(CombustiveThumperBlock.HORIZONTAL_AXIS));
        if (!(helper.getBlockEntity(THUMPER) instanceof CombustiveThumperBlockEntity thumper)) {
            throw new IllegalStateException("no combustive thumper block entity was created");
        }
        return thumper;
    }

    private static void fill(CombustiveThumperBlockEntity thumper, Fluid fluid, int amount) {
        int filled = thumper.fluidCapability().fill(new FluidStack(fluid, amount), IFluidHandler.FluidAction.EXECUTE);
        if (filled != amount) {
            throw new IllegalStateException("only " + filled + " of " + amount + " mB went into the tank");
        }
    }

    private static void arm(CombustiveThumperBlockEntity thumper) {
        try {
            Field armed = CombustiveThumperBlockEntity.class.getDeclaredField("armed");
            armed.setAccessible(true);
            armed.setBoolean(thumper, true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private CombustiveThumperGameTests() {
    }
}
