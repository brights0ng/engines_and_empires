// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class CivilianAppearanceTest {

    private static final CivilianAppearance ALL_ONES = new CivilianAppearance(1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
    private static final CivilianAppearance ALL_FIVES = new CivilianAppearance(5, 5, 5, 5, 5, 5, 5, 5, 5, 5);

    @Test
    void packUnpackRoundTripsTheExtremes() {
        assertEquals(0, ALL_ONES.pack());
        assertEquals(ALL_ONES, CivilianAppearance.unpack(ALL_ONES.pack()));
        assertEquals(ALL_FIVES, CivilianAppearance.unpack(ALL_FIVES.pack()));
    }

    @Test
    void packUnpackRoundTripsRandomLooks() {
        SplittableRandom random = new SplittableRandom(42);
        for (int i = 0; i < 10_000; i++) {
            CivilianAppearance look = CivilianAppearance.random(random);
            assertEquals(look, CivilianAppearance.unpack(look.pack()));
        }
    }

    @Test
    void packPutsEachSlotInItsOwnThreeBits() {
        CivilianAppearance look = new CivilianAppearance(2, 1, 1, 1, 1, 1, 1, 1, 1, 5);
        assertEquals(1 | (4 << 27), look.pack());
    }

    @Test
    void codeAndParseRoundTrip() {
        CivilianAppearance look = new CivilianAppearance(3, 2, 4, 5, 1, 1, 2, 3, 5, 4);
        assertEquals("3245112354", look.code());
        assertEquals(look, CivilianAppearance.parse("3245112354"));
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 1_000; i++) {
            CivilianAppearance r = CivilianAppearance.random(random);
            assertEquals(r, CivilianAppearance.parse(r.code()));
        }
    }

    @Test
    void parseRejectsBadCodes() {
        for (String bad : new String[]{"", "123", "0111111111", "6111111111", "11111a1111"}) {
            assertThrows(IllegalArgumentException.class, () -> CivilianAppearance.parse(bad), bad);
        }
    }

    @Test
    void randomStaysInOneToFive() {
        SplittableRandom random = new SplittableRandom(99);
        for (int i = 0; i < 10_000; i++) {
            CivilianAppearance look = CivilianAppearance.random(random);
            for (CivilianAppearance.Category c : CivilianAppearance.Category.values()) {
                assertTrue(look.style(c) >= 1 && look.style(c) <= 5);
                assertTrue(look.color(c) >= 1 && look.color(c) <= 5);
            }
        }
    }

    @Test
    void styleAndColorReadTheirCategory() {
        CivilianAppearance look = CivilianAppearance.parse("1234512345");
        assertEquals(1, look.style(CivilianAppearance.Category.SKIN));
        assertEquals(2, look.color(CivilianAppearance.Category.SKIN));
        assertEquals(3, look.style(CivilianAppearance.Category.HAIR));
        assertEquals(5, look.style(CivilianAppearance.Category.SHIRT));
        assertEquals(5, look.color(CivilianAppearance.Category.SHOES));
        assertEquals("shoes", CivilianAppearance.Category.SHOES.id());
    }
}
