package dev.brights0ng.enginesandempires.weather.surface;

import dev.brights0ng.enginesandempires.weather.rain.Precip;

/**
 * The numbers behind snow and ice on the ground (phase 5b of the weather backbone, 2026-10-08). Pure Java; the world
 * side is {@link SurfaceWeather}, which visits each surface column about once per in-game hour, so every rate here is
 * "per visit" = "per hour".
 *
 * <h2>Pace (Bright, 2026-10-07: realistic-ish)</h2>
 * <ul>
 *   <li>Heavy snow adds about a layer an hour; a long storm buries open ground 2 deep (deeper in drifts, against a
 *       wall downwind); 2 on leaves, 1 under them.</li>
 *   <li>Snow melts slowly just above 0 C and quickly above +5 C; rain and sunshine speed it up.</li>
 *   <li>Lakes and rivers freeze in from their shores below -1 C, right across in a hard freeze (below -8 C); the sea
 *       only freezes in from the coast in deep cold (below -6 C, within {@link #SEA_ICE_REACH} blocks of land).</li>
 *   <li>Ice thaws above 0 C, faster in rain.</li>
 *   <li>Glaze (phase 5c, Bright 2026-10-08): freezing rain on freezing ground builds it at snow's pace, up to
 *       {@link #GLAZE_MAX} one-pixel layers; it thaws as ice does and melts to nothing. It skips block light
 *       {@link #GLAZE_LIGHT} and up (lit roads ice over unless brightly lit). On leaves, a third layer instead breaks
 *       the leaves (and the glaze) {@link #LEAF_BREAK} of the time.</li>
 *   <li>Hail knocks exposed crops back a stage {@link #HAIL_CROP} of the visits it falls on (Bright: "moderate", about
 *       30% of a field per storm).</li>
 * </ul>
 */
public final class SurfaceRules {

    /** Below this wind (m/s) snow settles evenly: any full block shelters it, whatever the direction. */
    public static final double CALM = 1.5;
    /** How far (blocks) a full block shelters snow downwind of it. */
    public static final int DRIFT_REACH = 3;
    /** Snow depth (layers) on open ground, or beyond {@link #DRIFT_REACH} of a wall (Bright, 2026-10-08: 2). */
    public static final int OPEN_DEPTH = 2;
    /** Snow depth on top of leaves, whatever the shelter (Bright, 2026-10-08). */
    public static final int ON_LEAVES = 2;
    /** Snow depth on the ground under leaves (what falls through the canopy) (Bright, 2026-10-08). */
    public static final int UNDER_LEAVES = 1;
    /** Freshwater freezes below this, C (at the shore first). */
    public static final double FRESH_FREEZE = -1;
    /** Below this freshwater freezes anywhere, not only at an edge, C. */
    public static final double HARD_FREEZE = -8;
    /** The sea freezes below this, C, only near land. */
    public static final double SEA_FREEZE = -6;
    /** How far from land (blocks) sea ice reaches. */
    public static final int SEA_ICE_REACH = 6;
    /** Vanilla's chance a cauldron is visited by its precipitation tick, relative to ours (about 1 in 4). */
    public static final double CAULDRON_SHARE = 0.25;
    /** Glaze layers at most, a pixel each (Bright, 2026-10-08). */
    public static final int GLAZE_MAX = 2;
    /** Block light from which glaze doesn't form, and melts (Bright, 2026-10-08: 13, harder to stop than snow). */
    public static final int GLAZE_LIGHT = 13;
    /** The chance glaze at its cap on leaves breaks them when it would gain another layer (Bright, 2026-10-08). */
    public static final double LEAF_BREAK = 1.0 / 3;
    /**
     * The chance a visit with hail falling knocks an exposed crop back a stage. Hail falls about a fifth of the time
     * under a strong storm's core, so a core crossing a field (a few visits per column) hails on about one of them:
     * about 30% of the field knocked back, "moderate" (Bright, 2026-10-08). An estimate; tune in play.
     */
    public static final double HAIL_CROP = 0.3;

    /**
     * The chance a visit adds a layer of snow where snow of {@code strength} (0-1) falls on ground at {@code t} C: 1 in
     * heavy snow (0.8 and up), less in light snow; wet snow just above freezing mostly melts as it lands.
     */
    public static double snowChance(double strength, double t) {
        if (t > Precip.SNOW_MAX || strength <= 0) {
            return 0;
        }
        double c = Math.min(1, 1.25 * strength);
        if (t > 0) {
            c *= 1 - t / Precip.SNOW_MAX;
        }
        return c;
    }

    /**
     * How many layers of snow melt per hour at {@code t} C: none at or below freezing; 0.15 just above, rising to 1 at
     * +5 C, then a quarter more per degree up to 4; rain on it adds half a layer, sunshine a third.
     */
    public static double meltRate(double t, boolean rain, boolean sunny) {
        if (t <= 0) {
            return 0;
        }
        double r = t <= 5 ? 0.15 + 0.17 * t : Math.min(4, 1 + 0.25 * (t - 5));
        if (rain) {
            r += 0.5;
        }
        if (sunny) {
            r += 0.3;
        }
        return r;
    }

    /** {@code rate} per hour as a whole number of layers this visit: its whole part, plus one by chance for the rest. */
    public static int layersThisVisit(double rate, double roll) {
        int whole = (int) Math.floor(rate);
        return whole + (roll < rate - whole ? 1 : 0);
    }

    /**
     * The chance a visit adds a layer of glaze where freezing rain of {@code strength} (0-1) falls on ground at
     * {@code t} C: snow's pace (Bright, 2026-10-08: "match snow"), only below freezing.
     */
    public static double glazeChance(double strength, double t) {
        if (t >= 0 || strength <= 0) {
            return 0;
        }
        return Math.min(1, 1.25 * strength);
    }

    /**
     * The deepest snow (layers) can get with the nearest full block {@code distance} blocks away on its downwind side
     * (the wind piles it against the wall; 0 or less: none in reach): touching it 8 (a full block, a drift against the
     * wall), one block of gap 6, two 4, otherwise {@link #OPEN_DEPTH} (Bright, 2026-10-07/08).
     */
    public static int driftCap(int distance) {
        return switch (distance) {
            case 1 -> 8;
            case 2 -> 6;
            case 3 -> 4;
            default -> OPEN_DEPTH;
        };
    }

    /**
     * The chance a visit freezes a block of still freshwater at {@code t} C: from -1 C (sure by -5 C), only at an edge
     * (next to land or ice) unless the freeze is hard.
     */
    public static double freshFreezeChance(double t, boolean atEdge) {
        if (t >= FRESH_FREEZE || (!atEdge && t >= HARD_FREEZE)) {
            return 0;
        }
        return clamp((FRESH_FREEZE - t) / 4);
    }

    /** The chance a visit freezes sea water at {@code t} C: only at an edge near land, from -6 C (sure by -12 C). */
    public static double seaFreezeChance(double t, boolean atEdge, boolean nearLand) {
        if (t >= SEA_FREEZE || !atEdge || !nearLand) {
            return 0;
        }
        return clamp((SEA_FREEZE - t) / 6);
    }

    /** The chance a visit thaws a block of exposed ice at {@code t} C: from 0 C, sure by +6 C, sooner in rain. */
    public static double thawChance(double t, boolean rain) {
        if (t <= 0) {
            return 0;
        }
        return clamp(t / 6 + (rain ? 0.25 : 0));
    }

    /**
     * Vanilla's biome temperature for {@code celsius}: its snow line (0.15) at 0 C, 20 C a unit (the inverse of
     * {@code BiomeClimate.fallback}).
     */
    public static float vanillaTemperature(double celsius) {
        return (float) (celsius / 20 + 0.15);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private SurfaceRules() {
    }
}
