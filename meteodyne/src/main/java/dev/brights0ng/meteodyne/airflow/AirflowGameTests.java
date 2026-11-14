// Written by Claude (GreedyFluids M7b).
package dev.brights0ng.meteodyne.airflow;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.greedyfluids.region.AirRegion;
import dev.brights0ng.greedyfluids.solve.Background3D;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GreedyFluids M7b part b1: the bundled library loads and runs inside the game, which the unit tests can't show (they
 * run on Gradle's classpath, not the game's module layers), and the engine switch defaults to LEGACY. Runs in the bare
 * Meteodyne run, the core-less run and the aggregate.
 */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class AirflowGameTests {

    /** An 8 m cube, open above and at the sides, on a stone floor, with a 200 W source in the middle of the air. */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void libraryStepsARegionInGame(GameTestHelper helper) {
        int n = 8;
        CellGrid3D grid = new CellGrid3D(n, n, n);
        grid.fillSolid(0, 0, 0, n - 1, 0, n - 1, Material.STONE);
        AirRegion region = new AirRegion(grid, Background3D.uniform(n, n, n, 10), 0, 0);
        region.setSource(4, 1, 4, 200);
        for (int i = 0; i < 20; i++) {
            region.step();
        }
        AirFrame frame = region.publish();
        double t = frame.temperature(4.5, 1.5, 4.5);
        helper.assertTrue(Math.abs(region.time() - 10) < 1e-9, "20 steps of 0.5 s should be 10 s, got " + region.time());
        helper.assertTrue(Double.isFinite(t) && t > 10, "the source's air should be warmer than the 10 C background, got " + t);
        helper.assertTrue(Airflow.engine() == Airflow.Engine.LEGACY, "the default engine should be LEGACY, got " + Airflow.engine());
        helper.succeed();
    }
}
