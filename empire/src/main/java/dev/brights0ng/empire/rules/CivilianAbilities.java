// Written by ChatGPT.
package dev.brights0ng.empire.rules;

import java.util.Locale;
import java.util.random.RandomGenerator;

/** Rolled raw ability scores, packed in declaration order and independent of gameplay effects. */
public record CivilianAbilities(int endurance, int precision, int willpower, int wits, int organization, int teamwork) {
    public static final double REROLL_MIN_MEAN = 8.0;
    public static final double REROLL_MAX_MEAN = 13.0;
    public static final int MAX_REROLLS = 1000;

    public enum Ability {
        ENDURANCE, PRECISION, WILLPOWER, WITS, ORGANIZATION, TEAMWORK;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public CivilianAbilities {
        for (int value : new int[]{endurance, precision, willpower, wits, organization, teamwork}) {
            if (value < 3 || value > 18) {
                throw new IllegalArgumentException("ability score out of 3..18: " + value);
            }
        }
    }

    public int raw(Ability ability) {
        return switch (ability) {
            case ENDURANCE -> endurance;
            case PRECISION -> precision;
            case WILLPOWER -> willpower;
            case WITS -> wits;
            case ORGANIZATION -> organization;
            case TEAMWORK -> teamwork;
        };
    }

    public double normalized(Ability ability) {
        return (raw(ability) - 3) / 15.0;
    }

    public double mean() {
        return (endurance + precision + willpower + wits + organization + teamwork) / 6.0;
    }

    private static CivilianAbilities of(int[] values) {
        return new CivilianAbilities(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    /** Initial roll plus at most MAX_REROLLS whole-set retries; keeps the last set at the cap. */
    public static CivilianAbilities roll(RandomGenerator random) {
        CivilianAbilities result;
        int rerolls = 0;
        do {
            int[] values = new int[Ability.values().length];
            for (int i = 0; i < values.length; i++) {
                values[i] = random.nextInt(6) + random.nextInt(6) + random.nextInt(6) + 3;
            }
            result = of(values);
        } while ((result.mean() < REROLL_MIN_MEAN || result.mean() > REROLL_MAX_MEAN) && rerolls++ < MAX_REROLLS);
        return result;
    }

    public int pack() {
        int packed = 0;
        for (Ability ability : Ability.values()) {
            packed |= (raw(ability) - 3) << (4 * ability.ordinal());
        }
        return packed;
    }

    public static CivilianAbilities unpack(int packed) {
        if ((packed & 0xff000000) != 0) {
            throw new IllegalArgumentException("ability bits outside the packed 24 bits: " + packed);
        }
        int[] values = new int[Ability.values().length];
        for (int i = 0; i < values.length; i++) {
            values[i] = ((packed >>> (4 * i)) & 15) + 3;
        }
        return of(values);
    }

    public String code() {
        return endurance + "-" + precision + "-" + willpower + "-" + wits + "-" + organization + "-" + teamwork;
    }

    public static CivilianAbilities parse(String code) {
        if (code == null || !code.matches("[0-9]+(?:-[0-9]+){5}")) {
            throw new IllegalArgumentException("ability code must contain six raw scores separated by hyphens: " + code);
        }
        String[] parts = code.split("-");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i]);
        }
        return of(values);
    }
}
