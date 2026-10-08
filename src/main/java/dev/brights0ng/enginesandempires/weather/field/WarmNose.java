package dev.brights0ng.enginesandempires.weather.field;

import java.util.List;
import java.util.function.DoubleBinaryOperator;

import dev.brights0ng.enginesandempires.weather.sim.FrontGeometry;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;

/**
 * The warm layer aloft ahead of warm fronts (phase 5a, 2026-10-07): what turns snow into sleet and freezing rain. At a
 * warm front the warm sector's air slides up over the cold air ahead on a shallow slope, so for some way ahead of the
 * front's position on the ground the air a little way up is warmer than at the ground (an inversion, the "warm nose").
 * Pure.
 *
 * <ul>
 *   <li><b>Reach:</b> {@link #OVERRUN} blocks ahead of the front (a real warm front's slope of about 1:150 puts its warm
 *       air 1.5 km up some 200 km ahead; the systems' map is about 170 m a block).</li>
 *   <li><b>Warmth:</b> the warm sector's own air, sampled {@link #WARM_SAMPLE} blocks across the front, less
 *       {@link #NOSE_LAPSE} C for the height of the nose, and colder still further ahead, where it rides higher
 *       ({@link #NOSE_COOL} C at the reach's end).</li>
 *   <li><b>Depth:</b> the warm layer is deepest just ahead of the front and thins away from it; the cold layer under it
 *       deepens.</li>
 * </ul>
 * The result is a melt index (warmth above 0 C times the warm layer's depth share, times the front's strength) and the
 * cold layer's depth (0-1), for {@code Precip.decide}.
 */
public final class WarmNose {

    public static final double OVERRUN = 1500;
    static final double WARM_SAMPLE = 800;
    static final double NOSE_LAPSE = 3;
    static final double NOSE_COOL = 3;

    /** None: no warm layer aloft. */
    public static final double[] NONE = {0, 0};

    /**
     * {melt, coldDepth} at (x, z) under the lows in {@code systems}, with {@code surface} giving the air temperature at
     * sea level at any point (C).
     */
    public static double[] at(List<WeatherSystem> systems, double x, double z, DoubleBinaryOperator surface) {
        double bestMelt = 0;
        double bestCold = 0;
        for (WeatherSystem low : systems) {
            if (low.kind != WeatherSystem.Kind.LOW) {
                continue;
            }
            // Cheap first: too far from the low for its fronts to matter.
            double reach = 1.5 * low.radius() + OVERRUN;
            if ((x - low.x) * (x - low.x) + (z - low.z) * (z - low.z) > reach * reach) {
                continue;
            }
            FrontGeometry.Front warm = null;
            for (FrontGeometry.Front f : FrontGeometry.of(low)) {
                if (f.type() == FrontGeometry.Type.WARM) {
                    warm = f;
                }
            }
            if (warm == null || warm.strength() <= 0) {
                continue;
            }
            double[] d = FrontGeometry.distance(warm.points(), x, z);
            if (d[0] >= OVERRUN || d[1] <= 0.0 || d[1] >= 1.0) {
                continue;
            }
            double[] triple = warm.points().get(0);
            double angle = Math.atan2(low.hemisphere * (z - triple[1]), x - triple[0]);
            // Ahead of the warm front: on its cold side (toward the pole), not round behind the low.
            if (angle >= FrontGeometry.WARM_ANGLE || angle < FrontGeometry.WARM_ANGLE - Math.toRadians(80)) {
                continue;
            }
            double frac = d[0] / OVERRUN;
            // Across the front, into the warm sector.
            double a = FrontGeometry.WARM_ANGLE + Math.PI / 2;
            double step = d[0] + WARM_SAMPLE;
            double warmAir = surface.applyAsDouble(x + Math.cos(a) * step, z + low.hemisphere * Math.sin(a) * step);
            double nose = warmAir - NOSE_LAPSE - NOSE_COOL * frac;
            double melt = Math.max(0, nose) * (1 - frac) * warm.strength();
            if (melt > bestMelt) {
                bestMelt = melt;
                bestCold = frac;
            }
        }
        return bestMelt <= 0 ? NONE : new double[]{bestMelt, bestCold};
    }

    private WarmNose() {
    }
}
