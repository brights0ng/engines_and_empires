// Written by Claude (2026-11-21): the plausibility guard, after a tile whose heat ran away was saved and restored.
package dev.brights0ng.meteodyne.airflow;

import dev.brights0ng.greedyfluids.region.AirRegion;
import dev.brights0ng.greedyfluids.solve.BoxSolver3D;
import dev.brights0ng.greedyfluids.solve.WallStore3D;

/**
 * The temperatures a region's air and walls may hold: {@link #LOW_C} to {@link #HIGH_C} (Bright, 2026-11-21).
 * <ul>
 *   <li><b>Why:</b> a tile's heat ran away (GreedyFluids' over-relaxed heat sweeps in a storm, fixed in
 *       GreedyFluids 581bd94) to about 1e70 °C while its speeds stayed capped, so the finite-values check passed; its
 *       periodic save kept values near the float limit, and every rebuild restored them and failed again.</li>
 *   <li><b>Where it applies:</b> a running region whose air or (non-fixed) wall stores leave the band fails as having
 *       run away ({@link AirflowRuntime.Diverged}); the manager drops the tile's saves and builds it again as if
 *       loaded for the first time. Saving leaves out values outside the band, and restoring and the readers ignore
 *       saved ones outside it ({@link AirflowSaved}), so worlds saved before the guard heal when loaded.</li>
 *   <li><b>The band:</b> wide enough for anything the game makes (lava and lava cauldrons are walls fixed at
 *       1000 °C, and the hottest air in M8e's cave replay was 840 °C), far short of a run-away step.</li>
 * </ul>
 * Pure Java (GreedyFluids, no Minecraft).
 */
public final class AirflowPlausibility {

    /** The coldest and hottest air or wall a region may hold, °C. */
    public static final double LOW_C = -100, HIGH_C = 1500;
    /** The farthest a plausible temperature can be from a plausible background, °C: a saved offset beyond is not. */
    public static final double MAX_OFFSET_C = HIGH_C - LOW_C;

    /** Whether {@code celsius} is in the band (NaN is not). */
    public static boolean plausible(double celsius) {
        return celsius >= LOW_C && celsius <= HIGH_C;
    }

    /** Whether a saved offset from the background, °C, could be a plausible temperature (NaN is not). */
    public static boolean plausibleOffset(double celsius) {
        return Math.abs(celsius) <= MAX_OFFSET_C;
    }

    /**
     * What is out of the band in a region's air and non-fixed wall stores (a fixed store holds the temperature it
     * was given), as a short description; null when everything is in it. Reads every box and store: about as long
     * as a few of the step's sweeps.
     */
    public static String check(AirRegion region) {
        BoxSolver3D s = region.mesh().solver();
        for (int b = 0; b < s.graph().boxCount(); b++) {
            double t = s.temperature(b);
            if (!plausible(t)) {
                return String.format(java.util.Locale.ROOT, "air at %.4g C (box %d)", t, b);
            }
        }
        WallStore3D w = s.wallStore();
        for (int i = 0; i < w.count(); i++) {
            double t = w.temperature(i);
            if (!w.isFixed(i) && !plausible(t)) {
                return String.format(java.util.Locale.ROOT, "a wall at %.4g C (store %d)", t, i);
            }
        }
        return null;
    }

    private AirflowPlausibility() {
    }
}
