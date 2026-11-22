// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Plain-English folk quirks surfacing extreme low or high poles of the 11 hidden base traits
 * ({@link CivilianTraits.BaseTrait}), one quirk per extreme (22 total). Pure rules; no Minecraft imports.
 */
public enum CivilianQuirk {
    // Big Five (Personality) — 10 quirks
    CREATURE_OF_HABIT("creature_of_habit", "Creature of Habit", CivilianTraits.BaseTrait.OPENNESS, Pole.LOW),
    DAYDREAMER("daydreamer", "Daydreamer", CivilianTraits.BaseTrait.OPENNESS, Pole.HIGH),
    FREEWHEELING("freewheeling", "Freewheeling", CivilianTraits.BaseTrait.CONSCIENTIOUSNESS, Pole.LOW),
    PERFECTIONIST("perfectionist", "Perfectionist", CivilianTraits.BaseTrait.CONSCIENTIOUSNESS, Pole.HIGH),
    SOLITARY("solitary", "Solitary", CivilianTraits.BaseTrait.EXTRAVERSION, Pole.LOW),
    CHATTERBOX("chatterbox", "Chatterbox", CivilianTraits.BaseTrait.EXTRAVERSION, Pole.HIGH),
    CANTANKEROUS("cantankerous", "Cantankerous", CivilianTraits.BaseTrait.AGREEABLENESS, Pole.LOW),
    SOFTHEARTED("softhearted", "Softhearted", CivilianTraits.BaseTrait.AGREEABLENESS, Pole.HIGH),
    UNFLAPPABLE("unflappable", "Unflappable", CivilianTraits.BaseTrait.NEUROTICISM, Pole.LOW),
    HIGH_STRUNG("high_strung", "High-Strung", CivilianTraits.BaseTrait.NEUROTICISM, Pole.HIGH),

    // Orthogonality (Physique) — 12 quirks
    STOCKY("stocky", "Stocky", CivilianTraits.BaseTrait.ALLOMETRIC_SCALE, Pole.LOW),
    LANKY("lanky", "Lanky", CivilianTraits.BaseTrait.ALLOMETRIC_SCALE, Pole.HIGH),
    LEAN("lean", "Lean", CivilianTraits.BaseTrait.METABOLIC_RESERVE, Pole.LOW),
    STOUT("stout", "Stout", CivilianTraits.BaseTrait.METABOLIC_RESERVE, Pole.HIGH),
    SLIGHT("slight", "Slight", CivilianTraits.BaseTrait.CONTRACTILE_FORCE, Pole.LOW),
    BRAWNY("brawny", "Brawny", CivilianTraits.BaseTrait.CONTRACTILE_FORCE, Pole.HIGH),
    EXPLOSIVE("explosive", "Explosive", CivilianTraits.BaseTrait.AEROBIC_STAMINA, Pole.LOW),
    TIRELESS("tireless", "Tireless", CivilianTraits.BaseTrait.AEROBIC_STAMINA, Pole.HIGH),
    TAUT("taut", "Taut", CivilianTraits.BaseTrait.ELASTIC_COMPLIANCE, Pole.LOW),
    LIMBER("limber", "Limber", CivilianTraits.BaseTrait.ELASTIC_COMPLIANCE, Pole.HIGH),
    HEAVY_HANDED("heavy_handed", "Heavy-Handed", CivilianTraits.BaseTrait.MOTOR_SENSORY, Pole.LOW),
    DEFT("deft", "Deft", CivilianTraits.BaseTrait.MOTOR_SENSORY, Pole.HIGH);

    public enum Pole {
        LOW, HIGH
    }

    private static final Map<CivilianTraits.BaseTrait, CivilianQuirk> LOW_BY_TRAIT =
            new EnumMap<>(CivilianTraits.BaseTrait.class);
    private static final Map<CivilianTraits.BaseTrait, CivilianQuirk> HIGH_BY_TRAIT =
            new EnumMap<>(CivilianTraits.BaseTrait.class);

    static {
        for (CivilianQuirk quirk : values()) {
            Map<CivilianTraits.BaseTrait, CivilianQuirk> target = quirk.pole == Pole.LOW ? LOW_BY_TRAIT : HIGH_BY_TRAIT;
            if (target.put(quirk.trait, quirk) != null) {
                throw new IllegalStateException("Duplicate quirk for " + quirk.trait + " " + quirk.pole);
            }
        }
    }

    private final String id;
    private final String displayName;
    private final CivilianTraits.BaseTrait trait;
    private final Pole pole;

    CivilianQuirk(String id, String displayName, CivilianTraits.BaseTrait trait, Pole pole) {
        this.id = id;
        this.displayName = displayName;
        this.trait = trait;
        this.pole = pole;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public CivilianTraits.BaseTrait trait() {
        return trait;
    }

    public Pole pole() {
        return pole;
    }

    public String langKey() {
        return "civilian.empire.quirk." + id;
    }

    /** Returns the quirk for {@code trait}'s high ({@code high == true}) or low ({@code high == false}) extreme. */
    public static CivilianQuirk forPole(CivilianTraits.BaseTrait trait, boolean high) {
        Objects.requireNonNull(trait, "trait");
        CivilianQuirk quirk = (high ? HIGH_BY_TRAIT : LOW_BY_TRAIT).get(trait);
        if (quirk == null) {
            throw new IllegalStateException("No quirk registered for " + trait + " high=" + high);
        }
        return quirk;
    }
}
