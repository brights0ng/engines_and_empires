package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;

/**
 * The high deck's see-through clouds (2026-10-07 evening, Bright: cirrostratus "basically just strips, with the sky
 * visible between every one", sometimes the real milky veil; cirrus "the wispiest one of all"): drawn by a shader as
 * one translucent sheet over a grid around the camera ({@link CloudVeilRenderer}, cloud_veil.fsh), not as voxels.
 * This is the grid: at each point, the deck's height and how much cirrostratus and cirrus cover it, from the clouds'
 * domes (soft-edged, as the layer sheets: {@link CloudField#LAYER_REACH}). Pure Java.
 */
public final class CloudVeils {

    /** Whether clouds of type {@code t} are drawn as veils, not voxels. */
    public static boolean veil(CloudType t) {
        return t == CloudType.CIRROSTRATUS || t == CloudType.CIRRUS;
    }

    public static boolean veil(String typeId) {
        return veil(CloudType.of(typeId));
    }

    /**
     * Points {@code (x0 + i * spacing, z0 + j * spacing)}, i and j from 0 to n: the deck's height there, and the
     * cirrostratus and cirrus cover (0-1). {@code any}: whether any veil cloud reaches the grid.
     */
    record Grid(double x0, double z0, double spacing, int n, float[] height, float[] strips, float[] fibres,
                boolean any) {
        int index(int i, int j) {
            return j * (n + 1) + i;
        }
    }

    private record Dome(double x, double z, double r, double y, double cover, boolean cirrus) {
    }

    /** How far in from a dome's edge its cover is full, as a share of its radius. */
    static final double EDGE = 0.35;

    /**
     * The grid of {@code n} x {@code n} cells reaching {@code reach} blocks around (cx, cz), from {@code clouds} at
     * game time {@code time}; snapped to its spacing, so the same world points are sampled as the camera moves.
     */
    static Grid of(List<CloudShape> clouds, double time, double cx, double cz, double reach, int n) {
        double spacing = 2 * reach / n;
        double x0 = Math.floor((cx - reach) / spacing) * spacing;
        double z0 = Math.floor((cz - reach) / spacing) * spacing;
        List<Dome> domes = new ArrayList<>();
        double hSum = 0, wSum = 0;
        for (CloudShape c : clouds) {
            CloudType t = CloudType.of(c.typeId());
            if (!veil(t) || !c.visible()) {
                continue;
            }
            double r = c.radius() * CloudField.LAYER_REACH;
            double x = c.xAt(time), z = c.zAt(time);
            if (x + r < x0 || x - r > x0 + n * spacing || z + r < z0 || z - r > z0 + n * spacing) {
                continue;
            }
            double cover = Math.max(0, Math.min(1, c.effectiveCoverage()));
            double y = 0.5 * (c.baseY() + c.topY());
            domes.add(new Dome(x, z, r, y, cover, t == CloudType.CIRRUS));
            hSum += y * r;
            wSum += r;
        }
        int m = (n + 1) * (n + 1);
        float[] height = new float[m], strips = new float[m], fibres = new float[m];
        if (domes.isEmpty()) {
            return new Grid(x0, z0, spacing, n, height, strips, fibres, false);
        }
        double fallback = hSum / wSum;
        for (int j = 0; j <= n; j++) {
            double z = z0 + j * spacing;
            for (int i = 0; i <= n; i++) {
                double x = x0 + i * spacing;
                double hw = 0, hy = 0, cs = 0, ci = 0;
                for (Dome d : domes) {
                    double dx = x - d.x, dz = z - d.z;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    // The height: the nearby domes', smoothly, so the sheet doesn't tilt at their edges.
                    double reachH = 1.5 * d.r;
                    double w = Math.exp(-(dist / reachH) * (dist / reachH)) * d.r;
                    hw += w;
                    hy += w * d.y;
                    if (dist >= d.r) {
                        continue;
                    }
                    double in = smooth((d.r - dist) / (EDGE * d.r)) * d.cover;
                    if (d.cirrus) {
                        ci = Math.max(ci, in);
                    } else {
                        cs = Math.max(cs, in);
                    }
                }
                int k = j * (n + 1) + i;
                height[k] = (float) (hw > 1e-6 ? hy / hw : fallback);
                strips[k] = (float) cs;
                fibres[k] = (float) ci;
            }
        }
        return new Grid(x0, z0, spacing, n, height, strips, fibres, true);
    }

    /**
     * The veil clouds' mean velocity near (cx, cz) within {@code reach} (blocks per tick, weighted by area), or null if
     * there are none: the deck's pattern drifts with it.
     */
    static double[] velocity(List<CloudShape> clouds, double time, double cx, double cz, double reach) {
        double vx = 0, vz = 0, w = 0;
        for (CloudShape c : clouds) {
            if (!veil(c.typeId()) || !c.visible()) {
                continue;
            }
            double dx = c.xAt(time) - cx, dz = c.zAt(time) - cz;
            double r = c.radius() * CloudField.LAYER_REACH;
            if (dx * dx + dz * dz > (reach + r) * (reach + r)) {
                continue;
            }
            double a = r * r;
            vx += c.vxAt(time) * a;
            vz += c.vzAt(time) * a;
            w += a;
        }
        return w <= 0 ? null : new double[]{vx / w, vz / w};
    }

    private static double smooth(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }

    private CloudVeils() {
    }
}
