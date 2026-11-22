// Written by ChatGPT; expanded to 7 derived scores from CivilianTraits by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Locale;
import java.util.Objects;
import java.util.random.RandomGenerator;

import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;

/**
 * Player-facing civilian ability scores (7 scores on 3..18: 3 Body + 4 Mind), derived monotonically from the 11 hidden
 * base traits in {@link CivilianTraits}. Packed into 28 bits in declaration order. Pure rules; no Minecraft imports.
 */
public record CivilianAbilities(
        int brawn,
        int endurance,
        int precision,
        int resolve,
        int wits,
        int organization,
        int teamwork) {

    public static final int MIN_SCORE = 3;
    public static final int MAX_SCORE = 18;
    private static final double SCORE_MIDPOINT = 10.5;
    /** Scale applied to L2-normalized composite traits so derived scores match a ~3d6 spread (sigma ~2.8). */
    private static final double SCORE_SCALE = 7.0;

    public enum Ability {
        BRAWN("BRA"),
        ENDURANCE("END"),
        PRECISION("PRE"),
        RESOLVE("RES"),
        WITS("WIT"),
        ORGANIZATION("ORG"),
        TEAMWORK("TEA");

        private final String shortLabel;

        Ability(String shortLabel) {
            this.shortLabel = shortLabel;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String shortLabel() {
            return shortLabel;
        }

        public String langKey() {
            return "civilian.empire.ability." + id();
        }
    }

    public CivilianAbilities {
        for (int value : new int[]{brawn, endurance, precision, resolve, wits, organization, teamwork}) {
            if (value < MIN_SCORE || value > MAX_SCORE) {
                throw new IllegalArgumentException("ability score out of 3..18: " + value);
            }
        }
    }

    /** Derives the 7 player-facing scores monotonically from {@code traits} using L2-normalized linear projections. */
    public static CivilianAbilities derive(CivilianTraits traits) {
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

        int brawn = project(new double[]{0.65, -0.20, -0.15}, new double[]{cf, as, ec});
        int endurance = project(new double[]{0.55, 0.25, 0.20}, new double[]{ae, mr, c});
        int precision = project(new double[]{0.65, 0.15, 0.10, -0.10}, new double[]{ms, ec, c, n});
        int resolve = project(new double[]{-0.65, 0.20, 0.15}, new double[]{n, c, e});
        int wits = project(new double[]{0.65, 0.20, 0.15}, new double[]{o, ms, c});
        int organization = project(new double[]{0.75, 0.15, 0.10}, new double[]{c, o, ms});
        int teamwork = project(new double[]{0.55, 0.35, -0.10}, new double[]{a, e, n});
        return new CivilianAbilities(brawn, endurance, precision, resolve, wits, organization, teamwork);
    }

    private static int project(double[] weights, double[] norms) {
        double sum = 0.0;
        double normSq = 0.0;
        for (int i = 0; i < weights.length; i++) {
            sum += weights[i] * norms[i];
            normSq += weights[i] * weights[i];
        }
        double z = sum / Math.sqrt(normSq);
        return Math.clamp((int) Math.round(SCORE_MIDPOINT + SCORE_SCALE * z), MIN_SCORE, MAX_SCORE);
    }

    public int raw(Ability ability) {
        return switch (ability) {
            case BRAWN -> brawn;
            case ENDURANCE -> endurance;
            case PRECISION -> precision;
            case RESOLVE -> resolve;
            case WITS -> wits;
            case ORGANIZATION -> organization;
            case TEAMWORK -> teamwork;
        };
    }

    public double normalized(Ability ability) {
        return (raw(ability) - MIN_SCORE) / (double) (MAX_SCORE - MIN_SCORE);
    }

    public double mean() {
        return (brawn + endurance + precision + resolve + wits + organization + teamwork) / 7.0;
    }

    private static CivilianAbilities of(int[] values) {
        return new CivilianAbilities(
                values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
    }

    /** Rolls a fresh {@link CivilianTraits} and returns its derived scores. */
    public static CivilianAbilities roll(RandomGenerator random) {
        return CivilianTraits.roll(random).abilities();
    }

    public int pack() {
        int packed = 0;
        for (Ability ability : Ability.values()) {
            packed |= (raw(ability) - MIN_SCORE) << (4 * ability.ordinal());
        }
        return packed;
    }

    public static CivilianAbilities unpack(int packed) {
        if ((packed & 0xf0000000) != 0) {
            throw new IllegalArgumentException("ability bits outside the packed 28 bits: " + packed);
        }
        int[] values = new int[Ability.values().length];
        for (int i = 0; i < values.length; i++) {
            values[i] = ((packed >>> (4 * i)) & 15) + MIN_SCORE;
        }
        return of(values);
    }

    public String code() {
        return brawn + "-" + endurance + "-" + precision + "-" + resolve + "-" + wits + "-" + organization + "-" + teamwork;
    }

    public static CivilianAbilities parse(String code) {
        if (code == null || !code.matches("[0-9]+(?:-[0-9]+){6}")) {
            throw new IllegalArgumentException("ability code must contain seven raw scores separated by hyphens: " + code);
        }
        String[] parts = code.split("-");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i]);
        }
        return of(values);
    }
}
