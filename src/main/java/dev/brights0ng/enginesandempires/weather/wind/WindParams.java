package dev.brights0ng.enginesandempires.weather.wind;

/**
 * Every tunable number of the wind push, as one value. {@link WindConfig} builds it from the server config; tests use
 * {@link #DEFAULTS}.
 *
 * @param enabled         whether wind pushes physics objects at all
 * @param pushCoefficient force per (m² of silhouette × (m/s)²) at sea-level air pressure. The force is
 *                        {@code pushCoefficient × pressure × exposure × area × closing²}, where closing is how much
 *                        faster the wind moves than the object, along the wind. A first guess, to tune in play.
 * @param gustRampTicks   how long a rise in wind speed takes to reach a ship (95% of the way), so gusts build up
 *                        instead of landing as a jolt. Falls follow at once.
 * @param surfaceLayer    blocks above the ground that still feel only the surface wind
 * @param aloftHeight     y from which only the aloft wind is felt (meant to match the cloud base)
 * @param shelterInterval ticks between shelter checks of each ship
 * @param sideReach       how far out (blocks) a shelter probe looks from each side of a ship
 * @param roofReach       how far up (blocks) a shelter probe looks from the top of a ship
 * @param roofWeight      how much a full roof cuts the push on its own (0.5 = halves it)
 */
public record WindParams(boolean enabled, double pushCoefficient, int gustRampTicks, double surfaceLayer,
                         double aloftHeight, int shelterInterval, int sideReach, int roofReach, double roofWeight) {

    public static final WindParams DEFAULTS = new WindParams(true, 0.005, 20, 24.0, 192.0, 20, 16, 32, 0.5);
}
