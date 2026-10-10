package dev.brights0ng.enginesandempires.weather.climate;

/**
 * The climate from the world's own temperature and humidity fields (Bright, 2026-10-10). Pure.
 *
 * <h2>Why</h2>
 * The world places every biome from two smooth noise fields, temperature and humidity (vanilla's multi-noise biome
 * source; Tectonic reshapes the terrain but keeps them). A per-biome climate table can't match that: vanilla puts plains
 * in its cold and temperate bands and right beside jungles, so "plains = 11 C" dragged a jungle's region down to 18 C
 * and 67% humidity, its air to 8 C and 55%, and walking from the plains into the jungle jumped 10 C. Reading the fields
 * directly gives a climate that changes smoothly and always agrees with where the biomes are: the plains beside a
 * jungle read warm and humid because the world put them in warm, humid noise.
 *
 * <h2>How</h2>
 * Vanilla splits each field into five bands (temperature: frozen, cold, temperate, warm, hot; humidity: arid to
 * humid). The mapping pins each band's centre to a real-world analogue and runs linearly between them:
 * <table>
 *   <caption>Temperature noise</caption>
 *   <tr><th>Band</th><th>Biomes there</th><th>Mean</th><th>Seasonal swing</th></tr>
 *   <tr><td>frozen (-1 to -0.45)</td><td>snowy plains, snowy taiga, ice spikes</td><td>-10 C</td><td>15</td></tr>
 *   <tr><td>cold (-0.45 to -0.15)</td><td>taiga, cold plains and forests</td><td>3 C</td><td>14</td></tr>
 *   <tr><td>temperate (-0.15 to 0.2)</td><td>plains, forests, birch, dark forest</td><td>11 C</td><td>11</td></tr>
 *   <tr><td>warm (0.2 to 0.55)</td><td>savanna, jungle, warm forests</td><td>25 C</td><td>3</td></tr>
 *   <tr><td>hot (0.55 to 1)</td><td>desert, badlands</td><td>25 C</td><td>10</td></tr>
 * </table>
 * Humidity noise maps arid 0.15, dry 0.35, middling 0.5, moist 0.72, humid 0.92. In the hot band (where the world puts
 * deserts) the humidity dries out to 15% of that, so desert country is dry whatever the humidity noise says. Over water
 * the seasons swing half as much and the air is at least 80% humid.
 *
 * <p>The biome then adjusts this ({@link BiomeAdjust}): a few degrees or some humidity where it needs it, the ground,
 * and whether it never thaws. Temperatures are at sea level, as before; height cooling is added on top.
 */
public final class NoiseClimate {

    /** Temperature noise anchors and their mean temperatures (C) and seasonal swings (C). */
    static final double[] T_NOISE = {-1.0, -0.72, -0.30, 0.025, 0.375, 0.775, 1.0};
    static final double[] T_MEAN = {-17, -10, 3, 11, 25, 25, 27};
    static final double[] T_SWING = {17, 15, 14, 11, 3, 10, 11};
    /** Humidity noise anchors and their humidities (0-1). */
    static final double[] H_NOISE = {-1.0, -0.67, -0.22, 0.0, 0.2, 0.65, 1.0};
    static final double[] H_VALUE = {0.08, 0.15, 0.35, 0.50, 0.72, 0.92, 0.95};
    /** Where the hot band's drying starts and is complete (temperature noise), and how much humidity is left. */
    static final double DRY_FROM = 0.50;
    static final double DRY_TO = 0.62;
    static final double DRY_LEFT = 0.15;
    /** Over water: the seasons swing this much of the land's, and the air is at least this humid. */
    static final double WATER_SWING = 0.5;
    static final double WATER_HUMIDITY = 0.80;

    /** The mean temperature at temperature noise {@code t}, C. */
    public static double mean(double t) {
        return interpolate(T_NOISE, T_MEAN, t);
    }

    /** The seasonal swing at temperature noise {@code t}, C. */
    public static double swing(double t) {
        return interpolate(T_NOISE, T_SWING, t);
    }

    /** The humidity at humidity noise {@code h} and temperature noise {@code t}, 0-1 (the hot band dries it). */
    public static double humidity(double h, double t) {
        double dry = smooth((t - DRY_FROM) / (DRY_TO - DRY_FROM));
        return interpolate(H_NOISE, H_VALUE, h) * (1 - (1 - DRY_LEFT) * dry);
    }

    /** The climate at temperature noise {@code t} and humidity noise {@code h} for a biome adjusting it by {@code adj}. */
    public static BiomeClimate resolve(double t, double h, BiomeAdjust adj) {
        BiomeClimate.Surface surface = adj.surface() == null ? BiomeClimate.Surface.LAND : adj.surface();
        boolean water = surface == BiomeClimate.Surface.WATER;
        double mean = mean(t) + adj.temperatureOffset();
        double swing = swing(t) * (water ? WATER_SWING : 1);
        double humidity = adj.overridesHumidity() ? adj.humidity() : humidity(h, t) + adj.humidityOffset();
        if (water) {
            humidity = Math.max(humidity, WATER_HUMIDITY);
        }
        return new BiomeClimate(mean, swing, clamp(humidity, 0, 1), surface, adj.frozen());
    }

    static double interpolate(double[] xs, double[] ys, double x) {
        if (x <= xs[0]) {
            return ys[0];
        }
        for (int i = 1; i < xs.length; i++) {
            if (x <= xs[i]) {
                double f = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
                return ys[i - 1] + f * (ys[i] - ys[i - 1]);
            }
        }
        return ys[ys.length - 1];
    }

    private static double smooth(double x) {
        double c = clamp(x, 0, 1);
        return c * c * (3 - 2 * c);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private NoiseClimate() {
    }
}
