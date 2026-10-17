package dev.brights0ng.meteodyne.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * The open sky the weather's game tests need: the test framework roofs each test area with barrier blocks. Used by the
 * lightning tests and by the ship tests in {@code meteodyne.sable.gametest} (moved here from {@code ShipWeatherGameTests}
 * in split step 2c, so the tests that need no Sable don't load a class that does).
 */
public final class GameTestSky {

    /** Opens the test framework's barrier roof over the whole test area. */
    public static void openSky(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = -1; x <= 9; x++) {
            for (int z = -1; z <= 9; z++) {
                BlockPos base = helper.absolutePos(new BlockPos(x, 0, z));
                for (int y = 1; y <= 24; y++) {
                    BlockPos p = base.above(y);
                    if (level.getBlockState(p).is(Blocks.BARRIER)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private GameTestSky() {
    }
}
