package dev.brights0ng.enginesandempires.weather.cloud.sim;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;

/**
 * Storm outflow: the cold air a shower or thunderstorm's rain drags down spreads out along the ground as a gust front
 * (phase 4b of {@code claude/weather-backbone-plan.md}). Pure; the server rebuilds it every cloud pass and adds it to the
 * surface wind ({@code Atmosphere.wind}), so ships feel it.
 *
 * <h2>Shape (Bright, 2026-10-06: realistic)</h2>
 * <ul>
 *   <li>Blowing outward from the rain core, strongest at the gust front a little past the rain's edge
 *       ({@link #FRONT_PAST} blocks), dying out over {@link #FADE} blocks beyond it, so it reaches a few hundred
 *       blocks from the rain.</li>
 *   <li>Strongest ahead of the storm (the way it moves) and weaker behind it.</li>
 *   <li>Peak {@code 26 s^1.2} m/s for rain strength {@code s}: about 25 m/s from a strong cumulonimbus, 18 from a weaker
 *       one, 6 from a congestus shower.</li>
 *   <li>Gusty: it surges and lulls over a few seconds.</li>
 * </ul>
 */
public final class GustFronts {

    static final double FRONT_PAST = 150;
    static final double FADE = 200;
    static final double PEAK = 26;

    /** One storm's outflow, at game time {@code refTick}. Velocities in blocks per tick. */
    public record Outflow(double x, double z, double vx, double vz, long refTick, double core, double peak, int seed) {
    }

    public static final GustFronts NONE = new GustFronts(List.of());

    private final Outflow[] outflows;

    private GustFronts(List<Outflow> outflows) {
        this.outflows = outflows.toArray(new Outflow[0]);
    }

    /** The outflows of the raining heap clouds among {@code clouds} at {@code now}. */
    public static GustFronts of(List<SimCloud> clouds, long now) {
        List<Outflow> out = new ArrayList<>();
        for (SimCloud c : clouds) {
            if (!c.type.heap() || c.precipitation <= 0 || !Float.isInfinite(c.rainBottom)) {
                continue;
            }
            double s = c.type.rain.peak() * c.precipitation * c.phase(now).precipitation();
            if (s < 0.05) {
                continue;
            }
            SimCloud.Dome main = c.domes.getFirst();
            double core = main.radius() * c.type.rain.core();
            out.add(new Outflow(c.xAt(now) + main.dx(), c.zAt(now) + main.dz(), c.vx, c.vz, now, core,
                    PEAK * Math.pow(s, 1.2), main.seed()));
        }
        return new GustFronts(out);
    }

    public int size() {
        return outflows.length;
    }

    /** The outflow wind at (x, z) at game time {@code t}, m/s: {x, z}. */
    public double[] at(double x, double z, double t) {
        double wx = 0;
        double wz = 0;
        for (Outflow o : outflows) {
            double ox = o.x + o.vx * (t - o.refTick);
            double oz = o.z + o.vz * (t - o.refTick);
            double dx = x - ox;
            double dz = z - oz;
            double front = o.core + FRONT_PAST;
            double reach = front + 2 * FADE;
            double d2 = dx * dx + dz * dz;
            if (d2 > reach * reach || d2 < 1) {
                continue;
            }
            double d = Math.sqrt(d2);
            double profile = d <= front ? d / front : Math.exp(-((d - front) / FADE) * ((d - front) / FADE));
            double vl = Math.hypot(o.vx, o.vz);
            double ahead = vl < 1e-6 ? 0 : (dx * o.vx + dz * o.vz) / (d * vl);
            double bias = 0.65 + 0.35 * ahead;
            double gust = 0.8 + 0.2 * Math.sin(t * 0.09 + o.seed * 0.37) + 0.1 * Math.sin(t * 0.23 + d * 0.02);
            double speed = o.peak * profile * bias * gust;
            wx += dx / d * speed;
            wz += dz / d * speed;
        }
        return new double[]{wx, wz};
    }

    /** Whether any type gives outflow (cumulus congestus and cumulonimbus do). */
    static boolean gives(CloudType t) {
        return t.heap() && t.rain.rains();
    }
}
