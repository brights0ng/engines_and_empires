package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A heap cloud's shape as round bubbles (Bright, 2026-10-06: "bubbles make the shape"; the earlier dome, narrow
 * tower column and big swirling noise looked like dough and metal pillars). Pure Java, from the cloud's seed, so every
 * player sees the same cloud.
 *
 * <h2>Levels</h2>
 * <ol>
 *   <li><b>Body</b> (level 0): a wide base layer (a centre bubble and a ring) sitting on the flat base, then, as tall
 *       as the cloud is, stacked levels of bubble clusters, each a little narrower than the one below and wandering a
 *       little (leaning downwind for towers), ending in a rounded head whose top is the cloud's top. A tower is the
 *       whole stack: most of the cloud's width at the bottom, rounded all the way up, never a column. Congestus and
 *       storms have a broad body with one to four towers of different heights.</li>
 *   <li><b>Turrets</b> (level 1): two or three smaller bubbles on the upper and outer side of each body bubble.</li>
 *   <li>(A third, smaller level, the puffs, was dropped on 2026-10-07: they stuck out like boils. No bubble is
 *       smaller than {@link #minShare} of the cloud's radius; fine texture comes from the field's churn noise. Small
 *       clouds get fewer, chunkier bubbles: {@link #fewer}.)</li>
 * </ol>
 * No bubble reaches below the base, which the field cuts flat.
 *
 * <h2>Churn</h2>
 * The cloud boils by its bubbles ({@link #animate}): body bubbles breathe a little (±5% over 2 minutes); turrets swell
 * and push out, then shrink back, over 90 seconds; puffs the same over a minute, more strongly. Phases are staggered,
 * so the surface is always changing while the silhouette stays recognisably the same cloud. Nothing pops: sizes change
 * smoothly and never reach zero.
 *
 * <h2>Dying</h2>
 * A dying cloud frays and breaks up (Bright, 2026-10-07) rather than shrinking as one lump: each bubble has its own
 * turn ({@link #order}, 0 first to 1 last) and over its turn shrinks away to nothing while sinking a little. Turrets go
 * before the bubble they sit on, tops before bases and the outside before the middle, with a good share of chance, so
 * the cloud loses its crisp tops first, then comes apart into ragged pieces that dwindle one by one
 * ({@link #animate(double, double)}).
 *
 * <h2>Forming</h2>
 * The bubbles are laid out for the grown cloud from the start, so a forming cloud never reshapes (Bright, 2026-10-07):
 * instead each bubble has a turn to form ({@link #birth}), bottom first, and over it swells from nothing while rising
 * into place. The cloud starts as a few lumps on its base, builds upward level by level and gets its turrets last.
 */
final class Cumulus {

    static final int BODY = 0;
    static final int TURRET = 1;
    static final int PUFF = 2;

    /** Bubbles are a little flatter than round: height over width. */
    static final double SQUASH = 0.85;

    static final double BODY_PERIOD = 2400;
    static final double TURRET_PERIOD = 1800;
    static final double PUFF_PERIOD = 1200;

    /** Rest positions and sizes (anchor-local x and z, world y, blocks), level, phase (0-1) and push direction. */
    final double[] x;
    final double[] y;
    final double[] z;
    final double[] r;
    final int[] level;
    final double[] phase;
    final double[] ux;
    final double[] uy;
    final double[] uz;
    /** Each bubble's turn to evaporate in a death, 0 (first) to 1 (last). */
    final double[] order;
    /** Each bubble's turn to form in a birth, 0 (first) to 1 (last). */
    final double[] birth;
    /** Bubbles of levels 0-1 come first ({@link #withoutPuffs}), then the puffs: {@link #n} in all. */
    final int withoutPuffs;
    final int n;
    /** The member's centre and base, and how far any bubble reaches from the centre (horizontally) and up. */
    final double cx;
    final double cz;
    final double base;
    final double reach;
    final double top;

    private Cumulus(List<double[]> body, List<double[]> turrets, List<double[]> puffs, double cx, double cz,
                    double base) {
        int nb = body.size() + turrets.size();
        this.n = nb + puffs.size();
        this.withoutPuffs = nb;
        x = new double[n];
        y = new double[n];
        z = new double[n];
        r = new double[n];
        level = new int[n];
        phase = new double[n];
        ux = new double[n];
        uy = new double[n];
        uz = new double[n];
        order = new double[n];
        birth = new double[n];
        this.cx = cx;
        this.cz = cz;
        this.base = base;
        double reach = 0, top = base;
        int i = 0;
        int[] parent = new int[n];
        for (List<double[]> list : List.of(body, turrets, puffs)) {
            for (double[] b : list) {
                x[i] = b[0];
                y[i] = b[1];
                z[i] = b[2];
                r[i] = b[3];
                level[i] = (int) b[4];
                phase[i] = b[5];
                ux[i] = b[6];
                uy[i] = b[7];
                uz[i] = b[8];
                parent[i] = b.length > 9 ? (int) b[9] : -1;
                double grow = maxScale(level[i]) * r[i] + push(level[i]) * r[i];
                reach = Math.max(reach, Math.hypot(x[i] - cx, z[i] - cz) + grow);
                top = Math.max(top, y[i] + grow * SQUASH);
                i++;
            }
        }
        this.reach = reach;
        this.top = top;
        // ---- the order bubbles evaporate in a death: body bubbles by height, then how far out, then chance (ranked,
        // so the turns are spread evenly); turrets a while before the bubble they sit on.
        double[] score = new double[n];
        Integer[] bodyIdx = new Integer[n];
        int nBody = 0;
        for (int k = 0; k < n; k++) {
            if (level[k] == BODY) {
                double hf = top > base ? (y[k] - base) / (top - base) : 0;
                double out = reach > 0 ? Math.hypot(x[k] - cx, z[k] - cz) / reach : 0;
                score[k] = 0.35 * (1 - hf) + 0.15 * (1 - out) + 0.5 * chance(phase[k], 0.37);
                bodyIdx[nBody++] = k;
            }
        }
        java.util.Arrays.sort(bodyIdx, 0, nBody, (p, q) -> Double.compare(score[p], score[q]));
        for (int k = 0; k < nBody; k++) {
            order[bodyIdx[k]] = nBody > 1 ? (double) k / (nBody - 1) : 1;
        }
        for (int k = 0; k < n; k++) {
            if (level[k] != BODY) {
                double p = parent[k] >= 0 && parent[k] < n ? order[parent[k]] : 1;
                order[k] = p * (0.35 + 0.55 * chance(phase[k], 0.71));
            }
        }
        // ---- the order bubbles form in a birth: body bubbles mostly from the bottom up, the middle a little before
        // the edges; turrets after the bubble they sit on.
        double[] rise = new double[n];
        Integer[] riseIdx = new Integer[nBody];
        int m = 0;
        for (int k = 0; k < n; k++) {
            if (level[k] == BODY) {
                double hf = top > base ? (y[k] - base) / (top - base) : 0;
                double out = reach > 0 ? Math.hypot(x[k] - cx, z[k] - cz) / reach : 0;
                rise[k] = 0.6 * hf + 0.15 * out + 0.25 * chance(phase[k], 0.53);
                riseIdx[m++] = k;
            }
        }
        java.util.Arrays.sort(riseIdx, 0, m, (p, q) -> Double.compare(rise[p], rise[q]));
        for (int k = 0; k < m; k++) {
            birth[riseIdx[k]] = m > 1 ? (double) k / (m - 1) : 0;
        }
        for (int k = 0; k < n; k++) {
            if (level[k] != BODY) {
                double p = parent[k] >= 0 && parent[k] < n ? birth[parent[k]] : 0;
                birth[k] = p + (1 - p) * (0.15 + 0.45 * chance(phase[k], 0.89));
            }
        }
    }

    /** A second number from a bubble's random {@code phase}, roughly independent of it. */
    private static double chance(double phase, double salt) {
        double v = Math.sin((phase + salt) * 12.9898 * 43.7) * 43758.5453;
        return v - Math.floor(v);
    }

    /** The most a bubble of {@code level} swells, as a share of its rest radius. */
    static double maxScale(int level) {
        return level == BODY ? 1.05 : 1.0;
    }

    /** How far a bubble of {@code level} pushes out, at most, as a share of its rest radius. */
    static double push(int level) {
        return level == BODY ? 0 : level == TURRET ? 0.12 : 0.15;
    }

    /**
     * The bubbles of a heap cloud member centred at (cx, cz) on base {@code yb}: {@code r} its radius, {@code h} its
     * height, {@code tower} how towering its type is (0-1), footprint stretched by {@code aspect} and turned by
     * ({@code cos}, {@code sin}); towers lean downwind ({@code dx}, {@code dz}) by {@code lean} per block of height.
     * More body bubbles for bigger types ({@code richness}: 0 humilis to 1 storms).
     */
    static Cumulus of(double cx, double cz, double yb, double r, double h, double tower, double aspect, double cos,
                      double sin, double dx, double dz, double lean, double richness, SplittableRandom rng) {
        List<double[]> body = new ArrayList<>();
        double topY = yb + h;
        // Small clouds are built from fewer, chunkier bubbles (Bright, 2026-10-07: they looked like big clouds shrunk
        // down, and their smallest bubbles stood out like boils).
        double minR = r * minShare(r);
        double fewer = fewer(r);
        // ---- base layer: on the flat base, as wide as the cloud
        double rb = Math.max(3, Math.min(r * 0.5, h * 0.75));
        double ringR = Math.max(0, r - rb * 0.95);
        int ring = (int) Math.max(3, Math.min(10, Math.round(2 * Math.PI * ringR / (1.25 * rb) * fewer)));
        double layerY = yb + Math.min(rb * 0.45 * SQUASH, Math.max(0, topY - rb * SQUASH - yb));
        body.add(bubble(cx, layerY, cz, rb * (1.0 + 0.1 * rng.nextDouble()), BODY, rng, 0, 1, 0));
        double a0 = rng.nextDouble() * Math.PI * 2;
        for (int k = 0; k < ring && ringR > 1; k++) {
            double a = a0 + k * 2 * Math.PI / ring + 0.3 * (rng.nextDouble() - 0.5);
            double rr = ringR * (0.85 + 0.2 * rng.nextDouble());
            double bx = rr * aspect * Math.cos(a);
            double bz = rr / aspect * Math.sin(a);
            double wx = cx + bx * cos - bz * sin;
            double wz = cz + bx * sin + bz * cos;
            double size = Math.max(Math.min(minR, rb), rb * (0.75 + 0.3 * rng.nextDouble()));
            body.add(bubble(wx, layerY + 0.1 * size * rng.nextDouble(), wz, size, BODY, rng, Math.cos(a), 0.3,
                    Math.sin(a)));
        }
        // ---- towers: stacked clusters up to rounded heads. A humilis is one stack; a mediocris one or two (Bright,
        // 2026-10-07: wider than tall on average); a congestus or storm (richness 0.7+) a broad body with one to four
        // towers of different heights (2026-10-07, Bright: congestus are usually tall and wide).
        double pick = rng.nextDouble();
        int towers = richness < 0.3 ? 1 : richness < 0.7 ? (pick < 0.5 ? 2 : 1)
                : 1 + rng.nextInt(3) + (r > h * 0.8 ? 1 : 0);
        double t0 = rng.nextDouble() * Math.PI * 2;
        for (int k = 0; k < towers; k++) {
            double ox, oz, width, height;
            if (towers == 1) {
                double a = rng.nextDouble() * Math.PI * 2;
                double off = r * 0.15 * rng.nextDouble();
                ox = Math.cos(a) * off;
                oz = Math.sin(a) * off;
                width = r * (1 - 0.3 * tower) * 0.8;
                height = h;
            } else {
                double a = t0 + k * 2 * Math.PI / towers + 0.5 * (rng.nextDouble() - 0.5);
                double off = r * (k == 0 ? 0.15 : 0.35 + 0.25 * rng.nextDouble());
                ox = Math.cos(a) * off;
                oz = Math.sin(a) * off;
                width = r * (k == 0 ? 0.55 : 0.4 + 0.15 * rng.nextDouble());
                height = k == 0 ? h : h * (0.55 + 0.35 * rng.nextDouble());
            }
            stack(body, cx + ox * aspect * cos - oz / aspect * sin, cz + ox * aspect * sin + oz / aspect * cos, layerY,
                    rb, width, yb + height, r, minR, fewer, dx, dz, lean, rng);
        }
        // ---- turrets: on the upper and outer side of each body bubble, never smaller than the floor
        List<double[]> turrets = children(body, TURRET, cx, cz, yb, topY, 0.32, 0.5, minR, fewer, rng);
        return new Cumulus(body, turrets, List.of(), cx, cz, yb);
    }

    /** Clouds this small (radius, blocks) or smaller get the chunkiest bubbles; this big or bigger the finest. */
    static final double SMALL_CLOUD = 40;
    static final double BIG_CLOUD = 300;

    /** How big the cloud is between {@link #SMALL_CLOUD} (0) and {@link #BIG_CLOUD} (1), by ratio. */
    static double bigness(double r) {
        double t = Math.log(Math.max(r, 1) / SMALL_CLOUD) / Math.log(BIG_CLOUD / SMALL_CLOUD);
        return t < 0 ? 0 : Math.min(1, t);
    }

    /**
     * No bubble is smaller than this share of the cloud's radius {@code r} (Bright, 2026-10-07: small ones stuck out
     * like boils): a fifth on big clouds, up to a third on the smallest.
     */
    static double minShare(double r) {
        return 1.0 / 3 + (1.0 / 5 - 1.0 / 3) * bigness(r);
    }

    /** The share of the usual bubble counts (rings, clusters, turrets) a cloud of radius {@code r} gets: half to all. */
    static double fewer(double r) {
        return 0.5 + 0.5 * bigness(r);
    }

    /**
     * One tower: clusters stacked from the base layer (at {@code layerY}, its bubbles {@code rb}) up to a head whose
     * top is {@code topY}, starting {@code width} wide around (x, z) and narrowing a little each level, leaning
     * downwind.
     */
    private static void stack(List<double[]> body, double x, double z, double layerY, double rb, double width,
                              double topY, double r, double minR, double fewer, double dx, double dz, double lean,
                              SplittableRandom rng) {
        double clusterR = width;
        double y = layerY;
        double prevR = rb;
        double ccx = x, ccz = z;
        int levels = 0;
        while (levels < 12) {
            double br = Math.max(minR, clusterR * 0.6);
            double next = y + prevR * 0.9 * SQUASH;
            boolean head = next + br * SQUASH >= topY;
            if (head) {
                // The head: its top is the tower's top (unless the base layer already is).
                if (layerY + rb * SQUASH >= topY - 2) {
                    break;
                }
                br = Math.max(Math.min(minR, (topY - layerY) * 0.6), Math.min(br, (topY - layerY) * 0.6));
                next = topY - br * SQUASH;
            }
            double rise = next - layerY;
            ccx += dx * lean * (next - y) + clusterR * 0.12 * (rng.nextDouble() - 0.5);
            ccz += dz * lean * (next - y) + clusterR * 0.12 * (rng.nextDouble() - 0.5);
            body.add(bubble(ccx, next, ccz, br, BODY, rng, 0, 1, 0));
            int around = head ? (int) Math.max(2, Math.round((3 + rng.nextInt(2)) * fewer))
                    : (int) Math.max(2, Math.min(6, Math.round((4 * clusterR / r + 1) * fewer)));
            double b0 = rng.nextDouble() * Math.PI * 2;
            for (int k = 0; k < around; k++) {
                double a = b0 + k * 2 * Math.PI / around + 0.4 * (rng.nextDouble() - 0.5);
                double off = clusterR * (head ? 0.45 : 0.55) * (0.8 + 0.3 * rng.nextDouble());
                double size = Math.max(minR, br * 0.75 * (0.8 + 0.35 * rng.nextDouble()));
                double by = next - size * SQUASH * (head ? 0.3 : 0.15) * rng.nextDouble();
                body.add(bubble(ccx + Math.cos(a) * off, Math.min(by, topY - size * SQUASH), ccz + Math.sin(a) * off,
                        size, BODY, rng, Math.cos(a), 0.4, Math.sin(a)));
            }
            levels++;
            if (head || rise <= 0) {
                break;
            }
            y = next;
            prevR = br;
            // Towers keep most of their width: a broad column of rising thermals with a cauliflower head.
            clusterR *= 0.92 + 0.04 * rng.nextDouble();
        }
    }

    private static List<double[]> children(List<double[]> parents, int level, double cx, double cz, double yb,
                                           double topY, double minShare, double maxShare, double minR, double fewer,
                                           SplittableRandom rng) {
        List<double[]> out = new ArrayList<>();
        for (int pi = 0; pi < parents.size(); pi++) {
            double[] p = parents.get(pi);
            int kids = (int) Math.max(1, Math.round((2 + rng.nextInt(2)) * fewer));
            for (int k = 0; k < kids; k++) {
                // Outward from the cloud's axis and upward, with some scatter; never down (the base stays flat).
                double ox = p[0] - cx, oz = p[2] - cz;
                double ol = Math.hypot(ox, oz);
                double a = rng.nextDouble() * Math.PI * 2;
                double vx = (ol > 1e-6 ? ox / ol : 0) * (0.3 + 0.7 * rng.nextDouble()) + 0.7 * Math.cos(a);
                double vz = (ol > 1e-6 ? oz / ol : 0) * (0.3 + 0.7 * rng.nextDouble()) + 0.7 * Math.sin(a);
                double vy = 0.4 + 0.9 * rng.nextDouble();
                double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
                vx /= vl;
                vy /= vl;
                vz /= vl;
                double size = Math.max(minR, p[3] * (minShare + (maxShare - minShare) * rng.nextDouble()));
                if (size < 2.5) {
                    continue;
                }
                // On the parent's surface, sunk in by a third of its own size.
                double out1 = p[3] - size * 0.35;
                double bx = p[0] + vx * out1;
                double by = p[1] + vy * out1 * SQUASH;
                double bz = p[2] + vz * out1;
                // Not above the cloud's top by more than a little, nor hanging below its base.
                by = Math.min(by, topY + size * 0.2 - size * SQUASH);
                if (by - size * SQUASH * 0.5 < yb) {
                    continue;
                }
                double[] b = bubble(bx, by, bz, size, level, rng, vx, vy, vz);
                double[] withParent = java.util.Arrays.copyOf(b, 10);
                // The parent's index: the parents are the body, which comes first in the bubble arrays.
                withParent[9] = pi;
                out.add(withParent);
            }
        }
        return out;
    }

    private static double[] bubble(double x, double y, double z, double r, int level, SplittableRandom rng,
                                   double vx, double vy, double vz) {
        double l = Math.sqrt(vx * vx + vy * vy + vz * vz);
        return new double[]{x, y, z, r, level, rng.nextDouble(), l > 0 ? vx / l : 0, l > 0 ? vy / l : 1,
                l > 0 ? vz / l : 0};
    }

    /** The bubbles at game time {@code time}: positions and radii, as they boil. */
    Anim animate(double time) {
        return animate(time, 1, 0);
    }

    /** Where in a death a bubble starts evaporating ({@code order} times this), and how long its turn takes. */
    static final double DEATH_SPREAD = 0.78;
    static final double DEATH_TURN = 0.2;
    /** How far an evaporating bubble sinks by the end of its turn, as a share of its height. */
    static final double DEATH_SINK = 0.35;
    /** Where in a birth a bubble starts forming ({@code birth} times this), and how long its turn takes. */
    static final double BIRTH_SPREAD = 0.75;
    static final double BIRTH_TURN = 0.25;
    /** How far below its place a bubble starts forming, as a share of its height (it rises as it swells). */
    static final double BIRTH_RISE = 0.5;
    /** How far toward the cloud's axis a bubble starts forming, as a share of its distance from it. */
    static final double BIRTH_INWARD = 0.35;

    /**
     * The bubbles at game time {@code time}, {@code growth} (0-1) of the way through the cloud's birth and
     * {@code dying} (0-1) through its death: in a birth each swells from nothing over its turn ({@link #birth}), rising
     * into place; in a death each shrinks to nothing over its turn ({@link #order}), sinking as it goes.
     */
    Anim animate(double time, double growth, double dying) {
        double[] ax = new double[n], ay = new double[n], az = new double[n], ar = new double[n];
        for (int i = 0; i < n; i++) {
            double scale;
            double pushOut;
            switch (level[i]) {
                case BODY -> {
                    scale = 1 + 0.05 * Math.sin(2 * Math.PI * (time / BODY_PERIOD + phase[i]));
                    pushOut = 0;
                }
                case TURRET -> {
                    double s = Math.sin(2 * Math.PI * (time / TURRET_PERIOD + phase[i]));
                    scale = 0.9 + 0.1 * s;
                    pushOut = push(TURRET) * s;
                }
                default -> {
                    double s = Math.sin(2 * Math.PI * (time / PUFF_PERIOD + phase[i]));
                    scale = 0.7 + 0.3 * s;
                    pushOut = push(PUFF) * s;
                }
            }
            ax[i] = x[i] + ux[i] * pushOut * r[i];
            ay[i] = y[i] + uy[i] * pushOut * r[i] * SQUASH;
            az[i] = z[i] + uz[i] * pushOut * r[i];
            ar[i] = r[i] * scale;
            if (growth < 1) {
                double t = (growth - BIRTH_SPREAD * birth[i]) / BIRTH_TURN;
                double s = t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
                // It swells from inside the cloud below and toward its middle, so it shows only once it has some size
                // (a tiny new bubble on the surface would be a boil).
                ax[i] -= (1 - s) * BIRTH_INWARD * (x[i] - cx);
                az[i] -= (1 - s) * BIRTH_INWARD * (z[i] - cz);
                ay[i] -= (1 - s) * BIRTH_RISE * r[i] * SQUASH;
                ar[i] *= s;
            }
            if (dying > 0) {
                double t = (dying - DEATH_SPREAD * order[i]) / DEATH_TURN;
                if (t > 0) {
                    double s = t >= 1 ? 1 : t * t * (3 - 2 * t);
                    ay[i] -= s * DEATH_SINK * r[i] * SQUASH;
                    ar[i] *= 1 - s;
                }
            }
        }
        return new Anim(time, ax, ay, az, ar);
    }

    /** The bubbles at one time. */
    record Anim(double time, double[] x, double[] y, double[] z, double[] r) {
    }
}
