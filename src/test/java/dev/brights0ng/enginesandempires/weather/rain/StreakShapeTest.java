package dev.brights0ng.enginesandempires.weather.rain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Streaks spaced in the air, pivoting at eye level, bending with gusts, traced to the ground. */
class StreakShapeTest {

    private static final double PIVOT = 80;
    private static final double TOP = PIVOT + 24;

    /** A lean of {@code lean} (blocks per block, toward +x) held steady. */
    private static RainSlant steady(double lean) {
        RainSlant s = new RainSlant();
        s.step(lean * 7, 0); // a middling shower falls at 7 m/s
        return s;
    }

    @Test
    void aSteadyLeanIsStraightThroughThePivot() {
        StreakShape shape = new StreakShape(24, 24).build(steady(0.5), false, 0.6, PIVOT, TOP);
        assertTrue(shape.straight());
        assertEquals(0, shape.x(PIVOT), 1e-9, "it crosses eye level where it is spaced");
        assertEquals(5, shape.x(PIVOT - 10), 1e-9, "lower is downwind");
        assertEquals(-5, shape.x(PIVOT + 10), 1e-9, "higher is upwind");
        assertEquals(20, shape.x(PIVOT - 40), 1e-9, "below the band the lean carries on");
        assertEquals(0, shape.z(PIVOT - 10), 1e-9);
    }

    @Test
    void aGustBendsTheTopFirst() {
        RainSlant s = new RainSlant();
        RainSlant.maxChangePerSecond = 2;
        RainSlant.averageSeconds = 1;
        try {
            for (int t = 0; t < 200; t++) {
                s.step(0, 0);
            }
            for (int t = 0; t < 30; t++) {
                s.step(7, 0); // the lean swings to about 1 over a second and a half
            }
            StreakShape shape = new StreakShape(24, 24).build(s, false, 0.6, PIVOT, TOP);
            assertFalse(shape.straight(), "the new lean has only reached part way down");
            double topSlope = (shape.x(TOP - 4) - shape.x(TOP)) / 4;
            double bottomSlope = (shape.x(PIVOT - 24) - shape.x(PIVOT - 20)) / 4;
            assertTrue(topSlope > 0.5, "rain that started after the change leans: " + topSlope);
            assertEquals(0, bottomSlope, 1e-6, "rain that started before it still falls straight down");
        } finally {
            RainSlant.maxChangePerSecond = 0.1;
            RainSlant.averageSeconds = 15;
        }
    }

    @Test
    void landsOnFlatGroundDownwind() {
        StreakShape shape = new StreakShape(24, 24).build(steady(0.5), false, 0.6, PIVOT, TOP);
        double[] at = new double[2];
        double y = shape.land(10.5, 3.5, TOP, PIVOT - 64, (x, z) -> 64, at);
        assertEquals(64, y, 1e-9);
        assertEquals(10.5 + 8, at[0], 1e-9, "16 blocks below eye level at a lean of a half");
        assertEquals(3.5, at[1], 1e-9);
    }

    @Test
    void slopesGetOneStreakPerBlockOfAirWhicheverWayTheyFace() {
        StreakShape shape = new StreakShape(24, 24).build(steady(0.8), false, 0.6, PIVOT, TOP);
        // Ground rising a block per block toward +x (along the wind), around eye level.
        StreakShape.Ground slope = (x, z) -> 70 + x;
        double[] at = new double[2];
        double lastX = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 6; i++) {
            double y = shape.land(i + 0.5, 0.5, TOP, PIVOT - 64, slope, at);
            assertEquals(slope.height((int) Math.floor(at[0]), 0), y, 1.0, "it lands on the slope's face");
            assertTrue(at[0] > lastX, "each streak lands past the last");
            lastX = at[0];
        }
    }

    @Test
    void anOverhangCatchesTheRainAboveTheGround() {
        StreakShape shape = new StreakShape(24, 24).build(steady(0), false, 0.6, PIVOT, TOP);
        double[] at = new double[2];
        // A roof at 90 over x 0-4, open ground at 64 elsewhere.
        StreakShape.Ground ground = (x, z) -> x >= 0 && x <= 4 ? 90 : 64;
        assertEquals(90, shape.land(2.5, 0, TOP, PIVOT - 64, ground, at), 1e-9);
        assertEquals(64, shape.land(7.5, 0, TOP, PIVOT - 64, ground, at), 1e-9);
        assertEquals(TOP, shape.land(2.5, 0, TOP, PIVOT - 64, (x, z) -> 200, at), 1e-9,
                "buried above the band: it lands where it comes into view");
    }

    /** A jump moves the band (pivot); pinned at eye level, a leaning streak stays where it is in the world. */
    @Test
    void aPinnedStreakDoesNotSlideWhenThePivotMoves() {
        RainSlant slant = steady(0.5);
        StreakShape shape = new StreakShape(24, 24).build(slant, Precip.RAIN, 0.6, PIVOT, TOP);
        double[] before = new double[41];
        for (int j = 0; j <= 40; j++) {
            before[j] = shape.x(PIVOT - 20 + j);
        }
        double eye = PIVOT + 1.25;
        double oldX = shape.x(eye), oldZ = shape.z(eye);
        shape.build(slant, Precip.RAIN, 0.6, eye, eye + 24).pin(eye, oldX, oldZ);
        for (int j = 0; j <= 40; j++) {
            assertEquals(before[j], shape.x(PIVOT - 20 + j), 1e-9, "at " + (PIVOT - 20 + j));
        }
    }

    /** An airship climbing 200 blocks a tick at a time, then stopping: the rain never moves sideways. */
    @Test
    void aLongFastClimbLeavesTheRainWhereItIs() {
        RainSlant slant = steady(0.8);
        StreakShape shape = new StreakShape(24, 24).build(slant, Precip.RAIN, 0.6, PIVOT, TOP);
        double probe = PIVOT + 150;
        double start = shape.x(probe);
        double eye = PIVOT;
        for (int t = 0; t < 100; t++) {
            eye += 2;
            double oldX = shape.x(eye), oldZ = shape.z(eye);
            shape.build(slant, Precip.RAIN, 0.6, eye, eye + 24).pin(eye, oldX, oldZ);
            assertEquals(start, shape.x(probe), 1e-6, "tick " + t);
        }
        for (int t = 0; t < 200; t++) {
            double oldX = shape.x(eye), oldZ = shape.z(eye);
            shape.build(slant, Precip.RAIN, 0.6, eye, eye + 24).pin(eye, oldX, oldZ);
        }
        assertEquals(start, shape.x(probe), 1e-6, "nothing catches up after stopping");
    }

    /** A change of lean swings the streaks about eye level: still there, moved above and below. */
    @Test
    void aLeanChangeSwingsAboutEyeLevel() {
        StreakShape shape = new StreakShape(24, 24).build(steady(0.2), Precip.RAIN, 0.6, PIVOT, TOP);
        double eye = PIVOT + 2;
        double atEye = shape.x(eye);
        double below = shape.x(eye - 10);
        double oldX = shape.x(eye), oldZ = shape.z(eye);
        shape.build(steady(0.6), Precip.RAIN, 0.6, PIVOT, TOP).pin(eye, oldX, oldZ);
        assertEquals(atEye, shape.x(eye), 1e-9, "eye level stays put");
        assertEquals(below + 4, shape.x(eye - 10), 1e-9, "10 blocks down swings 4 further downwind");
    }
}
