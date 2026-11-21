// Written by Gemini.
package dev.brights0ng.empire.rules;

/**
 * Pure constants and arithmetic for Empire regions and {@link RegionType#HOME} housing.
 */
public final class RegionRules {

    /** Maximum interior block volume for a {@code HOME} region (D4, D5). */
    public static final int HOME_MAX_BLOCKS = 500;
    /** Minimum interior block volume for a {@code HOME} region (D5). */
    public static final int HOME_MIN_BLOCKS = 50;
    /** Minimum interior height in blocks for a {@code HOME} region (D5). */
    public static final int HOME_MIN_HEIGHT = 2;
    /** Maximum citizen capacity for a single {@code HOME} region (D6). */
    public static final int HOME_MAX_CAPACITY = 4;
    /** Ticks an invalid {@link RegionOrigin#AUTO} region persists before automatic deletion (3 MC days, D7). */
    public static final long INVALID_AUTO_DELETE_TICKS = 72_000L;

    /**
     * Citizen capacity of a {@code HOME} region by interior block count (D6):
     * {@code < 50 -> 0}, {@code 50..74 -> 1}, {@code 75..99 -> 2}, {@code 100..124 -> 3}, {@code 125+ -> 4}.
     */
    public static int homeCapacity(int blockCount) {
        if (blockCount < HOME_MIN_BLOCKS) {
            return 0;
        }
        return Math.min(HOME_MAX_CAPACITY, (blockCount - 25) / 25);
    }

    /**
     * Whether a region should be automatically deleted at {@code gameTime} (D7).
     * Only invalid {@link RegionOrigin#AUTO} regions that have stayed invalid for at least
     * {@link #INVALID_AUTO_DELETE_TICKS} are auto-deleted; {@link RegionOrigin#MANUAL} regions never auto-delete.
     */
    public static boolean shouldAutoDelete(RegionOrigin origin, boolean valid, long invalidSinceGameTime, long gameTime) {
        return origin == RegionOrigin.AUTO
                && !valid
                && invalidSinceGameTime >= 0L
                && (gameTime - invalidSinceGameTime) >= INVALID_AUTO_DELETE_TICKS;
    }

    private RegionRules() {
    }
}
