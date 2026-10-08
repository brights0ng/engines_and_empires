package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Wisps for one heap cloud (stage 3 of the cloud look, 2026-10-07, Bright: soft haze along the edges and ragged shreds
 * under the base, each drifting off and fading over 10-30 s; more on forming and dying clouds). Pure Java: the
 * renderer ({@link CloudWispRenderer}) keeps one of these per nearby cumulus and draws them as soft camera-facing
 * sprites.
 *
 * <ul>
 *   <li><b>Haze</b> sits on the cloud's surface, on the upper and outer sides of its bubbles, half over the edge, so
 *       the hard outline softens. It drifts slowly outward and up.</li>
 *   <li><b>Shreds</b> hang just under the flat base: torn, stretched tatters that drift outward and a little down.</li>
 * </ul>
 * Each is lit once, when it appears, the way the cloud is at that spot ({@link #light}): a sky part and a sun part, coloured
 * with the time of day like the cloud ({@link CloudColours}).
 *
 * <p>Positions are anchor-local x and z (the cloud's meshes' frame) and world y, as of the cloud's drawn generation.
 */
final class CloudWisps {

    static final int HAZE = 0;
    static final int SHRED = 1;

    /** Wisps per cloud bubble, before {@link CloudTuning#wispDensity}; and the fewest and most for one cloud. */
    static final double PER_BUBBLE = 1.5;
    static final int MIN_PER_CLOUD = 12;
    static final int MAX_PER_CLOUD = 300;
    /** How many more a cloud has while forming or dying (at the start of its birth, or the end of its death). */
    static final double FORMING_EXTRA = 1.5;
    static final double DYING_EXTRA = 1.5;
    /** The share of wisps that are shreds under the base. */
    static final double SHRED_SHARE = 0.2;
    /** Lifetimes, ticks (10-30 s). */
    static final double LIFE_MIN = 200;
    static final double LIFE_MAX = 600;
    /** The most new wisps per cloud per tick, so a cloud fills up over a second or two rather than at once. */
    static final int SPAWN_PER_TICK = 4;
    /** Peak opacity of haze and of shreds (before {@link CloudTuning#wispOpacity}). */
    static final double HAZE_ALPHA = 0.6;
    static final double SHRED_ALPHA = 0.4;

    /** One wisp. Velocities are blocks per tick. */
    static final class Wisp {
        int kind;
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        /** Half its width, blocks; shreds are {@link #stretch} times as tall as wide. */
        double size;
        double stretch = 1;
        /** Turned this far in the view (haze), radians. */
        double turn;
        double born;
        double life;
        /**
         * Its light, as a cloud vertex's (CloudVoxelizer.pack): the sky's light, the most of the sun's it can take,
         * how much gets through the cloud toward the sun, and its direction; lit live like the clouds
         * ({@link CloudShading}).
         */
        double sky;
        double sunScale;
        double through = 1;
        double nx;
        double ny = 1;
        double nz;

        /** {sky part, sun part} under the light toward (lx, ly, lz) at {@code strength} (CloudShading). */
        double[] parts(double lx, double ly, double lz, double strength) {
            return CloudShading.parts(sky, sunScale, through, nx, ny, nz, lx, ly, lz, strength, CloudTuning.shadowSide);
        }

        /** Where it is {@code age} ticks after it appeared: x, y, z. */
        double[] at(double time) {
            double age = time - born;
            return new double[]{x + vx * age, y + vy * age, z + vz * age};
        }

        /** Its opacity at {@code time}: fading in over the first quarter of its life, out over the last 45%. */
        double alpha(double time) {
            double t = (time - born) / life;
            if (t <= 0 || t >= 1) {
                return 0;
            }
            double in = smooth(t / 0.25);
            double out = smooth((1 - t) / 0.45);
            return Math.min(in, out) * (kind == HAZE ? HAZE_ALPHA : SHRED_ALPHA) * CloudTuning.wispOpacity;
        }
    }

    final List<Wisp> wisps = new ArrayList<>();
    private final SplittableRandom rng;
    private boolean filled;

    CloudWisps(long seed) {
        this.rng = new SplittableRandom(seed);
    }

    /** How many wisps cloud {@code f} should have. */
    static int target(CloudField f) {
        int bubbles = 0;
        double growth = 0;
        int heaps = 0;
        for (CloudField.Member m : f.members) {
            if (m.cu != null) {
                bubbles += m.cu.n;
                growth += m.growth;
                heaps++;
            }
        }
        if (heaps == 0) {
            return 0;
        }
        growth /= heaps;
        double n = Math.max(MIN_PER_CLOUD, Math.min(MAX_PER_CLOUD, bubbles * PER_BUBBLE));
        n *= 1 + FORMING_EXTRA * (1 - growth) + DYING_EXTRA * f.dying;
        return (int) Math.round(n * CloudTuning.wispDensity);
    }

    /**
     * Once a tick at {@code time}: drops expired wisps and adds new ones on cloud {@code f} (sampled as of its
     * generation's time {@code fieldTime}), up to {@code room} more in all.
     */
    void tick(CloudField f, double fieldTime, double time, int room) {
        wisps.removeIf(w -> time - w.born >= w.life);
        int want = target(f) - wisps.size();
        // The first time, fill up at once with wisps already part-way through their lives.
        int spawn = Math.min(room, filled ? Math.min(want, SPAWN_PER_TICK) : want);
        for (int k = 0, tries = 0; k < spawn && tries < spawn * 4; tries++) {
            Wisp w = rng.nextDouble() < SHRED_SHARE ? shred(f, fieldTime) : haze(f, fieldTime);
            if (w == null) {
                continue;
            }
            w.life = LIFE_MIN + (LIFE_MAX - LIFE_MIN) * rng.nextDouble();
            w.born = filled ? time : time - w.life * 0.8 * rng.nextDouble();
            wisps.add(w);
            k++;
        }
        filled = true;
    }

    /** A haze wisp on the surface of a random bubble's upper or outer side, or null if that spot is buried. */
    Wisp haze(CloudField f, double t) {
        CloudField.Member m = pickMember(f);
        if (m == null) {
            return null;
        }
        Cumulus.Anim a = m.anim(t);
        int b = rng.nextInt(m.cu.n);
        if (a.r()[b] < 1) {
            return null;
        }
        // A direction on the bubble, not much below sideways (the base stays crisp).
        double dy = -0.2 + 1.2 * rng.nextDouble();
        double ang = rng.nextDouble() * Math.PI * 2;
        double h = Math.sqrt(Math.max(0, 1 - dy * dy));
        double dx = h * Math.cos(ang), dz = h * Math.sin(ang);
        double r = a.r()[b];
        // March out from inside the bubble to the cloud's surface along that direction.
        CloudField.Column c = f.newColumn();
        double step = Math.max(1.5, r * 0.08);
        double px = 0, py = 0, pz = 0;
        boolean found = false;
        for (double s = r * 0.7; s <= r * 2.5; s += step) {
            px = a.x()[b] + dx * s;
            py = a.y()[b] + dy * s * Cumulus.SQUASH;
            pz = a.z()[b] + dz * s;
            f.column(px, pz, t, c);
            if (f.density(c, px, py, pz, t, false) <= 0) {
                found = s > r * 0.7;
                break;
            }
        }
        if (!found || py < f.baseY + 3) {
            return null;
        }
        Wisp w = new Wisp();
        w.kind = HAZE;
        w.size = Math.max(4, Math.min(80, r * (0.5 + 0.4 * rng.nextDouble())));
        // Half over the edge.
        w.x = px - dx * w.size * 0.3;
        w.y = py - dy * w.size * 0.3;
        w.z = pz - dz * w.size * 0.3;
        double speed = (0.2 + 0.4 * rng.nextDouble()) / 20;
        w.vx = dx * speed;
        w.vy = dy * speed + 0.15 / 20;
        w.vz = dz * speed;
        w.turn = rng.nextDouble() * Math.PI * 2;
        light(f, t, w, dx, dy, dz);
        return w;
    }

    /** A shred hanging under the base, or null if there is no base at that spot. */
    Wisp shred(CloudField f, double t) {
        CloudField.Member m = pickMember(f);
        if (m == null) {
            return null;
        }
        double ang = rng.nextDouble() * Math.PI * 2;
        double d = m.r * 0.85 * Math.sqrt(rng.nextDouble());
        double x = m.cx + Math.cos(ang) * d, z = m.cz + Math.sin(ang) * d;
        CloudField.Column c = f.newColumn();
        f.column(x, z, t, c);
        if (f.density(c, x, m.yb + 3, z, t, false) <= 0) {
            return null;
        }
        Wisp w = new Wisp();
        w.kind = SHRED;
        w.size = Math.max(4, Math.min(50, m.r * (0.1 + 0.1 * rng.nextDouble())));
        w.stretch = 1.1 + 0.6 * rng.nextDouble();
        w.x = x;
        w.z = z;
        // Hanging from the base: its top just inside it.
        w.y = m.yb - w.size * w.stretch * (0.4 + 0.4 * rng.nextDouble());
        double ox = x - m.cx, oz = z - m.cz, ol = Math.max(1e-6, Math.hypot(ox, oz));
        double speed = (0.15 + 0.3 * rng.nextDouble()) / 20;
        w.vx = ox / ol * speed;
        w.vz = oz / ol * speed;
        w.vy = -0.08 / 20;
        light(f, t, w, 0, -1, 0);
        return w;
    }

    private CloudField.Member pickMember(CloudField f) {
        List<CloudField.Member> heaps = new ArrayList<>();
        for (CloudField.Member m : f.members) {
            if (m.cu != null && m.cu.n > 0) {
                heaps.add(m);
            }
        }
        return heaps.isEmpty() ? null : heaps.get(rng.nextInt(heaps.size()));
    }

    /**
     * Lights wisp {@code w}, facing (nx, ny, nz), the way the cloud's surface is lit there (CloudVoxelizer's heap
     * colour without the creases): the sky's part, darker under a thick cloud, and the sun's (or moon's) part.
     */
    static void light(CloudField f, double t, Wisp w, double nx, double ny, double nz) {
        double facingShade = 0.875 + 0.125 * ny - 0.03 * Math.abs(nz);
        double down = Math.max(0, -ny);
        double above = Math.max(0, (f.topY - f.baseY) * 0.5);
        // Thin, so a little brighter than the cloud's own base would be.
        double under = 0.4 + 0.6 * CloudVoxelizer.brightness(above, f.water);
        double sky = (1 - down + down * under) * facingShade;
        double depth = f.lightDepth(w.x + nx * 2, w.y + ny * 2, w.z + nz * 2, f.lightX, f.lightY, f.lightZ, t);
        double through = CloudVoxelizer.brightness(depth, f.water * CloudVoxelizer.LIGHT_WATER);
        through = (through - CloudTuning.baseLight) / (1 - CloudTuning.baseLight);
        double[] shadow = f.shadowAt(w.x, w.y, w.z);
        w.sky = sky * shadow[0];
        w.sunScale = facingShade * shadow[1];
        w.through = through;
        w.nx = nx;
        w.ny = ny;
        w.nz = nz;
    }

    private static double smooth(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }
}
