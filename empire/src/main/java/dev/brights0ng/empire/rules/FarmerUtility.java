// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * Pure utility curves, action-duration scaling, seed-reserve math, and majority-crop selection for the
 * {@link Occupation#FARMER} job (zero Minecraft imports).
 */
public final class FarmerUtility {

    // PROVISIONAL: Base weights fed into TaskUtility: Gate * (BaseWeight * Urgency + MomentumBonus)
    public static final double BASE_END_SHIFT_WEIGHT = 200.0;
    public static final double BASE_DUMP_WEIGHT = 150.0;
    public static final double BASE_RETILL_WEIGHT = 140.0;
    public static final double BASE_FETCH_WEIGHT = 135.0;
    /** Shared base weight for field-scoped Harvest and Seed tasks so fresh selection compares backlog counts fairly. */
    public static final double BASE_FIELD_WORK_WEIGHT = 80.0;
    public static final double BASE_HARVEST_WEIGHT = BASE_FIELD_WORK_WEIGHT;
    public static final double BASE_PLANT_WEIGHT = BASE_FIELD_WORK_WEIGHT;
    public static final double BASE_PICKUP_WEIGHT = 55.0;
    public static final double BASE_WAIT_WEIGHT = 10.0;

    /**
     * Strong momentum bonus for active field work (Harvest or Seed) so completing a field or recovering from an
     * interruption heavily favors finishing the remaining fields of the same task type before switching
     * (still beaten by full-inventory Dump, Hoe Fetch, Retill, and Seed Fetch when needed).
     */
    public static final double FIELD_WORK_MOMENTUM_BONUS = 45.0;

    // PROVISIONAL: Base atomic action durations in ticks (scaled by derived labor/routing speed * productivity)
    public static final int HARVEST_BASE_TICKS = 12;
    public static final int PLANT_BASE_TICKS = 10;
    public static final int TILL_BASE_TICKS = 16;
    public static final int TRANSFER_BASE_TICKS = 14;

    // PROVISIONAL: Base hunger exhaustion per atomic farming action (scaled by activityExhaustionMultiplier)
    public static final float HARVEST_EXHAUSTION = 0.04F;
    public static final float PLANT_EXHAUSTION = 0.02F;
    public static final float TILL_EXHAUSTION = 0.08F;

    /**
     * Urgency for dumping farm produce/surplus seeds into shed storage.
     *
     * <p>Jumps to {@code 1.25} (score {@code 187.5}) when pockets are full or overflowed so dumping always beats
     * active field work even with momentum; stays low while busy with space remaining, and returns {@code >= 0.55}
     * when idle so partial harvests are stowed.
     */
    public static double dumpUrgency(double pocketFillFraction, boolean hasFieldWork) {
        double f = clamp01(pocketFillFraction);
        if (f >= 0.999) {
            return 1.25;
        }
        double curve = f * f * f * 0.45;
        return hasFieldWork ? curve : Math.max(curve, 0.55);
    }

    /**
     * Strictly monotonic count-based urgency in {@code (0.50, 1.0)} for primary field work (Harvest / Seed).
     * Never saturates at an integer cap, so {@code countA > countB} always produces a strictly higher score when
     * choosing between harvesting mature crops and seeding empty farmland.
     */
    public static double fieldWorkUrgency(int workItemCount) {
        if (workItemCount <= 0) {
            return 0.0;
        }
        return 0.50 + 0.50 * (workItemCount / (workItemCount + 16.0));
    }

    public static double harvestUrgency(int ripeCount) {
        return fieldWorkUrgency(ripeCount);
    }

    public static double plantUrgency(int seedableEmptyCount) {
        return fieldWorkUrgency(seedableEmptyCount);
    }

    /**
     * High-priority interruption urgency for re-tilling trampled/reverted dirt ({@code 0.95..1.0}, score
     * {@code ~133..140} — above active field work with momentum, below full-inventory dump).
     */
    public static double retillUrgency(int revertedCount) {
        if (revertedCount <= 0) {
            return 0.0;
        }
        return 0.95 + 0.05 * (revertedCount / (revertedCount + 8.0));
    }

    /**
     * Urgency for fetching a hoe ({@code 1.0}, score {@code 135.0}) or needed seeds ({@code 0.94..0.96}, score
     * {@code ~127..130}, beating active Seed+momentum while staying below Retill and full Dump).
     */
    public static double fetchUrgency(boolean needsHoe, int missingSeeds) {
        if (!needsHoe && missingSeeds <= 0) {
            return 0.0;
        }
        if (needsHoe) {
            return 1.0;
        }
        return 0.94 + 0.02 * (missingSeeds / (missingSeeds + 16.0));
    }

    public static double pickupUrgency(int strayCount) {
        if (strayCount <= 0) {
            return 0.0;
        }
        return clamp(0.50 + 0.45 * Math.min(1.0, strayCount / 8.0), 0.50, 0.95);
    }

    /**
     * Scales a base action duration in ticks by the citizen's speed multiplier (e.g.
     * {@link CivilianDerivedStats#heavyLaborSpeedMultiplier()} or {@code routingEfficiencyMultiplier}) and Fulfilment
     * productivity ({@code 0.5..1.0}). Always returns at least {@code 1} tick.
     */
    public static int scaledWorkTicks(int baseTicks, double speedMultiplier, double productivity) {
        return scaledWorkTicks(baseTicks, speedMultiplier, productivity, 1.0);
    }

    /**
     * Scales a base action duration in ticks by the citizen's trait speed multiplier, Fulfilment productivity
     * ({@code 0.5..1.0}), and occupational skill speed multiplier ({@link CivilianSkills#speedMultiplier(double)},
     * {@code 0.5..2.5}). Always returns at least {@code 1} tick.
     */
    public static int scaledWorkTicks(int baseTicks, double speedMultiplier, double productivity,
                                      double skillSpeedMultiplier) {
        if (baseTicks <= 1) {
            return 1;
        }
        double safeSpeed = Double.isFinite(speedMultiplier) ? Math.max(0.25, speedMultiplier) : 1.0;
        double safeProd = Double.isFinite(productivity) ? clamp(productivity, 0.5, 1.0) : 1.0;
        double safeSkill = Double.isFinite(skillSpeedMultiplier) ? Math.max(0.25, skillSpeedMultiplier) : 1.0;
        return Math.max(1, (int) Math.round(baseTicks / (safeSpeed * safeProd * safeSkill)));
    }

    /**
     * Maximum number of seeds of one crop type the farmer should retain in pockets while working:
     * enough to cover currently missing/empty spots on the farm for that crop, capped at {@code maxStackSize} (1 stack).
     */
    public static int desiredSeedKeepCount(int missingSpotsForCrop, int maxStackSize) {
        if (missingSpotsForCrop <= 0) {
            return 0;
        }
        return Math.min(missingSpotsForCrop, Math.max(1, maxStackSize));
    }

    /**
     * Picks the key with the highest positive count in {@code counts}, breaking ties lexicographically.
     * Returns {@code null} if no entry has a positive count.
     */
    @Nullable
    public static String majorityKey(@Nullable Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String key = entry.getKey();
            Integer val = entry.getValue();
            if (key == null || key.isBlank() || val == null || val <= 0) {
                continue;
            }
            if (best == null || val > bestCount || (val == bestCount && key.compareTo(best) < 0)) {
                best = key;
                bestCount = val;
            }
        }
        return best;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return clamp(value, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private FarmerUtility() {
    }
}
