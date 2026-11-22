// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;

class CivilianTraitsTest {

    @Test
    void neutralHasZeroesAndNoQuirks() {
        for (BaseTrait trait : BaseTrait.values()) {
            assertEquals(0, CivilianTraits.NEUTRAL.raw(trait), trait.id());
            assertEquals(0.0, CivilianTraits.NEUTRAL.norm(trait), 1e-9, trait.id());
        }
        assertEquals(0, CivilianTraits.NEUTRAL.quirkCount());
        assertTrue(CivilianTraits.NEUTRAL.quirks().isEmpty());
        assertEquals("0,0,0,0,0,0,0,0,0,0,0", CivilianTraits.NEUTRAL.code());
        assertEquals(CivilianTraits.NEUTRAL, CivilianTraits.parse(CivilianTraits.NEUTRAL.code()));
    }

    @Test
    void validatesBoundsAndLength() {
        assertThrows(IllegalArgumentException.class, () -> CivilianTraits.of(new int[10]));
        assertThrows(IllegalArgumentException.class, () -> CivilianTraits.of(new int[12]));
        for (BaseTrait trait : BaseTrait.values()) {
            assertThrows(IllegalArgumentException.class, () -> CivilianTraits.NEUTRAL.with(trait, -101));
            assertThrows(IllegalArgumentException.class, () -> CivilianTraits.NEUTRAL.with(trait, 101));
        }
        for (String bad : new String[]{"", "0,0,0", "101,0,0,0,0,0,0,0,0,0,0", "a,0,0,0,0,0,0,0,0,0,0", null}) {
            assertThrows(IllegalArgumentException.class, () -> CivilianTraits.parse(bad));
        }
    }

    @Test
    void codeRoundTripsSignedValuesAndWithReturnsCopy() {
        CivilianTraits traits = CivilianTraits.of(-100, -60, -1, 0, 1, 60, 100, -42, 77, -18, 55);
        assertEquals("-100,-60,-1,0,1,60,100,-42,77,-18,55", traits.code());
        assertEquals(traits, CivilianTraits.parse(traits.code()));

        CivilianTraits modified = traits.with(BaseTrait.OPENNESS, 25);
        assertEquals(-100, traits.raw(BaseTrait.OPENNESS));
        assertEquals(25, modified.raw(BaseTrait.OPENNESS));
        assertNotEquals(traits, modified);
    }

    @Test
    void baseTraitByIdMatchesFullIdAndShortLabel() {
        for (BaseTrait trait : BaseTrait.values()) {
            assertEquals(trait, BaseTrait.byId(trait.id()));
            assertEquals(trait, BaseTrait.byId(trait.shortLabel()));
            assertEquals(trait, BaseTrait.byId(trait.shortLabel().toLowerCase(java.util.Locale.ROOT)));
        }
        assertNull(BaseTrait.byId(null));
        assertNull(BaseTrait.byId("nonexistent"));
    }

    @Test
    void everyBaseTraitPoleMapsToItsExactQuirkAndSortsByMagnitude() {
        assertEquals(22, CivilianQuirk.values().length);
        for (BaseTrait trait : BaseTrait.values()) {
            // Just below threshold emits nothing.
            assertTrue(CivilianTraits.NEUTRAL.with(trait, CivilianTraits.QUIRK_THRESHOLD - 1).quirks().isEmpty());
            assertTrue(CivilianTraits.NEUTRAL.with(trait, -(CivilianTraits.QUIRK_THRESHOLD - 1)).quirks().isEmpty());

            // At threshold emits exact pole quirk.
            List<CivilianQuirk> low = CivilianTraits.NEUTRAL.with(trait, -CivilianTraits.QUIRK_THRESHOLD).quirks();
            List<CivilianQuirk> high = CivilianTraits.NEUTRAL.with(trait, CivilianTraits.QUIRK_THRESHOLD).quirks();
            assertEquals(List.of(CivilianQuirk.forPole(trait, false)), low);
            assertEquals(List.of(CivilianQuirk.forPole(trait, true)), high);
        }

        // Strongest magnitude sorts first; equal magnitude breaks tie by BaseTrait ordinal.
        CivilianTraits multi = CivilianTraits.NEUTRAL
                .with(BaseTrait.MOTOR_SENSORY, 70)       // DEFT (|70|, ordinal 10)
                .with(BaseTrait.OPENNESS, -70)           // CREATURE_OF_HABIT (|70|, ordinal 0)
                .with(BaseTrait.CONTRACTILE_FORCE, 92);  // BRAWNY (|92|, ordinal 7)
        assertEquals(
                List.of(CivilianQuirk.BRAWNY, CivilianQuirk.CREATURE_OF_HABIT, CivilianQuirk.DEFT),
                multi.quirks());
    }

    @Test
    void tenThousandSeededRollsStayBalancedWithHealthySpreadAndQuirkRate() {
        Random random = new Random(20261122L);
        int trials = 10_000;
        long totalQuirks = 0;
        int minAbilitySeen = 18;
        int maxAbilitySeen = 3;
        double sumAbilityMean = 0.0;

        for (int i = 0; i < trials; i++) {
            CivilianTraits rolled = CivilianTraits.roll(random);
            assertTrue(rolled.quirkCount() <= CivilianTraits.MAX_ROLLED_QUIRKS, "quirks=" + rolled.quirkCount());
            totalQuirks += rolled.quirkCount();

            CivilianAbilities abilities = rolled.abilities();
            double mean = abilities.mean();
            assertTrue(mean >= CivilianTraits.REROLL_MIN_ABILITY_MEAN && mean <= CivilianTraits.REROLL_MAX_ABILITY_MEAN,
                    "ability mean out of bounds: " + mean);
            sumAbilityMean += mean;

            for (BaseTrait trait : BaseTrait.values()) {
                int v = rolled.raw(trait);
                assertTrue(v >= CivilianTraits.MIN_VALUE && v <= CivilianTraits.MAX_VALUE);
            }
            for (CivilianAbilities.Ability ab : CivilianAbilities.Ability.values()) {
                int s = abilities.raw(ab);
                assertTrue(s >= 3 && s <= 18);
                minAbilitySeen = Math.min(minAbilitySeen, s);
                maxAbilitySeen = Math.max(maxAbilitySeen, s);
            }
        }

        double avgMean = sumAbilityMean / trials;
        double avgQuirks = totalQuirks / (double) trials;
        assertTrue(avgMean > 10.1 && avgMean < 10.9, "avgMean=" + avgMean);
        assertTrue(avgQuirks > 1.0 && avgQuirks < 2.2, "avgQuirks=" + avgQuirks);
        assertTrue(minAbilitySeen <= 4, "healthy low tail expected, min=" + minAbilitySeen);
        assertTrue(maxAbilitySeen >= 17, "healthy high tail expected, max=" + maxAbilitySeen);
    }

    @Test
    void lambdaRandomGeneratorWorksWithRoll() {
        Random backing = new Random(12345L);
        RandomGenerator lambda = backing::nextLong;
        CivilianTraits rolled = CivilianTraits.roll(lambda);
        assertNotNull(rolled);
        assertTrue(rolled.quirkCount() <= CivilianTraits.MAX_ROLLED_QUIRKS);
    }

    @Test
    void defaultForUuidIsStableAndDistinct() {
        UUID a = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        UUID b = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");
        assertEquals(CivilianTraits.defaultFor(a), CivilianTraits.defaultFor(a));
        assertNotEquals(CivilianTraits.defaultFor(a), CivilianTraits.defaultFor(b));
    }

    @Test
    void everyAbilityAndQuirkKeyExistsInLangFile() throws Exception {
        JsonObject lang;
        try (InputStream in = CivilianTraitsTest.class.getResourceAsStream("/assets/empire/lang/en_us.json")) {
            assertNotNull(in, "en_us.json on classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        assertTrue(lang.has("message.empire.civilian.abilities"));
        for (CivilianAbilities.Ability ability : CivilianAbilities.Ability.values()) {
            assertTrue(lang.has(ability.langKey()), "missing lang key " + ability.langKey());
        }
        for (CivilianQuirk quirk : CivilianQuirk.values()) {
            assertTrue(lang.has(quirk.langKey()), "missing lang key " + quirk.langKey());
            assertEquals(quirk.displayName(), lang.get(quirk.langKey()).getAsString(), quirk.langKey());
        }
    }
}
