// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Objects;

import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;

/**
 * Direct gameplay stat profile derived from a civilian's 11 underlying traits ({@link CivilianTraits}).
 *
 * <p>Covers native Minecraft 1.21.1 entity attributes, live Empire survival/sleep/fulfilment/speech mechanics, and
 * future-ready job/industry/combat multipliers. At {@link CivilianTraits#NEUTRAL}, every stat reproduces Empire's
 * baseline defaults identically. Pure rules; no Minecraft imports.
 */
public record CivilianDerivedStats(
        // Native 1.21.1 entity attributes
        double maxHealth,
        double movementSpeed,
        double jumpStrength,
        double safeFallDistance,
        double fallDamageMultiplier,
        double knockbackResistance,
        double attackDamage,
        double attackKnockback,
        double movementEfficiency,
        double waterMovementEfficiency,
        double oxygenBonus,
        double followRange,

        // Live Empire survival, sleep, fulfilment & speech
        double basalExhaustionMultiplier,
        double activityExhaustionMultiplier,
        double healingIntervalMultiplier,
        double starvationIntervalMultiplier,
        int hungerPenaltyThreshold,
        double hungerStrengthFloor,
        int sleepNeedTicks,
        float roughSleepCreditRate,
        int wakeCooldownTicks,
        double comfortTempShiftC,
        double comfortDangerMarginMultiplier,
        double communityTargetPeople,
        double socializingTargetInteractions,
        double shelterFloorPerOccupant,
        double grievanceBuildMultiplier,
        double grievanceDrainMultiplier,
        double speechCooldownMultiplier,
        double talkChance,

        // Future-ready job, Create, Encumbered & combat attributes
        double blockReach,
        double meleeReach,
        double mechanicalTorqueMultiplier,
        double mechanicalSpeedMultiplier,
        double sustainedLaborStamina,
        double carryCapacityMultiplier,
        double heavyLaborSpeedMultiplier,
        double fineWorkSpeedMultiplier,
        double coldFatiguePrecisionSensitivity,
        double fumbleRateMultiplier,
        double rangedAccuracyMultiplier,
        double cognitiveLearningMultiplier,
        double motorLearningMultiplier,
        double monotonyDrainRate,
        double reassignmentPenaltyMultiplier,
        double routingEfficiencyMultiplier,
        double teamworkSynergyMultiplier,
        double moraleAuraStrength,
        double moraleContagionSensitivity,
        double braveryScore,
        double bleedOutDurationMultiplier
) {

    public static final double BASE_MAX_HEALTH = 30.0;
    public static final double BASE_MOVEMENT_SPEED = 0.50;
    public static final double BASE_JUMP_STRENGTH = 0.42;
    public static final double BASE_SAFE_FALL_DISTANCE = 3.0;
    public static final double BASE_KNOCKBACK_RESISTANCE = 0.10;
    public static final double BASE_ATTACK_DAMAGE = 2.0;
    public static final double BASE_ATTACK_KNOCKBACK = 0.40;
    public static final double BASE_MOVEMENT_EFFICIENCY = 0.15;
    public static final double BASE_WATER_MOVEMENT_EFFICIENCY = 0.15;
    public static final double BASE_OXYGEN_BONUS = 0.50;
    public static final double BASE_FOLLOW_RANGE = 32.0;
    public static final int BASE_SLEEP_NEED_TICKS = 7_200;
    public static final float BASE_ROUGH_SLEEP_CREDIT = CivilianSleep.ROUGH_CREDIT_RATE;
    public static final int BASE_WAKE_COOLDOWN_TICKS = 200;
    public static final double BASE_TALK_CHANCE = 0.25;
    public static final double BASE_BLOCK_REACH = 3.8;
    public static final double BASE_MELEE_REACH = 2.6;

    /** Exact neutral baseline stats. */
    public static final CivilianDerivedStats NEUTRAL = from(CivilianTraits.NEUTRAL);

    /** Computes all direct derived mechanics from the 11 hidden base traits. */
    public static CivilianDerivedStats from(CivilianTraits traits) {
        Objects.requireNonNull(traits, "traits");
        double o = traits.norm(BaseTrait.OPENNESS);
        double c = traits.norm(BaseTrait.CONSCIENTIOUSNESS);
        double e = traits.norm(BaseTrait.EXTRAVERSION);
        double a = traits.norm(BaseTrait.AGREEABLENESS);
        double n = traits.norm(BaseTrait.NEUROTICISM);
        double as = traits.norm(BaseTrait.ALLOMETRIC_SCALE);
        double mr = traits.norm(BaseTrait.METABOLIC_RESERVE);
        double cf = traits.norm(BaseTrait.CONTRACTILE_FORCE);
        double ae = traits.norm(BaseTrait.AEROBIC_STAMINA);
        double ec = traits.norm(BaseTrait.ELASTIC_COMPLIANCE);
        double ms = traits.norm(BaseTrait.MOTOR_SENSORY);

        // --- Native 1.21.1 entity attributes ---
        double maxHealth = clamp(
                BASE_MAX_HEALTH + 5.0 * cf - 1.5 * as + 2.0 * (mr - 0.6 * mr * mr),
                22.0, 38.0);
        double movementSpeed = BASE_MOVEMENT_SPEED * clamp(
                1.0 + 0.06 * as - 0.07 * mr - 0.03 * cf - 0.03 * ae - 0.03 * ec,
                0.86, 1.14);
        double jumpStrength = BASE_JUMP_STRENGTH * clamp(
                1.0 + 0.06 * cf - 0.06 * ae - 0.06 * mr + 0.03 * as,
                0.88, 1.15);
        double safeFallDistance = clamp(
                BASE_SAFE_FALL_DISTANCE + 1.4 * ec + 0.5 * ms,
                2.0, 5.0);
        double fallDamageMultiplier = clamp(
                1.0 - 0.25 * ec + 0.12 * mr + 0.10 * cf + 0.08 * as - 0.08 * ms,
                0.60, 1.50);
        double knockbackResistance = clamp(
                BASE_KNOCKBACK_RESISTANCE + 0.14 * mr + 0.12 * cf - 0.08 * as - 0.06 * ec,
                0.0, 0.45);
        double attackDamage = BASE_ATTACK_DAMAGE * clamp(
                1.0 + 0.30 * cf - 0.08 * as - 0.08 * ae - 0.06 * ec,
                0.65, 1.50);
        double attackKnockback = clamp(
                BASE_ATTACK_KNOCKBACK + 0.30 * cf - 0.12 * ec - 0.10 * as,
                0.0, 1.0);
        double movementEfficiency = clamp(
                BASE_MOVEMENT_EFFICIENCY + 0.15 * ec + 0.10 * ms,
                0.0, 0.40);
        double waterMovementEfficiency = clamp(
                BASE_WATER_MOVEMENT_EFFICIENCY + 0.18 * ae - 0.06 * cf + 0.05 * mr + 0.06 * as,
                0.0, 0.45);
        double oxygenBonus = clamp(
                BASE_OXYGEN_BONUS + 1.5 * ae - 0.25 * cf,
                0.0, 2.5);
        double followRange = clamp(
                BASE_FOLLOW_RANGE + 8.0 * n + 3.0 * ms + 2.0 * o,
                20.0, 44.0);

        // --- Live Empire survival, sleep, fulfilment & speech ---
        double basalExhaustionMultiplier = clamp(
                1.0 + 0.28 * cf - 0.22 * mr + 0.05 * as,
                0.65, 1.45);
        double activityExhaustionMultiplier = clamp(
                1.0 - 0.30 * ae + 0.10 * ec + 0.06 * mr,
                0.60, 1.50);
        double healingIntervalMultiplier = clamp(
                1.0 - 0.18 * ae - 0.12 * cf + 0.10 * n + 0.15 * Math.max(0.0, mr - 0.3),
                0.65, 1.45);
        double starvationIntervalMultiplier = clamp(
                1.0 + 0.45 * mr + 0.15 * ae - 0.15 * cf,
                0.60, 1.65);
        int hungerPenaltyThreshold = Math.clamp((int) Math.round(9.0 - 3.5 * mr), 5, 13);
        double hungerStrengthFloor = clamp(0.65 + 0.10 * mr, 0.55, 0.75);

        int sleepNeedTicks = Math.clamp(
                (int) Math.round(BASE_SLEEP_NEED_TICKS * clamp(
                        1.0 - 0.18 * ae + 0.10 * cf + 0.12 * n + 0.08 * ms,
                        0.75, 1.42)),
                5_400, 10_200);
        float roughSleepCreditRate = (float) clamp(
                BASE_ROUGH_SLEEP_CREDIT + 0.07 * ec - 0.06 * n + 0.04 * mr,
                0.38, 0.62);
        int wakeCooldownTicks = Math.clamp(
                (int) Math.round(BASE_WAKE_COOLDOWN_TICKS * clamp(
                        1.0 + 0.45 * n - 0.15 * ae,
                        0.60, 1.60)),
                120, 320);

        double comfortTempShiftC = clamp(
                3.2 * as - 2.8 * mr - 0.8 * cf,
                -5.5, 5.5);
        double comfortDangerMarginMultiplier = clamp(
                1.0 + 0.15 * ae - 0.15 * n,
                0.75, 1.30);
        double communityTargetPeople = clamp(
                FulfilmentModel.COMMUNITY_TARGET_PEOPLE + 5.5 * e,
                4.0, 16.0);
        double socializingTargetInteractions = clamp(
                FulfilmentModel.SOCIALIZING_TARGET_INTERACTIONS + 3.5 * e,
                2.0, 9.0);
        double shelterFloorPerOccupant = clamp(
                FulfilmentModel.SHELTER_FLOOR_PER_OCCUPANT - 4.0 * e + 3.0 * n - 2.0 * a,
                19.0, 32.0);
        double grievanceBuildMultiplier = clamp(
                1.0 + 0.35 * n - 0.12 * a + 0.18 * Math.max(0.0, c - 0.4),
                0.60, 1.60);
        double grievanceDrainMultiplier = clamp(
                1.0 - 0.25 * n + 0.15 * a,
                0.65, 1.45);
        double speechCooldownMultiplier = clamp(
                1.0 - 0.35 * e,
                0.65, 1.60);
        double talkChance = clamp(
                BASE_TALK_CHANCE + 0.18 * e + 0.06 * a,
                0.08, 0.50);

        // --- Future-ready job, Create, Encumbered & combat attributes ---
        double blockReach = clamp(
                BASE_BLOCK_REACH + 0.55 * as + 0.15 * ec,
                3.1, 4.5);
        double meleeReach = clamp(
                BASE_MELEE_REACH + 0.40 * as + 0.10 * ec,
                2.1, 3.1);
        double mechanicalTorqueMultiplier = clamp(
                1.0 + 0.35 * cf - 0.15 * as - 0.12 * ec,
                0.55, 1.60);
        double mechanicalSpeedMultiplier = clamp(
                1.0 - 0.20 * ae + 0.12 * as + 0.12 * cf,
                0.65, 1.45);
        double sustainedLaborStamina = clamp(
                1.0 + 0.35 * ae + 0.12 * c - 0.10 * n - 0.08 * ec,
                0.55, 1.55);
        double carryCapacityMultiplier = clamp(
                1.0 + 0.38 * cf - 0.14 * as - 0.10 * ec + 0.08 * ae,
                0.55, 1.60);

        // Fitts's-Law trade-off: Coarse swings crude tools slightly faster (-0.08*ms); Calibrated excels at fine work
        // but suffers sharper cold/fatigue precision loss (+0.45*ms) and higher sleep consolidation need above.
        double heavyLaborSpeedMultiplier = clamp(
                1.0 + 0.25 * cf + 0.12 * ae - 0.10 * as - 0.08 * ms + 0.08 * c,
                0.65, 1.45);
        double fineWorkSpeedMultiplier = clamp(
                1.0 + 0.30 * ms + 0.10 * ec - 0.12 * n + 0.12 * c - 0.75 * sqExcess(c, 0.55),
                0.60, 1.45);
        double coldFatiguePrecisionSensitivity = clamp(
                1.0 + 0.45 * ms,
                0.55, 1.45);
        double fumbleRateMultiplier = clamp(
                1.0 - 0.40 * ms - 0.25 * c + 0.20 * Math.max(0.0, n) + 0.18 * Math.max(0.0, e - 0.5),
                0.15, 1.90);
        double rangedAccuracyMultiplier = clamp(
                1.0 + 0.35 * ms - 0.15 * n + 0.10 * c,
                0.60, 1.50);

        double cognitiveLearningMultiplier = clamp(
                1.0 + 0.30 * o - 1.20 * sqExcess(o, 0.60) + 0.12 * c,
                0.65, 1.45);
        double motorLearningMultiplier = clamp(
                1.0 + 0.30 * ms + 0.12 * o,
                0.65, 1.45);
        double monotonyDrainRate = clamp(0.5 + 0.5 * o, 0.0, 1.0);
        double reassignmentPenaltyMultiplier = clamp(
                1.0 - 0.35 * o + 0.30 * Math.max(0.0, c - 0.4),
                0.55, 1.65);
        double routingEfficiencyMultiplier = clamp(
                1.0 + 0.30 * c - 1.20 * sqExcess(c, 0.55) + 0.10 * o,
                0.65, 1.40);
        double teamworkSynergyMultiplier = clamp(
                1.0 + 0.25 * a + 0.18 * e - 1.00 * sqExcess(a, 0.55) - 0.90 * sqExcess(e, 0.55) - 0.10 * n,
                0.65, 1.40);
        double moraleAuraStrength = clamp(0.50 * a + 0.35 * e - 0.15 * n, -1.0, 1.0);
        double moraleContagionSensitivity = clamp(
                1.0 + 0.35 * a + 0.35 * n,
                0.40, 1.70);
        double braveryScore = clamp(
                0.50 - 0.35 * n + 0.18 * e - 0.15 * a + 0.12 * cf,
                0.05, 0.95);
        double bleedOutDurationMultiplier = clamp(
                1.0 + 0.25 * ae + 0.15 * cf + 0.12 * mr - 0.15 * n,
                0.60, 1.55);

        return new CivilianDerivedStats(
                maxHealth, movementSpeed, jumpStrength, safeFallDistance, fallDamageMultiplier,
                knockbackResistance, attackDamage, attackKnockback, movementEfficiency,
                waterMovementEfficiency, oxygenBonus, followRange,
                basalExhaustionMultiplier, activityExhaustionMultiplier, healingIntervalMultiplier,
                starvationIntervalMultiplier, hungerPenaltyThreshold, hungerStrengthFloor,
                sleepNeedTicks, roughSleepCreditRate, wakeCooldownTicks,
                comfortTempShiftC, comfortDangerMarginMultiplier,
                communityTargetPeople, socializingTargetInteractions, shelterFloorPerOccupant,
                grievanceBuildMultiplier, grievanceDrainMultiplier,
                speechCooldownMultiplier, talkChance,
                blockReach, meleeReach, mechanicalTorqueMultiplier, mechanicalSpeedMultiplier,
                sustainedLaborStamina, carryCapacityMultiplier,
                heavyLaborSpeedMultiplier, fineWorkSpeedMultiplier, coldFatiguePrecisionSensitivity,
                fumbleRateMultiplier, rangedAccuracyMultiplier,
                cognitiveLearningMultiplier, motorLearningMultiplier, monotonyDrainRate,
                reassignmentPenaltyMultiplier, routingEfficiencyMultiplier, teamworkSynergyMultiplier,
                moraleAuraStrength, moraleContagionSensitivity, braveryScore, bleedOutDurationMultiplier
        );
    }

    /**
     * Physical strength/output multiplier for the given food level (0..20). Full {@code 1.0} at or above
     * {@link #hungerPenaltyThreshold}, dropping linearly to {@link #hungerStrengthFloor} at food {@code 0}.
     */
    public double hungerStrengthFactor(int foodLevel) {
        if (foodLevel >= hungerPenaltyThreshold) {
            return 1.0;
        }
        if (foodLevel <= 0) {
            return hungerStrengthFloor;
        }
        double frac = foodLevel / (double) hungerPenaltyThreshold;
        return hungerStrengthFloor + (1.0 - hungerStrengthFloor) * frac;
    }

    /** Effective lower comfort temperature bound (°C) shifted by {@link #comfortTempShiftC}. */
    public double comfortMinC(double baseMinC) {
        return baseMinC + comfortTempShiftC;
    }

    /** Effective upper comfort temperature bound (°C) shifted by {@link #comfortTempShiftC}. */
    public double comfortMaxC(double baseMaxC) {
        return baseMaxC + comfortTempShiftC;
    }

    /** Effective danger margin (°C) beyond either comfort bound. */
    public double comfortDangerMarginC(double baseDangerMarginC) {
        return baseDangerMarginC * comfortDangerMarginMultiplier;
    }

    private static double sqExcess(double value, double threshold) {
        double excess = Math.max(0.0, value - threshold);
        return excess * excess;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
