package dev.brights0ng.enginesandempires.weather.heat.air;

/**
 * The constants of the heat simulation (weather phase 8f-1), in the sim's own units: temperatures are C (offsets from
 * the outside air), time is seconds, heat capacity is "air cells" (one block of air holds 1), so power is C x air cells
 * per second and a conductance is air cells per second.
 *
 * <p>{@link #DEFAULT} is calibrated (2026-10-15, {@code AirGridTest}) against Bright's timing: a 10x10x5 stone house
 * with one campfire, still air, reaches 95% of its warmth (about +12 C) in about 5 minutes; 1 block from the fire the
 * air rises in seconds, and the radiant term is felt at once.
 *
 * @param airMix        conductance between two neighbouring air cells (the stirring of a room's air)
 * @param openingMix    how many times faster indoor air trades with open-sky air across an opening (door, window,
 *                      missing wall) than air mixes indoors
 * @param film          resistance of the thin air layer on a wall's surface, per face
 * @param wallK         conductance of one block of stone (table conductance 1)
 * @param solidCap      heat capacity of one block of stone (table capacity 1), in air cells
 * @param exposedLoss   loss of open-sky air to the free atmosphere in still air, per second
 * @param exposedWind   extra loss of open-sky air per m/s of wind
 * @param convect       extra vertical air mixing per C of unstable layering (warmer below)
 * @param convectMax    most that unstable layering can multiply vertical mixing by
 * @param stable        vertical air mixing multiplier when the layering is stable (warmer above)
 * @param rise          buoyant drift of air per C of offset (warm up, cold down), air cells / s
 * @param riseMax       most buoyant drift, air cells / s
 * @param maxStep       longest single step, s; longer steps are split
 * @param sweeps        relaxation sweeps for a 1-second step (more for longer steps, {@link AirGrid#sweepsFor})
 * @param powerUnit     power of a source of table power 1 (a campfire), all of it into the air, C x air cells / s
 * @param radiantK      radiant warmth, C, 1 block from a source of table power 1 radiating all of it
 * @param radiantMin    distances closer than this count as this, blocks
 * @param radiantFloor  radiant warmth below this, C, is not looked for (sets each source's radiant reach)
 */
public record AirParams(double airMix, double openingMix, double film, double wallK, double solidCap,
                        double exposedLoss, double exposedWind, double convect, double convectMax, double stable,
                        double rise, double riseMax, double maxStep, int sweeps, double powerUnit, double radiantK,
                        double radiantMin, double radiantFloor) {

    public static final AirParams DEFAULT = new AirParams(
            1.0,    // airMix
            4.0,    // openingMix
            0.15,   // film
            0.035,  // wallK
            3.5,    // solidCap
            0.8,    // exposedLoss
            0.1,    // exposedWind
            0.15,   // convect
            4.0,    // convectMax
            0.5,    // stable
            0.01,   // rise
            0.5,    // riseMax
            10.0,   // maxStep
            4,      // sweeps
            150.0,  // powerUnit (a campfire's half that warms the air: 75)
            12.0,   // radiantK (a campfire's radiant half: +6 C at 1 block)
            1.0,    // radiantMin
            0.25);  // radiantFloor

    /**
     * The power a source puts into the grid: its table power less the share it radiates, in sim units.
     */
    public double gridPower(double tablePower, double radiantShare) {
        return tablePower * (1 - radiantShare) * powerUnit;
    }
}
