package dev.brights0ng.seismicsurveyance.gametest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.seismicsurveyance.CombustiveFiring;
import dev.brights0ng.seismicsurveyance.CombustiveThumperBlock;
import dev.brights0ng.seismicsurveyance.CombustiveThumperBlockEntity;
import dev.brights0ng.seismicsurveyance.SeismicContent;
import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import dev.brights0ng.seismicsurveyance.ThumperFluids;
import dev.brights0ng.seismicsurveyance.ThumperFuelData;
import dev.brights0ng.seismicsurveyance.ThumperFuels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The combustive thumper's fuel comes from data (split step 4c, {@link ThumperFuels}): with no fuel registered it can be
 * built but never fires, and says it needs a fuel source; with a fuel registered it fires on it. These run in Seismic
 * Surveyance's bare run (the library, Sparse Ores and Create: no fuel at all), and in every run that loads Seismic.
 *
 * <p>The fuels a test needs are put into the loaded data map for the length of the test, with a test-only fuel: lava,
 * which no mod makes a thumper fuel. Each test swaps the map's entries and puts them back before it returns, all on the
 * server thread in one go, so no other test ever sees them. The head is lifted by setting the block entity's armed flag
 * directly, as in {@link CombustiveThumperGameTests}.
 */
@GameTestHolder(SeismicSurveyance.MODID)
@PrefixGameTestTemplate(false)
public final class ThumperFuelGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    private static final BlockPos PLATE = new BlockPos(3, 1, 3);
    private static final BlockPos THUMPER = PLATE.above();

    /** The test-only fuel. */
    private static final Fluid TEST_FUEL = Fluids.LAVA;

    @GameTest(template = SCRATCH)
    public static void withNoFuelRegisteredItCannotFireAndSaysWhy(GameTestHelper helper) {
        withFuels(Map.of(), () -> {
            helper.assertFalse(ThumperFuels.anyRegistered(), "no fuel should be registered");
            helper.assertTrue(ThumperFluids.classify(new FluidStack(TEST_FUEL, 1000)) == CombustiveFiring.Charge.INERT,
                    "with no fuel registered, lava should not burn");
            CombustiveThumperBlockEntity thumper = build(helper);
            fill(thumper, TEST_FUEL, 1000);
            arm(thumper);
            thumper.fireIfArmed();
            helper.assertTrue(thumper.fluid().getAmount() == 1000, "it burned " + (1000 - thumper.fluid().getAmount())
                    + " mB with no fuel registered");
            helper.assertTrue(hinted(thumper.fuelLines()), "the goggles should say it needs a fuel source: "
                    + thumper.fuelLines());
            helper.assertTrue(hinted(itemTooltip(helper)), "the item should say it needs a fuel source: " + itemTooltip(helper));
        });
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void withATestFuelRegisteredItFires(GameTestHelper helper) {
        withFuels(Map.of(key(TEST_FUEL), new ThumperFuels.Fuel(ThumperFuels.Grade.PREMIUM)), () -> {
            helper.assertTrue(ThumperFuels.anyRegistered(), "the test fuel should be registered");
            CombustiveThumperBlockEntity thumper = build(helper);
            fill(thumper, TEST_FUEL, 1000);
            arm(thumper);
            thumper.fireIfArmed();
            helper.assertTrue(thumper.fluid().getAmount() == 1000 - CombustiveFiring.PREMIUM_COST,
                    "a premium shot should burn " + CombustiveFiring.PREMIUM_COST + " mB, " + thumper.fluid().getAmount()
                            + " mB left");
            helper.assertFalse(hinted(thumper.fuelLines()), "no hint once there is a fuel: " + thumper.fuelLines());
            helper.assertFalse(hinted(itemTooltip(helper)), "no hint once there is a fuel: " + itemTooltip(helper));
        });
        helper.succeed();
    }

    /**
     * Seismic's own entries are only the fluid tags (empty unless another mod fills them) and Create: Diesel Generators'
     * fluids behind a condition on that mod: in a game without Diesel Generators no fuel is registered at all, and with
     * it, its fluids have the grades they had before split step 4c.
     */
    @GameTest(template = SCRATCH)
    public static void seismicsOwnFuelsComeWithDieselGenerators(GameTestHelper helper) {
        if (!ModList.get().isLoaded(ThumperFuelData.DIESEL_GENERATORS)) {
            helper.assertTrue(ThumperFuels.all().isEmpty(), "without Diesel Generators no fuel should be registered, found "
                    + ThumperFuels.all());
        } else {
            for (Map.Entry<String, ThumperFuels.Grade> fuel : ThumperFuelData.DIESEL_GENERATORS_FUELS.entrySet()) {
                Fluid fluid = BuiltInRegistries.FLUID.get(
                        ResourceLocation.fromNamespaceAndPath(ThumperFuelData.DIESEL_GENERATORS, fuel.getKey()));
                ThumperFuels.Fuel loaded = ThumperFuels.of(fluid);
                helper.assertTrue(loaded != null && loaded.grade() == fuel.getValue(),
                        fuel.getKey() + " should be " + fuel.getValue() + ", is " + loaded);
            }
        }
        helper.succeed();
    }

    // ---- helpers ----

    /**
     * Runs {@code body} with exactly these fuels in the loaded data map, then puts the loaded ones back, whatever
     * happens. The map is NeoForge's own, filled at every data load; the test changes it only while it runs.
     */
    private static void withFuels(Map<ResourceKey<Fluid>, ThumperFuels.Fuel> fuels, Runnable body) {
        Map<ResourceKey<Fluid>, ThumperFuels.Fuel> loaded = ThumperFuels.all();
        Map<ResourceKey<Fluid>, ThumperFuels.Fuel> saved = new HashMap<>(loaded);
        try {
            loaded.clear();
            loaded.putAll(fuels);
        } catch (UnsupportedOperationException e) {
            throw new IllegalStateException("the thumper fuel data map was not loaded (no file for it in any pack?)", e);
        }
        try {
            body.run();
        } finally {
            loaded.clear();
            loaded.putAll(saved);
        }
    }

    private static boolean hinted(List<Component> lines) {
        for (Component line : lines) {
            if (line.getString().contains(CombustiveThumperBlock.noFuelHint().getString())) {
                return true;
            }
        }
        return false;
    }

    private static List<Component> itemTooltip(GameTestHelper helper) {
        List<Component> lines = new ArrayList<>();
        SeismicContent.COMBUSTIVE_THUMPER.get().appendHoverText(new ItemStack(SeismicContent.COMBUSTIVE_THUMPER.get()),
                Item.TooltipContext.of(helper.getLevel()), lines, TooltipFlag.NORMAL);
        return lines;
    }

    private static ResourceKey<Fluid> key(Fluid fluid) {
        return BuiltInRegistries.FLUID.getResourceKey(fluid).orElseThrow();
    }

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

    private ThumperFuelGameTests() {
    }
}
