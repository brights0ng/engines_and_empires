package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.geophone.ReadingSummary.Compass8;

class ReadingSummaryTest {

    private static final String OVERWORLD = "minecraft:overworld";

    /** A reading of a block, and a player standing exactly at the middle of another block, so the offsets are whole numbers. */
    private static ReadingSummary from(int readingX, int readingY, int readingZ, boolean hasHeight, int playerX, int playerY, int playerZ) {
        return ReadingSummary.of(new ReaderReading(OVERWORLD, readingX, readingY, readingZ, hasHeight), OVERWORLD,
                playerX + 0.5, playerY + 0.5, playerZ + 0.5);
    }

    @Test
    void theEightCompassPointsAreWhereTheyShouldBe() {
        // North is towards -z, east towards +x, as in the game.
        assertEquals(Compass8.N, from(0, 0, -40, true, 0, 0, 0).direction());
        assertEquals(Compass8.NE, from(40, 0, -40, true, 0, 0, 0).direction());
        assertEquals(Compass8.E, from(40, 0, 0, true, 0, 0, 0).direction());
        assertEquals(Compass8.SE, from(40, 0, 40, true, 0, 0, 0).direction());
        assertEquals(Compass8.S, from(0, 0, 40, true, 0, 0, 0).direction());
        assertEquals(Compass8.SW, from(-40, 0, 40, true, 0, 0, 0).direction());
        assertEquals(Compass8.W, from(-40, 0, 0, true, 0, 0, 0).direction());
        assertEquals(Compass8.NW, from(-40, 0, -40, true, 0, 0, 0).direction());
    }

    @Test
    void aPointBetweenTwoCompassPointsGoesToTheNearerOne() {
        assertEquals(Compass8.N, from(10, 0, -40, true, 0, 0, 0).direction(), "14 degrees east of north is still north");
        assertEquals(Compass8.NE, from(25, 0, -40, true, 0, 0, 0).direction(), "32 degrees east of north is nearer north-east");
        assertEquals(Compass8.E, from(40, 0, -10, true, 0, 0, 0).direction());
    }

    @Test
    void theDistanceIsAcrossTheGroundAndRounded() {
        assertEquals(50, from(30, 0, 40, true, 0, 0, 0).distance(), "a 3-4-5 triangle, ten times over");
        assertEquals(50, from(30, 500, 40, true, 0, 0, 0).distance(), "how high it is does not change how far across the ground");
        assertEquals(0, from(0, 0, 0, true, 0, 0, 0).distance());
        // 12 and 5 make 13.
        assertEquals(13, from(12, 0, 5, true, 0, 0, 0).distance());
    }

    @Test
    void aReadingRightOnTopOfYouIsHereWithNoDirection() {
        assertEquals(Compass8.HERE, from(0, 0, 0, true, 0, 0, 0).direction());
        assertEquals(Compass8.HERE, from(1, 0, 0, true, 0, 0, 0).direction(), "one block away is still here");
        assertTrue(from(2, 0, 0, true, 0, 0, 0).direction() != Compass8.HERE, "two blocks away has a direction");
    }

    @Test
    void theHeightSaysAboveLevelOrBelowWithHowFar() {
        ReadingSummary above = from(30, 80, 40, true, 0, 60, 0);
        assertEquals(ReaderCompass.HeightBand.UP, above.height());
        assertEquals(20, above.heightDifference());

        ReadingSummary below = from(30, -40, 40, true, 0, 60, 0);
        assertEquals(ReaderCompass.HeightBand.DOWN, below.height());
        assertEquals(-100, below.heightDifference());

        ReadingSummary level = from(30, 61, 40, true, 0, 60, 0);
        assertEquals(ReaderCompass.HeightBand.LEVEL, level.height());
        assertEquals(1, level.heightDifference());
    }

    @Test
    void withoutTheHeightItSaysNothingAboutHeight() {
        assertEquals(ReaderCompass.HeightBand.NONE, from(30, -40, 40, false, 0, 60, 0).height(), "the reader did not know it, so neither does the logbook");
    }

    @Test
    void aReadingInAnotherDimensionOnlySaysWhichOne() {
        ReaderReading nether = new ReaderReading("minecraft:the_nether", 100, 50, 100, true);
        ReadingSummary summary = ReadingSummary.of(nether, OVERWORLD, 0, 64, 0);
        assertFalse(summary.sameDimension());
        assertEquals("the Nether", summary.dimensionName());
        assertEquals(0, summary.distance());
        assertEquals(ReaderCompass.HeightBand.NONE, summary.height());
    }

    @Test
    void theDimensionsHaveReadableNames() {
        assertEquals("the Overworld", ReadingSummary.dimensionName("minecraft:overworld"));
        assertEquals("the Nether", ReadingSummary.dimensionName("minecraft:the_nether"));
        assertEquals("the End", ReadingSummary.dimensionName("minecraft:the_end"));
        assertEquals("deep dark", ReadingSummary.dimensionName("somemod:deep_dark"), "any other is its plain name");
    }

    @Test
    void theSameDimensionIsRecognised() {
        assertTrue(from(1, 1, 1, true, 0, 0, 0).sameDimension());
    }
}
