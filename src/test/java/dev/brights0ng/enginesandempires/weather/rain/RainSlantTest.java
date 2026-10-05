package dev.brights0ng.enginesandempires.weather.rain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The streaks' lean: steady through gusts, slow to change, and bends only rain that starts falling after a change. */
class RainSlantTest {

    /** A middling shower falls at 7 m/s, so 7 m/s of wind leans it one block per block. */
    private static final double SHOWER_FALL = 7;

    @AfterEach
    void defaults() {
        RainSlant.averageSeconds = 15;
        RainSlant.maxChangePerSecond = 0.1;
    }

    private static double leanX(RainSlant s, boolean snow, double strength, double ticksAgo) {
        double[] out = new double[2];
        s.at(snow, strength, ticksAgo, out);
        return out[0];
    }

    @Test
    void startsAtTheWindNowWithoutSwinging() {
        RainSlant s = new RainSlant();
        s.step(SHOWER_FALL / 2, 0);
        assertEquals(0.5, leanX(s, false, 0.6, 0), 1e-9);
    }

    @Test
    void aGustBarelyMovesIt() {
        RainSlant s = new RainSlant();
        for (int t = 0; t < 600; t++) {
            s.step(3.5, 0);
        }
        double before = leanX(s, false, 0.6, 0);
        for (int t = 0; t < 40; t++) {
            s.step(6.5, 0); // a 2 s gust to nearly twice the wind (PA's gusts swing about this much)
        }
        for (int t = 0; t < 20; t++) {
            s.step(3.5, 0);
        }
        assertTrue(leanX(s, false, 0.6, 0) - before < 0.06,
                "a two-second gust leans the streaks by under 0.06: " + (leanX(s, false, 0.6, 0) - before));
    }

    @Test
    void changesNoFasterThanTheCap() {
        RainSlant s = new RainSlant();
        RainSlant.averageSeconds = 1;
        s.step(0, 0);
        double last = 0;
        for (int t = 1; t <= 200; t++) {
            s.step(SHOWER_FALL, 0); // a sudden steady wind
            double now = leanX(s, false, 0.6, 0);
            assertTrue(now - last <= 0.1 / 20 + 1e-9, "tick " + t + " changed by " + (now - last));
            last = now;
        }
        assertEquals(0.996, last, 0.01, "and it gets there");
    }

    @Test
    void rainThatStartedFallingEarlierKeepsItsLean() {
        RainSlant s = new RainSlant();
        for (int t = 0; t < 400; t++) {
            s.step(0, 0);
        }
        for (int t = 0; t < 100; t++) {
            s.step(SHOWER_FALL, 0);
        }
        assertTrue(leanX(s, false, 0.6, 0) > 0.2, "new rain leans with the new wind");
        assertEquals(0, leanX(s, false, 0.6, 150), 1e-9, "rain from before the change doesn't");
        assertEquals(0, leanX(s, false, 0.6, 1e9), 1e-9, "further back than kept gives the oldest");
    }

    @Test
    void harderRainLeansLessAndSnowMore() {
        RainSlant s = new RainSlant();
        s.step(SHOWER_FALL / 2, 0);
        double shower = leanX(s, false, 0.6, 0);
        assertTrue(leanX(s, false, 1.0, 0) < shower, "heavy rain falls faster, so leans less");
        assertTrue(leanX(s, false, 0.1, 0) > shower, "drizzle leans more");
        assertEquals(RainSlant.MAX_SNOW, leanX(s, true, 0, 0), 1e-9, "snow leans furthest, up to its cap");
        assertFalse(leanX(s, false, 0, 0) > RainSlant.MAX_RAIN, "rain is capped too");
    }
}
