package dev.brights0ng.enginesandempires.weather.field;

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
 *       snow little (cold, and little evaporation).</li>
 *   <li><b>Evaporation time</b> ({@link #evaporationTicks}): how fast it gets there: a day over water, a day and a half
 *       over forest, two and a half over open land, four over ice.</li>
 * </ul>
 */
public final class Moisture {

    /** The most precipitable water air at {@code celsius} can hold, mm. */
    public static double capacity(double celsius) {
        double t = Math.max(-40, Math.min(45, celsius));
        return 10 * Math.exp(0.06 * t);
    }

    /** The relative humidity (0-1) the ground pulls the air toward. */
    public static double targetHumidity(int surface, double biomeHumidity) {
        double h = Math.max(0, Math.min(1, biomeHumidity));
        return switch (surface) {
            case 2 -> 0.85;               // water
            case 3 -> 0.40;               // ice
            case 1 -> 0.45 + 0.45 * h;    // forest
            default -> 0.25 + 0.5 * h;    // land
        };
    }

    /** How long evaporation takes to bring the air to its equilibrium over {@code surface}, ticks. */
    public static double evaporationTicks(int surface) {
        return switch (surface) {
            case 2 -> 24_000;
            case 3 -> 96_000;
            case 1 -> 36_000;
            default -> 60_000;
        };
    }

    private Moisture() {
    }
}
