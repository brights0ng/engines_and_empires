// Written by Claude (GreedyFluids M8c c0).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import dev.brights0ng.greedyfluids.grid.Material;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.Bodies;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.Edit;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.Entry;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.Region;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.Retire;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.Source;
import dev.brights0ng.meteodyne.airflow.AirflowBackgroundLog.Weather;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Cell;
import dev.brights0ng.meteodyne.airflow.AirflowCells.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** GreedyFluids M8c c0: the background dump writes what a region is built from and sent, and reads it back. */
class AirflowBackgroundLogTest {

    static final Cell AIR = new Cell(Kind.AIR, null);
    static final Cell STONE = new Cell(Kind.SOLID, Material.STONE);
    static final Cell LAVA = new Cell(Kind.SOLID, Material.STONE, 1100);
    static final Cell SHUT = new Cell(Kind.DOOR_SHUT, Material.DOOR);

    /** Two sections side by side along x (32×16×16): stone floor at y 0; the second section's (x 16) cell 3, 1, 4 lava. */
    private static AirflowRegionModel.Spec spec(float[] lattice, int[] tops) {
        byte[] a = new byte[AirflowSections.SECTION_CELLS], b = new byte[AirflowSections.SECTION_CELLS];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                a[AirflowSections.Codes.index(x, 0, z)] = 1;
                b[AirflowSections.Codes.index(x, 0, z)] = 1;
            }
        }
        b[AirflowSections.Codes.index(3, 1, 4)] = 2;
        AirflowSections.Codes[] codes = {new AirflowSections.Codes(0, 1, AirflowCells.AIR, a),
                new AirflowSections.Codes(1, 1, AirflowCells.AIR, b)};
        AirflowCells.Table table = new AirflowCells.Table(1, new Cell[] {AIR, STONE, LAVA});
        return new AirflowRegionModel.Spec(-128, 64, 256, 32, 16, 16, codes, table, lattice[0], 1.5, -2,
                new int[] {(5 * 16 + 6) * 32 + 20}, new double[] {10_000}, new double[] {Double.NaN}, null, 100,
                lattice, tops, 0.05);
    }

    private static float[] lattice(float t) {
        float[] l = new float[AirflowField.points(32) * AirflowField.points(16) * AirflowField.points(16)];
        Arrays.fill(l, t);
        l[1] = t + 0.5f;
        return l;
    }

    @Test
    void aRegionAndItsInputsReadBackInOrder(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(AirflowRecorder.BACKGROUND_FILE);
        int[] tops = new int[32 * 16];
        Arrays.fill(tops, 1);
        float[] first = lattice(10), second = lattice(9.5f);
        try (AirflowBackgroundLog log = new AirflowBackgroundLog(file, 1000)) {
            log.region(1000, 7, spec(first, tops), false);
            log.weather(1020, 7, new AirflowRegionModel.Weather(10, 2, -2, first, tops.clone())); // nothing new
            log.weather(1040, 7, new AirflowRegionModel.Weather(9.5, 2, -2, second, tops.clone()));
            log.edit(1050, 7, 4, 1, 4, SHUT);
            log.source(1060, 7, 8, 1, 8, 500, 30);
            log.bodies(1080, 7, new double[] {8.5, 2.4, 8.5, 500});
            log.bodies(1100, 7, new double[] {8.6, 2.4, 8.5, 500}); // moved less than BODY_STEP: not written
            log.bodies(1120, 7, new double[] {12.5, 2.4, 8.5, 500});
            log.retire(1200, 7);
            log.close();
            assertArrayEquals(new long[] {1, 2, 1, 1, 2}, log.counts());
            assertNull(log.error());
        }

        AirflowBackgroundLog.Dump dump = AirflowBackgroundLog.read(file);
        assertEquals(1000, dump.startTick());
        List<Entry> e = dump.entries();
        assertEquals(8, e.size());

        Region r = assertInstanceOf(Region.class, e.get(0));
        assertEquals(7, r.id());
        assertEquals(-128, r.x0());
        assertEquals(32, r.nx());
        assertEquals(0.05, r.kappa(), 0);
        assertFalse(r.restored());
        assertFalse(r.snapshot());
        assertEquals(3, r.palette().length);
        assertEquals(1, r.palette()[1].kind(), "stone is a solid");
        assertEquals(Material.STONE.conductancePerBlock(), r.palette()[1].conductance(), 0);
        assertEquals(1100, r.palette()[2].wall(), 0, "lava's fixed wall");
        assertTrue(Double.isNaN(r.palette()[1].wall()));
        assertEquals(1, r.cells()[(0 * 16 + 4) * 32 + 3], "the floor, first section");
        assertEquals(0, r.cells()[(1 * 16 + 4) * 32 + 3], "air above it");
        assertEquals(2, r.cells()[(1 * 16 + 4) * 32 + 19], "lava at x 16 + 3 in region-cell order");
        assertArrayEquals(new double[] {10_000}, r.sourceWatts());
        assertArrayEquals(first, r.weather().lattice());
        assertArrayEquals(tops, r.weather().tops());
        assertEquals(1.5, r.weather().windX(), 0);

        Weather same = assertInstanceOf(Weather.class, e.get(1));
        assertSame(r.weather().lattice(), same.lattice(), "an unchanged lattice is filled in from the last");
        assertEquals(2, same.windX(), 0);
        Weather cooler = assertInstanceOf(Weather.class, e.get(2));
        assertArrayEquals(second, cooler.lattice());
        assertArrayEquals(tops, cooler.tops());

        Edit edit = assertInstanceOf(Edit.class, e.get(3));
        assertEquals(2, edit.cell().kind(), "a shut door");
        assertEquals(4, edit.x());
        Source s = assertInstanceOf(Source.class, e.get(4));
        assertEquals(500, s.watts(), 0);
        assertEquals(30, s.limit(), 0);
        assertEquals(8.5, assertInstanceOf(Bodies.class, e.get(5)).xyzw()[0], 0);
        assertEquals(12.5, assertInstanceOf(Bodies.class, e.get(6)).xyzw()[0], 0);
        assertEquals(1200, assertInstanceOf(Retire.class, e.get(7)).tick());
    }

    @Test
    void nothingIsWrittenAfterClosing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.gfbg");
        AirflowBackgroundLog log = new AirflowBackgroundLog(file, 5);
        log.close();
        log.retire(10, 1);
        log.close();
        assertEquals(List.of(), AirflowBackgroundLog.read(file).entries());
    }
}
