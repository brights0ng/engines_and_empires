package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;

/**
 * Clouds shading other clouds (2026-10-07, Bright: a small cumulus under a nimbostratus was perfectly bright under a
 * dark layer; realistic strength). A cheap, coarse picture of all the cloud above any point: each cloud's domes as
 * soft discs with a base, a top and a water content, in a 256-block grid. A cloud being built asks how much of
 * the other clouds lies above each of its corners and dims its sky light and its sunlight by it (the light taken
 * as coming from straight above). Immutable; the client makes a new one every second ({@link #current}).
 */
public final class CloudShadows {

    /** Grid cell side, blocks. */
    static final double CELL = 256;

    /** One dome: centre (world x, z at {@link #time}), radius, base and top, and its water times its cover. */
    record Caster(UUID id, double x, double z, double r, double base, double top, double water) {
    }

    static final CloudShadows EMPTY = new CloudShadows(Map.of(), 0);
    private static volatile CloudShadows current = EMPTY;

    private final Map<Long, List<Caster>> grid;
    final double time;

    private CloudShadows(Map<Long, List<Caster>> grid, double time) {
        this.grid = grid;
        this.time = time;
    }

    /** The latest picture (never null). */
    public static CloudShadows current() {
        return current;
    }

    /** Makes a new picture from the clouds drawn now. Client thread. */
    public static void update(List<CloudShape> clouds, double time) {
        current = of(clouds, time);
    }

    /** The picture of {@code clouds} at game time {@code time}. */
    static CloudShadows of(List<CloudShape> clouds, double time) {
        Map<Long, List<Caster>> grid = new HashMap<>();
        for (CloudShape c : clouds) {
            if (!c.visible() || c.topY() <= c.baseY()) {
                continue;
            }
            CloudType t = CloudType.of(c.typeId());
            boolean layer = t != null && t.layer();
            double r = c.radius() * (layer ? CloudField.LAYER_REACH : 1);
            double water = CloudField.typeWater(c.typeId()) * c.effectiveCoverage();
            if (water <= 0 || r <= 0) {
                continue;
            }
            Caster k = new Caster(c.id(), c.xAt(time), c.zAt(time), r, c.baseY(), c.topY(), water);
            int i0 = (int) Math.floor((k.x - r) / CELL), i1 = (int) Math.floor((k.x + r) / CELL);
            int j0 = (int) Math.floor((k.z - r) / CELL), j1 = (int) Math.floor((k.z + r) / CELL);
            for (int i = i0; i <= i1; i++) {
                for (int j = j0; j <= j1; j++) {
                    grid.computeIfAbsent(key(i, j), q -> new ArrayList<>()).add(k);
                }
            }
        }
        return new CloudShadows(grid, time);
    }

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
    }

    /**
     * The cloud above world point (x, y, z), as blocks of cloud times water (the brightness model's depth at water 1),
     * leaving out the domes in {@code own} (the cloud being built). Soft at the domes' edges.
     */
    double above(double x, double y, double z, Set<UUID> own) {
        List<Caster> list = grid.get(key((int) Math.floor(x / CELL), (int) Math.floor(z / CELL)));
        if (list == null) {
            return 0;
        }
        double sum = 0;
        for (Caster k : list) {
            if (k.top <= y || own.contains(k.id)) {
                continue;
            }
            double dx = x - k.x, dz = z - k.z;
            double d2 = dx * dx + dz * dz;
            if (d2 >= k.r * k.r) {
                continue;
            }
            double d = Math.sqrt(d2) / k.r;
            double edge = 1 - smooth((d - 0.55) / 0.45);
            sum += (k.top - Math.max(k.base, y)) * k.water * edge;
        }
        return sum;
    }

    /**
     * How much of the sky's light and of the sunlight reach (x, y, z) past the other clouds above: {sky, sun}, each
     * 0-1, at {@link CloudTuning#cloudShadows} strength. The sky's light keeps the brightness model's floor (light
     * scattered in from around); direct sunlight is cut much harder.
     */
    double[] light(double x, double y, double z, Set<UUID> own) {
        double depth = above(x, y, z, own);
        if (depth <= 0) {
            return FULL;
        }
        double k = CloudTuning.cloudShadows;
        double sky = CloudVoxelizer.brightness(depth, 1);
        // Direct sunlight: the physical transmission (the brightness model without its perceptual curve and floor).
        double metres = depth / dev.brights0ng.enginesandempires.weather.cloud.CloudScale.SCALE;
        double sun = 1 / (1 + 0.1125 * 0.15 * CloudTuning.waterContent * metres);
        return new double[]{1 - k * (1 - sky), Math.max(0, 1 - k * (1 - sun))};
    }

    private static final double[] FULL = {1, 1};

    private static double smooth(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }
}
