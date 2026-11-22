// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Underlying civilian character traits: 5 Big Five personality axes and 6 Orthogonality physical axes, each quantized
 * on {@code [-100, +100]} with exact neutral center {@code 0}.
 *
 * <p>Gameplay mechanics read directly from {@link CivilianDerivedStats#from(CivilianTraits)}, while players see the 7
 * derived summary scores ({@link CivilianAbilities}) and any plain-English {@link CivilianQuirk}s triggered at extreme
 * poles ({@code |raw| >= QUIRK_THRESHOLD}). Pure rules; no Minecraft imports.
 */
public final class CivilianTraits {

    public static final int MIN_VALUE = -100;
    public static final int MAX_VALUE = 100;
    /** PROVISIONAL: standard deviation of each independent Gaussian base trait roll on [-100, +100]. */
    public static final double ROLL_STD_DEV = 40.0;
    /** PROVISIONAL: absolute raw threshold at or above which a trait emits its folk quirk (~13.4% per trait). */
    public static final int QUIRK_THRESHOLD = 60;
    /** PROVISIONAL: derived 7-ability mean bounds for whole-set rerolls. */
    public static final double REROLL_MIN_ABILITY_MEAN = 8.5;
    public static final double REROLL_MAX_ABILITY_MEAN = 12.5;
    /** PROVISIONAL: maximum simultaneous quirks allowed on a rolled civilian before rerolling. */
    public static final int MAX_ROLLED_QUIRKS = 4;
    /** Maximum whole-set retries after the initial roll; keeps the last set at the cap. */
    public static final int MAX_REROLLS = 1000;

    public enum Category {
        PERSONALITY,
        ORTHOGONALITY
    }

    /** The 11 hidden base trait axes in canonical storage order (5 Big Five, then 6 Orthogonality). */
    public enum BaseTrait {
        OPENNESS("openness", "O", Category.PERSONALITY),
        CONSCIENTIOUSNESS("conscientiousness", "C", Category.PERSONALITY),
        EXTRAVERSION("extraversion", "E", Category.PERSONALITY),
        AGREEABLENESS("agreeableness", "A", Category.PERSONALITY),
        NEUROTICISM("neuroticism", "N", Category.PERSONALITY),
        ALLOMETRIC_SCALE("allometric_scale", "AS", Category.ORTHOGONALITY),
        METABOLIC_RESERVE("metabolic_reserve", "MR", Category.ORTHOGONALITY),
        CONTRACTILE_FORCE("contractile_force", "CF", Category.ORTHOGONALITY),
        AEROBIC_STAMINA("aerobic_stamina", "AE", Category.ORTHOGONALITY),
        ELASTIC_COMPLIANCE("elastic_compliance", "EC", Category.ORTHOGONALITY),
        MOTOR_SENSORY("motor_sensory", "MS", Category.ORTHOGONALITY);

        private final String id;
        private final String shortLabel;
        private final Category category;

        BaseTrait(String id, String shortLabel, Category category) {
            this.id = id;
            this.shortLabel = shortLabel;
            this.category = category;
        }

        public String id() {
            return id;
        }

        public String shortLabel() {
            return shortLabel;
        }

        public Category category() {
            return category;
        }

        public static BaseTrait byId(String id) {
            if (id == null) {
                return null;
            }
            String lower = id.toLowerCase(Locale.ROOT);
            for (BaseTrait trait : values()) {
                if (trait.id.equals(lower) || trait.shortLabel.equalsIgnoreCase(lower)) {
                    return trait;
                }
            }
            return null;
        }
    }

    public static final int TRAIT_COUNT = BaseTrait.values().length;

    /** Exact neutral baseline (all 11 axes at 0), matching every pre-traits Empire default. */
    public static final CivilianTraits NEUTRAL = new CivilianTraits(new int[TRAIT_COUNT]);

    private final int[] values;

    private CivilianTraits(int[] validatedCopy) {
        this.values = validatedCopy;
    }

    public static CivilianTraits of(
            int openness,
            int conscientiousness,
            int extraversion,
            int agreeableness,
            int neuroticism,
            int allometricScale,
            int metabolicReserve,
            int contractileForce,
            int aerobicStamina,
            int elasticCompliance,
            int motorSensory) {
        return of(new int[]{
                openness, conscientiousness, extraversion, agreeableness, neuroticism,
                allometricScale, metabolicReserve, contractileForce, aerobicStamina, elasticCompliance, motorSensory
        });
    }

    public static CivilianTraits of(int[] rawValues) {
        Objects.requireNonNull(rawValues, "rawValues");
        if (rawValues.length != TRAIT_COUNT) {
            throw new IllegalArgumentException("Expected " + TRAIT_COUNT + " base traits, got " + rawValues.length);
        }
        int[] copy = new int[TRAIT_COUNT];
        for (int i = 0; i < TRAIT_COUNT; i++) {
            int v = rawValues[i];
            if (v < MIN_VALUE || v > MAX_VALUE) {
                throw new IllegalArgumentException(
                        BaseTrait.values()[i].id() + " out of " + MIN_VALUE + ".." + MAX_VALUE + ": " + v);
            }
            copy[i] = v;
        }
        return new CivilianTraits(copy);
    }

    /** Raw integer score on [-100, +100]. */
    public int raw(BaseTrait trait) {
        return values[Objects.requireNonNull(trait, "trait").ordinal()];
    }

    /** Normalized bipolar score on [-1.0, +1.0], with 0.0 at neutral. */
    public double norm(BaseTrait trait) {
        return raw(trait) / 100.0;
    }

    /** Returns a copy with {@code trait} replaced by {@code rawValue} (validated to [-100, +100]). */
    public CivilianTraits with(BaseTrait trait, int rawValue) {
        Objects.requireNonNull(trait, "trait");
        if (rawValue < MIN_VALUE || rawValue > MAX_VALUE) {
            throw new IllegalArgumentException(trait.id() + " out of " + MIN_VALUE + ".." + MAX_VALUE + ": " + rawValue);
        }
        int[] copy = values.clone();
        copy[trait.ordinal()] = rawValue;
        return new CivilianTraits(copy);
    }

    /** Defensive copy of the 11 raw values in {@link BaseTrait} order. */
    public int[] toArray() {
        return values.clone();
    }

    /** Derives the 7 player-facing ability scores (3–18). */
    public CivilianAbilities abilities() {
        return CivilianAbilities.derive(this);
    }

    /** Derives the full mechanical stat profile directly from these 11 axes. */
    public CivilianDerivedStats derivedStats() {
        return CivilianDerivedStats.from(this);
    }

    /**
     * Returns all folk quirks triggered at {@code |raw| >= QUIRK_THRESHOLD}, ordered from strongest magnitude to
     * weakest (ties broken by {@link BaseTrait} declaration order).
     */
    public List<CivilianQuirk> quirks() {
        List<BaseTrait> triggered = new ArrayList<>();
        for (BaseTrait trait : BaseTrait.values()) {
            if (Math.abs(raw(trait)) >= QUIRK_THRESHOLD) {
                triggered.add(trait);
            }
        }
        if (triggered.isEmpty()) {
            return List.of();
        }
        triggered.sort(Comparator.<BaseTrait>comparingInt(t -> Math.abs(raw(t))).reversed()
                .thenComparingInt(Enum::ordinal));
        List<CivilianQuirk> result = new ArrayList<>(triggered.size());
        for (BaseTrait trait : triggered) {
            result.add(CivilianQuirk.forPole(trait, raw(trait) > 0));
        }
        return Collections.unmodifiableList(result);
    }

    /** Number of quirks triggered by this trait set. */
    public int quirkCount() {
        int count = 0;
        for (int v : values) {
            if (Math.abs(v) >= QUIRK_THRESHOLD) {
                count++;
            }
        }
        return count;
    }

    /**
     * Rolls a bell-curved set of 11 traits using {@code Gaussian(0, ROLL_STD_DEV)} clamped to {@code [-100, +100]}.
     * Retries up to {@link #MAX_REROLLS} times until the derived ability mean is within
     * {@code [REROLL_MIN_ABILITY_MEAN, REROLL_MAX_ABILITY_MEAN]} and quirk count is at most {@link #MAX_ROLLED_QUIRKS}.
     */
    public static CivilianTraits roll(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        CivilianTraits candidate;
        int rerolls = 0;
        do {
            int[] rolled = new int[TRAIT_COUNT];
            for (int i = 0; i < TRAIT_COUNT; i++) {
                double g = random.nextGaussian() * ROLL_STD_DEV;
                rolled[i] = Math.clamp((int) Math.round(g), MIN_VALUE, MAX_VALUE);
            }
            candidate = new CivilianTraits(rolled);
        } while (!isBalancedRoll(candidate) && rerolls++ < MAX_REROLLS);
        return candidate;
    }

    private static boolean isBalancedRoll(CivilianTraits candidate) {
        if (candidate.quirkCount() > MAX_ROLLED_QUIRKS) {
            return false;
        }
        double mean = candidate.abilities().mean();
        return mean >= REROLL_MIN_ABILITY_MEAN && mean <= REROLL_MAX_ABILITY_MEAN;
    }

    /** Deterministic fallback traits seeded from a civilian UUID (used for migrating legacy records). */
    public static CivilianTraits defaultFor(UUID id) {
        Objects.requireNonNull(id, "id");
        long seed = id.getMostSignificantBits() ^ (id.getLeastSignificantBits() * 31L) ^ 0x7A17_5EEDL;
        return roll(new Random(seed));
    }

    /** Compact comma-separated representation of all 11 signed raw values in {@link BaseTrait} order. */
    public String code() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TRAIT_COUNT; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    /** Parses a comma-separated 11-integer code produced by {@link #code()}. */
    public static CivilianTraits parse(String code) {
        if (code == null || !code.matches("-?[0-9]+(?:,-?[0-9]+){10}")) {
            throw new IllegalArgumentException("trait code must contain 11 signed integers separated by commas: " + code);
        }
        String[] parts = code.split(",");
        int[] parsed = new int[TRAIT_COUNT];
        for (int i = 0; i < TRAIT_COUNT; i++) {
            parsed[i] = Integer.parseInt(parts[i]);
        }
        return of(parsed);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof CivilianTraits other && Arrays.equals(values, other.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CivilianTraits[");
        BaseTrait[] traits = BaseTrait.values();
        for (int i = 0; i < TRAIT_COUNT; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(traits[i].shortLabel()).append('=').append(values[i]);
        }
        return sb.append(']').toString();
    }
}
