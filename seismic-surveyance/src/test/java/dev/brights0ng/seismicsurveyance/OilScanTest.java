// Written by Gemini.
// Edited by Gemini: added multi-chunk oil trap shapes, 100% fullness boundary tests, and multi-deposit hover tracking tests.
package dev.brights0ng.seismicsurveyance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.util.List;

import dev.brights0ng.sparseores.shape.oil.OilTrapType;

/** Tests for Create: Diesel Generators oil detection tiers, colors, tab layouts, and multi-chunk traps. */
class OilScanTest {

    @Test
    void oilThresholdTiers() {
        assertEquals("", OilTiers.tierForAmount(0));
        assertEquals("", OilTiers.tierForAmount(3_999_999));
        assertEquals(OilTiers.CRUDE_OIL, OilTiers.tierForAmount(4_000_000));
        assertEquals(OilTiers.CRUDE_OIL, OilTiers.tierForAmount(6_999_999));
        assertEquals(OilTiers.CRUDE_OIL_RICH, OilTiers.tierForAmount(7_000_000));
        assertEquals(OilTiers.CRUDE_OIL_RICH, OilTiers.tierForAmount(9_999_999));
        assertEquals(OilTiers.CRUDE_OIL_BOTTOMLESS, OilTiers.tierForAmount(10_000_000));
        assertEquals(OilTiers.CRUDE_OIL_BOTTOMLESS, OilTiers.tierForAmount(50_000_000));
        assertEquals(OilTiers.CRUDE_OIL_BOTTOMLESS, OilTiers.tierForAmount(Integer.MAX_VALUE));
    }

    @Test
    void oilColoursAndReadingOresDelegation() {
        assertEquals(0xFF6A4FB0, OilTiers.colour(OilTiers.CRUDE_OIL));
        assertEquals(0xFF8A6FE0, OilTiers.colour(OilTiers.CRUDE_OIL_RICH));
        assertEquals(0xFFBA9FFF, OilTiers.colour(OilTiers.CRUDE_OIL_BOTTOMLESS));

        assertEquals(0xFF6A4FB0, ReadingOres.colour(OilTiers.CRUDE_OIL));
        assertEquals(0xFF8A6FE0, ReadingOres.colour(OilTiers.CRUDE_OIL_RICH));
        assertEquals(0xFFBA9FFF, ReadingOres.colour(OilTiers.CRUDE_OIL_BOTTOMLESS));

        float[] glow = GeophoneGlow.colorFor(OilTiers.CRUDE_OIL);
        assertArrayEquals(new float[]{0x6A / 255f, 0x4F / 255f, 0xB0 / 255f}, glow, 0.01f);
    }

    @Test
    void loggerDeskTabsAreAlongTopEdge() {
        // v in [1.8375, 1.9275]
        // 4 tabs: ORES [0.1125, 0.4125], OIL [0.4375, 0.7375], FRONTIER [0.7625, 1.0625], EMPIRE [1.0875, 1.3875]
        assertEquals(LoggerDisplay.Tab.ORES, LoggerDisplay.tabAt(0.26, 1.88));
        assertEquals(LoggerDisplay.Tab.OIL, LoggerDisplay.tabAt(0.58, 1.88));
        assertEquals(LoggerDisplay.Tab.FRONTIER, LoggerDisplay.tabAt(0.91, 1.88));
        assertEquals(LoggerDisplay.Tab.EMPIRE, LoggerDisplay.tabAt(1.23, 1.88));

        // Outside tab bounds and gaps
        assertNull(LoggerDisplay.tabAt(0.425, 1.88), "gap between ores and oil");
        assertNull(LoggerDisplay.tabAt(0.75, 1.88), "gap between oil and frontier");
        assertNull(LoggerDisplay.tabAt(1.075, 1.88), "gap between frontier and empire");
        assertNull(LoggerDisplay.tabAt(0.05, 1.88), "left of tabs");
        assertNull(LoggerDisplay.tabAt(1.45, 1.88), "right of tabs");
        assertNull(LoggerDisplay.tabAt(0.75, 1.00), "middle of map");
        assertNull(LoggerDisplay.tabAt(0.60, 1.70), "below tabs");
        assertNull(LoggerDisplay.tabAt(0.60, 1.95), "above tabs");
    }

    @Test
    void dieselGeneratorsCompatConstant() {
        assertEquals("createdieselgenerators", dev.brights0ng.seismicsurveyance.compat.dieselgenerators.DieselGeneratorsCompat.MODID);
    }

    @Test
    void oilChunksAreHoveredByTheirEntireChunkSquare() {
        LoggerDisplay.View view = new LoggerDisplay.View(56, 72, 6);
        LoggerDisplay.Frame south = new LoggerDisplay.Frame(1, 0, 0, 1);
        long chunkDeposit = ((long) 3 & 0xFFFFFFFFL) | (((long) 4 & 0xFFFFFFFFL) << 32);
        ReaderReading oilReading = new ReaderReading("minecraft:overworld", 56, -60, 72, true, chunkDeposit, 100,
                ReaderAccuracy.Confidence.PRECISE, OilTiers.CRUDE_OIL);
        List<ReaderReading> readings = List.of(oilReading);

        // Chunk (3, 4) spans world blocks [48..63, 64..79]
        // Hover at (50, 66) inside chunk (3, 4)
        double[] inside = LoggerDisplay.toScreen(view, south, 50, 66);
        assertEquals(0, LoggerDisplay.hovered(view, south, inside[0], inside[1], readings, "minecraft:overworld"));

        // Hover at corner (48, 64) inside chunk (3, 4)
        double[] corner = LoggerDisplay.toScreen(view, south, 48.5, 64.5);
        assertEquals(0, LoggerDisplay.hovered(view, south, corner[0], corner[1], readings, "minecraft:overworld"));

        // Hover at (47, 64) in chunk (2, 4) outside chunk (3, 4)
        double[] outside = LoggerDisplay.toScreen(view, south, 47, 64);
        assertEquals(-1, LoggerDisplay.hovered(view, south, outside[0], outside[1], readings, "minecraft:overworld"));

        // PRD display hovering over chunk
        double[] prdInside = PrdDisplay.toScreen(view, 0, 52, 68);
        assertEquals(0, PrdDisplay.hovered(view, 0, prdInside[0], prdInside[1], readings, "minecraft:overworld"));

        double[] prdOutside = PrdDisplay.toScreen(view, 0, 70, 85);
        assertEquals(-1, PrdDisplay.hovered(view, 0, prdOutside[0], prdOutside[1], readings, "minecraft:overworld"));
    }

    @Test
    void multiChunkOilTrapReadingAndHover() {
        String oreId = "crude_oil:anticline:45:85";
        assertTrue(OilTiers.isOil(oreId));
        assertEquals(OilTrapType.ANTICLINE, OilTiers.trapOf(oreId));
        assertEquals(Math.toRadians(45), OilTiers.strikeAngleOf(oreId), 0.001);
        assertEquals(0.85, OilTiers.fullnessOf(oreId), 0.001);
        assertEquals("Anticlinal Fold (85%)", OilTiers.displayName(oreId));

        long seed = 987654321L;
        ReaderReading reading = new ReaderReading("minecraft:overworld", 100, -60, 200, true, seed, 500,
                ReaderAccuracy.Confidence.PRECISE, oreId);

        // Check polygon generation
        double[][] poly = OilTiers.getPolygon(reading, 32);
        assertNotNull(poly);
        assertEquals(32, poly.length);

        // Center should be contained
        assertTrue(OilTiers.contains(reading, 100, 200));

        // Hover test on LoggerDisplay
        LoggerDisplay.View view = new LoggerDisplay.View(100, 200, 4);
        LoggerDisplay.Frame south = new LoggerDisplay.Frame(1, 0, 0, 1);
        double[] screenCenter = LoggerDisplay.toScreen(view, south, 100, 200);
        assertEquals(0, LoggerDisplay.hovered(view, south, screenCenter[0], screenCenter[1], List.of(reading), "minecraft:overworld"));

        // Way outside deposit reach
        double[] screenFar = LoggerDisplay.toScreen(view, south, 1000, 2000);
        assertEquals(-1, LoggerDisplay.hovered(view, south, screenFar[0], screenFar[1], List.of(reading), "minecraft:overworld"));
    }

    @Test
    void fullReservoirDoesNotContainPointsOutsideBoundary() {
        // At 100% fullness (fullness = 1.0, threshold = 0.0), points outside the reservoir must return false
        String oreId100 = "crude_oil:anticline:0:100";
        ReaderReading reading100 = new ReaderReading("minecraft:overworld", 100, -60, 200, true, 12345L, 500,
                ReaderAccuracy.Confidence.PRECISE, oreId100);

        assertTrue(OilTiers.contains(reading100, 100, 200), "Deposit center must be contained at 100% fullness");
        assertFalse(OilTiers.contains(reading100, 100, 500), "Distant point 300 blocks away must NOT be contained");
        assertFalse(OilTiers.contains(reading100, 2000, 3000), "Very distant point must NOT be contained");
    }

    @Test
    void multiDepositHoverCorrectlyTracksTargetDeposit() {
        String oreIdA = "crude_oil:anticline:0:100";
        ReaderReading readingA = new ReaderReading("minecraft:overworld", 200, -60, 200, true, 11111L, 500,
                ReaderAccuracy.Confidence.PRECISE, oreIdA);

        String oreIdB = "crude_oil:mound:0:100";
        ReaderReading readingB = new ReaderReading("minecraft:overworld", 400, -60, 400, true, 22222L, 500,
                ReaderAccuracy.Confidence.PRECISE, oreIdB);

        List<ReaderReading> readings = List.of(readingA, readingB);
        LoggerDisplay.View view = new LoggerDisplay.View(300, 300, 2);
        LoggerDisplay.Frame south = new LoggerDisplay.Frame(1, 0, 0, 1);

        // Hover over Deposit A
        double[] screenA = LoggerDisplay.toScreen(view, south, 200, 200);
        assertEquals(0, LoggerDisplay.hovered(view, south, screenA[0], screenA[1], readings, "minecraft:overworld"),
                "Should hover deposit A (index 0)");

        // Hover over Deposit B
        double[] screenB = LoggerDisplay.toScreen(view, south, 400, 400);
        assertEquals(1, LoggerDisplay.hovered(view, south, screenB[0], screenB[1], readings, "minecraft:overworld"),
                "Should hover deposit B (index 1), not stay stuck on deposit A");

        // Hover in empty space between them
        double[] screenMiddle = LoggerDisplay.toScreen(view, south, 300, 300);
        assertEquals(-1, LoggerDisplay.hovered(view, south, screenMiddle[0], screenMiddle[1], readings, "minecraft:overworld"),
                "Should hover nothing when pointing at empty ground");
    }
}
