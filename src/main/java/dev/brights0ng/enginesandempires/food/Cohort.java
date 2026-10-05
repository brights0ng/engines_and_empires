package dev.brights0ng.enginesandempires.food;

/**
 * Some of the units in a food stack that share a freshness: {@code count} of them, whose age is counted from game tick
 * {@code born}. A stack holds one cohort per freshness stage at most (see {@link FoodFreshness#settle}).
 *
 * <p>Plain Java on purpose (no Minecraft types), so the stacking rules can be unit tested. The codecs live in
 * {@link FoodCodecs}.
 */
public record Cohort(long born, int count) {

    public Cohort withCount(int newCount) {
        return new Cohort(born, newCount);
    }
}
