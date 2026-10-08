package dev.brights0ng.enginesandempires.weather.rain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.CloudLife;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudFormation;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;

/**
 * Where it rains and snows, and how hard, from the clouds: the one model the renderer and gameplay both use, on the
 * server and on every client. Pure Java (the clouds come in as {@link CloudShape}s), so it is unit-tested.
 *
 * <h2>Rain cores (Bright, 2026-10-04: realistic, with different strengths)</h2>
 * Only some cloud types rain ({@link CloudType#rain}), and only under their precipitation core, at real-world
 * strengths: cumulus congestus brief light showers, cumulonimbus a central shaft (with thunder), nimbostratus steady
 * moderate rain under nearly all of it, stratocumulus and stratus drizzle, altostratus at most light rain. Each cloud's
 * strength is its type's peak times how hard the simulation has it raining ({@link CloudShape#precipitation}: 0 in
 * phase 4a, so nothing rains yet).
 *
 * <p>Footprints are the clouds' drawn sizes. Strength rises once a cloud is well formed and tapers over its death; a
 * lingering anvil is dry ({@link CloudLife.Phase#precipitation}).
 *
 * <h2>Virga (Bright, 2026-10-06)</h2>
 * Under a high base in dry air, rain evaporates on the way down: below a cloud's {@link CloudShape#rainBottom} nothing
 * arrives (easing out over the {@value #VIRGA_FADE} blocks above it), so the ground stays dry under a visibly raining
 * cloud, while a ship flying higher up is rained on.
 *
 * <h2>Wind drift (decided 2026-10-02)</h2>
 * A drop falls from the cloud base at {@code v} while the wind it falls through differs from the cloud's own motion by
 * {@code du}, so it lands {@code du / v x (base - y)} downwind. A point is wet if the cloud rains at that much upwind
 * of it. Fall speeds: drizzle 4 m/s up to heavy rain 9 m/s, snow 1.2 m/s (no cap: snow drifts far). The wind is the
 * average of the surface and aloft winds at the point, in m/s; cloud velocities come in blocks per tick and are turned
 * back into the wind that carries them (the field's {@value #ADVECTION} blocks a second per m/s).
 */
public final class RainModel {

    public static final double DRIZZLE_FALL = 4;
    public static final double HEAVY_FALL = 9;
    public static final double SNOW_FALL = 1.2;
    /** Below this, no rain. */
    public static final double MIN = 0.02;

    /** Share of a cloud's rain that reaches the ground in the driest air (deserts: light rain at most). */
    public static final double DRY_SHARE = 0.15;
    /** Regional humidity below which the air is as dry as it gets, and from which rain arrives in full. */
    public static final double DRY_HUMIDITY = 0.1;
    public static final double WET_HUMIDITY = 0.45;

    /**
     * How much of a cloud's rain arrives in air of regional humidity {@code humidity} (0-1): {@link #DRY_SHARE} in a
     * desert, rising smoothly to all of it from {@link #WET_HUMIDITY} (temperate country). Replaces the biomes' own
     * on/off precipitation flag (Bright, 2026-10-08).
     */
    public static double wetness(double humidity) {
        return DRY_SHARE + (1 - DRY_SHARE) * smooth(DRY_HUMIDITY, WET_HUMIDITY, humidity);
    }
    /** Blocks per second of cloud motion per m/s of wind ({@code AtmosphereField.ADVECTION}). */
    public static final double ADVECTION = 0.4;
    /** Blocks over which rain evaporates away above its bottom (virga). */
    public static final double VIRGA_FADE = 30;

    /** How a cloud type rains, or null if it doesn't. */
    static CloudType.Rain kind(String typeId) {
        CloudType t = CloudType.of(typeId);
        return t == null || !t.rain.rains() ? null : t.rain;
    }

    /** Whether type {@code typeId} brings thunder. */
    static boolean thunder(String typeId) {
        CloudType t = CloudType.of(typeId);
        return t != null && t.thunder;
    }

    /**
     * Where a cloud is drawn at time {@code t}, and how fast it moves (blocks per tick): raw or the renderer's
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

    /** One rain core: a dome's, world x and z, with its own strength factor. */
    record Core(double x, double z, double radius, double peak, boolean sheet, double life) {
    }

    /** One dome's footprint, for how covered a spot is (world x and z). */
    record Footprint(double x, double z, double radius, double cover) {
    }

    /**
     * One formation, ready to sample.
     *
     * @param vx    its drift, as the wind that carries it, m/s
     * @param life  its precipitation lifecycle factor, 0-1
     * @param peak  its strongest rain, for its fall speed
     * @param ax    its anchor's world position
     * @param reach how far from the anchor anything of it reaches (blocks)
     */
    public record Cloud(UUID region, String type, boolean thunder, double baseY, double topY, double vx, double vz,
                        double life, double peak, double ax, double az, double reach, List<Core> cores,
                        List<Footprint> footprints, double rainBottom, double convection) {
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
        double ax = p.x(anchor, t);
        double az = p.z(anchor, t);
        String type = anchor.typeId();
        CloudType.Rain kind = kind(type);
        double vx = 0, vz = 0, base = Double.MAX_VALUE, top = -Double.MAX_VALUE, reach = 0, peak = 0;
        double bottom = Double.NEGATIVE_INFINITY;
        double convection = 0;
        List<Core> cores = new ArrayList<>();
        List<Footprint> footprints = new ArrayList<>();
        for (CloudShape m : members) {
            vx += p.vx(m);
            vz += p.vz(m);
            double memberLife = new CloudLife.Phase(m.growth(), m.decay(), m.anvilDecay()).precipitation();
            base = Math.min(base, m.baseY());
            top = Math.max(top, m.topY());
            bottom = Math.max(bottom, m.rainBottom());
            convection = Math.max(convection, clamp01(m.lightning()));
            double mx = ax + f.offsetX(m);
            double mz = az + f.offsetZ(m);
            double r = Math.max(4, m.radius() * (0.75 + 0.25 * clamp01(m.growth())));
            footprints.add(new Footprint(mx, mz, r, m.effectiveCoverage()));
            double strength = kind == null ? 0 : kind.peak() * clamp01(m.precipitation());
            if (strength > 0) {
                cores.add(new Core(mx, mz, r * kind.core(), strength, kind.sheet(), memberLife));
                peak = Math.max(peak, strength);
            }
            reach = Math.max(reach, Math.hypot(mx - ax, mz - az) + r);
        }
        int n = members.size();
        vx /= n;
        vz /= n;
        if (smoother != null) {
            double[] v = smoother.smooth(f.regionId(), vx, vz, (long) Math.floor(t));
            vx = v[0];
            vz = v[1];
        }
        // Blocks per tick to blocks per second, then to the wind that carries the cloud (m/s).
        double toWind = 20 / ADVECTION;
        return new Cloud(f.regionId(), type, thunder(type), base, top, vx * toWind, vz * toWind, 1, peak, ax, az, reach,
                cores, footprints, bottom, convection);
    }

    /**
     * What falls at a spot.
     *
     * @param strength how hard, 0-1 (drizzle up to about 0.25, heavy from about 0.7)
     * @param thunder  whether it comes from a thunder cloud
     * @param cover    how covered the spot is by cloud overhead, 0-1 (rain or not)
     * @param region   the formation it comes from, or null
     * @param type     that formation's cloud type, or null
     * @param hail     whether a hail burst is falling here (strong thunderstorm cores only, {@link #hailAt})
     */
    public record Sample(double strength, boolean thunder, double cover, UUID region, String type, boolean hail) {

        public static final Sample NONE = new Sample(0, false, 0, null, null, false);

        public Sample(double strength, boolean thunder, double cover, UUID region, String type) {
            this(strength, thunder, cover, region, type, false);
        }

        public boolean raining() {
            return strength > MIN;
        }
    }

    /**
     * What falls at (x, y, z), with the wind there (m/s, the average of surface and aloft) and whether it would be snow
     * there.
     */
    public static Sample sample(List<Cloud> clouds, double x, double y, double z, double windX, double windZ, boolean snow) {
        return sample(clouds, x, y, z, windX, windZ, snow ? Precip.SNOW : Precip.RAIN, Double.NaN);
    }

    /**
     * What falls at (x, y, z), with the wind there (m/s), what kind it would be there (for how far the wind carries it
     * on the way down) and the game time (for hail bursts; NaN for none).
     */
    public static Sample sample(List<Cloud> clouds, double x, double y, double z, double windX, double windZ,
                                Precip kind, double time) {
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
            double virga = 1;
            if (y < c.rainBottom() + VIRGA_FADE) {
                if (y <= c.rainBottom()) {
                    continue;
                }
                virga = smooth(0, 1, (y - c.rainBottom()) / VIRGA_FADE);
            }
            double v = kind.fallMps(c.peak());
            double h = Math.max(0, c.baseY() - y);
            double qx = x - (windX - c.vx()) / v * h;
            double qz = z - (windZ - c.vz()) / v * h;
            if (Math.hypot(qx - c.ax(), qz - c.az()) > c.reach()) {
                continue;
            }
            double s = rainAt(c, qx, qz) * c.life() * virga;
            if (s > bestStrength) {
                bestStrength = s;
                best = c;
            }
        }
        if (best == null || bestStrength <= MIN) {
            return cover > 0 ? new Sample(0, false, cover, null, null) : Sample.NONE;
        }
        double strength = Math.min(1, bestStrength);
        return new Sample(strength, best.thunder(), Math.max(cover, bestStrength), best.region(), best.type(),
                Double.isFinite(time) && hailAt(best, strength, time));
    }

    /** Hail bursts: windows of this many ticks, each with or without a burst. */
    static final long HAIL_WINDOW = 1200;

    /**
     * Whether cloud {@code c} drops hail where it rains {@code strength} at game time {@code time} (phase 5a): only
     * thunderstorms with strong updrafts (their lightning, standing for how strong the convection is, above 0.35), only
     * in the heart of the shaft (strength above 0.55), and in bursts: each minute-long window has a burst with a chance
     * rising with the convection (up to 60%), lasting a quarter to over half of it, at a time fixed by the cloud and the
     * window, so the server and every client agree.
     */
    public static boolean hailAt(Cloud c, double strength, double time) {
        if (!c.thunder() || c.convection() < 0.35 || strength < 0.55 || c.region() == null) {
            return false;
        }
        long window = (long) Math.floor(time / HAIL_WINDOW);
        long h = c.region().getMostSignificantBits() ^ c.region().getLeastSignificantBits() ^ window * 0x9E3779B97F4A7C15L;
        double chance = 0.6 * smooth(0.35, 1, c.convection());
        if (unit(h, 1) >= chance) {
            return false;
        }
        double start = 0.5 * unit(h, 2);
        double length = 0.25 + 0.3 * unit(h, 3);
        double into = (time - window * HAIL_WINDOW) / HAIL_WINDOW;
        return into >= start && into < start + length;
    }

    private static double unit(long h, int salt) {
        long z = h + salt * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    /** How hard cloud {@code c} rains at world (x, z), before its lifecycle. */
    static double rainAt(Cloud c, double x, double z) {
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
