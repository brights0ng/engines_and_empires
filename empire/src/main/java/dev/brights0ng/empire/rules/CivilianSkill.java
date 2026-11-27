// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;

/**
 * Cross-job occupational skill domains tied to reusable atomic actions (Bright, 2026-11-27; zero Minecraft imports).
 *
 * <p>Each skill defines its motor vs. cognitive learning blend (feeding {@link CivilianDerivedStats#motorLearningMultiplier()}
 * and {@link CivilianDerivedStats#cognitiveLearningMultiplier()}) and how strongly a civilian's 11 base traits bias
 * starting experience when rolling travellers or migrating legacy records.
 */
public enum CivilianSkill {
    HARVESTING("harvesting", "Harvesting", 0.80, 0.20),
    CULTIVATION("cultivation", "Cultivation", 0.75, 0.25),
    LOGISTICS("logistics", "Logistics", 0.30, 0.70),
    EXCAVATION("excavation", "Excavation", 0.85, 0.15),
    CONSTRUCTION("construction", "Construction", 0.55, 0.45);

    private final String id;
    private final String displayName;
    private final double motorWeight;
    private final double cognitiveWeight;

    CivilianSkill(String id, String displayName, double motorWeight, double cognitiveWeight) {
        this.id = id;
        this.displayName = displayName;
        this.motorWeight = motorWeight;
        this.cognitiveWeight = cognitiveWeight;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Translation key for the player-facing skill name (e.g. {@code skill.empire.harvesting}). */
    public String langKey() {
        return "skill.empire." + id;
    }

    public double motorWeight() {
        return motorWeight;
    }

    public double cognitiveWeight() {
        return cognitiveWeight;
    }

    /** Blended trait learning multiplier from {@code motorLearningMultiplier} and {@code cognitiveLearningMultiplier}. */
    public double learningMultiplier(CivilianDerivedStats stats) {
        Objects.requireNonNull(stats, "stats");
        return motorWeight * stats.motorLearningMultiplier()
                + cognitiveWeight * stats.cognitiveLearningMultiplier();
    }

    /**
     * Trait-based starting affinity in {@code [-1.0, +1.0]} used to bias which skills and starting levels newly
     * arriving travellers (and migrated legacy citizens) roll.
     */
    public double startingAffinity(CivilianTraits traits) {
        Objects.requireNonNull(traits, "traits");
        double o = traits.norm(BaseTrait.OPENNESS);
        double c = traits.norm(BaseTrait.CONSCIENTIOUSNESS);
        double as = traits.norm(BaseTrait.ALLOMETRIC_SCALE);
        double cf = traits.norm(BaseTrait.CONTRACTILE_FORCE);
        double ae = traits.norm(BaseTrait.AEROBIC_STAMINA);
        double ms = traits.norm(BaseTrait.MOTOR_SENSORY);

        double raw = switch (this) {
            case HARVESTING -> 0.40 * ms + 0.30 * ae + 0.20 * cf + 0.10 * c;
            case CULTIVATION -> 0.35 * ms + 0.30 * c + 0.20 * ae + 0.15 * o;
            case LOGISTICS -> 0.50 * c + 0.25 * o + 0.15 * ms + 0.10 * ae;
            case EXCAVATION -> 0.45 * cf + 0.35 * ae - 0.10 * as + 0.10 * c;
            case CONSTRUCTION -> 0.35 * ms + 0.30 * c + 0.20 * cf + 0.15 * o;
        };
        return Math.max(-1.0, Math.min(1.0, raw));
    }

    @Nullable
    public static CivilianSkill byId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (CivilianSkill skill : values()) {
            if (skill.id.equals(lower) || skill.name().equalsIgnoreCase(lower)) {
                return skill;
            }
        }
        return null;
    }
}
