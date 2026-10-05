package dev.brights0ng.enginesandempires.weather.rain;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The wind rain drifts in, averaged over about the time rain takes to fall ({@link #TIME_TICKS}, 20 s), for one level
 * on the server (Bright, 2026-10-04). A drop is carried by the wind over its whole fall, not by this second's gust, so
 * the wet patch on the ground follows the average; taking the instant wind made the rain edge chase PA's gusts.
 *
 * <p>The wind is averaged at nodes {@link #SPACING} blocks apart around the players ({@link #observe}, once a second)
 * and blended between the four nodes around a spot, so the drift has no seams. A spot with a node that isn't being kept
 * (far from every player) uses the instant wind there. Pure Java (the wind comes in through {@link Raw}), tested.
 */
public final class WindAverage {

    /** The averaging time, ticks (20 s, about how long rain takes to fall). */
    public static final double TIME_TICKS = 400;
    /** Blocks between nodes. PA's wind is blended between regions 2000 blocks apart, so this resolves it. */
    public static final int SPACING = 512;
    /** Nodes kept around each player, each way (so spots within about 1000 blocks are averaged). */
    public static final int REACH = 2;
    /** Ticks a node not near any player is kept. */
    static final long FORGET = 600;

    /** The instant wind (x, z, m/s) at a spot, or null where there is none. */
    @FunctionalInterface
    public interface Raw {
        double[] at(double x, double z);
    }

    private static final class Node {
        double x;
        double z;
        long tick;
    }

    private final Map<Long, Node> nodes = new HashMap<>();

    private static long key(int nx, int nz) {
        return ((long) nx << 32) | (nz & 0xFFFFFFFFL);
    }

    /** Brings the nodes around a player at (x, z) up to date with the wind now. */
    public synchronized void observe(double x, double z, long tick, Raw raw) {
        int cx = Math.floorDiv((int) Math.floor(x), SPACING);
        int cz = Math.floorDiv((int) Math.floor(z), SPACING);
        for (int nz = cz - REACH; nz <= cz + REACH + 1; nz++) {
            for (int nx = cx - REACH; nx <= cx + REACH + 1; nx++) {
                long k = key(nx, nz);
                Node n = nodes.get(k);
                if (n != null && n.tick == tick) {
                    continue;
                }
                double[] w = raw.at((double) nx * SPACING, (double) nz * SPACING);
                if (w == null) {
                    continue;
                }
                if (n == null) {
                    n = new Node();
                    n.x = w[0];
                    n.z = w[1];
                    nodes.put(k, n);
                } else {
                    double a = 1 - Math.exp(-Math.max(0, tick - n.tick) / TIME_TICKS);
                    n.x += (w[0] - n.x) * a;
                    n.z += (w[1] - n.z) * a;
                }
                n.tick = tick;
            }
        }
    }

    /** Forgets nodes no player has been near for a while. */
    public synchronized void forget(long tick) {
        for (Iterator<Node> it = nodes.values().iterator(); it.hasNext(); ) {
            if (tick - it.next().tick > FORGET) {
                it.remove();
            }
        }
    }

    /** The averaged wind at (x, z), m/s: blended between the four nodes around it; the instant wind where one is missing. */
    public synchronized double[] at(double x, double z, Raw raw) {
        double gx = x / SPACING;
        double gz = z / SPACING;
        int x0 = (int) Math.floor(gx);
        int z0 = (int) Math.floor(gz);
        Node n00 = nodes.get(key(x0, z0));
        Node n10 = nodes.get(key(x0 + 1, z0));
        Node n01 = nodes.get(key(x0, z0 + 1));
        Node n11 = nodes.get(key(x0 + 1, z0 + 1));
        if (n00 == null || n10 == null || n01 == null || n11 == null) {
            return raw.at(x, z);
        }
        double fx = gx - x0;
        double fz = gz - z0;
        double wx = (n00.x * (1 - fx) + n10.x * fx) * (1 - fz) + (n01.x * (1 - fx) + n11.x * fx) * fz;
        double wz = (n00.z * (1 - fx) + n10.z * fx) * (1 - fz) + (n01.z * (1 - fx) + n11.z * fx) * fz;
        return new double[]{wx, wz};
    }

    public synchronized int size() {
        return nodes.size();
    }
}
