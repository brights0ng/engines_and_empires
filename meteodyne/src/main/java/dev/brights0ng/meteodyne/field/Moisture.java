package dev.brights0ng.meteodyne.field;

/**
 * How much water air can hold and how moist the ground keeps it (phase 3 of {@code claude/weather-backbone-plan.md}).
 * Pure.
 *
 * <ul>
 *   <li><b>Capacity</b> ({@link #capacity}): the most water a column of air can hold as vapour, in millimetres of
 *       "precipitable water", roughly doubling every 11-12 C (warm air holds far more): about 4 mm at -15 C, 10 at 0 C,
 *       33 at 20 C, 60 at 30 C, close to real columns from polar winter to the tropics.</li>
 *   <li><b>Equilibrium</b> ({@link #targetHumidity}): the relative humidity the ground pulls the air toward: open water
 *       85%, forests and moist land up to ~90% of their biome's humidity range, dry land and deserts far less, ice and
 *       snow little (cold, and little evaporation). <b>Cold air sits nearer saturation</b> (weather phase 8a,
 *       2026-10-14): below 15 C the equilibrium climbs toward saturation, by up to {@value #COLD_BOOST} of the gap at
 *       -5 C and colder, as real winters have higher relative humidity than summers (temperate land about 65-70% in
 *       winter against 45-55% in summer).</li>
 *   <li><b>Evaporation time</b> ({@link #evaporationTicks}): how fast it gets there: a day over water, a day and a half
 *       over forest, two and a half over open land, four over ice. <b>Cold air over warmer water</b> takes up water
 *       faster: a quarter as long again for every 4 C the water is warmer (8 C warmer, three times as fast).</li>
 * </ul>
 */
public final class Moisture {

    /** How much of the gap to saturation cold air's equilibrium closes, at its coldest. */
    static final double COLD_BOOST = 0.45;
    /**
     * The most the cold-air boost can raise the equilibrium to (8b follow-up, 2026-10-14). The boost gives dry and
     * middling ground its higher winter humidity (temperate land ~50% → ~70%), but on forest and sea, already at
     * 84-90%, it pushed polar air to ~95% all day, so the night's cooling saturated it and low stratus drizzled most of
     * the polar summer. Ground whose own equilibrium is above this keeps it; the boost just can't take anything past it.
     * Lift (fronts, lows, upslope wind) still saturates air as before.
     */
    public static final double COLD_BOOST_CAP = 0.88;
    /**
     * How long air moister than its ground's equilibrium takes to dry back toward it, ticks: three days, slower than a
     * passing storm (weather phase 8b).
     */
    public static final double DRYING_TICKS = 72_000;

    /** The most precipitable water air at {@code celsius} can hold, mm. */
    public static double capacity(double celsius) {
        double t = Math.max(-40, Math.min(45, celsius));
        return 10 * Math.exp(0.06 * t);
    }

    /** The relative humidity (0-1) the ground pulls warm air toward (15 C and above). */
    public static double targetHumidity(int surface, double biomeHumidity) {
        double h = Math.max(0, Math.min(1, biomeHumidity));
        return switch (surface) {
            // Water; a tropical sea's dry season (its humidity pushed below the oceans' 0.5) lowers it too (weather
            // phase 8b, 2026-10-14: sea air kept tropical coasts humid through the dry season).
            case 2 -> 0.85 + Math.min(0, h - 0.5);
            case 3 -> 0.40;               // ice
            case 1 -> 0.45 + 0.45 * h;    // forest
            default -> 0.25 + 0.5 * h;    // land
        };
    }

    /** The relative humidity (0-1) the ground pulls air at {@code celsius} toward: higher in cold air. */
    public static double targetHumidity(int surface, double biomeHumidity, double celsius) {
        double base = targetHumidity(surface, biomeHumidity);
        double x = Math.max(0, Math.min(1, (15 - celsius) / 20));
        double cold = x * x * (3 - 2 * x);
        double boosted = base + COLD_BOOST * cold * (1 - base);
        if (LEGACY_CAP) {
            return boosted;
        }
        return Math.min(boosted, Math.max(base, COLD_BOOST_CAP));
    }

    /** Debug only: {@code -Dmeteodyne.legacy8b=true} also lifts the cap, for before/after runs. */
    static final boolean LEGACY_CAP = Boolean.getBoolean("meteodyne.legacy8b");

    /** How long evaporation takes to bring the air to its equilibrium over {@code surface}, ticks. */
    public static double evaporationTicks(int surface) {
        return switch (surface) {
            case 2 -> 24_000;
            case 3 -> 96_000;
            case 1 -> 36_000;
            default -> 60_000;
        };
    }

    /**
     * As {@link #evaporationTicks(int)} for air at {@code airT} over a surface at {@code surfaceT} (C): faster where
     * cold air lies over warmer water.
     */
    public static double evaporationTicks(int surface, double airT, double surfaceT) {
        double ticks = evaporationTicks(surface);
        if (surface == 2 && surfaceT > airT) {
            ticks /= 1 + (surfaceT - airT) / 4;
        }
        return ticks;
    }

    private Moisture() {
    }
}
