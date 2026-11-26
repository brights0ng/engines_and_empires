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
    public static final double BASE_END_SHIFT_WEIGHT = 160.0;
    public static final double BASE_DUMP_WEIGHT = 120.0;
    public static final double BASE_FETCH_WEIGHT = 95.0;
    public static final double BASE_RETILL_WEIGHT = 85.0;
    public static final double BASE_HARVEST_WEIGHT = 80.0;
    public static final double BASE_PLANT_WEIGHT = 70.0;
    public static final double BASE_PICKUP_WEIGHT = 55.0;
    public static final double BASE_WAIT_WEIGHT = 10.0;

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
     * <p>Follows a steep cubic curve ({@code f^3}) as pocket fullness approaches {@code 1.0} so the farmer finishes
     * harvesting smoothly until pockets are full (jumping to {@code 1.25}, overtaking harvest even with momentum),
     * while returning a steady fallback ({@code 0.55}) when no field work remains so partial harvests are stowed.
     */
    public static double dumpUrgency(double pocketFillFraction, boolean hasFieldWork) {
        double f = clamp01(pocketFillFraction);
        if (f >= 0.999) {
            return 1.25;
        }
        double curve = f * f * f * 1.15;
        return hasFieldWork ? curve : Math.max(curve, 0.55);
    }

    public static double harvestUrgency(int ripeCount) {
        if (ripeCount <= 0) {
            return 0.0;
        }
        return clamp(0.65 + 0.35 * Math.min(1.0, ripeCount / 16.0), 0.65, 1.0);
    }

    public static double retillUrgency(int revertedCount) {
        if (revertedCount <= 0) {
            return 0.0;
        }
        return clamp(0.60 + 0.35 * Math.min(1.0, revertedCount / 8.0), 0.60, 0.95);
    }

    public static double plantUrgency(int emptyFarmlandCount) {
        if (emptyFarmlandCount <= 0) {
            return 0.0;
        }
        return clamp(0.55 + 0.40 * Math.min(1.0, emptyFarmlandCount / 16.0), 0.55, 0.95);
    }

    public static double fetchUrgency(boolean needsHoe, int missingSeeds) {
        if (!needsHoe && missingSeeds <= 0) {
            return 0.0;
        }
        if (needsHoe) {
            return 0.95;
        }
        return clamp(0.70 + 0.25 * Math.min(1.0, missingSeeds / 16.0), 0.70, 0.95);
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
        if (baseTicks <= 1) {
            return 1;
        }
        double safeSpeed = Double.isFinite(speedMultiplier) ? Math.max(0.25, speedMultiplier) : 1.0;
        double safeProd = Double.isFinite(productivity) ? clamp(productivity, 0.5, 1.0) : 1.0;
        return Math.max(1, (int) Math.round(baseTicks / (safeSpeed * safeProd)));
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
