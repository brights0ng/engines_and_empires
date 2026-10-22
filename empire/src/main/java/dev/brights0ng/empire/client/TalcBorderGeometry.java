// Written by Claude.
package dev.brights0ng.empire.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Builds the talc border mesh: chalk lines draped over the terrain along claim borders, and the light veils above them.
 * Touches no Minecraft or library classes (terrain comes in through {@link Terrain}), so it is unit-tested.
 *
 * <h2>Model</h2>
 * Borders are unit edges on block-grid lines. Every block column that touches a border edge or a border vertex is cut
 * into a grid of rectangular <em>patches</em>: the cuts are at {@code 0, HALF_W, 1 - HALF_W, 1} plus every x/z
 * coordinate of the boxes in that column's surface blocks, so each patch has a single surface height (the top of the
 * highest box covering it). A patch is in the <em>band</em> when it lies within {@code HALF_W} of a border edge of its
 * cell, or in the corner square of its cell at a border vertex (a <em>cap</em>, which squares off corners and line ends).
 * <ul>
 *   <li>Higher side: a band patch is painted only if its side of the border is at least as high, at the border plane, as
 *       the other side. At a step the whole ribbon therefore sits on the higher side and folds over its edge.</li>
 *   <li>Tops: every painted patch gets a flat quad on its surface.</li>
 *   <li>Faces: every drop from a painted patch to a lower neighbour (inside its cell or across a cell edge) is painted
 *       on the higher patch's face, from the top down to where its column stops being solid or the neighbour's surface.
 *       A face parallel to the border the patch is painted for gets only a {@code HALF_W} band at its top edge (the
 *       ribbon folding over the edge) and, on a cliff taller than {@link #BASE_BAND_MIN_DROP} whose face reaches the
 *       ground, a {@code HALF_W} band at its base; drops no taller than {@code 2 * HALF_W} and faces across the
 *       border's direction (a step along the line, already only {@code HALF_W} wide) are painted whole.</li>
 *   <li>Veils: one vertical sheet on each border edge, rising from the higher side's surface at the plane.</li>
 * </ul>
 * Every surface piece is emitted exactly once, so overlapping translucent quads never double up.
 */
public final class TalcBorderGeometry {

    /** Half the width of the chalk ribbon, in blocks. */
    public static final double HALF_W = 0.11;
    /** Outward nudge of every line quad off its surface; the render type's polygon offset does the real work. */
    public static final double LIFT = 0.002;
    /** Height of the light veil above the surface. */
    public static final double VEIL_HEIGHT = 2.8;
    /** A drop parallel to the border gets a band at its base only when it is taller than this (a cliff, not a step). */
    public static final double BASE_BAND_MIN_DROP = 2.0;

    static final double EPS = 1.0e-4;
    /** Blocks scanned below a column's top surface block while its footprint still has holes (fences, walls). */
    static final int MAX_COLUMN_DEPTH = 6;
    /** Blocks walked down a face looking for its bottom. */
    static final int MAX_FACE_DEPTH = 96;

    static final int WEST = 0;
    static final int EAST = 1;
    static final int NORTH = 2;
    static final int SOUTH = 3;

    /** One box, block-local coordinates (y may exceed 1 for tall shapes). */
    @FunctionalInterface
    public interface BoxSink {
        void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
    }

    /** What the builder needs to know about the world. */
    public interface Terrain {
        int UNLOADED = Integer.MIN_VALUE;

        /** The block y to start scanning down from in column (x, z), or {@link #UNLOADED}. */
        int scanTop(int x, int z);

        /** The lowest block y that may be scanned. */
        int scanBottom();

        /**
         * Feeds the boxes of block (x, y, z) that count as terrain surface, in block-local coordinates. Feeds nothing for
         * air, leaves and anything the line should pass through (tall grass, flowers, torches).
         */
        void boxes(int x, int y, int z, BoxSink sink);
    }

    /** The border edges to draw, each with a style (claim state: 1 our empire, 2 another empire). */
    public static final class Borders {
        /** Edge (x, z): the segment (x, z)-(x + 1, z) on plane z, between cells (x, z - 1) and (x, z). */
        final Map<Long, Integer> zEdges = new HashMap<>();
        /** Edge (x, z): the segment (x, z)-(x, z + 1) on plane x, between cells (x - 1, z) and (x, z). */
        final Map<Long, Integer> xEdges = new HashMap<>();

        /** Adds the edge along x on plane z. */
        public void addZEdge(int x, int z, int style) {
            zEdges.merge(key(x, z), style, TalcBorderGeometry::mergeStyle);
        }

        /** Adds the edge along z on plane x. */
        public void addXEdge(int x, int z, int style) {
            xEdges.merge(key(x, z), style, TalcBorderGeometry::mergeStyle);
        }

        public boolean isEmpty() {
            return zEdges.isEmpty() && xEdges.isEmpty();
        }

        int z(int x, int z) {
            return zEdges.getOrDefault(key(x, z), 0);
        }

        int x(int x, int z) {
            return xEdges.getOrDefault(key(x, z), 0);
        }
    }

    /** A growable list of doubles. */
    static final class DoubleList {
        private double[] values = new double[48];
        private int size;

        void add(double v) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = v;
        }

        double getDouble(int i) {
            return values[i];
        }

        int size() {
            return size;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void clear() {
            size = 0;
        }
    }

    /** The built quads, stored relative to an origin so float precision holds far from 0,0. */
    public static final class Mesh {
        public static final int LINE = 0;
        /** Veil quads: vertices 0 and 3 are at the bottom, 1 and 2 at the top. */
        public static final int VEIL = 1;

        private final int originX;
        private final int originZ;
        private float[] pos = new float[256 * 12];
        private byte[] kinds = new byte[256];
        private byte[] styles = new byte[256];
        private int quads;
        private java.util.Set<Long> columns = java.util.Set.of();

        Mesh(int originX, int originZ) {
            this.originX = originX;
            this.originZ = originZ;
        }

        public int originX() {
            return originX;
        }

        public int originZ() {
            return originZ;
        }

        public int quadCount() {
            return quads;
        }

        /** Whether a block change in column (x, z) can change this mesh: the column was read while building it. */
        public boolean readsColumn(int x, int z) {
            return columns.contains(key(x, z));
        }

        public int kind(int q) {
            return kinds[q];
        }

        public int style(int q) {
            return styles[q];
        }

        /** Vertex v (0-3) of quad q, relative to the origin (y absolute). */
        public float x(int q, int v) {
            return pos[q * 12 + v * 3];
        }

        public float y(int q, int v) {
            return pos[q * 12 + v * 3 + 1];
        }

        public float z(int q, int v) {
            return pos[q * 12 + v * 3 + 2];
        }

        void horizontal(int kind, int style, double x0, double z0, double x1, double z1, double y) {
            add(kind, style, x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0);
        }

        /** A vertical quad on plane x = plane (normalX) or z = plane, spanning s0..s1 along the other axis. */
        void vertical(int kind, int style, boolean normalX, double plane, double s0, double s1, double yLo, double yHi) {
            if (normalX) {
                add(kind, style, plane, yLo, s0, plane, yHi, s0, plane, yHi, s1, plane, yLo, s1);
            } else {
                add(kind, style, s0, yLo, plane, s0, yHi, plane, s1, yHi, plane, s1, yLo, plane);
            }
        }

        private void add(int kind, int style, double... v) {
            if (quads == kinds.length) {
                int n = quads * 2;
                pos = Arrays.copyOf(pos, n * 12);
                kinds = Arrays.copyOf(kinds, n);
                styles = Arrays.copyOf(styles, n);
            }
            int o = quads * 12;
            for (int i = 0; i < 12; i += 3) {
                pos[o + i] = (float) (v[i] - originX);
                pos[o + i + 1] = (float) v[i + 1];
                pos[o + i + 2] = (float) (v[i + 2] - originZ);
            }
            kinds[quads] = (byte) kind;
            styles[quads] = (byte) style;
            quads++;
        }
    }

    /** Builds the mesh for {@code borders}; the mesh's coordinates are relative to (originX, originZ). */
    public static Mesh build(Terrain terrain, Borders borders, int originX, int originZ) {
        return new Builder(terrain, borders, new Mesh(originX, originZ)).run();
    }

    static long key(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    static int keyX(long k) {
        return (int) k;
    }

    static int keyZ(long k) {
        return (int) (k >> 32);
    }

    /** Our empire wins over a rival where differently styled borders touch. */
    static int mergeStyle(int a, int b) {
        if (a == 0) {
            return b;
        }
        if (b == 0) {
            return a;
        }
        return (a == 1 || b == 1) ? 1 : a;
    }

    /** One block column cut into patches. */
    static final class Cell {
        final int x;
        final int z;
        final double[] xs;
        final double[] zs;
        final int nx;
        final int nz;
        final double[] h;
        final boolean hasHole;

        Cell(int x, int z, double[] xs, double[] zs, double[] h) {
            this.x = x;
            this.z = z;
            this.xs = xs;
            this.zs = zs;
            this.nx = xs.length - 1;
            this.nz = zs.length - 1;
            this.h = h;
            boolean hole = false;
            for (double v : h) {
                hole |= Double.isNaN(v);
            }
            this.hasHole = hole;
        }

        double h(int i, int j) {
            return h[i + j * nx];
        }

        int col(double lx) {
            return find(xs, lx);
        }

        int row(double lz) {
            return find(zs, lz);
        }

        private static int find(double[] cuts, double v) {
            for (int i = 0; i < cuts.length - 2; i++) {
                if (v < cuts[i + 1]) {
                    return i;
                }
            }
            return cuts.length - 2;
        }
    }

    private static final class Builder {
        private static final Cell MISSING = new Cell(0, 0, new double[]{0, 1}, new double[]{0, 1}, new double[]{Double.NaN});

        private final Terrain terrain;
        private final Borders borders;
        private final Mesh mesh;
        private final Map<Long, Cell> cells = new HashMap<>();
        private final DoubleList scratch = new DoubleList();
        private final DoubleList spans = new DoubleList();

        Builder(Terrain terrain, Borders borders, Mesh mesh) {
            this.terrain = terrain;
            this.borders = borders;
            this.mesh = mesh;
        }

        Mesh run() {
            LinkedHashSet<Long> todo = new LinkedHashSet<>();
            for (long k : borders.zEdges.keySet()) {
                int x = keyX(k);
                int z = keyZ(k);
                todo.add(key(x, z - 1));
                todo.add(key(x, z));
                addVertexCells(todo, x, z);
                addVertexCells(todo, x + 1, z);
            }
            for (long k : borders.xEdges.keySet()) {
                int x = keyX(k);
                int z = keyZ(k);
                todo.add(key(x - 1, z));
                todo.add(key(x, z));
                addVertexCells(todo, x, z);
                addVertexCells(todo, x, z + 1);
            }
            for (long k : todo) {
                paintCell(keyX(k), keyZ(k));
            }
            for (Map.Entry<Long, Integer> e : borders.zEdges.entrySet()) {
                veil(keyX(e.getKey()), keyZ(e.getKey()), false, e.getValue());
            }
            for (Map.Entry<Long, Integer> e : borders.xEdges.entrySet()) {
                veil(keyX(e.getKey()), keyZ(e.getKey()), true, e.getValue());
            }
            mesh.columns = java.util.Set.copyOf(cells.keySet());
            return mesh;
        }

        private static void addVertexCells(LinkedHashSet<Long> todo, int vx, int vz) {
            todo.add(key(vx - 1, vz - 1));
            todo.add(key(vx, vz - 1));
            todo.add(key(vx - 1, vz));
            todo.add(key(vx, vz));
        }

        // ---- cells ----

        Cell cell(int x, int z) {
            long k = key(x, z);
            Cell c = cells.get(k);
            if (c == null) {
                c = buildCell(x, z);
                cells.put(k, c == null ? MISSING : c);
                return c;
            }
            return c == MISSING ? null : c;
        }

        private Cell buildCell(int cx, int cz) {
            int y = terrain.scanTop(cx, cz);
            if (y == Terrain.UNLOADED) {
                return null;
            }
            int bottom = terrain.scanBottom();
            DoubleList boxes = new DoubleList();
            while (y >= bottom && boxes.isEmpty()) {
                feed(boxes, cx, y, cz);
                y--;
            }
            if (boxes.isEmpty()) {
                return null;
            }
            Cell cell = grid(cx, cz, boxes);
            for (int depth = 0; cell.hasHole && depth < MAX_COLUMN_DEPTH && y >= bottom; depth++, y--) {
                int before = boxes.size();
                feed(boxes, cx, y, cz);
                if (boxes.size() != before) {
                    cell = grid(cx, cz, boxes);
                }
            }
            return cell;
        }

        private void feed(DoubleList out, int x, int y, int z) {
            terrain.boxes(x, y, z, (x0, y0, z0, x1, y1, z1) -> {
                double cx0 = clamp01(x0);
                double cx1 = clamp01(x1);
                double cz0 = clamp01(z0);
                double cz1 = clamp01(z1);
                if (cx1 - cx0 < EPS || cz1 - cz0 < EPS || y1 - y0 < 1.0e-6) {
                    return;
                }
                out.add(cx0);
                out.add(y + y0);
                out.add(cz0);
                out.add(cx1);
                out.add(y + y1);
                out.add(cz1);
            });
        }

        private static Cell grid(int cx, int cz, DoubleList boxes) {
            double[] xs = cuts(boxes, 0);
            double[] zs = cuts(boxes, 2);
            int nx = xs.length - 1;
            int nz = zs.length - 1;
            double[] h = new double[nx * nz];
            Arrays.fill(h, Double.NaN);
            for (int b = 0; b < boxes.size(); b += 6) {
                double bx0 = boxes.getDouble(b);
                double top = boxes.getDouble(b + 4);
                double bz0 = boxes.getDouble(b + 2);
                double bx1 = boxes.getDouble(b + 3);
                double bz1 = boxes.getDouble(b + 5);
                for (int j = 0; j < nz; j++) {
                    if (zs[j] < bz0 - EPS || zs[j + 1] > bz1 + EPS) {
                        continue;
                    }
                    for (int i = 0; i < nx; i++) {
                        if (xs[i] < bx0 - EPS || xs[i + 1] > bx1 + EPS) {
                            continue;
                        }
                        int idx = i + j * nx;
                        if (Double.isNaN(h[idx]) || top > h[idx]) {
                            h[idx] = top;
                        }
                    }
                }
            }
            return new Cell(cx, cz, xs, zs, h);
        }

        /** Sorted, de-duplicated cut positions along x (offset 0) or z (offset 2). */
        private static double[] cuts(DoubleList boxes, int offset) {
            double[] raw = new double[4 + boxes.size() / 3];
            int n = 0;
            raw[n++] = 0;
            raw[n++] = HALF_W;
            raw[n++] = 1 - HALF_W;
            raw[n++] = 1;
            for (int b = 0; b < boxes.size(); b += 6) {
                raw[n++] = boxes.getDouble(b + offset);
                raw[n++] = boxes.getDouble(b + offset + 3);
            }
            Arrays.sort(raw, 0, n);
            double[] out = new double[n];
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (m == 0 || raw[i] - out[m - 1] > EPS) {
                    out[m++] = raw[i];
                }
            }
            out[m - 1] = 1;
            return Arrays.copyOf(out, m);
        }

        // ---- heights at edges and vertices ----

        private static double edgeHeight(Cell c, int side, double t) {
            return switch (side) {
                case WEST -> c.h(0, c.row(t));
                case EAST -> c.h(c.nx - 1, c.row(t));
                case NORTH -> c.h(c.col(t), 0);
                default -> c.h(c.col(t), c.nz - 1);
            };
        }

        private Cell neighbour(Cell c, int side) {
            return switch (side) {
                case WEST -> cell(c.x - 1, c.z);
                case EAST -> cell(c.x + 1, c.z);
                case NORTH -> cell(c.x, c.z - 1);
                default -> cell(c.x, c.z + 1);
            };
        }

        private static int opposite(int side) {
            return side ^ 1;
        }

        /** Whether c's side of its border edge on {@code side} is at least as high as the other side, at t. */
        private boolean higherSide(Cell c, int side, double t) {
            double self = edgeHeight(c, side, t);
            if (Double.isNaN(self)) {
                return false;
            }
            Cell nb = neighbour(c, side);
            if (nb == null) {
                return true;
            }
            double other = edgeHeight(nb, opposite(side), t);
            return Double.isNaN(other) || self >= other - EPS;
        }

        private static double cornerHeight(Cell c, int vx, int vz) {
            int i = vx == c.x ? 0 : c.nx - 1;
            int j = vz == c.z ? 0 : c.nz - 1;
            return c.h(i, j);
        }

        /** A cap is painted when its corner is the highest of the four around the vertex. */
        private boolean capPainted(Cell c, int vx, int vz) {
            double self = cornerHeight(c, vx, vz);
            if (Double.isNaN(self)) {
                return false;
            }
            for (int dz = -1; dz <= 0; dz++) {
                for (int dx = -1; dx <= 0; dx++) {
                    if (vx + dx == c.x && vz + dz == c.z) {
                        continue;
                    }
                    Cell q = cell(vx + dx, vz + dz);
                    if (q != null) {
                        double hq = cornerHeight(q, vx, vz);
                        if (!Double.isNaN(hq) && hq > self + EPS) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private int vertexStyle(int vx, int vz) {
            int s = mergeStyle(borders.z(vx - 1, vz), borders.z(vx, vz));
            s = mergeStyle(s, borders.x(vx, vz - 1));
            return mergeStyle(s, borders.x(vx, vz));
        }

        // ---- painting ----

        /** Paint info bits: style in bits 0-1; a painted parallel-to-x-axis (north/south) edge; a painted east/west edge; cap. */
        private static final int ALONG_X = 4;
        private static final int ALONG_Z = 8;
        private static final int CAP = 16;

        private int paintInfo(Cell c, double xm, double zm) {
            boolean nearN = zm < HALF_W;
            boolean nearS = zm > 1 - HALF_W;
            boolean nearW = xm < HALF_W;
            boolean nearE = xm > 1 - HALF_W;
            if (!(nearN || nearS || nearW || nearE)) {
                return 0;
            }
            int info = 0;
            boolean member = false;
            int edge;
            if (nearN && (edge = borders.z(c.x, c.z)) != 0) {
                member = true;
                if (higherSide(c, NORTH, xm)) {
                    info = mergeStyle(info & 3, edge) | (info & ~3) | ALONG_X;
                }
            }
            if (nearS && (edge = borders.z(c.x, c.z + 1)) != 0) {
                member = true;
                if (higherSide(c, SOUTH, xm)) {
                    info = mergeStyle(info & 3, edge) | (info & ~3) | ALONG_X;
                }
            }
            if (nearW && (edge = borders.x(c.x, c.z)) != 0) {
                member = true;
                if (higherSide(c, WEST, zm)) {
                    info = mergeStyle(info & 3, edge) | (info & ~3) | ALONG_Z;
                }
            }
            if (nearE && (edge = borders.x(c.x + 1, c.z)) != 0) {
                member = true;
                if (higherSide(c, EAST, zm)) {
                    info = mergeStyle(info & 3, edge) | (info & ~3) | ALONG_Z;
                }
            }
            if (!member && (nearN || nearS) && (nearW || nearE)) {
                int vx = nearW ? c.x : c.x + 1;
                int vz = nearN ? c.z : c.z + 1;
                int style = vertexStyle(vx, vz);
                if (style != 0 && capPainted(c, vx, vz)) {
                    info = style | CAP;
                }
            }
            return info;
        }

        private void paintCell(int cx, int cz) {
            Cell c = cell(cx, cz);
            if (c == null) {
                return;
            }
            for (int j = 0; j < c.nz; j++) {
                double z0 = c.zs[j];
                double z1 = c.zs[j + 1];
                double zm = (z0 + z1) * 0.5;
                int runStart = -1;
                double runH = 0;
                int runStyle = 0;
                for (int i = 0; i <= c.nx; i++) {
                    int info = 0;
                    double h = Double.NaN;
                    if (i < c.nx) {
                        h = c.h(i, j);
                        if (!Double.isNaN(h)) {
                            info = paintInfo(c, (c.xs[i] + c.xs[i + 1]) * 0.5, zm);
                        }
                    }
                    boolean painted = info != 0;
                    boolean continues = painted && runStart >= 0 && Math.abs(h - runH) < EPS && (info & 3) == runStyle;
                    if (runStart >= 0 && !continues) {
                        mesh.horizontal(Mesh.LINE, runStyle, cx + c.xs[runStart], cz + z0, cx + c.xs[i], cz + z1, runH + LIFT);
                        runStart = -1;
                    }
                    if (painted) {
                        if (runStart < 0) {
                            runStart = i;
                            runH = h;
                            runStyle = info & 3;
                        }
                        faces(c, i, j, h, info);
                    }
                }
            }
        }

        private void faces(Cell c, int i, int j, double h, int info) {
            boolean cap = (info & CAP) != 0;
            // A face whose normal is x is parallel to the east/west borders; painted whole when the patch also
            // belongs to a north/south border (it then crosses that border's band) or is a cap.
            boolean fullX = cap || (info & ALONG_X) != 0;
            boolean fullZ = cap || (info & ALONG_Z) != 0;
            for (int dir = 0; dir < 4; dir++) {
                neighbourSpans(c, i, j, dir);
                for (int s = 0; s < spans.size(); s += 3) {
                    double hn = spans.getDouble(s + 2);
                    if (Double.isNaN(hn) || h <= hn + EPS) {
                        continue;
                    }
                    boolean normalX = dir == WEST || dir == EAST;
                    face(c, i, j, dir, spans.getDouble(s), spans.getDouble(s + 1), h, hn, info & 3, normalX ? fullX : fullZ);
                }
            }
        }

        /** Fills {@link #spans} with (s0, s1, neighbour height) triples along patch (i, j)'s side {@code dir}. */
        private void neighbourSpans(Cell c, int i, int j, int dir) {
            spans.clear();
            switch (dir) {
                case WEST -> {
                    if (i > 0) {
                        addSpan(c.zs[j], c.zs[j + 1], c.h(i - 1, j));
                    } else {
                        Cell nb = cell(c.x - 1, c.z);
                        if (nb != null) {
                            for (int k = 0; k < nb.nz; k++) {
                                addSpan(Math.max(c.zs[j], nb.zs[k]), Math.min(c.zs[j + 1], nb.zs[k + 1]), nb.h(nb.nx - 1, k));
                            }
                        }
                    }
                }
                case EAST -> {
                    if (i < c.nx - 1) {
                        addSpan(c.zs[j], c.zs[j + 1], c.h(i + 1, j));
                    } else {
                        Cell nb = cell(c.x + 1, c.z);
                        if (nb != null) {
                            for (int k = 0; k < nb.nz; k++) {
                                addSpan(Math.max(c.zs[j], nb.zs[k]), Math.min(c.zs[j + 1], nb.zs[k + 1]), nb.h(0, k));
                            }
                        }
                    }
                }
                case NORTH -> {
                    if (j > 0) {
                        addSpan(c.xs[i], c.xs[i + 1], c.h(i, j - 1));
                    } else {
                        Cell nb = cell(c.x, c.z - 1);
                        if (nb != null) {
                            for (int k = 0; k < nb.nx; k++) {
                                addSpan(Math.max(c.xs[i], nb.xs[k]), Math.min(c.xs[i + 1], nb.xs[k + 1]), nb.h(k, nb.nz - 1));
                            }
                        }
                    }
                }
                default -> {
                    if (j < c.nz - 1) {
                        addSpan(c.xs[i], c.xs[i + 1], c.h(i, j + 1));
                    } else {
                        Cell nb = cell(c.x, c.z + 1);
                        if (nb != null) {
                            for (int k = 0; k < nb.nx; k++) {
                                addSpan(Math.max(c.xs[i], nb.xs[k]), Math.min(c.xs[i + 1], nb.xs[k + 1]), nb.h(k, 0));
                            }
                        }
                    }
                }
            }
        }

        private void addSpan(double s0, double s1, double h) {
            if (s1 - s0 > EPS) {
                spans.add(s0);
                spans.add(s1);
                spans.add(h);
            }
        }

        private void face(Cell c, int i, int j, int dir, double s0, double s1, double top, double ground, int style, boolean whole) {
            double bottom = faceBottom(c.x, c.z, c.xs[i], c.xs[i + 1], c.zs[j], c.zs[j + 1], top, ground);
            if (bottom >= top - EPS) {
                return;
            }
            boolean normalX = dir == WEST || dir == EAST;
            double plane = switch (dir) {
                case WEST -> c.x + c.xs[i] - LIFT;
                case EAST -> c.x + c.xs[i + 1] + LIFT;
                case NORTH -> c.z + c.zs[j] - LIFT;
                default -> c.z + c.zs[j + 1] + LIFT;
            };
            double base = normalX ? c.z : c.x;
            double a = base + s0;
            double b = base + s1;
            double yTop = top + LIFT;
            if (whole || top - bottom <= 2 * HALF_W + EPS) {
                mesh.vertical(Mesh.LINE, style, normalX, plane, a, b, bottom, yTop);
                return;
            }
            mesh.vertical(Mesh.LINE, style, normalX, plane, a, b, top - HALF_W, yTop);
            if (bottom <= ground + EPS && top - bottom > BASE_BAND_MIN_DROP + EPS) {
                mesh.vertical(Mesh.LINE, style, normalX, plane, a, b, bottom, bottom + HALF_W);
            }
        }

        /**
         * Walks down the solid run under a patch's top: returns the lowest y (not below {@code ground}) that the column
         * stays continuously solid to under footprint [x0, x1] x [z0, z1]. Stops at a gap (an overhang).
         */
        private double faceBottom(int cx, int cz, double x0, double x1, double z0, double z1, double top, double ground) {
            double y = top;
            int b = (int) Math.floor(top - EPS);
            boolean first = true;
            for (int iter = 0; iter < MAX_FACE_DEPTH && y > ground + EPS && b >= terrain.scanBottom(); iter++) {
                scratch.clear();
                final int by = b;
                terrain.boxes(cx, b, cz, (bx0, by0, bz0, bx1, by1, bz1) -> {
                    if (clamp01(bx0) <= x0 + EPS && clamp01(bx1) >= x1 - EPS
                            && clamp01(bz0) <= z0 + EPS && clamp01(bz1) >= z1 - EPS) {
                        scratch.add(by + by0);
                        scratch.add(by + by1);
                    }
                });
                boolean found = false;
                boolean progress = true;
                while (progress) {
                    progress = false;
                    for (int k = 0; k < scratch.size(); k += 2) {
                        double lo = scratch.getDouble(k);
                        double hi = scratch.getDouble(k + 1);
                        if (hi >= y - EPS && lo < y - EPS) {
                            y = lo;
                            progress = true;
                            found = true;
                        }
                    }
                }
                if (!found) {
                    if (first) {
                        // The top may belong to a taller-than-a-block shape of the block below.
                        first = false;
                        b--;
                        continue;
                    }
                    break;
                }
                first = false;
                if (y > b + EPS) {
                    break;
                }
                b--;
            }
            return Math.max(y, ground);
        }

        // ---- veils ----

        private void veil(int x, int z, boolean onXPlane, int style) {
            Cell a;
            Cell b;
            int sideA;
            int sideB;
            if (onXPlane) {
                a = cell(x - 1, z);
                b = cell(x, z);
                sideA = EAST;
                sideB = WEST;
            } else {
                a = cell(x, z - 1);
                b = cell(x, z);
                sideA = SOUTH;
                sideB = NORTH;
            }
            if (a == null && b == null) {
                return;
            }
            double[] cutsA = a == null ? new double[0] : (onXPlane ? a.zs : a.xs);
            double[] cutsB = b == null ? new double[0] : (onXPlane ? b.zs : b.xs);
            double[] cuts = new double[cutsA.length + cutsB.length];
            System.arraycopy(cutsA, 0, cuts, 0, cutsA.length);
            System.arraycopy(cutsB, 0, cuts, cutsA.length, cutsB.length);
            Arrays.sort(cuts);
            double plane = onXPlane ? x : z;
            double along = onXPlane ? z : x;
            double runStart = Double.NaN;
            double runBase = Double.NaN;
            double prev = Double.NaN;
            for (int k = 0; k < cuts.length; k++) {
                if (!Double.isNaN(prev) && cuts[k] - prev <= EPS) {
                    continue;
                }
                if (!Double.isNaN(prev)) {
                    double tm = (prev + cuts[k]) * 0.5;
                    double ha = a == null ? Double.NaN : edgeHeight(a, sideA, tm);
                    double hb = b == null ? Double.NaN : edgeHeight(b, sideB, tm);
                    double base = Double.isNaN(ha) ? hb : (Double.isNaN(hb) ? ha : Math.max(ha, hb));
                    if (!Double.isNaN(runStart) && (Double.isNaN(base) || Math.abs(base - runBase) > EPS)) {
                        emitVeil(style, onXPlane, plane, along + runStart, along + prev, runBase);
                        runStart = Double.NaN;
                    }
                    if (!Double.isNaN(base) && Double.isNaN(runStart)) {
                        runStart = prev;
                        runBase = base;
                    }
                }
                prev = cuts[k];
            }
            if (!Double.isNaN(runStart)) {
                emitVeil(style, onXPlane, plane, along + runStart, along + prev, runBase);
            }
        }

        private void emitVeil(int style, boolean onXPlane, double plane, double s0, double s1, double base) {
            mesh.vertical(Mesh.VEIL, style, onXPlane, plane, s0, s1, base + LIFT, base + VEIL_HEIGHT);
        }

        private static double clamp01(double v) {
            return v < 0 ? 0 : (v > 1 ? 1 : v);
        }
    }

    private TalcBorderGeometry() {
    }
}
