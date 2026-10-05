package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaveModelTest {

    private static WaveModel.OreCells cells(double[]... points) {
        double[] x = new double[points.length];
        double[] y = new double[points.length];
        double[] z = new double[points.length];
        for (int i = 0; i < points.length; i++) {
            x[i] = points[i][0];
            y[i] = points[i][1];
            z[i] = points[i][2];
        }
        return new WaveModel.OreCells(x, y, z);
    }

    @Test
    void everyVibrationTravelsAtSixtyFourBlocksASecond() {
        assertEquals(64.0, WaveModel.BLOCKS_PER_SECOND, 0.0);
        assertEquals(3.2, WaveModel.BLOCKS_PER_TICK, 1.0e-12);
    }

    @Test
    void aVibrationTravelsFromTheSourceToTheOreAndOutToTheGeophone() {
        // 30 blocks from source to ore, 40 from ore to geophone: 70 blocks in all.
        WaveModel.Arrival arrival = WaveModel.arrival(cells(new double[]{0, 0, 0}), -30, 0, 0, 40, 0, 0, 100);
        assertNotNull(arrival);
        assertEquals(70.0 / 3.2, arrival.earliestTicks(), 1.0e-9);
        assertEquals(arrival.earliestTicks(), arrival.latestTicks(), 0.0, "one ore block gives one instant");
        assertEquals(0.0, arrival.spreadTicks(), 0.0);
    }

    @Test
    void anOreBlockOutOfRangeOfTheSourceReceivesNothing() {
        assertNull(WaveModel.arrival(cells(new double[]{50, 0, 0}), 0, 0, 0, 50, 0, 0, 16));
    }

    @Test
    void aGeophoneOutOfRangeOfTheOreHearsNothing() {
        // The ore is right beside the source, but the geophone is 50 blocks from it and the range is only 16.
        assertNull(WaveModel.arrival(cells(new double[]{2, 0, 0}), 0, 0, 0, 52, 0, 0, 16));
    }

    @Test
    void rangeIsExactlyInclusive() {
        assertNotNull(WaveModel.arrival(cells(new double[]{16, 0, 0}), 0, 0, 0, 16, 0, 0, 16));
        assertNull(WaveModel.arrival(cells(new double[]{16.001, 0, 0}), 0, 0, 0, 16.001, 0, 0, 16));
    }

    @Test
    void aDepositIsHeardFromItsNearestOreFirstAndItsFarthestLast() {
        // Source and geophone together; ore at 10 and at 20 blocks. The echo comes back after 20 and after 40 blocks.
        WaveModel.Arrival arrival = WaveModel.arrival(cells(new double[]{10, 0, 0}, new double[]{0, 20, 0}), 0, 0, 0, 0, 0, 0, 30);
        assertNotNull(arrival);
        assertEquals(20.0 / 3.2, arrival.earliestTicks(), 1.0e-9);
        assertEquals(40.0 / 3.2, arrival.latestTicks(), 1.0e-9);
        assertEquals(20.0 / 3.2, arrival.spreadTicks(), 1.0e-9);
    }

    @Test
    void theRangeIsAppliedToEachOreBlockOnItsOwn() {
        // A line of ore. Only the blocks within 30 of both source and geophone reflect anything.
        WaveModel.OreCells line = cells(new double[]{0, 0, 0}, new double[]{10, 0, 0}, new double[]{20, 0, 0},
                new double[]{30, 0, 0}, new double[]{40, 0, 0});
        WaveModel.Arrival arrival = WaveModel.arrival(line, 0, 0, 0, 35, 0, 0, 30);
        assertNotNull(arrival);
        // The ore at 0 is 35 from the geophone and the ore at 40 is 40 from the source: both out of range. The rest are
        // on the line between them, so they all take 35 blocks.
        assertEquals(35.0 / 3.2, arrival.earliestTicks(), 1.0e-9);
        assertEquals(35.0 / 3.2, arrival.latestTicks(), 1.0e-9);
    }

    @Test
    void aFartherGeophoneHearsLater() {
        WaveModel.OreCells ore = cells(new double[]{0, 0, 0});
        double previous = 0.0;
        for (double distance = 5.0; distance <= 60.0; distance += 5.0) {
            WaveModel.Arrival arrival = WaveModel.arrival(ore, 0, 0, 0, distance, 0, 0, 100);
            assertNotNull(arrival);
            assertTrue(arrival.earliestTicks() > previous, "geophone at " + distance);
            previous = arrival.earliestTicks();
        }
    }

    @Test
    void swappingTheSourceAndTheGeophoneGivesTheSameAnswer() {
        WaveModel.OreCells ore = cells(new double[]{3, -8, 5}, new double[]{9, 2, -4}, new double[]{-6, 7, 1});
        WaveModel.Arrival forward = WaveModel.arrival(ore, 12, 1, -3, -9, 4, 8, 40);
        WaveModel.Arrival backward = WaveModel.arrival(ore, -9, 4, 8, 12, 1, -3, 40);
        assertNotNull(forward);
        assertEquals(forward.earliestTicks(), backward.earliestTicks(), 1.0e-9);
        assertEquals(forward.latestTicks(), backward.latestTicks(), 1.0e-9);
    }

    @Test
    void depthCountsBecauseDistanceIsMeasuredInThreeDimensions() {
        WaveModel.OreCells ore = cells(new double[]{0, -40, 0});
        // Directly below the source, 40 blocks down: out of range for a 16-block hammer, in range for a 128-block plate.
        assertNull(WaveModel.arrival(ore, 0, 0, 0, 0, 0, 0, 16));
        WaveModel.Arrival arrival = WaveModel.arrival(ore, 0, 0, 0, 0, 0, 0, 128);
        assertNotNull(arrival);
        assertEquals(80.0 / 3.2, arrival.earliestTicks(), 1.0e-9);
    }

    @Test
    void aDepositWithNoOreIsNeverHeard() {
        assertNull(WaveModel.arrival(new WaveModel.OreCells(new double[0], new double[0], new double[0]), 0, 0, 0, 0, 0, 0, 100));
    }

    @Test
    void oreCellsMustAgreeInLength() {
        assertThrows(IllegalArgumentException.class, () -> new WaveModel.OreCells(new double[2], new double[3], new double[2]));
    }
}
