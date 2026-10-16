package dev.brights0ng.enginesandempires.food;

/**
 * Some of the units in a food stack that share a freshness: {@code count} of them, whose age is counted from game tick
 * {@code born}, and whether they are {@code frozen} (food by temperature, 2026-10-15: frozen food nearly stops aging,
 * and thawing costs half a stage; see {@link FoodClimate}). A stack holds one cohort per stage and frozen state at most
 * (see {@link FoodFreshness#settle}).
 *
 * <p>Plain Java on purpose (no Minecraft types), so the stacking rules can be unit tested. The codecs live in
 * {@link FoodCodecs}.
 */
public record Cohort(long born, int count, boolean frozen) {

    /** Unfrozen units. */
    public Cohort(long born, int count) {
        this(born, count, false);
    }

    public Cohort withCount(int newCount) {
        return new Cohort(born, newCount, frozen);
    }

    public Cohort withBorn(long newBorn) {
        return new Cohort(newBorn, count, frozen);
    }
}
