package dev.brights0ng.enginesandempires.weather.rain;

import dev.brights0ng.enginesandempires.weather.climate.Temperature;

/**
 * The wind and temperature around this client's player, as the server last sent them ({@link WeatherSyncPayload}).
 * Plain static state (no Minecraft classes), so the shared query can read it and tests can set it.
 *
 * <p>Two winds, both steady (Bright, 2026-10-04: rain shafts swung with every gust):
 * <ul>
 *   <li><b>Drift</b> ({@link #windX}): the server's wind averaged over the rain's fall ({@link WindAverage}), so the
 *       client's wet patch matches the server's; eased toward each sync over {@link #WIND_EASE_TICKS}.</li>
 *   <li><b>Lean</b> ({@link #slant()}): the surface wind averaged over 15 s with a capped rate of change, and its
 *       history, so gusts bend only new rain ({@link RainSlant}).</li>
 * </ul>
 */
public final class ClientWeather {

    public static final double WIND_EASE_TICKS = 60;

    /** What the last sync said; see {@link WeatherSyncPayload} for the fields. */
    public record State(boolean localized, double surfaceX, double surfaceZ, double aloftX, double aloftZ, double driftX,
                        double driftZ, int seaLevel, double coolingScale, double maxCooling, int originX, int originZ,
                        int spacing, int size, float[] temperatures) {
    }

    private static volatile State last;
    /** The eased drift wind: x, z (m/s). */
    private static final double[] wind = new double[2];
    private static boolean windSet;
    private static final RainSlant SLANT = new RainSlant();

    public static void accept(State state) {
        last = state;
        if (!windSet && state != null) {
            wind[0] = state.driftX();
            wind[1] = state.driftZ();
            windSet = true;
        }
    }

    public static void clear() {
        last = null;
        windSet = false;
        java.util.Arrays.fill(wind, 0);
        SLANT.reset();
    }

    /** Eases the drift wind toward the last sync and steps the lean. Once per client tick. */
    public static void tick() {
        State p = last;
        if (p == null) {
            return;
        }
        double a = 1 - Math.exp(-1 / WIND_EASE_TICKS);
        wind[0] += (p.driftX() - wind[0]) * a;
        wind[1] += (p.driftZ() - wind[1]) * a;
        SLANT.step(p.surfaceX(), p.surfaceZ());
    }

    /** Whether the pack's rain model is on (true until the server says otherwise). */
    public static boolean localized() {
        State p = last;
        return p == null || p.localized();
    }

    /** The wind rain drifts in (surface and aloft, averaged over its fall, from the server), m/s. */
    public static double windX() {
        return wind[0];
    }

    public static double windZ() {
        return wind[1];
    }

    /** The streaks' lean and its history (client thread only). */
    public static RainSlant slant() {
        return SLANT;
    }

    /**
     * The air temperature at (x, y, z), C: the synced sea-level grid (bilinear, clamped to its edge) plus the height
     * cooling ({@link Temperature#heightCorrection}). NaN before the first sync.
     */
    public static double temperature(double x, double y, double z) {
        State p = last;
        if (p == null || p.size() < 1) {
            return Double.NaN;
        }
        double base = grid(p, x, z);
        if (!Double.isFinite(base)) {
            return Double.NaN;
        }
        return base + Temperature.heightCorrection(p.seaLevel(), y, p.coolingScale(), p.maxCooling());
    }

    static double grid(State p, double x, double z) {
        int n = p.size();
        double gx = Math.max(0, Math.min(n - 1, (x - p.originX()) / (double) p.spacing()));
        double gz = Math.max(0, Math.min(n - 1, (z - p.originZ()) / (double) p.spacing()));
        int i0 = (int) Math.floor(gx), k0 = (int) Math.floor(gz);
        int i1 = Math.min(n - 1, i0 + 1), k1 = Math.min(n - 1, k0 + 1);
        double fx = gx - i0, fz = gz - k0;
        float[] t = p.temperatures();
        double[] v = {t[k0 * n + i0], t[k0 * n + i1], t[k1 * n + i0], t[k1 * n + i1]};
        double[] w = {(1 - fx) * (1 - fz), fx * (1 - fz), (1 - fx) * fz, fx * fz};
        double sum = 0, weight = 0;
        for (int i = 0; i < 4; i++) {
            if (Double.isFinite(v[i]) && w[i] > 0) {
                sum += v[i] * w[i];
                weight += w[i];
            }
        }
        return weight > 1e-9 ? sum / weight : Double.NaN;
    }

    private ClientWeather() {
    }
}
