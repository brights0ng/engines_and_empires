package dev.brights0ng.enginesandempires.weather.wind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WindBlendTest {

    private static final int SIZE = 2000;

    @Test
    void aRegionCentreUsesOnlyThatRegion() {
        WindBlend.Corners c = WindBlend.corners(1000, 1000, SIZE);
        assertEquals(0, c.x0());
        assertEquals(0, c.z0());
        assertEquals(0, c.tx(), 1e-9);
        assertEquals(0, c.tz(), 1e-9);
        assertEquals(7, c.blend(7, 99, 99, 99), 1e-9);
    }

    @Test
    void aRegionBorderIsHalfAndHalf() {
        WindBlend.Corners c = WindBlend.corners(2000, 1000, SIZE);
        assertEquals(0, c.x0());
        assertEquals(0.5, c.tx(), 1e-9);
        assertEquals(15, c.blend(10, 20, 0, 0), 1e-9);
    }

    @Test
    void negativeCoordinatesFindTheRightRegions() {
        // x = -500 lies in region -1 (centred at -1000), between the centres of -1 and 0.
        WindBlend.Corners c = WindBlend.corners(-500, -1000, SIZE);
        assertEquals(-1, c.x0());
        assertEquals(0.25, c.tx(), 1e-9);
        assertEquals(-1, c.z0());
        assertEquals(0, c.tz(), 1e-9);
        assertEquals(-1000, WindBlend.centre(-1, SIZE));
        assertEquals(1000, WindBlend.centre(0, SIZE));
    }

    @Test
    void nearTheGroundIsSurfaceWindAndHighUpIsAloft() {
        assertEquals(0, WindBlend.aloftShare(80, 64, 24, 192), 1e-9);
        assertEquals(1, WindBlend.aloftShare(200, 64, 24, 192), 1e-9);
        assertEquals(0.5, WindBlend.aloftShare(140, 64, 24, 192), 1e-9);
    }

    @Test
    void aMountaintopStillHasSurfaceWindAndASmoothBlend() {
        // Ground at 190: the surface layer reaches 214, above the aloft height.
        assertEquals(0, WindBlend.aloftShare(210, 190, 24, 192), 1e-9);
        double mid = WindBlend.aloftShare(222, 190, 24, 192);
        assertTrue(mid > 0 && mid < 1, "blends over at least 16 blocks: " + mid);
        assertEquals(1, WindBlend.aloftShare(230, 190, 24, 192), 1e-9);
    }

    @Test
    void gustsRampUpOverTheRampTimeAndDropAtOnce() {
        double speed = 10;
        for (int tick = 0; tick < 20; tick++) {
            speed = WindBlend.ramp(speed, 20, 20);
        }
        assertEquals(19.5, speed, 1e-6, "95% of the rise after the ramp time");
        assertTrue(WindBlend.ramp(10, 20, 20) < 12, "no jolt on the first tick");
        assertEquals(8, WindBlend.ramp(19.5, 8, 20), 1e-9);
    }

    @Test
    void yawFollowsMinecraftsConvention() {
        double[] south = WindColumn.fromYaw(10, 0);
        assertEquals(0, south[0], 1e-9);
        assertEquals(10, south[1], 1e-9);
        double[] east = WindColumn.fromYaw(10, 270);
        assertEquals(10, east[0], 1e-9);
        assertEquals(0, east[1], 1e-9);
        assertEquals(90, WindColumn.yawOf(-1, 0), 1e-9, "west");
        assertEquals(180, WindColumn.yawOf(0, -1), 1e-9, "north");
    }

    @Test
    void aColumnBlendsByHeight() {
        WindColumn column = new WindColumn(4, 0, 0, 12);
        double[] half = column.at(0.5);
        assertEquals(2, half[0], 1e-9);
        assertEquals(6, half[1], 1e-9);
    }
}
