// Written by Claude (GreedyFluids M7b).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.greedyfluids.region.AirRegion;
import dev.brights0ng.greedyfluids.solve.Background3D;

/**
 * GreedyFluids M7b part b1: Meteodyne compiles against the pinned GreedyFluids and runs it. (The engine switch needs
 * NeoForge's config classes, which these unit tests run without; {@code AirflowGameTests} checks its default.)
 */
class AirflowLibraryTest {

    /** An 8 m cube, open above and at the sides, on a stone floor, with a 200 W source in the middle of the air. */
    @Test
    void pinnedLibraryStepsARegion() {
        int n = 8;
        CellGrid3D grid = new CellGrid3D(n, n, n);
        grid.fillSolid(0, 0, 0, n - 1, 0, n - 1, Material.STONE);
        AirRegion region = new AirRegion(grid, Background3D.uniform(n, n, n, 10), 0, 0);
        region.setSource(4, 1, 4, 200);
        for (int i = 0; i < 20; i++) {
            region.step();
        }
        AirFrame frame = region.publish();
        assertEquals(10, region.time(), 1e-9);
        double source = frame.temperature(4.5, 1.5, 4.5);
        assertTrue(Double.isFinite(source) && source > 10, "source air " + source + " C");
        assertEquals(10, frame.temperature(4.5, 0.5, 4.5), 1e-12, "solid reads the background");
    }
}
