// Written by ChatGPT; updated for 7 derived abilities from CivilianTraits by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Random;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;

import static org.junit.jupiter.api.Assertions.*;

class CivilianAbilitiesTest {
    @Test
    void packedRoundTripsIncludingExtremes() {
        for (String code : new String[]{"3-3-3-3-3-3-3", "18-18-18-18-18-18-18", "13-12-9-11-10-14-8"}) {
            CivilianAbilities scores = CivilianAbilities.parse(code);
            assertEquals(scores, CivilianAbilities.unpack(scores.pack()));
        }
    }

    @Test
    void unpackRejectsBitsOutside28Bits() {
        assertThrows(IllegalArgumentException.class, () -> CivilianAbilities.unpack(1 << 28));
        assertThrows(IllegalArgumentException.class, () -> CivilianAbilities.unpack(-1));
    }

    @Test
    void constructorRejectsOutOfRangeInEverySlot() {
        for (int invalid : new int[]{2, 19}) {
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(invalid, 10, 10, 10, 10, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, invalid, 10, 10, 10, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, 10, invalid, 10, 10, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, 10, 10, invalid, 10, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, 10, 10, 10, invalid, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, 10, 10, 10, 10, invalid, 10));
            assertThrows(IllegalArgumentException.class, () -> new CivilianAbilities(10, 10, 10, 10, 10, 10, invalid));
        }
    }

    @Test
    void normalizedEndpoints() {
        for (CivilianAbilities.Ability ability : CivilianAbilities.Ability.values()) {
            assertEquals(0.0, CivilianAbilities.parse("3-3-3-3-3-3-3").normalized(ability));
            assertEquals(1.0, CivilianAbilities.parse("18-18-18-18-18-18-18").normalized(ability));
        }
    }

    @Test
    void derivedScoresAreMonotonicWithTheirBaseTraits() {
        CivilianAbilities neutral = CivilianTraits.NEUTRAL.abilities();
        for (CivilianAbilities.Ability ability : CivilianAbilities.Ability.values()) {
            assertEquals(11, neutral.raw(ability));
        }

        assertTrue(CivilianTraits.NEUTRAL.with(BaseTrait.CONTRACTILE_FORCE, 100).abilities().brawn()
                > CivilianTraits.NEUTRAL.with(BaseTrait.CONTRACTILE_FORCE, -100).abilities().brawn());
        assertTrue(CivilianTraits.NEUTRAL.with(BaseTrait.AEROBIC_STAMINA, 100).abilities().endurance()
                > CivilianTraits.NEUTRAL.with(BaseTrait.AEROBIC_STAMINA, -100).abilities().endurance());
        assertTrue(CivilianTraits.NEUTRAL.with(BaseTrait.MOTOR_SENSORY, 100).abilities().precision()
                > CivilianTraits.NEUTRAL.with(BaseTrait.MOTOR_SENSORY, -100).abilities().precision());
        assertTrue(CivilianTraits.NEUTRAL.with(BaseTrait.NEUROTICISM, -100).abilities().resolve()
                > CivilianTraits.NEUTRAL.with(BaseTrait.NEUROTICISM, 100).abilities().resolve());
        assertTrue(CivilianTraits.NEUTRAL.with(BaseTrait.OPENNESS, 100).abilities().wits()
                > CivilianTraits.NEUTRAL.with(BaseTrait.OPENNESS, -100).abilities().wits());
        assertTrue(CivilianTraits.NEUTRAL.with(BaseTrait.CONSCIENTIOUSNESS, 100).abilities().organization()
                > CivilianTraits.NEUTRAL.with(BaseTrait.CONSCIENTIOUSNESS, -100).abilities().organization());
        assertTrue(CivilianTraits.NEUTRAL.with(BaseTrait.AGREEABLENESS, 100).abilities().teamwork()
                > CivilianTraits.NEUTRAL.with(BaseTrait.AGREEABLENESS, -100).abilities().teamwork());

        // Decision #4: extreme Conscientiousness stays high on displayed Organization (no inverted-U dip on readout).
        assertTrue(CivilianTraits.NEUTRAL.with(BaseTrait.CONSCIENTIOUSNESS, 100).abilities().organization()
                >= CivilianTraits.NEUTRAL.with(BaseTrait.CONSCIENTIOUSNESS, 65).abilities().organization());
    }

    @Test
    void seededRollsStayInRange() {
        RandomGenerator random = new Random(20261122L);
        for (int i = 0; i < 5_000; i++) {
            CivilianAbilities scores = CivilianAbilities.roll(random);
            assertTrue(scores.mean() >= CivilianTraits.REROLL_MIN_ABILITY_MEAN
                    && scores.mean() <= CivilianTraits.REROLL_MAX_ABILITY_MEAN);
            for (CivilianAbilities.Ability ability : CivilianAbilities.Ability.values()) {
                assertTrue(scores.raw(ability) >= 3 && scores.raw(ability) <= 18);
            }
        }
    }

    @Test
    void codeRoundTripsInEnumOrder() {
        CivilianAbilities scores = new CivilianAbilities(13, 12, 9, 11, 10, 14, 8);
        assertEquals("13-12-9-11-10-14-8", scores.code());
        assertEquals(scores, CivilianAbilities.parse(scores.code()));
        assertEquals(77 / 7.0, scores.mean());
        int[] expected = {13, 12, 9, 11, 10, 14, 8};
        for (CivilianAbilities.Ability ability : CivilianAbilities.Ability.values()) {
            assertEquals(expected[ability.ordinal()], scores.raw(ability));
            assertEquals(ability.name().toLowerCase(java.util.Locale.ROOT), ability.id());
        }
    }

    @Test
    void rejectsMalformedCodes() {
        for (String code : new String[]{
                "1-2-3", "12-9-11-10-14-8", "a-b-c-d-e-f-g", "19-3-3-3-3-3-3",
                "3-3-3-3-3-3-3-", "", "999999999999-3-3-3-3-3-3"}) {
            assertThrows(IllegalArgumentException.class, () -> CivilianAbilities.parse(code));
        }
        assertThrows(IllegalArgumentException.class, () -> CivilianAbilities.parse(null));
    }
}
