// Written by Claude (GreedyFluids M7d d2); edited by Claude (M7f: stores one by one).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import dev.brights0ng.greedyfluids.region.ThermalState;
import org.junit.jupiter.api.Test;

class AirflowSavedTest {

    private static final int NX = 32, NY = 16, NZ = 16;

    private static int cell(int x, int y, int z) {
        return (y * NZ + z) * NX + x;
    }

    private static ThermalState state() {
        float[] air = new float[NX * NY * NZ], solid = new float[air.length];
        Arrays.fill(air, 10f);
        Arrays.fill(solid, Float.NaN);
        air[cell(3, 4, 5)] = 25f; // a warm cell in the first section
        air[cell(20, 1, 2)] = 2f; // a cold one in the second
        air[cell(7, 7, 7)] = 10.01f; // under KEEP: left out
        air[cell(9, 9, 9)] = Float.NaN; // solid
        solid[cell(9, 9, 9)] = 18f;
        return new ThermalState(NX, NY, NZ, air, solid);
    }

    @Test
    void packKeepsOnlyOffsetsAwayFromTheBackground() {
        AirflowSaved.Section[] s = AirflowSaved.pack(state(), c -> 10, 100);
        assertEquals(2, s.length);
        assertEquals(2, s[0].cells().length, "the warm air cell and the wall: " + Arrays.toString(s[0].cells()));
        assertEquals(1, s[1].cells().length);
        assertEquals(15, s[0].offset((4 * 16 + 5) * 16 + 3, false, 100), 1e-6);
        assertEquals(8, s[0].offset((9 * 16 + 9) * 16 + 9, true, 100), 1e-6);
        assertTrue(Double.isNaN(s[0].offset((9 * 16 + 9) * 16 + 9, false, 100)), "no air value in a solid cell");
        assertTrue(Double.isNaN(s[0].offset((7 * 16 + 7) * 16 + 7, false, 100)), "under KEEP");
        assertEquals(-8, s[1].offset((1 * 16 + 2) * 16 + 4, false, 100), 1e-6);
    }

    @Test
    void unpackDecaysTowardTodaysBackground() {
        AirflowSaved.Section[] s = AirflowSaved.pack(state(), c -> 10, 100);
        long day = (long) AirflowSaved.TAU_TICKS;
        ThermalState back = AirflowSaved.unpack(s, NX, NY, NZ, c -> 4, 100 + day);
        assertEquals(4 + 15 * Math.exp(-1), back.air(cell(3, 4, 5)), 1e-4, "one time constant later, on a 4 C day");
        assertEquals(4 + 8 * Math.exp(-1), back.solid(cell(9, 9, 9)), 1e-4);
        assertTrue(Float.isNaN(back.air(cell(0, 0, 0))), "unsaved cells start at the background");
        assertEquals(4 - 8 * Math.exp(-1), back.air(cell(20, 1, 2)), 1e-4);
        ThermalState same = AirflowSaved.unpack(s, NX, NY, NZ, c -> 10, 100);
        assertEquals(25, same.air(cell(3, 4, 5)), 1e-5, "no time passed: as saved");
    }

    @Test
    void nothingSavedIsNull() {
        assertNull(AirflowSaved.unpack(new AirflowSaved.Section[2], NX, NY, NZ, c -> 10, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowSaved.unpack(new AirflowSaved.Section[3], NX, NY, NZ, c -> 10, 0));
    }

    @Test
    void storesKeptOneByOneComeBackOneByOne() {
        float[] air = new float[NX * NY * NZ], solid = new float[air.length];
        Arrays.fill(air, 10f);
        Arrays.fill(solid, Float.NaN);
        solid[cell(9, 9, 9)] = 18f; // a block mean: not written when stores are kept
        // three stores: two in the first section (one under KEEP), one in the second, out of order
        int[] cells = {cell(20, 3, 4), cell(5, 6, 7), cell(5, 6, 7), cell(1, 1, 1)};
        byte[] dirs = {6, 1, 3, 0};
        float[] temps = {4f, 16f, 13f, 10.01f};
        ThermalState state = new ThermalState(NX, NY, NZ, air, solid, cells, dirs, temps);
        AirflowSaved.Section[] s = AirflowSaved.pack(state, c -> 10, 100);
        for (AirflowSaved.Section section : s) {
            for (int i = 1; i < section.cells().length; i++) {
                assertTrue(section.cells()[i] > section.cells()[i - 1], "ascending: " + Arrays.toString(section.cells()));
            }
            for (int key : section.cells()) {
                assertTrue(key < AirflowSaved.KEY_LIMIT && (key & AirflowSaved.WALL) == 0, "no block means: " + key);
            }
        }
        assertEquals(2, s[0].cells().length, "two stores in the first section");
        assertEquals(1, s[1].cells().length);
        ThermalState back = AirflowSaved.unpack(s, NX, NY, NZ, c -> 10, 100);
        assertEquals(3, back.stores());
        assertTrue(Float.isNaN(back.solid(cell(9, 9, 9))), "block means are left out");
        for (int k = 0; k < back.stores(); k++) {
            int c = back.storeCell(k), d = back.storeDir(k);
            float t = back.storeT(k);
            if (c == cell(20, 3, 4)) {
                assertEquals(6, d);
                assertEquals(4f, t, 1e-5);
            } else {
                assertEquals(cell(5, 6, 7), c);
                assertEquals(d == 1 ? 16f : 13f, t, 1e-5, "direction " + d);
            }
        }
        long day = (long) AirflowSaved.TAU_TICKS;
        ThermalState later = AirflowSaved.unpack(s, NX, NY, NZ, c -> 4, 100 + day);
        for (int k = 0; k < later.stores(); k++) {
            if (later.storeCell(k) == cell(20, 3, 4)) {
                assertEquals(4 - 6 * Math.exp(-1), later.storeT(k), 1e-4, "decays toward today's background");
            }
        }
    }

    @Test
    void decayIsExponentialAndIgnoresNegativeTime() {
        assertEquals(10, AirflowSaved.decay(10, -5), 0);
        assertEquals(10 * Math.exp(-0.5), AirflowSaved.decay(10, 12_000), 1e-9);
    }
}
