package dev.brights0ng.enginesandempires.weather.wind;

import java.util.HashMap;
import java.util.Map;

/**
 * A physics object's outline as seen along each of its own three axes, kept up to date one block at a time.
 *
 * <p>Seen along X, the outline is the set of (y, z) columns that hold at least one solid block, and its area is how many
 * there are (one block face is 1 m²). Hollow insides don't count, which is right: air inside a hull doesn't catch wind.
 * The area across any direction d is then approximated as {@code |dx|·Ax + |dy|·Ay + |dz|·Az}, exact for boxes and close
 * for most hulls.
 *
 * <p>Each outline also keeps its centroid. Pushing at the blend of the centroids (the centre of pressure) rather than at
 * the centre of mass gives the torque for free: ships turn their long side away from the wind, tall masts lean.
 *
 * <p>Coordinates are the object's own block coordinates (Sable's plot), never world ones.
 */
public final class Silhouette {

    /** 0: seen along X (columns keyed by y, z). 1: along Y (x, z). 2: along Z (x, y). */
    private final Outline[] outlines = {new Outline(), new Outline(), new Outline()};

    public void add(int x, int y, int z) {
        this.outlines[0].add(y, z);
        this.outlines[1].add(x, z);
        this.outlines[2].add(x, y);
    }

    public void remove(int x, int y, int z) {
        this.outlines[0].remove(y, z);
        this.outlines[1].remove(x, z);
        this.outlines[2].remove(x, y);
    }

    public void clear() {
        for (Outline outline : this.outlines) {
            outline.clear();
        }
    }

    /** The outline's area seen along the given axis (0 = X, 1 = Y, 2 = Z), in m². */
    public int area(int axis) {
        return this.outlines[axis].area();
    }

    public boolean isEmpty() {
        return this.outlines[0].area() == 0;
    }

    /** The area across a direction given in the object's own frame (any length; only its direction matters). */
    public double projectedArea(double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-12) {
            return 0;
        }
        return (Math.abs(dx) * area(0) + Math.abs(dy) * area(1) + Math.abs(dz) * area(2)) / length;
    }

    /**
     * Where the wind's push acts, for wind along d (object frame). Each outline gives a point: its centroid in the two
     * coordinates it spans, and the centre of mass in the one it is seen along. The points are blended by how much each
     * outline faces the wind.
     *
     * @param out receives x, y, z
     * @return false if the object has no outline facing d (then {@code out} holds the centre of mass)
     */
    public boolean centreOfPressure(double dx, double dy, double dz, double comX, double comY, double comZ, double[] out) {
        double w0 = Math.abs(dx) * area(0);
        double w1 = Math.abs(dy) * area(1);
        double w2 = Math.abs(dz) * area(2);
        double total = w0 + w1 + w2;
        out[0] = comX;
        out[1] = comY;
        out[2] = comZ;
        if (total <= 0) {
            return false;
        }
        Outline alongX = this.outlines[0];
        Outline alongY = this.outlines[1];
        Outline alongZ = this.outlines[2];
        out[0] = (w0 * comX + w1 * alongY.centreU() + w2 * alongZ.centreU()) / total;
        out[1] = (w0 * alongX.centreU() + w1 * comY + w2 * alongZ.centreV()) / total;
        out[2] = (w0 * alongX.centreV() + w1 * alongY.centreV() + w2 * comZ) / total;
        return true;
    }

    /** One outline: how many solid blocks are in each column, and the sums of the filled columns' coordinates. */
    private static final class Outline {
        private final Map<Long, Integer> counts = new HashMap<>();
        private double sumU;
        private double sumV;

        void add(int u, int v) {
            int count = this.counts.merge(key(u, v), 1, Integer::sum);
            if (count == 1) {
                this.sumU += u;
                this.sumV += v;
            }
        }

        void remove(int u, int v) {
            long key = key(u, v);
            Integer count = this.counts.get(key);
            if (count == null) {
                return;
            }
            if (count == 1) {
                this.counts.remove(key);
                this.sumU -= u;
                this.sumV -= v;
            } else {
                this.counts.put(key, count - 1);
            }
        }

        void clear() {
            this.counts.clear();
            this.sumU = 0;
            this.sumV = 0;
        }

        int area() {
            return this.counts.size();
        }

        /** The centroid's first coordinate, at block centres. */
        double centreU() {
            return this.counts.isEmpty() ? 0 : this.sumU / this.counts.size() + 0.5;
        }

        double centreV() {
            return this.counts.isEmpty() ? 0 : this.sumV / this.counts.size() + 0.5;
        }

        private static long key(int u, int v) {
            return ((long) u << 32) | (v & 0xFFFFFFFFL);
        }
    }
}
