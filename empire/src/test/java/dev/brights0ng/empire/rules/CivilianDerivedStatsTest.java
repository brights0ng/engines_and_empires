// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;

class CivilianDerivedStatsTest {

    private static final double EPS = 1e-6;

    @Test
    void neutralMatchesAllBaselineDefaultsExactly() {
        CivilianDerivedStats s = CivilianDerivedStats.NEUTRAL;
        assertEquals(30.0, s.maxHealth(), EPS);
        assertEquals(0.50, s.movementSpeed(), EPS);
        assertEquals(0.42, s.jumpStrength(), EPS);
        assertEquals(3.0, s.safeFallDistance(), EPS);
        assertEquals(1.0, s.fallDamageMultiplier(), EPS);
        assertEquals(0.10, s.knockbackResistance(), EPS);
        assertEquals(2.0, s.attackDamage(), EPS);
        assertEquals(0.40, s.attackKnockback(), EPS);
        assertEquals(0.15, s.movementEfficiency(), EPS);
        assertEquals(0.15, s.waterMovementEfficiency(), EPS);
        assertEquals(0.50, s.oxygenBonus(), EPS);
        assertEquals(32.0, s.followRange(), EPS);

        assertEquals(1.0, s.basalExhaustionMultiplier(), EPS);
        assertEquals(1.0, s.activityExhaustionMultiplier(), EPS);
        assertEquals(1.0, s.healingIntervalMultiplier(), EPS);
        assertEquals(1.0, s.starvationIntervalMultiplier(), EPS);
        assertEquals(9, s.hungerPenaltyThreshold());
        assertEquals(0.65, s.hungerStrengthFloor(), EPS);
        assertEquals(7200, s.sleepNeedTicks());
        assertEquals(0.50F, s.roughSleepCreditRate(), 1e-6F);
        assertEquals(200, s.wakeCooldownTicks());

        assertEquals(0.0, s.comfortTempShiftC(), EPS);
        assertEquals(1.0, s.comfortDangerMarginMultiplier(), EPS);
        assertEquals(10.0, s.communityTargetPeople(), EPS);
        assertEquals(5.0, s.socializingTargetInteractions(), EPS);
        assertEquals(25.0, s.shelterFloorPerOccupant(), EPS);
        assertEquals(1.0, s.grievanceBuildMultiplier(), EPS);
        assertEquals(1.0, s.grievanceDrainMultiplier(), EPS);
        assertEquals(1.0, s.speechCooldownMultiplier(), EPS);
        assertEquals(0.25, s.talkChance(), EPS);

        assertEquals(3.8, s.blockReach(), EPS);
        assertEquals(2.6, s.meleeReach(), EPS);
        assertEquals(1.0, s.mechanicalTorqueMultiplier(), EPS);
        assertEquals(1.0, s.mechanicalSpeedMultiplier(), EPS);
        assertEquals(1.0, s.sustainedLaborStamina(), EPS);
        assertEquals(1.0, s.carryCapacityMultiplier(), EPS);
        assertEquals(1.0, s.heavyLaborSpeedMultiplier(), EPS);
        assertEquals(1.0, s.fineWorkSpeedMultiplier(), EPS);
        assertEquals(1.0, s.coldFatiguePrecisionSensitivity(), EPS);
        assertEquals(1.0, s.fumbleRateMultiplier(), EPS);
        assertEquals(1.0, s.rangedAccuracyMultiplier(), EPS);
        assertEquals(1.0, s.cognitiveLearningMultiplier(), EPS);
        assertEquals(1.0, s.motorLearningMultiplier(), EPS);
        assertEquals(0.5, s.monotonyDrainRate(), EPS);
        assertEquals(1.0, s.reassignmentPenaltyMultiplier(), EPS);
        assertEquals(1.0, s.routingEfficiencyMultiplier(), EPS);
        assertEquals(1.0, s.teamworkSynergyMultiplier(), EPS);
        assertEquals(0.0, s.moraleAuraStrength(), EPS);
        assertEquals(1.0, s.moraleContagionSensitivity(), EPS);
        assertEquals(0.5, s.braveryScore(), EPS);
        assertEquals(1.0, s.bleedOutDurationMultiplier(), EPS);
    }

    @Test
    void orthogonalityTradeOffsFollowBiomechanics() {
        // 1. Allometric Scale: Compact (-100) vs Elongated (+100)
        CivilianDerivedStats compact = CivilianTraits.NEUTRAL.with(BaseTrait.ALLOMETRIC_SCALE, -100).derivedStats();
        CivilianDerivedStats elongated = CivilianTraits.NEUTRAL.with(BaseTrait.ALLOMETRIC_SCALE, 100).derivedStats();
        assertTrue(compact.comfortTempShiftC() < 0 && elongated.comfortTempShiftC() > 0, "Allen's rule thermal shift");
        assertTrue(compact.mechanicalTorqueMultiplier() > elongated.mechanicalTorqueMultiplier());
        assertTrue(compact.carryCapacityMultiplier() > elongated.carryCapacityMultiplier());
        assertTrue(compact.knockbackResistance() > elongated.knockbackResistance());
        assertTrue(elongated.blockReach() > compact.blockReach());
        assertTrue(elongated.meleeReach() > compact.meleeReach());
        assertTrue(elongated.movementSpeed() > compact.movementSpeed());

        // 2. Metabolic Reserve: Lean (-100) vs Fatty (+100)
        CivilianDerivedStats lean = CivilianTraits.NEUTRAL.with(BaseTrait.METABOLIC_RESERVE, -100).derivedStats();
        CivilianDerivedStats fatty = CivilianTraits.NEUTRAL.with(BaseTrait.METABOLIC_RESERVE, 100).derivedStats();
        assertTrue(lean.movementSpeed() > fatty.movementSpeed());
        assertTrue(lean.basalExhaustionMultiplier() > fatty.basalExhaustionMultiplier());
        assertTrue(fatty.starvationIntervalMultiplier() > lean.starvationIntervalMultiplier());
        assertTrue(fatty.comfortTempShiftC() < lean.comfortTempShiftC(), "adipose cold insulation");
        assertTrue(lean.hungerPenaltyThreshold() > fatty.hungerPenaltyThreshold());
        assertTrue(fatty.hungerStrengthFactor(6) > lean.hungerStrengthFactor(6));
        assertEquals(1.0, fatty.hungerStrengthFactor(6), EPS);
        assertTrue(lean.hungerStrengthFactor(6) < 1.0);
        assertEquals(lean.hungerStrengthFloor(), lean.hungerStrengthFactor(0), EPS);
        assertEquals(1.0, lean.hungerStrengthFactor(20), EPS);

        // 3. Contractile Force: Gracile (-100) vs Hypertrophic (+100)
        CivilianDerivedStats gracile = CivilianTraits.NEUTRAL.with(BaseTrait.CONTRACTILE_FORCE, -100).derivedStats();
        CivilianDerivedStats hyper = CivilianTraits.NEUTRAL.with(BaseTrait.CONTRACTILE_FORCE, 100).derivedStats();
        assertTrue(hyper.maxHealth() > gracile.maxHealth());
        assertTrue(hyper.attackDamage() > gracile.attackDamage());
        assertTrue(hyper.mechanicalTorqueMultiplier() > gracile.mechanicalTorqueMultiplier());
        assertTrue(hyper.carryCapacityMultiplier() > gracile.carryCapacityMultiplier());
        assertTrue(hyper.basalExhaustionMultiplier() > gracile.basalExhaustionMultiplier(), "muscle costs calories");
        assertTrue(hyper.sleepNeedTicks() > gracile.sleepNeedTicks());
        assertTrue(gracile.waterMovementEfficiency() > hyper.waterMovementEfficiency());

        // 4. Aerobic Stamina: Anaerobic (-100) vs Oxygen-Efficient (+100)
        CivilianDerivedStats anaerobic = CivilianTraits.NEUTRAL.with(BaseTrait.AEROBIC_STAMINA, -100).derivedStats();
        CivilianDerivedStats aerobic = CivilianTraits.NEUTRAL.with(BaseTrait.AEROBIC_STAMINA, 100).derivedStats();
        assertTrue(aerobic.activityExhaustionMultiplier() < anaerobic.activityExhaustionMultiplier());
        assertTrue(aerobic.sustainedLaborStamina() > anaerobic.sustainedLaborStamina());
        assertTrue(aerobic.oxygenBonus() > anaerobic.oxygenBonus());
        assertTrue(aerobic.sleepNeedTicks() < anaerobic.sleepNeedTicks());
        assertTrue(aerobic.healingIntervalMultiplier() < anaerobic.healingIntervalMultiplier());
        assertTrue(anaerobic.mechanicalSpeedMultiplier() > aerobic.mechanicalSpeedMultiplier(), "Type II peak RPM");
        assertTrue(anaerobic.jumpStrength() > aerobic.jumpStrength());
        assertTrue(anaerobic.attackDamage() > aerobic.attackDamage());

        // 5. Elastic Compliance: Stiff (-100) vs Flexible (+100)
        CivilianDerivedStats stiff = CivilianTraits.NEUTRAL.with(BaseTrait.ELASTIC_COMPLIANCE, -100).derivedStats();
        CivilianDerivedStats flex = CivilianTraits.NEUTRAL.with(BaseTrait.ELASTIC_COMPLIANCE, 100).derivedStats();
        assertTrue(flex.safeFallDistance() > stiff.safeFallDistance());
        assertTrue(flex.fallDamageMultiplier() < stiff.fallDamageMultiplier());
        assertTrue(flex.movementEfficiency() > stiff.movementEfficiency());
        assertTrue(flex.roughSleepCreditRate() > stiff.roughSleepCreditRate());
        assertTrue(stiff.activityExhaustionMultiplier() < flex.activityExhaustionMultiplier(), "elastic recoil economy");
        assertTrue(stiff.mechanicalTorqueMultiplier() > flex.mechanicalTorqueMultiplier());
        assertTrue(stiff.knockbackResistance() > flex.knockbackResistance());

        // 6. Motor-Sensory Integration (Fitts's Law trade-off): Coarse (-100) vs Calibrated (+100)
        CivilianDerivedStats coarse = CivilianTraits.NEUTRAL.with(BaseTrait.MOTOR_SENSORY, -100).derivedStats();
        CivilianDerivedStats calibrated = CivilianTraits.NEUTRAL.with(BaseTrait.MOTOR_SENSORY, 100).derivedStats();
        assertTrue(calibrated.fineWorkSpeedMultiplier() > coarse.fineWorkSpeedMultiplier());
        assertTrue(calibrated.fumbleRateMultiplier() < coarse.fumbleRateMultiplier());
        assertTrue(calibrated.rangedAccuracyMultiplier() > coarse.rangedAccuracyMultiplier());
        assertTrue(calibrated.motorLearningMultiplier() > coarse.motorLearningMultiplier());
        assertTrue(coarse.heavyLaborSpeedMultiplier() > calibrated.heavyLaborSpeedMultiplier(), "Coarse ballistic swings");
        assertTrue(calibrated.sleepNeedTicks() > coarse.sleepNeedTicks(), "cerebellar consolidation sleep cost");
        assertTrue(calibrated.coldFatiguePrecisionSensitivity() > coarse.coldFatiguePrecisionSensitivity());
    }

    @Test
    void bigFiveDualPolesAndInvertedUCurvesWorkAsDesigned() {
        // Conscientiousness inverted-U: mid-high (+65) outperforms extreme perfectionist (+100) on fine work & routing.
        CivilianDerivedStats lowC = CivilianTraits.NEUTRAL.with(BaseTrait.CONSCIENTIOUSNESS, -80).derivedStats();
        CivilianDerivedStats midC = CivilianTraits.NEUTRAL.with(BaseTrait.CONSCIENTIOUSNESS, 65).derivedStats();
        CivilianDerivedStats maxC = CivilianTraits.NEUTRAL.with(BaseTrait.CONSCIENTIOUSNESS, 100).derivedStats();
        assertTrue(midC.routingEfficiencyMultiplier() > lowC.routingEfficiencyMultiplier());
        assertTrue(midC.routingEfficiencyMultiplier() > maxC.routingEfficiencyMultiplier(), "perfectionist routing drag");
        assertTrue(midC.fineWorkSpeedMultiplier() > maxC.fineWorkSpeedMultiplier(), "perfectionist over-checking");
        assertTrue(maxC.fumbleRateMultiplier() < lowC.fumbleRateMultiplier());
        assertTrue(maxC.grievanceBuildMultiplier() > midC.grievanceBuildMultiplier(), "perfectionist stress");

        // Openness inverted-U on cognitive learning + monotonic monotony drain.
        CivilianDerivedStats lowO = CivilianTraits.NEUTRAL.with(BaseTrait.OPENNESS, -80).derivedStats();
        CivilianDerivedStats midO = CivilianTraits.NEUTRAL.with(BaseTrait.OPENNESS, 70).derivedStats();
        CivilianDerivedStats maxO = CivilianTraits.NEUTRAL.with(BaseTrait.OPENNESS, 100).derivedStats();
        assertTrue(midO.cognitiveLearningMultiplier() > lowO.cognitiveLearningMultiplier());
        assertTrue(midO.cognitiveLearningMultiplier() > maxO.cognitiveLearningMultiplier(), "daydreamer drag");
        assertTrue(maxO.monotonyDrainRate() > lowO.monotonyDrainRate());
        assertTrue(lowO.reassignmentPenaltyMultiplier() > maxO.reassignmentPenaltyMultiplier());

        // Extraversion: social targets, speech cadence, teamwork inverted-U.
        CivilianDerivedStats intro = CivilianTraits.NEUTRAL.with(BaseTrait.EXTRAVERSION, -90).derivedStats();
        CivilianDerivedStats ambiv = CivilianTraits.NEUTRAL.with(BaseTrait.EXTRAVERSION, 65).derivedStats();
        CivilianDerivedStats extra = CivilianTraits.NEUTRAL.with(BaseTrait.EXTRAVERSION, 100).derivedStats();
        assertTrue(intro.communityTargetPeople() < extra.communityTargetPeople());
        assertTrue(intro.socializingTargetInteractions() < extra.socializingTargetInteractions());
        assertTrue(intro.shelterFloorPerOccupant() > extra.shelterFloorPerOccupant());
        assertTrue(extra.speechCooldownMultiplier() < intro.speechCooldownMultiplier());
        assertTrue(extra.talkChance() > intro.talkChance());
        assertTrue(ambiv.teamworkSynergyMultiplier() > extra.teamworkSynergyMultiplier(), "chatterbox distraction");

        // Agreeableness: teamwork inverted-U, morale aura, combat hesitation.
        CivilianDerivedStats gruff = CivilianTraits.NEUTRAL.with(BaseTrait.AGREEABLENESS, -90).derivedStats();
        CivilianDerivedStats coop = CivilianTraits.NEUTRAL.with(BaseTrait.AGREEABLENESS, 65).derivedStats();
        CivilianDerivedStats soft = CivilianTraits.NEUTRAL.with(BaseTrait.AGREEABLENESS, 100).derivedStats();
        assertTrue(coop.teamworkSynergyMultiplier() > gruff.teamworkSynergyMultiplier());
        assertTrue(coop.teamworkSynergyMultiplier() > soft.teamworkSynergyMultiplier(), "over-yielding drag");
        assertTrue(gruff.moraleAuraStrength() < 0 && soft.moraleAuraStrength() > 0);
        assertTrue(gruff.braveryScore() > soft.braveryScore(), "agreeable combat hesitation");
        assertTrue(soft.moraleContagionSensitivity() > gruff.moraleContagionSensitivity());

        // Neuroticism: vigilance vs complacency, sleep, grievance, bravery.
        CivilianDerivedStats stoic = CivilianTraits.NEUTRAL.with(BaseTrait.NEUROTICISM, -90).derivedStats();
        CivilianDerivedStats anxious = CivilianTraits.NEUTRAL.with(BaseTrait.NEUROTICISM, 90).derivedStats();
        assertTrue(anxious.followRange() > stoic.followRange(), "hyper-vigilance vs complacency");
        assertTrue(anxious.sleepNeedTicks() > stoic.sleepNeedTicks());
        assertTrue(anxious.wakeCooldownTicks() > stoic.wakeCooldownTicks());
        assertTrue(anxious.grievanceBuildMultiplier() > stoic.grievanceBuildMultiplier());
        assertTrue(anxious.grievanceDrainMultiplier() < stoic.grievanceDrainMultiplier());
        assertTrue(stoic.braveryScore() > anxious.braveryScore());
    }

    @Test
    void twoThousandRandomRollsProduceFiniteInBoundsDerivedStats() {
        Random rng = new Random(999L);
        for (int i = 0; i < 2_000; i++) {
            CivilianDerivedStats s = CivilianTraits.roll(rng).derivedStats();
            assertInRange(s.maxHealth(), 22.0, 38.0);
            assertInRange(s.movementSpeed(), 0.50 * 0.86, 0.50 * 1.14);
            assertInRange(s.jumpStrength(), 0.42 * 0.88, 0.42 * 1.15);
            assertInRange(s.safeFallDistance(), 2.0, 5.0);
            assertInRange(s.fallDamageMultiplier(), 0.60, 1.50);
            assertInRange(s.knockbackResistance(), 0.0, 0.45);
            assertInRange(s.attackDamage(), 2.0 * 0.65, 2.0 * 1.50);
            assertInRange(s.attackKnockback(), 0.0, 1.0);
            assertInRange(s.movementEfficiency(), 0.0, 0.40);
            assertInRange(s.waterMovementEfficiency(), 0.0, 0.45);
            assertInRange(s.oxygenBonus(), 0.0, 2.5);
            assertInRange(s.followRange(), 20.0, 44.0);
            assertInRange(s.basalExhaustionMultiplier(), 0.65, 1.45);
            assertInRange(s.activityExhaustionMultiplier(), 0.60, 1.50);
            assertInRange(s.healingIntervalMultiplier(), 0.65, 1.45);
            assertInRange(s.starvationIntervalMultiplier(), 0.60, 1.65);
            assertTrue(s.hungerPenaltyThreshold() >= 5 && s.hungerPenaltyThreshold() <= 13);
            assertInRange(s.hungerStrengthFloor(), 0.55, 0.75);
            assertTrue(s.sleepNeedTicks() >= 5400 && s.sleepNeedTicks() <= 10200);
            assertInRange(s.roughSleepCreditRate(), 0.38, 0.62);
            assertTrue(s.wakeCooldownTicks() >= 120 && s.wakeCooldownTicks() <= 320);
            assertInRange(s.comfortTempShiftC(), -5.5, 5.5);
            assertInRange(s.comfortDangerMarginMultiplier(), 0.75, 1.30);
            assertInRange(s.communityTargetPeople(), 4.0, 16.0);
            assertInRange(s.socializingTargetInteractions(), 2.0, 9.0);
            assertInRange(s.shelterFloorPerOccupant(), 19.0, 32.0);
            assertInRange(s.grievanceBuildMultiplier(), 0.60, 1.60);
            assertInRange(s.grievanceDrainMultiplier(), 0.65, 1.45);
            assertInRange(s.speechCooldownMultiplier(), 0.65, 1.60);
            assertInRange(s.talkChance(), 0.08, 0.50);
            assertInRange(s.blockReach(), 3.1, 4.5);
            assertInRange(s.meleeReach(), 2.1, 3.1);
            assertInRange(s.sustainedLaborStamina(), 0.55, 1.55);
        }
    }

    private static void assertInRange(double v, double lo, double hi) {
        assertTrue(Double.isFinite(v) && v >= lo - EPS && v <= hi + EPS,
                "Expected " + v + " in [" + lo + ", " + hi + "]");
    }
}
