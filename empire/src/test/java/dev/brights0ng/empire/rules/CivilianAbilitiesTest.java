// Written by ChatGPT.
package dev.brights0ng.empire.rules;

import java.util.Random;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CivilianAbilitiesTest {
    @Test
    void packedRoundTripsIncludingExtremes() {
        for (String code : new String[]{"3-3-3-3-3-3", "18-18-18-18-18-18", "12-9-11-10-14-8"}) {
            CivilianAbilities scores = CivilianAbilities.parse(code);
            assertEquals(scores, CivilianAbilities.unpack(scores.pack()));
        }
    }

    @Test
    void unpackRejectsBitsOutside24Bits() {
        assertThrows(IllegalArgumentException.class, () -> CivilianAbilities.unpack(1 << 24));
        assertThrows(IllegalArgumentException.class, () -> CivilianAbilities.unpack(-1));
    }

    @Test
    void constructorRejectsOutOfRangeInEverySlot() {
        for (int invalid : new int[]{2, 19}) {
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(invalid, 10, 10, 10, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, invalid, 10, 10, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, 10, invalid, 10, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, 10, 10, invalid, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, 10, 10, 10, invalid, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, 10, 10, 10, 10, invalid));
        }
    }

    @Test
    void normalizedEndpoints() {
        for (CivilianAbilities.Ability ability : CivilianAbilities.Ability.values()) {
            assertEquals(0.0, CivilianAbilities.parse("3-3-3-3-3-3").normalized(ability));
            assertEquals(1.0, CivilianAbilities.parse("18-18-18-18-18-18").normalized(ability));
        }
    }

    @Test
    void seededRollsStayInRange() {
        RandomGenerator random = new Random(20261026);
        for (int i = 0; i < 10_000; i++) {
            CivilianAbilities scores = CivilianAbilities.roll(random);
            assertTrue(scores.mean() >= 8.0 && scores.mean() <= 13.0);
            for (CivilianAbilities.Ability ability : CivilianAbilities.Ability.values()) {
                assertTrue(scores.raw(ability) >= 3 && scores.raw(ability) <= 18);
            }
        }
    }

    @Test
    void rerollsWholeRejectedSet() {
        RandomGenerator random = new RandomGenerator() {
            private int calls;

            @Override
            public long nextLong() {
                throw new AssertionError("roll must request dice with nextInt(6)");
            }

            @Override
            public int nextInt(int bound) {
                assertEquals(6, bound);
                return calls++ < 18 ? 0 : 2;
            }
        };
        assertEquals("9-9-9-9-9-9", CivilianAbilities.roll(random).code());
    }

    @Test
    void rerollCapKeepsLastSet() {
        int[] calls = {0};
        RandomGenerator random = new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new AssertionError("unexpected nextLong");
            }

            @Override
            public int nextInt(int bound) {
                calls[0]++;
                return 5;
            }
        };
        assertEquals("18-18-18-18-18-18", CivilianAbilities.roll(random).code());
        assertEquals(18 * (CivilianAbilities.MAX_REROLLS + 1), calls[0]);
    }

    @Test
    void codeRoundTripsInEnumOrder() {
        CivilianAbilities scores = new CivilianAbilities(12, 9, 11, 10, 14, 8);
        assertEquals("12-9-11-10-14-8", scores.code());
        assertEquals(scores, CivilianAbilities.parse(scores.code()));
        assertEquals(64 / 6.0, scores.mean());
        int[] expected = {12, 9, 11, 10, 14, 8};
        for (CivilianAbilities.Ability ability : CivilianAbilities.Ability.values()) {
            assertEquals(expected[ability.ordinal()], scores.raw(ability));
            assertEquals(ability.name().toLowerCase(java.util.Locale.ROOT), ability.id());
        }
    }

    @Test
    void rejectsMalformedCodes() {
        for (String code : new String[]{"1-2-3", "a-b-c-d-e-f", "19-3-3-3-3-3", "3-3-3-3-3-3-", "", "999999999999-3-3-3-3-3"}) {
            assertThrows(IllegalArgumentException.class, () -> CivilianAbilities.parse(code));
        }
        assertThrows(IllegalArgumentException.class, () -> CivilianAbilities.parse(null));
    }
}
