package dev.brights0ng.enginesandempires.weather.field;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.enginesandempires.weather.sim.FrontGeometry;
import dev.brights0ng.enginesandempires.weather.sim.SimMath;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;

/**
 * Where the weather systems push warm and cold air (phase 3). A coarse grid smears fronts into mush on its own, so the
 * systems keep the air masses distinct: the field relaxes toward these targets where a system is strong. Pure.
 *
 * <ul>
 *   <li><b>Lows:</b> inside the warm sector (the wedge between the warm and cold fronts, measured from the triple
 *       point) the air is pushed warmer; everywhere else round the low, colder: cold air wraps round behind the cold
 *       front. Cold outbreaks are stronger than warm surges. All strongest in winter (Bright, 2026-10-05: realistic:
 *       a typical cold front drops 5-10 C, a strong winter outbreak 15-20 C together with advection).</li>
 *   <li><b>Blocking highs:</b> heat waves in summer, cold snaps in winter; ordinary highs a little of the same.</li>
 * </ul>
 * Each pushes with a weight falling off with distance (1.8 radii for lows, 1 for highs) and with its strength; several
 * systems' pushes are averaged by weight. {@link #prepare} works out each system's geometry once per step.
 */
public final class AirMassContact {

    static final double WARM = 4;
    static final double COLD = 6;
    static final double BLOCK = 5;
    static final double HIGH = 1.5;

    private record Prepared(boolean low, double x, double z, double reach, double strength, double offset,
                            double tripleX, double tripleZ, int hemisphere, double coldAngle, double warmOffset) {
    }

    private final List<Prepared> prepared;

    private AirMassContact(List<Prepared> prepared) {
        this.prepared = prepared;
    }

    /** The pushes of {@code systems} in season {@code season} (-1 winter to +1 summer). */
    public static AirMassContact prepare(List<WeatherSystem> systems, double season) {
        double s = Double.isFinite(season) ? Math.max(-1, Math.min(1, season)) : 0;
        double amp = 1 - 0.35 * s;
        List<Prepared> out = new ArrayList<>();
        for (WeatherSystem sys : systems) {
            double strength = SimMath.clamp01(sys.strength() / Math.max(1e-6, sys.peak));
            if (sys.kind == WeatherSystem.Kind.LOW) {
                List<FrontGeometry.Front> fronts = FrontGeometry.of(sys);
                if (fronts.isEmpty()) {
                    continue;
                }
                double[] triple = fronts.stream().filter(f -> f.type() == FrontGeometry.Type.WARM).findFirst()
                        .map(f -> f.points().get(0)).orElse(new double[]{sys.x, sys.z});
                out.add(new Prepared(true, sys.x, sys.z, 1.8 * sys.radius(), strength, -COLD * amp * strength,
                        triple[0], triple[1], sys.hemisphere, FrontGeometry.coldAngle(sys.life()),
                        WARM * amp * strength));
            } else {
                out.add(new Prepared(false, sys.x, sys.z, sys.radius(), 0.6 * strength,
                        (sys.blocking ? BLOCK : HIGH) * s, 0, 0, sys.hemisphere, 0, 0));
            }
        }
        return new AirMassContact(out);
    }

    /** {offset C, weight 0-1} at (x, z). */
    public double[] at(double x, double z) {
        double sumW = 0;
        double sumO = 0;
        for (Prepared p : prepared) {
            double dx = x - p.x;
            double dz = z - p.z;
            double d2 = dx * dx + dz * dz;
            if (d2 > 9 * p.reach * p.reach) {
                continue;
            }
            double w = p.strength * Math.exp(-d2 / (p.reach * p.reach));
            double offset = p.offset;
            if (p.low) {
                double angle = Math.atan2(p.hemisphere * (z - p.tripleZ), x - p.tripleX);
                if (angle > FrontGeometry.WARM_ANGLE && angle < p.coldAngle) {
                    offset = p.warmOffset;
                }
            }
            sumW += w;
            sumO += w * offset;
        }
        if (sumW < 1e-6) {
            return new double[]{0, 0};
        }
        return new double[]{sumO / sumW, Math.min(1, sumW)};
    }

    /** Only the pushes that can reach the rectangle [minX, maxX] x [minZ, maxZ] (they fade out by 3 reaches). */
    public AirMassContact near(double minX, double minZ, double maxX, double maxZ) {
        List<Prepared> out = new ArrayList<>();
        for (Prepared p : prepared) {
            double dx = Math.max(0, Math.max(minX - p.x, p.x - maxX));
            double dz = Math.max(0, Math.max(minZ - p.z, p.z - maxZ));
            if (dx * dx + dz * dz <= 9 * p.reach * p.reach) {
                out.add(p);
            }
        }
        return new AirMassContact(out);
    }
}
