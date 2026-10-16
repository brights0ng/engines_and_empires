package dev.brights0ng.paradynelib.gametest;

import dev.brights0ng.paradynelib.ParadyneLib;
import dev.brights0ng.paradynelib.services.StormProvider;
import dev.brights0ng.paradynelib.services.TemperatureProvider;
import dev.brights0ng.paradynelib.services.WindProvider;
import dev.brights0ng.paradynelib.wind.WindColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What the library's shared questions answer with nobody to ask (split seams S1-S3), in a real level: the defaults a mod
 * gets when the mod that would answer is not installed. (Unit tests cannot ask them: they run without Minecraft.)
 */
@GameTestHolder(ParadyneLib.MODID)
@PrefixGameTestTemplate(false)
public final class ServicesGameTests {

    private static final BlockPos SPOT = new BlockPos(3, 1, 3);

    @GameTest(template = GameTestStructures.EMPTY, batch = "services_defaults")
    public static void withoutAProviderTemperaturesAreUnknown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(SPOT);
        TemperatureProvider none = TemperatureProvider.NONE;
        helper.assertTrue(Double.isNaN(none.air(level, at)), "unknown air: food ages by the clock alone");
        helper.assertTrue(Double.isNaN(none.seasonal(level, at)), "unknown seasonal mean");
        helper.assertTrue(none.room(level, Vec3.atCenterOf(at)) == 0, "no room");
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "services_defaults")
    public static void withoutAProviderItIsCalm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(SPOT);
        WindProvider none = WindProvider.NONE;
        helper.assertTrue(none.column(level, at.getX(), at.getZ()) == null, "no wind anywhere: nothing is pushed");
        none.setOverride(WindColumn.uniform(10, 0));
        helper.assertTrue(none.column(level, at.getX(), at.getZ()) == null, "nothing to force without a provider");
        helper.succeed();
    }

    @GameTest(template = GameTestStructures.EMPTY, batch = "services_defaults")
    public static void withoutAProviderThereIsNoStorm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(SPOT);
        StormProvider none = StormProvider.NONE;
        helper.assertFalse(none.owns(level), "vanilla's weather decides everywhere");
        helper.assertFalse(none.thunderOver(level, at), "no thunderstorm");
        helper.assertTrue(none.skyDarken(level, at) == 0, "no storm darkening the sky");
        helper.succeed();
    }

    private ServicesGameTests() {
    }
}
