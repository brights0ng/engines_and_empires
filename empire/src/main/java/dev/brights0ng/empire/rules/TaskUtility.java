// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Pure Level-2 utility task scorer ({@code docs/empire/empire-plan-brainstorming.md} §4.2, zero Minecraft imports).
 *
 * <p>Every candidate Task within an active Slot scores:
 * {@code Gate * (BaseWeight * Urgency + MomentumBonus)} where {@code MomentumBonus} applies only to the currently
 * active task to prevent jittering at threshold boundaries.
 */
public final class TaskUtility {

    /** PROVISIONAL: default momentum bonus added to the currently active task. */
    public static final double DEFAULT_MOMENTUM_BONUS = 15.0;

    public record Candidate<T>(T task, boolean gate, double baseWeight, double urgency,
                               boolean active, double momentumBonus) {
        public Candidate(T task, boolean gate, double baseWeight, double urgency, boolean active) {
            this(task, gate, baseWeight, urgency, active, DEFAULT_MOMENTUM_BONUS);
        }

        public double computeScore() {
            return TaskUtility.score(gate, baseWeight, urgency, active, momentumBonus);
        }
    }

    /** Computes {@code Gate * (BaseWeight * Urgency + (active ? MomentumBonus : 0))}. */
    public static double score(boolean gate, double baseWeight, double urgency,
                               boolean active, double momentumBonus) {
        if (!gate || !Double.isFinite(baseWeight) || !Double.isFinite(urgency)
                || baseWeight <= 0.0 || urgency < 0.0) {
            return 0.0;
        }
        double momentum = active && Double.isFinite(momentumBonus) ? Math.max(0.0, momentumBonus) : 0.0;
        double value = baseWeight * urgency + momentum;
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    /** Picks the highest-scoring gated candidate (ties keep declaration order), or {@code null} if none pass. */
    @Nullable
    public static <T> T pickBest(List<Candidate<T>> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        T best = null;
        double bestScore = 0.0;
        for (Candidate<T> c : candidates) {
            if (c == null || !c.gate()) {
                continue;
            }
            double s = c.computeScore();
            if (best == null || s > bestScore) {
                bestScore = s;
                best = c.task();
            }
        }
        return best;
    }

    private TaskUtility() {
    }
}
