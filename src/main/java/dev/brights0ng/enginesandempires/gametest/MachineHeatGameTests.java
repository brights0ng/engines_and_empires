package dev.brights0ng.enginesandempires.gametest;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.meteodyne.heat.HeatField;
import dev.brights0ng.meteodyne.heat.HeatSourceData;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The pack's machines in Meteodyne's heat tables (Bright, step 1c-B: Create's blaze burner is a core machine entry, not
 * one of Meteodyne's own heat sources). Moved here from Meteodyne's {@code HeatGameTests.theSeededTableIsThere}.
 */
@GameTestHolder(EnginesAndEmpires.MODID)
@PrefixGameTestTemplate(false)
public final class MachineHeatGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(template = SCRATCH, batch = "heat")
    public static void theBlazeBurnerIsAHeatSource(GameTestHelper helper) {
        BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse("create:blaze_burner")).ifPresent(burner -> {
            check(helper, with(burner, "blaze", "seething"), 16.67, 6, 33.33, 204.4);
            check(helper, with(burner, "blaze", "kindled"), 11.11, 5, 25, 204.4);
            check(helper, with(burner, "blaze", "fading"), 6.67, 4, 16.67, 204.4);
            check(helper, with(burner, "blaze", "smouldering"), 2.78, 3, 16.67, 204.4);
            helper.assertTrue(HeatField.variantOf(with(burner, "blaze", "none")) == null, "an unlit burner gives nothing");
        });
        helper.succeed();
    }

    private static BlockState with(Block block, String property, String value) {
        Property<?> p = block.getStateDefinition().getProperty(property);
        if (p == null) {
            throw new IllegalStateException(block + " has no property " + property);
        }
        return set(block.defaultBlockState(), p, value);
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> p, String value) {
        return state.setValue(p, p.getValue(value).orElseThrow(() -> new IllegalStateException(p + " has no " + value)));
    }

    private static void check(GameTestHelper helper, BlockState state, double change, double range, double cap, double limit) {
        HeatSourceData.Variant v = HeatField.variantOf(state);
        helper.assertTrue(v != null, state + " should be a source");
        String what = state.toString();
        near(helper, what + " change", change, v.change());
        near(helper, what + " range", range, v.range());
        near(helper, what + " cap", cap, v.maxEffect());
        near(helper, what + " limit", limit, v.limit());
    }

    private static void near(GameTestHelper helper, String what, double expected, double got) {
        helper.assertTrue(Math.abs(expected - got) < 0.01, what + ": expected " + expected + ", got " + got);
    }

    private MachineHeatGameTests() {
    }
}
