package dev.brights0ng.enginesandempires.gametest;

import java.lang.reflect.Field;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.seismicsurveyance.logic.CombustiveFiring;
import dev.brights0ng.seismicsurveyance.block.CombustiveThumperBlock;
import dev.brights0ng.seismicsurveyance.blockentity.CombustiveThumperBlockEntity;
import dev.brights0ng.seismicsurveyance.SeismicContent;
import dev.brights0ng.seismicsurveyance.logic.ThumperFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-world tests for the combustive thumper on Create: Diesel Generators' fuels, run by the {@code GameTestServer} run
 * configuration (or {@code /test runall} in a dev world). They need Diesel Generators, which the dev runs always have.
 * That makes them core integration tests (split step 2e): Seismic Surveyance only has Diesel Generators optionally, and
 * its bare run goes without it. The thumper's tests that need no fuel are Seismic Surveyance's own
 * ({@code seismicsurveyance.gametest.CombustiveThumperGameTests}, split step 2d).
 *
 * <p>Each test starts from an empty template ({@link GameTestStructures}) and builds what it needs. The head is lifted by
 * setting the block entity's armed flag directly: the tests are about what a shot does, not about the torque that lifts it.
 */
@GameTestHolder(EnginesAndEmpires.MODID)
@PrefixGameTestTemplate(false)
public final class CombustiveThumperFuelGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    private static final BlockPos PLATE = new BlockPos(3, 1, 3);
    private static final BlockPos THUMPER = PLATE.above();

    /** A diesel shot must burn 150 mB out of the tank. */
    @GameTest(template = SCRATCH)
    public static void dieselShotBurnsItsFuel(GameTestHelper helper) {
        CombustiveThumperBlockEntity thumper = build(helper);
        fill(thumper, fluid("createdieselgenerators:diesel"), 1000);
        arm(thumper);
        thumper.fireIfArmed();
        int left = thumper.fluid().getAmount();
        if (left != 850) {
            helper.fail("expected 850 mB of diesel left after one shot, found " + left);
            return;
        }
        helper.succeed();
    }

    /** Diesel Generators' diesel must be sorted as premium fuel by the real, loaded tags. */
    @GameTest(template = SCRATCH)
    public static void dieselIsPremiumFuel(GameTestHelper helper) {
        CombustiveFiring.Charge charge = ThumperFluids.classify(new FluidStack(fluid("createdieselgenerators:diesel"), 1000));
        if (charge != CombustiveFiring.Charge.PREMIUM_FUEL) {
            helper.fail("diesel was sorted as " + charge);
            return;
        }
        helper.succeed();
    }

    /** The in-game path: a redstone signal arriving next to an armed thumper fires it, and the diesel is burned. */
    @GameTest(template = SCRATCH)
    public static void redstoneFiresItAndBurnsDiesel(GameTestHelper helper) {
        CombustiveThumperBlockEntity thumper = build(helper);
        fill(thumper, fluid("createdieselgenerators:diesel"), 1000);
        arm(thumper);
        helper.setBlock(THUMPER.north(), Blocks.REDSTONE_BLOCK);
        helper.succeedWhen(() -> {
            int left = thumper.fluid().getAmount();
            if (left != 850) {
                throw new net.minecraft.gametest.framework.GameTestAssertException("expected 850 mB left, found " + left);
            }
        });
    }

    /** A redstone signal reaching the top of the column fires it just the same as one at the base. */
    @GameTest(template = SCRATCH)
    public static void redstoneAtTheTopFiresItToo(GameTestHelper helper) {
        CombustiveThumperBlockEntity thumper = build(helper);
        fill(thumper, fluid("createdieselgenerators:diesel"), 1000);
        arm(thumper);
        helper.setBlock(THUMPER.above(2).north(), Blocks.REDSTONE_BLOCK);
        helper.succeedWhen(() -> {
            if (thumper.fluid().getAmount() != 850) {
                throw new GameTestAssertException("expected 850 mB left, found " + thumper.fluid().getAmount());
            }
        });
    }

    /** Pipes reach the base's tank through the fuel cylinder on top. */
    @GameTest(template = SCRATCH)
    public static void theCylinderSharesTheBasesTank(GameTestHelper helper) {
        CombustiveThumperBlockEntity thumper = build(helper);
        IFluidHandler top = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK,
                helper.absolutePos(THUMPER.above(2)), Direction.NORTH);
        if (top == null) {
            helper.fail("the cylinder has no fluid capability");
            return;
        }
        top.fill(new FluidStack(fluid("createdieselgenerators:diesel"), 500), IFluidHandler.FluidAction.EXECUTE);
        if (thumper.fluid().getAmount() != 500) {
            helper.fail("filling the cylinder did not reach the base's tank: " + thumper.fluid().getAmount());
            return;
        }
        helper.succeed();
    }

    /**
     * Firing on ethanol must blow up the stone around it, not just the thumper and its plate, and only once the ram has
     * come down, a few ticks after the pulse.
     */
    @GameTest(template = SCRATCH)
    public static void ethanolShotBlowsUpItsSurroundings(GameTestHelper helper) {
        CombustiveThumperBlockEntity thumper = build(helper);
        BlockPos beside = THUMPER.east();
        helper.setBlock(beside, Blocks.STONE);
        fill(thumper, fluid("createdieselgenerators:ethanol"), 1000);
        arm(thumper);
        thumper.fireIfArmed();
        if (helper.getBlockState(beside).isAir()) {
            helper.fail("the blast went off at the pulse, before the ram came down");
            return;
        }
        helper.succeedWhen(() -> {
            if (!helper.getBlockState(beside).isAir()) {
                throw new GameTestAssertException("the stone right beside the thumper survived the blast");
            }
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

    private static Fluid fluid(String id) {
        Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(id));
        if (fluid == Fluids.EMPTY) {
            throw new IllegalStateException(id + " is not registered: is Create: Diesel Generators loaded?");
        }
        return fluid;
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

    private CombustiveThumperFuelGameTests() {
    }
}
