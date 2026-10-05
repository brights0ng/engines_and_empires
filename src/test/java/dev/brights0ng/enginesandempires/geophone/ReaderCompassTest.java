package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.geophone.ReaderCompass.HeightBand;

class ReaderCompassTest {

    /** Game yaw: 0 faces south (+z), 90 faces west (-x), 180 faces north (-z), -90 faces east (+x). */
    private static double needle(double yaw, double toX, double toZ) {
        return ReaderCompass.needleFraction(yaw, 0, 0, toX, toZ);
    }

    @Test
    void theNeedlePointsUpWhenTheTargetIsDeadAhead() {
        assertEquals(0.0, needle(0, 0, 10), 1.0e-9, "facing south, target south");
        assertEquals(0.0, needle(90, -10, 0), 1.0e-9, "facing west, target west");
        assertEquals(0.0, needle(180, 0, -10), 1.0e-9, "facing north, target north");
        assertEquals(0.0, needle(-90, 10, 0), 1.0e-9, "facing east, target east");
    }

    @Test
    void theNeedleTurnsRightForATargetOnYourRightAndLeftForOneOnYourLeft() {
        // Facing south, your right hand is west and your left hand is east.
        assertEquals(0.25, needle(0, -10, 0), 1.0e-9, "target to the right");
        assertEquals(0.75, needle(0, 10, 0), 1.0e-9, "target to the left");
        assertEquals(0.5, needle(0, 0, -10), 1.0e-9, "target behind");
    }

    @Test
    void turningToTheRightMakesTheNeedleTurnToTheLeftByTheSameAmount() {
        // Target to the south. Facing south it is ahead; turn to face west (yaw 90) and it is now on your left.
        assertEquals(0.0, needle(0, 0, 10), 1.0e-9);
        assertEquals(0.75, needle(90, 0, 10), 1.0e-9);
        assertEquals(0.5, needle(180, 0, 10), 1.0e-9);
        assertEquals(0.25, needle(-90, 0, 10), 1.0e-9);
    }

    @Test
    void theNeedleIsMeasuredFromTheHolderAndNotTheOrigin() {
        assertEquals(needle(37, 5, -8), ReaderCompass.needleFraction(37, 100, 200, 105, 192), 1.0e-9);
    }

    @Test
    void theNeedleIsAlwaysAFractionOfATurn() {
        for (double yaw = -720; yaw <= 720; yaw += 13.7) {
            for (int dx = -20; dx <= 20; dx += 7) {
                for (int dz = -20; dz <= 20; dz += 9) {
                    double fraction = needle(yaw, dx, dz);
                    assertTrue(fraction >= 0.0 && fraction < 1.0, "yaw " + yaw + " to " + dx + ", " + dz + " gave " + fraction);
                }
            }
        }
    }

    @Test
    void theNearestOfTheThirtyTwoPicturesIsChosen() {
        assertEquals(0, ReaderCompass.frameFor(0.0));
        assertEquals(8, ReaderCompass.frameFor(0.25));
        assertEquals(16, ReaderCompass.frameFor(0.5));
        assertEquals(24, ReaderCompass.frameFor(0.75));
        assertEquals(0, ReaderCompass.frameFor(0.999), "just short of a full turn is nearly straight up again");
        assertEquals(0, ReaderCompass.frameFor(0.015));
        assertEquals(1, ReaderCompass.frameFor(0.016));
    }

    /** The item model switches pictures at these fractions, so they must be exactly where the nearest picture changes. */
    @Test
    void theModelsThresholdsAreExactlyWhereThePicturesChange() {
        assertEquals(0.0, ReaderCompass.frameThreshold(0), 0.0);
        for (int frame = 1; frame < ReaderCompass.FRAMES; frame++) {
            double threshold = ReaderCompass.frameThreshold(frame);
            assertEquals(frame, ReaderCompass.frameFor(threshold), "at the threshold, frame " + frame);
            assertEquals(frame - 1, ReaderCompass.frameFor(threshold - 1.0e-9), "just before it, the frame before");
        }
        double wrap = ReaderCompass.wrapThreshold();
        assertEquals(0, ReaderCompass.frameFor(wrap), "past the last threshold it is nearer the first picture again");
        assertEquals(ReaderCompass.FRAMES - 1, ReaderCompass.frameFor(wrap - 1.0e-9));
    }

    @Test
    void theHeightArrowSaysWhereTheReadingIsComparedWithYou() {
        assertEquals(HeightBand.UP, ReaderCompass.heightBand(20, true));
        assertEquals(HeightBand.DOWN, ReaderCompass.heightBand(-45, true));
        assertEquals(HeightBand.LEVEL, ReaderCompass.heightBand(0, true));
        assertEquals(HeightBand.LEVEL, ReaderCompass.heightBand(ReaderCompass.LEVEL_TOLERANCE, true), "exactly the tolerance is still level");
        assertEquals(HeightBand.LEVEL, ReaderCompass.heightBand(-ReaderCompass.LEVEL_TOLERANCE, true));
        assertEquals(HeightBand.UP, ReaderCompass.heightBand(ReaderCompass.LEVEL_TOLERANCE + 0.01, true));
        assertEquals(HeightBand.DOWN, ReaderCompass.heightBand(-ReaderCompass.LEVEL_TOLERANCE - 0.01, true));
    }

    @Test
    void withoutTheHeightThereIsNoArrowWhateverTheDifference() {
        assertEquals(HeightBand.NONE, ReaderCompass.heightBand(100, false));
        assertEquals(HeightBand.NONE, ReaderCompass.heightBand(-100, false));
        assertEquals(HeightBand.NONE, ReaderCompass.heightBand(0, false));
    }

    /**
     * The item model picks a height picture by asking whether the height number is at least a threshold, and the last
     * picture that fits wins. That only works if the four numbers go up in the order the models are listed in.
     */
    @Test
    void theHeightNumbersAreInTheOrderTheModelsAreListedIn() {
        assertTrue(HeightBand.NONE.value() < HeightBand.UP.value());
        assertTrue(HeightBand.UP.value() < HeightBand.LEVEL.value());
        assertTrue(HeightBand.LEVEL.value() < HeightBand.DOWN.value());
        assertEquals(0.0F, HeightBand.NONE.value());
        assertTrue(HeightBand.DOWN.value() <= 1.0F, "an item property is clamped to at most 1");
    }
}
