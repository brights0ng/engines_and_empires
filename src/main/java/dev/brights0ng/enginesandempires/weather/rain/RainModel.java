package dev.brights0ng.enginesandempires.weather.rain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.CloudLife;
import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudFormation;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;
import dev.brights0ng.enginesandempires.weather.cloud.client.SupercellRain;

/**
 * Where it rains and snows, and how hard, from the clouds: the one model the renderer and gameplay both use, on the
 * server and on every client. Pure Java (the clouds come in as {@link CloudShape}s), so it is unit-tested.
 *
 * <h2>Rain cores (Bright, 2026-10-04: realistic, with different strengths)</h2>
 * Only some cloud types rain, and only under their precipitation core, at real-world strengths:
 * <ul>
 *   <li><b>Cumulus congestus:</b> brief light showers under the middle of each tower (peak 0.3).</li>
 *   <li><b>Cumulonimbus calvus / capillatus:</b> a central shaft, heaviest in the middle (peaks 0.75 / 0.95); thunder.</li>
 *   <li><b>Supercell:</b> heavy rain under the forward flank, a lighter rear flank wrapping behind the updraft, and dry
 *       under the updraft's base ({@link SupercellRain}); thunder.</li>
 *   <li><b>Nimbostratus:</b> steady moderate rain under nearly all of it (0.55).</li>
 *   <li><b>Stratocumulus / stratus:</b> drizzle (0.2 / 0.15).</li>
 *   <li>Fair-weather cumulus, vapour and cirrus: none.</li>
 * </ul>
 * Footprints are the clouds' drawn sizes (PA's radii stretched by {@link CloudScale#horizontal}, as the renderer does).
 * Strength rises once a cloud is well formed and tapers over its death; a lingering anvil is dry
 * ({@link CloudLife.Phase#precipitation}).
 *
 * <h2>Wind drift (decided 2026-10-02)</h2>
 * A drop falls from the cloud base at {@code v} while the wind it falls through differs from the cloud's own motion by
 * {@code du}, so it lands {@code du / v x (base - y)} downwind. A point is wet if the cloud rains at that much upwind
 * of it. Fall speeds: drizzle 4 m/s up to heavy rain 9 m/s, snow 1.2 m/s (no cap: snow drifts far). The wind is the
 * average of the surface and aloft winds at the point, in m/s; cloud velocities are blocks per second, which PA's wind
 * treats as m/s.
 */
public final class RainModel {

    public static final double DRIZZLE_FALL = 4;
    public static final double HEAVY_FALL = 9;
    public static final double SNOW_FALL = 1.2;
    /** Below this, no rain (PA's own threshold). */
    public static final double MIN = 0.02;

    /** How a cloud type rains: its peak strength, its core's radius (of the cloud's), and whether it is a sheet. */
    record Kind(double peak, double core, boolean sheet) {
    }

    /** How type {@code typeId} rains, or null if it doesn't. */
    static Kind kind(String typeId) {
        if (typeId == null) {
            return null;
        }
        String id = typeId.contains(":") ? typeId.substring(typeId.indexOf(':') + 1) : typeId;
        return switch (id) {
            case "cumulus_congestus" -> new Kind(0.3, 0.45, false);
            case "cumulonimbus_calvus" -> new Kind(0.75, 0.55, false);
            case "cumulonimbus_capillatus" -> new Kind(0.95, 0.6, false);
            case "supercell" -> new Kind(1.0, 1, false);
            case "nimbostratus" -> new Kind(0.55, 0.95, true);
            case "stratocumulus" -> new Kind(0.2, 0.7, true);
            case "stratus_nebulosus" -> new Kind(0.15, 0.9, true);
            default -> null;
        };
    }

    /** Whether type {@code typeId} brings thunder. */
    static boolean thunder(String typeId) {
        return typeId != null && (typeId.contains("cumulonimbus") || typeId.contains("supercell"));
    }

    /**
     * Where a cloud is drawn at time {@code t}, and how fast it moves (blocks per tick): raw (PA's) or the renderer's
     * smoothing and measured velocity.
     */
    public interface Positions {
        double x(CloudShape c, double t);

        double z(CloudShape c, double t);

        default double vx(CloudShape c) {
            return c.vx();
        }

        default double vz(CloudShape c) {
            return c.vz();
        }
    }

    public static final Positions RAW = new Positions() {
        @Override
        public double x(CloudShape c, double t) {
            return c.xAt(t);
        }

        @Override
        public double z(CloudShape c, double t) {
            return c.zAt(t);
        }
    };

    /** One rain core: a cluster's, world x and z, with its own lifecycle factor (a dissolving ghost tapers alone). */
    record Core(double x, double z, double radius, double peak, boolean sheet, double life) {
    }

    /** One cluster's footprint, for how covered a spot is (world x and z). */
    record Footprint(double x, double z, double radius, double cover) {
    }

    /**
     * One formation, ready to sample.
     *
     * @param vx    its drift, m/s
     * @param life  its precipitation lifecycle factor, 0-1
     * @param peak  its strongest rain, for its fall speed
     * @param ax    its anchor's world position (for the supercell's anchor-local layout)
     * @param reach how far from the anchor anything of it reaches (blocks)
     * @param storm the supercell's rain layout, or null
     */
    public record Cloud(UUID region, String type, boolean thunder, double baseY, double topY, double vx, double vz,
                        double life, double peak, double ax, double az, double reach, List<Core> cores,
                        List<Footprint> footprints, SupercellRain storm) {
    }

    /** The clouds {@code shapes} as formations ready to sample, at time {@code t}. */
    public static List<Cloud> build(List<CloudShape> shapes, double t, Positions p) {
        return build(shapes, t, p, null);
    }

    /**
     * The same, with each formation's drift velocity eased by {@code smoother} (null: as given), so the wet area
     * doesn't jump when a velocity steps.
     */
    public static List<Cloud> build(List<CloudShape> shapes, double t, Positions p, VelocitySmoother smoother) {
        Map<UUID, List<CloudShape>> byRegion = new LinkedHashMap<>();
        for (CloudShape c : shapes) {
            if (c.visible()) {
                byRegion.computeIfAbsent(c.regionId(), k -> new ArrayList<>()).add(c);
            }
        }
        List<Cloud> out = new ArrayList<>(byRegion.size());
        for (Map.Entry<UUID, List<CloudShape>> e : byRegion.entrySet()) {
            out.add(cloud(CloudFormation.of(e.getKey(), e.getValue()), t, p, smoother));
        }
        return out;
    }

    private static Cloud cloud(CloudFormation f, double t, Positions p, VelocitySmoother smoother) {
        List<CloudShape> members = f.members();
        CloudShape anchor = f.anchor();
        // Where the anchor is drawn: PA's centre (smoothed on the client) plus its layout offset.
        double ax = p.x(anchor, t) + f.originX();
        double az = p.z(anchor, t) + f.originZ();
        boolean supercell = SupercellRain.is(members);
        String type = supercell ? "supercell" : anchor.typeId();
        Kind kind = kind(type);
        double vx = 0, vz = 0, life = 0, base = Double.MAX_VALUE, top = -Double.MAX_VALUE, reach = 0;
        List<Core> cores = new ArrayList<>();
        List<Footprint> footprints = new ArrayList<>();
        for (CloudShape m : members) {
            vx += p.vx(m);
            vz += p.vz(m);
            double memberLife = new CloudLife.Phase(m.growth(), m.decay(), m.anvilDecay()).precipitation();
            life += memberLife;
            base = Math.min(base, m.baseY());
            top = Math.max(top, m.topY());
            double mx = ax + f.offsetX(m);
            double mz = az + f.offsetZ(m);
            double r = Math.max(4, m.radius() * CloudFormation.spread(m) * (0.75 + 0.25 * clamp01(m.growth())));
            footprints.add(new Footprint(mx, mz, r, m.effectiveCoverage()));
            if (kind != null && !supercell) {
                cores.add(new Core(mx, mz, r * kind.core(), kind.peak(), kind.sheet(), memberLife));
            }
            reach = Math.max(reach, Math.hypot(mx - ax, mz - az) + r);
        }
        int n = members.size();
        vx /= n;
        vz /= n;
        // A supercell rains as one storm (the members' average); other clouds per core.
        life = supercell ? life / n : 1;
        if (smoother != null) {
            double[] v = smoother.smooth(f.regionId(), vx, vz, (long) Math.floor(t));
            vx = v[0];
            vz = v[1];
        }
        SupercellRain storm = null;
        if (supercell) {
            double vl = Math.hypot(vx, vz);
            double dx = vl > 1e-6 ? vx / vl : 1;
            double dz = vl > 1e-6 ? vz / vl : 0;
            storm = SupercellRain.of(members, anchor, dx, dz);
            reach = Math.max(reach, storm.reach());
        }
        // Blocks per tick to blocks (metres) per second.
        return new Cloud(f.regionId(), type, thunder(type), base, top, vx * 20, vz * 20, life,
                kind == null ? 0 : kind.peak(), ax, az, reach, cores, footprints, storm);
    }

    /**
     * What falls at a spot.
     *
     * @param strength how hard, 0-1 (drizzle up to about 0.25, heavy from about 0.7)
     * @param thunder  whether it comes from a thunder cloud
     * @param cover    how covered the spot is by cloud overhead, 0-1 (rain or not)
     * @param region   the formation it comes from, or null
     * @param type     that formation's cloud type, or null
     */
    public record Sample(double strength, boolean thunder, double cover, UUID region, String type) {

        public static final Sample NONE = new Sample(0, false, 0, null, null);

        public boolean raining() {
            return strength > MIN;
        }
    }

    /**
     * What falls at (x, y, z), with the wind there (m/s, the average of surface and aloft) and whether it would be snow
     * there.
     */
    public static Sample sample(List<Cloud> clouds, double x, double y, double z, double windX, double windZ, boolean snow) {
        Cloud best = null;
        double bestStrength = 0;
        double cover = 0;
        for (Cloud c : clouds) {
            if (y > c.topY()) {
                continue;
            }
            if (Math.hypot(x - c.ax(), z - c.az()) <= c.reach()) {
                for (Footprint fp : c.footprints()) {
                    double d = Math.hypot(x - fp.x(), z - fp.z()) / fp.radius();
                    cover = Math.max(cover, fp.cover() * (1 - smooth(0.8, 1, d)));
                }
            }
            if (c.peak() <= 0 || c.life() <= 0) {
                continue;
            }
            double v = snow ? SNOW_FALL : DRIZZLE_FALL + (HEAVY_FALL - DRIZZLE_FALL) * Math.min(1, c.peak());
            double h = Math.max(0, c.baseY() - y);
            double qx = x - (windX - c.vx()) / v * h;
            double qz = z - (windZ - c.vz()) / v * h;
            if (Math.hypot(qx - c.ax(), qz - c.az()) > c.reach()) {
                continue;
            }
            double s = rainAt(c, qx, qz) * c.life();
            if (s > bestStrength) {
                bestStrength = s;
                best = c;
            }
        }
        if (best == null || bestStrength <= MIN) {
            return cover > 0 ? new Sample(0, false, cover, null, null) : Sample.NONE;
        }
        return new Sample(Math.min(1, bestStrength), best.thunder(), Math.max(cover, bestStrength), best.region(),
                best.type());
    }

    /** How hard cloud {@code c} rains at world (x, z), before its lifecycle. */
    static double rainAt(Cloud c, double x, double z) {
        if (c.storm() != null) {
            return c.storm().rain(x - c.ax(), z - c.az());
        }
        double s = 0;
        for (Core core : c.cores()) {
            double d = Math.hypot(x - core.x(), z - core.z()) / core.radius();
            if (d >= 1) {
                continue;
            }
            double profile = core.sheet() ? 1 - smooth(0.75, 1, d) : 1 - smooth(0.3, 1, d);
            s = Math.max(s, core.peak() * profile * core.life());
        }
        return s;
    }

    static double smooth(double e0, double e1, double v) {
        double t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    private RainModel() {
    }
}
