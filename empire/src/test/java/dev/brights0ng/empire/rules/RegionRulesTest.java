// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegionRulesTest {

    @Test
    void blockKeysRoundTripPositiveNegativeAndExtremes() {
        int[][] coords = {
                {0, 0, 0},
                {1, 2, 3},
                {-1, -1, -1},
                {-12345, -64, 67890},
                {33_554_431, 2047, 33_554_431},
                {-33_554_432, -2048, -33_554_432}
        };
        for (int[] c : coords) {
            long packed = BlockKeys.pack(c[0], c[1], c[2]);
            assertEquals(c[0], BlockKeys.getX(packed), "X mismatch for " + c[0]);
            assertEquals(c[1], BlockKeys.getY(packed), "Y mismatch for " + c[1]);
            assertEquals(c[2], BlockKeys.getZ(packed), "Z mismatch for " + c[2]);
        }
    }

    @Test
    void blockKeysChunkKeyUsesFloorDivisionForNegatives() {
        long pos = BlockKeys.pack(16, 64, 31);
        assertEquals(ChunkKeys.pack(1, 1), BlockKeys.chunkKey(pos));

        long neg = BlockKeys.pack(-1, -64, -17);
        assertEquals(ChunkKeys.pack(-1, -2), BlockKeys.chunkKey(neg));
    }

    @Test
    void enumIdRoundTripsAndFallbacks() {
        for (RegionType type : RegionType.values()) {
            assertEquals(type, RegionType.byId(type.id()));
        }
        assertNull(RegionType.byId("unknown"));
        assertNull(RegionType.byId(null));

        for (RegionOrigin origin : RegionOrigin.values()) {
            assertEquals(origin, RegionOrigin.byId(origin.id()));
        }
        assertNull(RegionOrigin.byId("unknown"));
        assertNull(RegionOrigin.byId(null));

        for (RegionInvalidReason reason : RegionInvalidReason.values()) {
            assertEquals(reason, RegionInvalidReason.byId(reason.id()));
        }
        assertEquals(RegionInvalidReason.NONE, RegionInvalidReason.byId("unknown"));
        assertEquals(RegionInvalidReason.NONE, RegionInvalidReason.byId(null));
    }

    @Test
    void homeCapacityMatchesThresholdsAndCap() {
        assertEquals(0, RegionRules.homeCapacity(-10));
        assertEquals(0, RegionRules.homeCapacity(0));
        assertEquals(0, RegionRules.homeCapacity(49));

        assertEquals(1, RegionRules.homeCapacity(50));
        assertEquals(1, RegionRules.homeCapacity(74));

        assertEquals(2, RegionRules.homeCapacity(75));
        assertEquals(2, RegionRules.homeCapacity(99));

        assertEquals(3, RegionRules.homeCapacity(100));
        assertEquals(3, RegionRules.homeCapacity(124));

        assertEquals(4, RegionRules.homeCapacity(125));
        assertEquals(4, RegionRules.homeCapacity(500));
        assertEquals(4, RegionRules.homeCapacity(1000));
    }

    @Test
    void shouldAutoDeleteRespectsOriginValidityAndThreeDayExpiry() {
        long start = 10_000L;
        long exactExpiry = start + RegionRules.INVALID_AUTO_DELETE_TICKS;

        // Valid regions are never deleted.
        assertFalse(RegionRules.shouldAutoDelete(RegionOrigin.AUTO, true, start, exactExpiry + 1000L));
        assertFalse(RegionRules.shouldAutoDelete(RegionOrigin.MANUAL, true, start, exactExpiry + 1000L));

        // MANUAL invalid regions are never auto-deleted.
        assertFalse(RegionRules.shouldAutoDelete(RegionOrigin.MANUAL, false, start, exactExpiry));
        assertFalse(RegionRules.shouldAutoDelete(RegionOrigin.MANUAL, false, start, exactExpiry + 1_000_000L));

        // AUTO invalid regions delete at exact 3-day boundary and after, not before.
        assertFalse(RegionRules.shouldAutoDelete(RegionOrigin.AUTO, false, start, exactExpiry - 1L));
        assertTrue(RegionRules.shouldAutoDelete(RegionOrigin.AUTO, false, start, exactExpiry));
        assertTrue(RegionRules.shouldAutoDelete(RegionOrigin.AUTO, false, start, exactExpiry + 500L));

        // Uninitialized timestamp never triggers deletion.
        assertFalse(RegionRules.shouldAutoDelete(RegionOrigin.AUTO, false, -1L, exactExpiry));
    }
}
