// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.brights0ng.empire.rules.CivilianSkills.SkillTier;
import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;

class CivilianSkillsTest {

    @Test
    void cubicCurveMatchesExactDayMilestonesAndTiers() {
        assertEquals(0.0, CivilianSkills.xpForLevel(0.0), 1e-9);
        assertEquals(300.0, CivilianSkills.xpForLevel(0.25), 1e-9);    // 3 days
        assertEquals(2_400.0, CivilianSkills.xpForLevel(0.50), 1e-9);  // 24 days (8h)
        assertEquals(8_100.0, CivilianSkills.xpForLevel(0.75), 1e-9);  // 81 days
        assertEquals(19_200.0, CivilianSkills.xpForLevel(1.00), 1e-9); // 192 days (2 yr)

        assertEquals(0.00, CivilianSkills.levelForXp(0.0), 1e-9);
        assertEquals(0.25, CivilianSkills.levelForXp(300.0), 1e-9);
        assertEquals(0.50, CivilianSkills.levelForXp(2_400.0), 1e-9);
        assertEquals(0.75, CivilianSkills.levelForXp(8_100.0), 1e-9);
        assertEquals(1.00, CivilianSkills.levelForXp(19_200.0), 1e-9);
        assertEquals(1.00, CivilianSkills.levelForXp(999_999.0), 1e-9);

        assertEquals(SkillTier.UNSKILLED, SkillTier.forLevel(0.0));
        assertEquals(SkillTier.UNSKILLED, SkillTier.forLevel(0.24));
        assertEquals(SkillTier.SIMPLE, SkillTier.forLevel(0.25));
        assertEquals(SkillTier.COMPETENT, SkillTier.forLevel(0.50));
        assertEquals(SkillTier.ADVANCED, SkillTier.forLevel(0.75));
        assertEquals(SkillTier.MASTERED, SkillTier.forLevel(1.00));
    }

    @Test
    void mechanicalMultipliersMatchLinearSpecifications() {
        // Speed: 0.5x -> 1.0x -> 1.5x -> 2.0x -> 2.5x
        assertEquals(0.5, CivilianSkills.speedMultiplier(0.00), 1e-9);
        assertEquals(1.0, CivilianSkills.speedMultiplier(0.25), 1e-9);
        assertEquals(1.5, CivilianSkills.speedMultiplier(0.50), 1e-9);
        assertEquals(2.0, CivilianSkills.speedMultiplier(0.75), 1e-9);
        assertEquals(2.5, CivilianSkills.speedMultiplier(1.00), 1e-9);

        // Exhaustion: 1.0x at 0.0 -> 0.5x at 1.0
        assertEquals(1.000, CivilianSkills.exhaustionMultiplier(0.00), 1e-9);
        assertEquals(0.750, CivilianSkills.exhaustionMultiplier(0.50), 1e-9);
        assertEquals(0.500, CivilianSkills.exhaustionMultiplier(1.00), 1e-9);

        // Tool durability chance: 1.0 at 0.0 -> 0.25 at 1.0
        assertEquals(1.0000, CivilianSkills.toolDurabilityChance(0.00), 1e-9);
        assertEquals(0.6250, CivilianSkills.toolDurabilityChance(0.50), 1e-9);
        assertEquals(0.2500, CivilianSkills.toolDurabilityChance(1.00), 1e-9);

        // Crop yield: 1.0x at 0.0 -> 1.5x at 1.0
        assertEquals(1.00, CivilianSkills.cropYieldMultiplier(0.00), 1e-9);
        assertEquals(1.25, CivilianSkills.cropYieldMultiplier(0.50), 1e-9);
        assertEquals(1.50, CivilianSkills.cropYieldMultiplier(1.00), 1e-9);

        // Probabilistic yield average matches 1.5x at Mastery
        Random rng = new Random(42L);
        int sum = 0;
        int trials = 10_000;
        for (int i = 0; i < trials; i++) {
            sum += CivilianSkills.rollScaledYield(1, 1.0, rng);
        }
        double avg = sum / (double) trials;
        assertEquals(1.50, avg, 0.03);
        assertEquals(2, CivilianSkills.rollScaledYield(2, 0.0, rng));
    }

    @Test
    void actionXpScalesWithTimeEfficiencyTraitsFulfilmentAndSleep() {
        CivilianDerivedStats neutral = CivilianDerivedStats.NEUTRAL;
        // At Simple (0.25, speed 1.0x), baseline modifiers give exact baseActionXp.
        double simpleXp = CivilianSkills.computeActionXp(CivilianSkill.HARVESTING, 1.0, 0.25, neutral, 1.0, 1.0F);
        assertEquals(1.0, simpleXp, 1e-9);
        // At Advanced (0.75, speed 2.0x), worker gains 0.5x XP per action relative to Simple.
        double advXp = CivilianSkills.computeActionXp(CivilianSkill.HARVESTING, 1.0, 0.75, neutral, 1.0, 1.0F);
        assertEquals(0.5, advXp, 1e-9);
        // At Mastered (1.0, speed 2.5x), 0.4x XP per action.
        assertEquals(0.4, CivilianSkills.computeActionXp(CivilianSkill.HARVESTING, 1.0, 1.0, neutral, 1.0, 1.0F), 1e-9);

        // Low productivity and poor sleep reduce XP gain; NaN sleep (first day) defaults to 1.0.
        assertEquals(1.0, CivilianSkills.computeActionXp(CivilianSkill.HARVESTING, 1.0, 0.25, neutral, 1.0, Float.NaN), 1e-9);
        assertEquals(0.5, CivilianSkills.computeActionXp(CivilianSkill.HARVESTING, 1.0, 0.25, neutral, 0.5, 1.0F), 1e-9);
        assertEquals(0.5, CivilianSkills.computeActionXp(CivilianSkill.HARVESTING, 1.0, 0.25, neutral, 1.0, 0.0F), 1e-9);
        assertEquals(0.25, CivilianSkills.computeActionXp(CivilianSkill.HARVESTING, 1.0, 0.25, neutral, 0.5, 0.0F), 1e-9);

        // Motor vs. cognitive trait learning weights differentiate Harvesting (motor-heavy) vs Logistics (cog-heavy).
        CivilianDerivedStats deft = CivilianTraits.NEUTRAL.with(BaseTrait.MOTOR_SENSORY, 90).derivedStats();
        CivilianDerivedStats witty = CivilianTraits.NEUTRAL.with(BaseTrait.OPENNESS, 60)
                .with(BaseTrait.CONSCIENTIOUSNESS, 80).derivedStats();
        assertTrue(CivilianSkill.HARVESTING.learningMultiplier(deft) > CivilianSkill.LOGISTICS.learningMultiplier(deft));
        assertTrue(CivilianSkill.LOGISTICS.learningMultiplier(witty) > CivilianSkill.HARVESTING.learningMultiplier(witty));

        // Transfer XP scales with items moved and respects cap.
        assertEquals(0.0, CivilianSkills.transferBaseXp(0), 1e-9);
        assertTrue(CivilianSkills.transferBaseXp(64) > CivilianSkills.transferBaseXp(1));
        assertEquals(CivilianSkills.TRANSFER_MAX_XP, CivilianSkills.transferBaseXp(10_000), 1e-9);
    }

    @Test
    void rookieFumblesStopAtSimpleAndRespondToTraitsAndColdFatigue() {
        CivilianDerivedStats neutral = CivilianDerivedStats.NEUTRAL;
        double f0 = CivilianSkills.fumbleChance(0.0, neutral, 1.0, 1.0F, 1.0);
        double fMid = CivilianSkills.fumbleChance(0.125, neutral, 1.0, 1.0F, 1.0);
        double fSimple = CivilianSkills.fumbleChance(0.25, neutral, 1.0, 1.0F, 1.0);
        double fMaster = CivilianSkills.fumbleChance(1.0, neutral, 0.0, 0.0F, 0.5);

        assertEquals(CivilianSkills.BASE_ROOKIE_FUMBLE_CHANCE, f0, 1e-9);
        assertEquals(CivilianSkills.BASE_ROOKIE_FUMBLE_CHANCE * 0.5, fMid, 1e-9);
        assertEquals(0.0, fSimple, 1e-9);
        assertEquals(0.0, fMaster, 1e-9);

        // Cold/fatigue stress penalizes Deft workers (higher coldFatiguePrecisionSensitivity) proportionally more.
        CivilianDerivedStats deft = CivilianTraits.NEUTRAL.with(BaseTrait.MOTOR_SENSORY, 90).derivedStats();
        CivilianDerivedStats coarse = CivilianTraits.NEUTRAL.with(BaseTrait.MOTOR_SENSORY, -90).derivedStats();
        double deftWarm = CivilianSkills.fumbleChance(0.0, deft, 1.0, 1.0F, 1.0);
        double deftCold = CivilianSkills.fumbleChance(0.0, deft, 0.0, 0.5F, 1.0);
        double coarseWarm = CivilianSkills.fumbleChance(0.0, coarse, 1.0, 1.0F, 1.0);
        double coarseCold = CivilianSkills.fumbleChance(0.0, coarse, 0.0, 0.5F, 1.0);
        assertTrue(deftWarm < coarseWarm, "deft fumbles less than coarse when comfortable");
        assertTrue(deftCold > deftWarm, "cold/fatigue increases fumble chance");
        assertTrue((deftCold / deftWarm) > (coarseCold / coarseWarm),
                "Fitts's-Law: deft suffers sharper proportional precision loss under cold/fatigue");
        assertTrue(coarseCold <= CivilianSkills.MAX_FUMBLE_CHANCE, "fumble chance stays capped");
    }

    @Test
    void decayRespectsGracePeriodAndDecaysAtTwentyPercentRateInXpSpace() {
        CivilianSkills skills = new CivilianSkills();
        skills.setLevel(CivilianSkill.HARVESTING, 0.50); // 2,400 XP
        double startXp = skills.xp(CivilianSkill.HARVESTING);
        assertEquals(2_400.0, startXp, 1e-9);

        // 3 consecutive inactive days: within grace period, zero XP lost.
        skills.advanceDays(CivilianSkills.DECAY_GRACE_DAYS);
        assertEquals(startXp, skills.xp(CivilianSkill.HARVESTING), 1e-9);
        assertEquals(CivilianSkills.DECAY_GRACE_DAYS, skills.inactiveDays(CivilianSkill.HARVESTING));

        // 4th inactive day: loses exactly DAILY_DECAY_XP (20.0 XP = 20% of 100 XP/day).
        skills.advanceDay();
        assertEquals(startXp - CivilianSkills.DAILY_DECAY_XP, skills.xp(CivilianSkill.HARVESTING), 1e-9);

        // Practicing resets inactivity and prevents decay on that day's rollover.
        skills.recordAction(CivilianSkill.HARVESTING, 1.0, CivilianDerivedStats.NEUTRAL, 1.0, 1.0F);
        assertEquals(0, skills.inactiveDays(CivilianSkill.HARVESTING));
        assertTrue(skills.usedToday(CivilianSkill.HARVESTING));
        double xpAfterPractice = skills.xp(CivilianSkill.HARVESTING);
        skills.advanceDay();
        assertEquals(xpAfterPractice, skills.xp(CivilianSkill.HARVESTING), 1e-9);
        assertFalse(skills.usedToday(CivilianSkill.HARVESTING));
        assertEquals(0, skills.inactiveDays(CivilianSkill.HARVESTING));

        // Enough inactive days eventually decay to 0 and stay non-negative.
        skills.advanceDays(500);
        assertEquals(0.0, skills.xp(CivilianSkill.HARVESTING), 1e-9);
        assertEquals(0, skills.inactiveDays(CivilianSkill.HARVESTING));
    }

    @Test
    void startingSkillsRollWithinZeroToHalfAndFollowTraitAffinity() {
        CivilianTraits organizer = CivilianTraits.NEUTRAL
                .with(BaseTrait.CONSCIENTIOUSNESS, 95)
                .with(BaseTrait.OPENNESS, 70)
                .with(BaseTrait.CONTRACTILE_FORCE, -80);
        CivilianTraits miner = CivilianTraits.NEUTRAL
                .with(BaseTrait.CONTRACTILE_FORCE, 95)
                .with(BaseTrait.AEROBIC_STAMINA, 90)
                .with(BaseTrait.CONSCIENTIOUSNESS, -70);

        Random rng = new Random(12345L);
        int orgLogistics = 0;
        int orgExcavation = 0;
        int minerExcavation = 0;
        int minerLogistics = 0;
        for (int i = 0; i < 400; i++) {
            CivilianSkills sOrg = CivilianSkills.rollStarting(rng, organizer);
            int nonZero = sOrg.nonZeroSkillsSorted().size();
            assertTrue(nonZero >= 1 && nonZero <= 3, "starting skills count in 1..3, got " + nonZero);
            for (CivilianSkill skill : CivilianSkill.values()) {
                double lvl = sOrg.level(skill);
                assertTrue(lvl >= 0.0 && lvl <= CivilianSkills.START_SKILL_MAX_LEVEL + 1e-9,
                        "starting skill within 0.0..0.5, got " + lvl);
            }
            if (sOrg.xp(CivilianSkill.LOGISTICS) > 0) orgLogistics++;
            if (sOrg.xp(CivilianSkill.EXCAVATION) > 0) orgExcavation++;

            CivilianSkills sMin = CivilianSkills.rollStarting(rng, miner);
            if (sMin.xp(CivilianSkill.EXCAVATION) > 0) minerExcavation++;
            if (sMin.xp(CivilianSkill.LOGISTICS) > 0) minerLogistics++;
        }
        assertTrue(orgLogistics > orgExcavation * 2,
                "organizer strongly favors Logistics (" + orgLogistics + ") over Excavation (" + orgExcavation + ")");
        assertTrue(minerExcavation > minerLogistics * 2,
                "strong/tireless worker strongly favors Excavation (" + minerExcavation + ") over Logistics (" + minerLogistics + ")");

        // Deterministic UUID migration helper is reproducible and round-trips via Saved.
        UUID id = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        CivilianSkills a = CivilianSkills.defaultFor(id, organizer);
        CivilianSkills b = CivilianSkills.defaultFor(id, organizer);
        assertEquals(a.save(), b.save());
        assertEquals(a.save(), CivilianSkills.restore(a.save()).save());
    }

    @Test
    void skillLookupAndLangKeysAreWellFormed() throws Exception {
        JsonObject lang;
        try (InputStream in = CivilianSkillsTest.class.getResourceAsStream("/assets/empire/lang/en_us.json")) {
            assertNotNull(in, "en_us.json on the classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (CivilianSkill skill : CivilianSkill.values()) {
            assertEquals(skill, CivilianSkill.byId(skill.id()));
            assertEquals(skill, CivilianSkill.byId(skill.name()));
            assertEquals(1.0, skill.motorWeight() + skill.cognitiveWeight(), 1e-9);
            assertNotNull(skill.langKey());
            assertTrue(skill.langKey().startsWith("skill.empire."));
            assertTrue(lang.has(skill.langKey()), "missing lang key " + skill.langKey());
        }
        for (SkillTier tier : SkillTier.values()) {
            assertTrue(lang.has(tier.langKey()), "missing lang key " + tier.langKey());
        }
        assertNull(CivilianSkill.byId("nonexistent"));
        assertNull(CivilianSkill.byId(null));
    }
}
