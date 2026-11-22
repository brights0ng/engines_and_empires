// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.brights0ng.empire.rules.FulfilmentModel.Evaluation;
import dev.brights0ng.empire.rules.FulfilmentModel.ExpectationPoint;
import dev.brights0ng.empire.rules.FulfilmentModel.Meter;
import dev.brights0ng.empire.rules.FulfilmentModel.MeterReading;
import dev.brights0ng.empire.rules.FulfilmentModel.SourceKind;
import dev.brights0ng.empire.rules.FulfilmentModel.Tier;

class FulfilmentModelTest {

    private static final double EPS = 1e-6;

    private static Map<Meter, MeterReading> uniformReadings(double value) {
        EnumMap<Meter, MeterReading> map = new EnumMap<>(Meter.class);
        for (Meter m : Meter.values()) {
            map.put(m, MeterReading.live(value, "test"));
        }
        return map;
    }

    @Test
    void weightsSumToOneAndEachTierSumsToQuarter() {
        double total = 0.0;
        for (Tier tier : Tier.values()) {
            double tierSum = 0.0;
            for (Meter m : Meter.values()) {
                if (m.tier() == tier) {
                    tierSum += m.weight();
                }
            }
            assertEquals(0.25, tierSum, EPS, "tier " + tier);
            total += tierSum;
        }
        assertEquals(1.0, total, EPS);
    }

    @Test
    void fullMetersProduceFullHappinessAndGates() {
        Evaluation eval = FulfilmentModel.evaluate(uniformReadings(1.0), 0.50, 0.5);
        assertEquals(1.0, eval.happiness(), EPS);
        assertEquals(2.0, eval.fulfilment(), EPS);
        assertEquals(1.0, eval.productivity(), EPS); // capped at 1.0 even when fulfilment > 1
        for (Tier tier : Tier.values()) {
            assertEquals(1.0, eval.tierFractions().get(tier), EPS);
            assertEquals(1.0, eval.gates().get(tier), EPS);
            assertEquals(0.25, eval.effectiveTiers().get(tier), EPS);
        }
        assertEquals(0.0, eval.topShortfallGain(), EPS);
    }

    @Test
    void survivalGatePassThroughMatchesSpecExamples() {
        // Spec §3: Survival 0.5 with everything else full gives Security * 0.5, Belonging * 0.5, Purpose * 0.5.
        Map<Meter, MeterReading> halfSurvival = uniformReadings(1.0);
        halfSurvival.put(Meter.HUNGER, MeterReading.live(0.5, ""));
        halfSurvival.put(Meter.SLEEP, MeterReading.live(0.5, ""));
        halfSurvival.put(Meter.HEALTH, MeterReading.live(0.5, ""));
        Evaluation eval = FulfilmentModel.evaluate(halfSurvival, 1.0, 0.5);
        assertEquals(0.5, eval.tierFractions().get(Tier.SURVIVAL), EPS);
        assertEquals(1.0, eval.gates().get(Tier.SURVIVAL), EPS);
        assertEquals(0.5, eval.gates().get(Tier.SECURITY), EPS);
        assertEquals(0.5, eval.gates().get(Tier.BELONGING), EPS);
        assertEquals(0.5, eval.gates().get(Tier.PURPOSE), EPS);
        assertEquals(0.5, eval.happiness(), EPS);

        // Spec §3: Survival 0.01 shuts every tier above it down to ~0.
        Map<Meter, MeterReading> lowSurvival = uniformReadings(1.0);
        lowSurvival.put(Meter.HUNGER, MeterReading.live(0.01, ""));
        lowSurvival.put(Meter.SLEEP, MeterReading.live(0.01, ""));
        lowSurvival.put(Meter.HEALTH, MeterReading.live(0.01, ""));
        Evaluation low = FulfilmentModel.evaluate(lowSurvival, 1.0, 0.5);
        assertEquals(0.01, low.gates().get(Tier.SECURITY), EPS);
        assertEquals(0.01, low.gates().get(Tier.BELONGING), EPS);
        assertEquals(0.01, low.gates().get(Tier.PURPOSE), EPS);
        assertEquals(0.01, low.happiness(), EPS);
    }

    @Test
    void cascadingTierGatesMultiplyUpstreamFractions() {
        Map<Meter, MeterReading> m = uniformReadings(1.0);
        // Survival = 0.8, Security = 0.5, Belonging = 0.5, Purpose = 1.0
        m.put(Meter.HUNGER, MeterReading.live(0.8, ""));
        m.put(Meter.SLEEP, MeterReading.live(0.8, ""));
        m.put(Meter.HEALTH, MeterReading.live(0.8, ""));
        m.put(Meter.SAFETY, MeterReading.live(0.5, ""));
        m.put(Meter.COMFORT, MeterReading.live(0.5, ""));
        m.put(Meter.SHELTER, MeterReading.live(0.5, ""));
        m.put(Meter.COMMUNITY, MeterReading.live(0.5, ""));
        m.put(Meter.SOCIALIZING, MeterReading.live(0.5, ""));
        m.put(Meter.COMMITMENT, MeterReading.live(0.5, ""));

        Evaluation eval = FulfilmentModel.evaluate(m, 1.0, 0.5);
        assertEquals(1.0, eval.gates().get(Tier.SURVIVAL), EPS);
        assertEquals(0.8, eval.gates().get(Tier.SECURITY), EPS);
        assertEquals(0.4, eval.gates().get(Tier.BELONGING), EPS);
        assertEquals(0.2, eval.gates().get(Tier.PURPOSE), EPS);
        // Effective: 0.8*1*0.25 + 0.5*0.8*0.25 + 0.5*0.4*0.25 + 1.0*0.2*0.25 = 0.2 + 0.1 + 0.05 + 0.05 = 0.40
        assertEquals(0.40, eval.happiness(), EPS);
    }

    @Test
    void topShortfallFindsUpstreamBottleneck() {
        Map<Meter, MeterReading> m = uniformReadings(1.0);
        m.put(Meter.HUNGER, MeterReading.live(0.0, "")); // drops Survival by 0.4, crushing all downstream gates!
        m.put(Meter.ENJOYMENT, MeterReading.live(0.0, "")); // weight 0.15 in Purpose
        Evaluation eval = FulfilmentModel.evaluate(m, 1.0, 0.5);
        assertEquals(Meter.HUNGER, eval.topShortfall());
        assertTrue(eval.topShortfallGain() > 0.3);
    }

    @Test
    void missingMetersDefaultToConfiguredPlaceholder() {
        Evaluation eval = FulfilmentModel.evaluate(Map.of(), 1.0, 0.5);
        for (Meter meter : Meter.values()) {
            assertTrue(eval.meters().get(meter).isPlaceholder());
            assertEquals(0.5, eval.meterValue(meter), EPS);
        }
    }

    @Test
    void individualMeterCalculatorsMatchSpec() {
        // Hunger: lower half only
        assertEquals(1.0, FulfilmentModel.hunger(20), EPS);
        assertEquals(1.0, FulfilmentModel.hunger(10), EPS);
        assertEquals(0.5, FulfilmentModel.hunger(5), EPS);
        assertEquals(0.0, FulfilmentModel.hunger(0), EPS);

        // Sleep: NaN -> placeholder, finite -> live
        MeterReading sleepPlaceholder = FulfilmentModel.sleep(Float.NaN, 0.5);
        assertTrue(sleepPlaceholder.isPlaceholder());
        assertEquals(0.5, sleepPlaceholder.value(), EPS);
        MeterReading sleepLive = FulfilmentModel.sleep(0.75F, 0.5);
        assertFalse(sleepLive.isPlaceholder());
        assertEquals(0.75, sleepLive.value(), EPS);

        // Health
        assertEquals(1.0, FulfilmentModel.health(30.0F, 30.0F), EPS);
        assertEquals(0.5, FulfilmentModel.health(15.0F, 30.0F), EPS);
        assertEquals(0.0, FulfilmentModel.health(0.0F, 30.0F), EPS);

        // Safety
        assertEquals(1.0, FulfilmentModel.safety(0, 0, 10.0), EPS);
        assertEquals(0.5, FulfilmentModel.safety(5, 0, 10.0), EPS);
        assertEquals(0.0, FulfilmentModel.safety(10, 0, 10.0), EPS);
        assertEquals(1.0, FulfilmentModel.safety(6, 2, 10.0), EPS);
        assertEquals(0.0, FulfilmentModel.safety(13, 1, 10.0), EPS);

        // Comfort
        assertEquals(1.0, FulfilmentModel.comfortFromTemp(20.0, 10.0, 35.0, 15.0), EPS);
        assertEquals(1.0, FulfilmentModel.comfortFromTemp(10.0, 10.0, 35.0, 15.0), EPS);
        assertEquals(1.0, FulfilmentModel.comfortFromTemp(35.0, 10.0, 35.0, 15.0), EPS);
        assertEquals(0.5, FulfilmentModel.comfortFromTemp(2.5, 10.0, 35.0, 15.0), EPS);
        assertEquals(0.0, FulfilmentModel.comfortFromTemp(-5.0, 10.0, 35.0, 15.0), EPS);
        assertEquals(0.5, FulfilmentModel.comfortFromTemp(42.5, 10.0, 35.0, 15.0), EPS);
        assertEquals(0.0, FulfilmentModel.comfortFromTemp(50.0, 10.0, 35.0, 15.0), EPS);
        assertTrue(Double.isNaN(FulfilmentModel.comfortFromTemp(Double.NaN, 10.0, 35.0, 15.0)));

        // Shelter (25 walkable indoor floor blocks per inhabitant)
        assertEquals(0.0, FulfilmentModel.shelter(false, 50, 1), EPS);
        assertEquals(1.0, FulfilmentModel.shelter(true, 25, 1), EPS);
        assertEquals(1.0, FulfilmentModel.shelter(true, 40, 1), EPS);
        assertEquals(0.5, FulfilmentModel.shelter(true, 25, 2), EPS);
        assertEquals(1.0, FulfilmentModel.shelter(true, 100, 4), EPS);
        assertEquals(0.6, FulfilmentModel.shelter(true, 15, 1), EPS);

        // Community & Socializing
        assertEquals(0.0, FulfilmentModel.community(0), EPS);
        assertEquals(0.5, FulfilmentModel.community(5), EPS);
        assertEquals(1.0, FulfilmentModel.community(10), EPS);
        assertEquals(1.0, FulfilmentModel.community(25), EPS);
        assertEquals(0.0, FulfilmentModel.socializing(0), EPS);
        assertEquals(0.6, FulfilmentModel.socializing(3), EPS);
        assertEquals(1.0, FulfilmentModel.socializing(5), EPS);

        // Individualized shelter, community & socializing thresholds
        assertEquals(1.0, FulfilmentModel.shelter(true, 20, 1, 20.0), EPS);
        assertEquals(0.5, FulfilmentModel.shelter(true, 15, 1, 30.0), EPS);
        assertEquals(1.0, FulfilmentModel.community(4, 4.0), EPS);
        assertEquals(0.5, FulfilmentModel.community(8, 16.0), EPS);
        assertEquals(1.0, FulfilmentModel.socializing(2, 2.0), EPS);
        assertEquals(0.5, FulfilmentModel.socializing(4, 8.0), EPS);

        // Commitment + newcomer bonus (§4)
        assertEquals(0.5, FulfilmentModel.commitment(0.0), EPS);
        assertEquals(0.25 + 12.5 / 504.0, FulfilmentModel.commitment(12.5), EPS);
        assertEquals(25.0 / 504.0, FulfilmentModel.commitment(25.0), EPS);
        assertEquals(0.5, FulfilmentModel.commitment(252.0), EPS);
        assertEquals(1.0, FulfilmentModel.commitment(504.0), EPS);
        assertEquals(1.0, FulfilmentModel.commitment(1000.0), EPS);

        // Enjoyment (live food half + 50% placeholder activity half = 0.25)
        MeterReading e0 = FulfilmentModel.enjoyment(0, 0.5);
        assertEquals(SourceKind.PARTIAL, e0.source());
        assertEquals(0.25, e0.value(), EPS);
        assertEquals(0.50, FulfilmentModel.enjoyment(3, 0.5).value(), EPS);
        assertEquals(0.75, FulfilmentModel.enjoyment(6, 0.5).value(), EPS);
        assertEquals(0.75, FulfilmentModel.enjoyment(12, 0.5).value(), EPS);
    }

    @Test
    void expectationCurveInterpolatesAndParsesConfig() {
        List<ExpectationPoint> curve = FulfilmentModel.DEFAULT_EXPECTATION_CURVE;
        assertEquals(0.20, FulfilmentModel.expectation(1, curve), EPS);
        assertEquals(0.275, FulfilmentModel.expectation(3, curve), EPS); // midpoint of (1, 0.20) and (5, 0.35)
        assertEquals(0.35, FulfilmentModel.expectation(5, curve), EPS);
        assertEquals(0.50, FulfilmentModel.expectation(10, curve), EPS);
        assertEquals(1.00, FulfilmentModel.expectation(35, curve), EPS);
        assertEquals(1.30, FulfilmentModel.expectation(50, curve), EPS);
        assertEquals(1.30, FulfilmentModel.expectation(80, curve), EPS);

        List<ExpectationPoint> parsed = FulfilmentModel.parseExpectationCurve(List.of("10:0.6", "bad", "1:0.2"));
        assertEquals(2, parsed.size());
        assertEquals(0.4, FulfilmentModel.expectation(5, parsed), 0.05);
        assertEquals(FulfilmentModel.DEFAULT_EXPECTATION_CURVE, FulfilmentModel.parseExpectationCurve(List.of("invalid")));
    }

    @Test
    void productivityAndGrievanceFollowSpec() {
        assertEquals(0.5, FulfilmentModel.productivity(0.0), EPS);
        assertEquals(0.75, FulfilmentModel.productivity(0.5), EPS);
        assertEquals(1.0, FulfilmentModel.productivity(1.0), EPS);
        assertEquals(1.0, FulfilmentModel.productivity(1.8), EPS);

        long oneDay = CivilianFood.DAY_LENGTH;
        // Builds scaled by shortfall while < 0.75 and member online
        double g1 = FulfilmentModel.stepGrievance(0.0, 0.25, 0.75, 1.0, 0.5, oneDay, true);
        assertEquals(0.50, g1, EPS);
        // Paused when no member is online
        double paused = FulfilmentModel.stepGrievance(0.50, 0.0, 0.75, 1.0, 0.5, oneDay, false);
        assertEquals(0.50, paused, EPS);
        // Drains above threshold when online
        double drained = FulfilmentModel.stepGrievance(0.50, 0.90, 0.75, 1.0, 0.5, oneDay / 2, true);
        assertEquals(0.25, drained, EPS);
        // Unchanged at exact threshold
        assertEquals(0.25, FulfilmentModel.stepGrievance(0.25, 0.75, 0.75, 1.0, 0.5, oneDay, true), EPS);
    }
}
