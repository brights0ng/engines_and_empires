package dev.brights0ng.enginesandempires.frontier.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SettledIndexTest {

    @Test
    void keysRoundTripLikeSectionPos() {
        int[][] cases = {{0, 0, 0}, {-1, -4, -1}, {1875000, 19, -1875000}, {-7, 3, 12}, {123, -64 >> 4, -456}};
        for (int[] c : cases) {
            long key = SectionKey.of(c[0], c[1], c[2]);
            assertEquals(c[0], SectionKey.x(key));
            assertEquals(c[1], SectionKey.y(key));
            assertEquals(c[2], SectionKey.z(key));
        }
        // SectionPos.asLong(1, 2, 3), worked out by hand from its bit layout.
        assertEquals((1L << 42) | 2L | (3L << 20), SectionKey.of(1, 2, 3));
    }

    @Test
    void findsSettledLandWithinTheRadiusAsACube() {
        SettledIndex index = new SettledIndex();
        index.set(SectionKey.of(0, 4, 0), true);
        assertTrue(index.anyWithin(SectionKey.of(8, 4, 8), 8), "a diagonal of 8 is within 8 as a cube");
        assertTrue(index.anyWithin(SectionKey.of(-8, -4, 3), 8));
        assertFalse(index.anyWithin(SectionKey.of(9, 4, 0), 8));
        assertFalse(index.anyWithin(SectionKey.of(0, 13, 0), 8));
        assertTrue(index.anyWithin(SectionKey.of(0, 4, 0), 0), "a section is within 0 of itself");
    }

    @Test
    void worksAcrossRegionBordersAndNegativeCoordinates() {
        SettledIndex index = new SettledIndex();
        index.set(SectionKey.of(-1, 0, -1), true); // region -1
        assertTrue(index.anyWithin(SectionKey.of(0, 0, 0), 1));
        assertTrue(index.anyWithin(SectionKey.of(7, 0, 7), 8));
        assertFalse(index.anyWithin(SectionKey.of(8, 0, 8), 8));
    }

    @Test
    void removingAndVersioning() {
        SettledIndex index = new SettledIndex();
        long a = SectionKey.of(3, 3, 3);
        long v0 = index.version();
        assertTrue(index.set(a, true));
        assertFalse(index.set(a, true), "already there");
        assertEquals(1, index.size());
        assertTrue(index.version() > v0);
        long v1 = index.version();
        assertTrue(index.set(a, false));
        assertFalse(index.set(a, false));
        assertEquals(0, index.size());
        assertTrue(index.version() > v1);
        assertFalse(index.anyWithin(a, 8));
    }
}
