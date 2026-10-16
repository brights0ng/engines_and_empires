package dev.brights0ng.enginesandempires.food;

import java.util.ArrayList;
import java.util.List;

/**
 * Food spoilage by temperature (Bright, 2026-10-15; {@code claude/food-temperature.md}): how fast food ages where it is,
 * and freezing and thawing. Pure maths, unit tested.
 *
 * <ul>
 *   <li><b>Rate:</b> doubles every {@code doubling} C above the reference (20 C: the tuned stage lengths) and halves
 *       every as many below: 2^((T - 20) / 10).</li>
 *   <li><b>Frozen</b> food ages at a flat {@code frozenRate} (0.05).</li>
 *   <li><b>Freezing has a margin:</b> food freezes once below {@code freezeBelow} (-2 C) and thaws only once above
 *       {@code thawAbove} (+2 C); between, it keeps its state.</li>
 *   <li><b>A thaw</b> ages it by {@code thawPenaltyStages} of a stage (half), once.</li>
 * </ul>
 * Food ages by the global clock (its cohorts' birth times); a place's rate is applied by moving birth times: ticks spent
 * at rate r move birth by (1 - r) ticks per tick, later for slow places (never past now), earlier for hot ones.
 */
public final class FoodClimate {

    /**
     * The rules.
     *
     * @param reference        the temperature food ages at the tuned speed, C
     * @param doubling         how many C warmer doubles the speed
     * @param frozenRate       the speed of frozen food
     * @param freezeBelow      food freezes below this, C
     * @param thawAbove        frozen food thaws above this, C
     * @param thawPenalty      the share of a stage a thaw ages food by
     */
    public record Rules(double reference, double doubling, double frozenRate, double freezeBelow, double thawAbove,
                        double thawPenalty) {

        public static final Rules DEFAULTS = new Rules(20, 10, 0.05, -2, 2, 0.5);

        /** How fast unfrozen food ages at {@code celsius}, relative to the tuned speed. */
        public double rate(double celsius) {
            return Math.pow(2, (celsius - reference) / doubling);
        }

        /** Whether food that {@code wasFrozen} is frozen at {@code celsius} (with the margin). */
        public boolean frozenAt(double celsius, boolean wasFrozen) {
            return celsius < freezeBelow || (wasFrozen && celsius <= thawAbove);
        }
    }

    /**
     * Food that spent {@code ticks} at {@code celsius} up to {@code now}: frozen or thawed by the margin (a thaw costs
     * its penalty), then aged at the rate of its state. {@code forceFrozen}: kept frozen whatever the temperature (the
     * ice chest). A temperature that isn't finite leaves the food as it is.
     */
    public static FoodFreshness apply(FoodFreshness food, long now, long ticks, double celsius, boolean forceFrozen,
                                      Rules rules, SpoilTimes times) {
        if (food.isEmpty() || (!forceFrozen && !Double.isFinite(celsius))) {
            return food;
        }
        long penalty = Math.round(rules.thawPenalty() * stageLength(times));
        List<Cohort> out = new ArrayList<>(food.cohorts().size());
        for (Cohort c : food.cohorts()) {
            boolean frozen = forceFrozen || rules.frozenAt(celsius, c.frozen());
            long born = c.born();
            if (c.frozen() && !frozen) {
                born -= penalty;
            }
            if (ticks > 0) {
                double rate = frozen ? rules.frozenRate() : rules.rate(celsius);
                born += Math.round((1 - rate) * ticks);
            }
            out.add(new Cohort(Math.min(now, born), c.count(), frozen));
        }
        return new FoodFreshness(out, food.top()).settle(now, times);
    }

    /** A stage's length for the thaw penalty: the average of the three timed stages. */
    static long stageLength(SpoilTimes times) {
        return (times.freshTicks() + times.ripeTicks() + times.staleTicks()) / 3;
    }

    private FoodClimate() {
    }
}
