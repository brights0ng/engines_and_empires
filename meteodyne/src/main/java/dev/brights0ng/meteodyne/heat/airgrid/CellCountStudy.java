// Written by Claude.
package dev.brights0ng.meteodyne.heat.airgrid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Geometry-only cell-count study for the local-air redesign: how many simulation cells three grid schemes need for one
 * snapshot of the world. Pure Java, no Minecraft types; no physics.
 *
 * <p>Cells are simulated blocks ({@link AirCellType#simulated()}); SOLID and FLUID are walls. Only identical types merge.
 * <ul>
 *   <li>Uniform: one cell per simulated block.</li>
 *   <li>Octree: aligned power-of-two cubes of edge 1 to 16, aligned to world coordinates (16 = a chunk section); a node
 *       merges when every block is in the box, simulated, not forced and the same type. Optionally 2:1 balanced (face
 *       neighbours at most one level apart).</li>
 *   <li>Greedy: maximal single-type cuboids, scanning x fastest, then z, then y, extending x, then z, then y. With an
 *       aspect limit the finished box's longer edges are cut back to {@code limit * shortest edge}.</li>
 * </ul>
 * The active variants force size-1 cells at blocks within {@link #ACTIVE_RADIUS} (Chebyshev) of a marked point.
 * Interfaces are face-adjacent cell pairs; a pair is mismatched when the two cells' dimensions differ.
 */
public final class CellCountStudy {
    public static final int MAX_LEVEL = 4;
    public static final int MAX_OCTREE_EDGE = 1 << MAX_LEVEL;
    public static final int ACTIVE_RADIUS = 8;
    public static final int GREEDY_EDGE = 16;
    public static final int GREEDY_ASPECT = 4;

    private static final boolean[] SIMULATED = new boolean[AirCellType.values().length];
    static {
        for (AirCellType t : AirCellType.values()) {
            SIMULATED[t.ordinal()] = t.simulated();
        }
    }

    /**
     * A box of blocks: world origin, sizes, one {@link AirCellType} ordinal per block (y-major, then z, then x) and,
     * optionally, one byte per block that is 1 where sky light is 15.
     */
    public record Grid(int x0, int y0, int z0, int sx, int sy, int sz, byte[] types, byte[] sky) {
        public Grid {
            if (sx <= 0 || sy <= 0 || sz <= 0 || (long) sx * sy * sz != types.length) {
                throw new IllegalArgumentException("Grid " + sx + "x" + sy + "x" + sz + " does not match " + types.length + " types");
            }
            if (sky != null && sky.length != types.length) {
                throw new IllegalArgumentException("Sky bytes do not match the grid");
            }
        }

        public int size() {
            return types.length;
        }

        public int index(int x, int y, int z) {
            return (y * sz + z) * sx + x;
        }

        public boolean simulated(int i) {
            return SIMULATED[types[i]];
        }
    }

    /** A set of box-shaped cells covering every simulated block once, in box-local coordinates. */
    public static final class Partition {
        private static final int STRIDE = 7;
        private final Grid grid;
        private final int[] label;
        private int[] data = new int[STRIDE * 256];
        private int count;

        Partition(Grid grid) {
            this.grid = grid;
            this.label = new int[grid.size()];
            Arrays.fill(label, -1);
        }

        void add(int x, int y, int z, int dx, int dy, int dz, int type) {
            if ((count + 1) * STRIDE > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            int o = count * STRIDE;
            data[o] = x;
            data[o + 1] = y;
            data[o + 2] = z;
            data[o + 3] = dx;
            data[o + 4] = dy;
            data[o + 5] = dz;
            data[o + 6] = type;
            for (int yy = y; yy < y + dy; yy++) {
                for (int zz = z; zz < z + dz; zz++) {
                    int row = grid.index(x, yy, zz);
                    Arrays.fill(label, row, row + dx, count);
                }
            }
            count++;
        }

        public int count() {
            return count;
        }

        /** The cell holding the box-local block, or -1 for a wall. */
        public int cellAt(int x, int y, int z) {
            return label[grid.index(x, y, z)];
        }

        public int x(int c) { return data[c * STRIDE]; }
        public int y(int c) { return data[c * STRIDE + 1]; }
        public int z(int c) { return data[c * STRIDE + 2]; }
        public int dx(int c) { return data[c * STRIDE + 3]; }
        public int dy(int c) { return data[c * STRIDE + 4]; }
        public int dz(int c) { return data[c * STRIDE + 5]; }
        public AirCellType type(int c) { return AirCellType.of(data[c * STRIDE + 6]); }

        public int volume(int c) {
            return dx(c) * dy(c) * dz(c);
        }

        boolean sameDims(int a, int b) {
            return dx(a) == dx(b) && dy(a) == dy(b) && dz(a) == dz(b);
        }
    }

    /** One scheme's numbers. {@code largest} is {@code {volume, dx, dy, dz}}. */
    public record Metrics(String scheme, long cells, long interfaces, long mismatched, int[] largest, double medianVolume,
                          double buildMs) {
        public Map<String, Object> toMap(long uniformCells) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scheme", scheme);
            m.put("cells", cells);
            m.put("interfaces", interfaces);
            m.put("interfaceMismatch", mismatched);
            m.put("largestCellVolume", largest[0]);
            m.put("largestCellDims", List.of(largest[1], largest[2], largest[3]));
            m.put("medianCellVolume", medianVolume);
            m.put("buildMs", buildMs);
            m.put("savingVsUniform", saving(uniformCells));
            return m;
        }

        public double saving(long uniformCells) {
            return uniformCells == 0 ? 0 : 1.0 - (double) cells / uniformCells;
        }
    }

    /** The whole study for one grid. */
    public record Result(List<Metrics> schemes, long[] typeCounts, long simulatedOutdoor, long simulatedIndoor,
                         long airOutdoor, long airIndoor, int activePoints, long activeBlocks, double activeMaskMs) {
        public long uniformCells() {
            return schemes.get(0).cells();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> types = new LinkedHashMap<>();
            for (AirCellType t : AirCellType.values()) {
                types.put(t.name(), typeCounts[t.ordinal()]);
            }
            m.put("typeCounts", types);
            Map<String, Object> sky = new LinkedHashMap<>();
            sky.put("simulatedOutdoor", simulatedOutdoor);
            sky.put("simulatedIndoor", simulatedIndoor);
            sky.put("airOutdoor", airOutdoor);
            sky.put("airIndoor", airIndoor);
            sky.put("rule", "outdoor = sky light 15 at the block");
            m.put("sky", sky);
            Map<String, Object> active = new LinkedHashMap<>();
            active.put("points", activePoints);
            active.put("forcedBlocks", activeBlocks);
            active.put("radiusChebyshev", ACTIVE_RADIUS);
            active.put("maskMs", activeMaskMs);
            m.put("active", active);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Metrics s : schemes) {
                list.add(s.toMap(uniformCells()));
            }
            m.put("schemes", list);
            return m;
        }

        /** One line per scheme: cells, interfaces, saving vs uniform and build time. */
        public List<String> chatLines() {
            List<String> lines = new ArrayList<>();
            for (Metrics s : schemes) {
                lines.add(String.format(Locale.ROOT, "%s: %,d cells, %,d interfaces, saves %.1f%%, %.1f ms",
                        s.scheme(), s.cells(), s.interfaces(), 100 * s.saving(uniformCells()), s.buildMs()));
            }
            return lines;
        }
    }

    /** Runs every scheme and variant. {@code activePoints} are box-local block indices (the player, heat sources). */
    public static Result run(Grid g, int[] activePoints) {
        List<Metrics> schemes = new ArrayList<>();
        long t = System.nanoTime();
        Metrics uniform = uniform(g);
        schemes.add(new Metrics("uniform", uniform.cells(), uniform.interfaces(), 0, uniform.largest(), uniform.medianVolume(), ms(t)));
        addSchemes(schemes, g, null, "");
        t = System.nanoTime();
        boolean[] forced = forcedNear(g, activePoints, ACTIVE_RADIUS);
        double maskMs = ms(t);
        addSchemes(schemes, g, forced, "active ");

        long[] types = new long[AirCellType.values().length];
        long simOut = 0, simIn = 0, airOut = 0, airIn = 0, activeBlocks = 0;
        int air = AirCellType.AIR.ordinal();
        for (int i = 0; i < g.size(); i++) {
            types[g.types()[i]]++;
            if (forced[i]) {
                activeBlocks++;
            }
            if (g.sky() != null && g.simulated(i)) {
                boolean out = g.sky()[i] != 0;
                if (out) { simOut++; } else { simIn++; }
                if (g.types()[i] == air) {
                    if (out) { airOut++; } else { airIn++; }
                }
            }
        }
        return new Result(schemes, types, simOut, simIn, airOut, airIn, activePoints.length, activeBlocks, maskMs);
    }

    private static void addSchemes(List<Metrics> out, Grid g, boolean[] forced, String prefix) {
        long t = System.nanoTime();
        byte[] levels = octreeLevels(g, forced);
        Partition octree = fromLevels(g, levels);
        out.add(measure(prefix + "octree", octree, ms(t)));
        t = System.nanoTime();
        byte[] balanced = octreeLevels(g, forced);
        balance(g, balanced);
        out.add(measure(prefix + "octree 2:1", fromLevels(g, balanced), ms(t)));
        t = System.nanoTime();
        out.add(measure(prefix + "greedy", greedy(g, forced, 0, 0), ms(t)));
        t = System.nanoTime();
        out.add(measure(prefix + "greedy edge16", greedy(g, forced, GREEDY_EDGE, 0), ms(t)));
        t = System.nanoTime();
        out.add(measure(prefix + "greedy edge16 aspect4", greedy(g, forced, GREEDY_EDGE, GREEDY_ASPECT), ms(t)));
    }

    private static double ms(long start) {
        return (System.nanoTime() - start) / 1e6;
    }

    // ---- uniform ----

    /** Uniform metrics without building cells: one per simulated block. */
    public static Metrics uniform(Grid g) {
        long cells = 0, interfaces = 0;
        int sx = g.sx(), sy = g.sy(), sz = g.sz();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int i = g.index(x, y, z);
                    if (!g.simulated(i)) {
                        continue;
                    }
                    cells++;
                    if (x + 1 < sx && g.simulated(i + 1)) { interfaces++; }
                    if (z + 1 < sz && g.simulated(i + sx)) { interfaces++; }
                    if (y + 1 < sy && g.simulated(i + sx * sz)) { interfaces++; }
                }
            }
        }
        return new Metrics("uniform", cells, interfaces, 0, cells == 0 ? new int[] {0, 0, 0, 0} : new int[] {1, 1, 1, 1},
                cells == 0 ? 0 : 1, 0);
    }

    // ---- octree ----

    public static Partition octree(Grid g, boolean[] forced, boolean balanced) {
        byte[] levels = octreeLevels(g, forced);
        if (balanced) {
            balance(g, levels);
        }
        return fromLevels(g, levels);
    }

    /** The unbalanced octree as a per-block leaf level (edge {@code 1 << level}), or -1 for a wall. */
    static byte[] octreeLevels(Grid g, boolean[] forced) {
        int e = MAX_OCTREE_EDGE;
        int ax0 = Math.floorDiv(g.x0(), e) * e, ay0 = Math.floorDiv(g.y0(), e) * e, az0 = Math.floorDiv(g.z0(), e) * e;
        int ox = g.x0() - ax0, oy = g.y0() - ay0, oz = g.z0() - az0;
        int ax = ceilTo(ox + g.sx(), e), ay = ceilTo(oy + g.sy(), e), az = ceilTo(oz + g.sz(), e);
        byte[][] code = new byte[MAX_LEVEL + 1][];
        code[0] = new byte[ax * ay * az];
        Arrays.fill(code[0], (byte) -1);
        for (int y = 0; y < g.sy(); y++) {
            for (int z = 0; z < g.sz(); z++) {
                for (int x = 0; x < g.sx(); x++) {
                    int i = g.index(x, y, z);
                    if (g.simulated(i) && (forced == null || !forced[i])) {
                        code[0][((y + oy) * az + z + oz) * ax + x + ox] = g.types()[i];
                    }
                }
            }
        }
        for (int l = 1; l <= MAX_LEVEL; l++) {
            int nx = ax >> l, ny = ay >> l, nz = az >> l, cx = nx * 2, cz = nz * 2;
            byte[] child = code[l - 1];
            byte[] node = new byte[nx * ny * nz];
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    for (int x = 0; x < nx; x++) {
                        int first = ((2 * y) * cz + 2 * z) * cx + 2 * x;
                        byte c = child[first];
                        boolean same = c >= 0;
                        for (int k = 0; k < 8 && same; k++) {
                            int j = ((2 * y + (k >> 2)) * cz + 2 * z + ((k >> 1) & 1)) * cx + 2 * x + (k & 1);
                            same = child[j] == c;
                        }
                        node[(y * nz + z) * nx + x] = same ? c : -1;
                    }
                }
            }
            code[l] = node;
        }
        byte[] levels = new byte[g.size()];
        Arrays.fill(levels, (byte) -1);
        int top = ax >> MAX_LEVEL, topY = ay >> MAX_LEVEL, topZ = az >> MAX_LEVEL;
        for (int y = 0; y < topY; y++) {
            for (int z = 0; z < topZ; z++) {
                for (int x = 0; x < top; x++) {
                    emit(g, forced, code, levels, MAX_LEVEL, x, y, z, ax, az, ox, oy, oz);
                }
            }
        }
        return levels;
    }

    private static void emit(Grid g, boolean[] forced, byte[][] code, byte[] levels, int l, int x, int y, int z,
                             int ax, int az, int ox, int oy, int oz) {
        int nx = ax >> l, nz = az >> l, s = 1 << l;
        if (code[l][(y * nz + z) * nx + x] >= 0) {
            // A merged node lies wholly inside the box.
            fill(g, levels, x * s - ox, y * s - oy, z * s - oz, s, (byte) l);
            return;
        }
        if (l == 0) {
            int bx = x - ox, by = y - oy, bz = z - oz;
            if (bx >= 0 && by >= 0 && bz >= 0 && bx < g.sx() && by < g.sy() && bz < g.sz()
                    && g.simulated(g.index(bx, by, bz))) {
                levels[g.index(bx, by, bz)] = 0; // forced, or not mergeable with its siblings
            }
            return;
        }
        for (int k = 0; k < 8; k++) {
            int cx = 2 * x + (k & 1), cy = 2 * y + (k >> 2), cz = 2 * z + ((k >> 1) & 1);
            // Skip children wholly outside the box.
            int cs = s >> 1;
            int wx = cx * cs - ox, wy = cy * cs - oy, wz = cz * cs - oz;
            if (wx >= g.sx() || wy >= g.sy() || wz >= g.sz() || wx + cs <= 0 || wy + cs <= 0 || wz + cs <= 0) {
                continue;
            }
            emit(g, forced, code, levels, l - 1, cx, cy, cz, ax, az, ox, oy, oz);
        }
    }

    private static void fill(Grid g, byte[] levels, int x, int y, int z, int s, byte value) {
        for (int yy = y; yy < y + s; yy++) {
            for (int zz = z; zz < z + s; zz++) {
                int row = g.index(x, yy, zz);
                Arrays.fill(levels, row, row + s, value);
            }
        }
    }

    /** Splits leaves until no two face-adjacent cells differ by more than one level. */
    static void balance(Grid g, byte[] levels) {
        long[] stack = new long[1024];
        int[] found = new int[6];
        int top = 0;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] >= 2 && origin(g, i, levels[i])) {
                if (top == stack.length) { stack = Arrays.copyOf(stack, top * 2); }
                stack[top++] = ((long) levels[i] << 32) | i;
            }
        }
        while (top > 0) {
            long entry = stack[--top];
            int l = (int) (entry >>> 32), i = (int) entry;
            if (levels[i] != l || !violates(g, levels, i, l)) {
                continue;
            }
            int s = 1 << l, h = s >> 1;
            int x = i % g.sx(), z = (i / g.sx()) % g.sz(), y = i / (g.sx() * g.sz());
            fill(g, levels, x, y, z, s, (byte) (l - 1));
            if (l - 1 >= 2) {
                for (int k = 0; k < 8; k++) {
                    if (top == stack.length) { stack = Arrays.copyOf(stack, top * 2); }
                    stack[top++] = ((long) (l - 1) << 32) | g.index(x + (k & 1) * h, y + (k >> 2) * h, z + ((k >> 1) & 1) * h);
                }
            }
            // Neighbours two or more levels above the new children must be checked again.
            int n = neighbours(g, levels, x, y, z, s, l + 1, found);
            for (int k = 0; k < n; k++) {
                if (top == stack.length) { stack = Arrays.copyOf(stack, top * 2); }
                int j = found[k];
                stack[top++] = ((long) levels[j] << 32) | leafOrigin(g, j, levels[j]);
            }
        }
    }

    private static boolean violates(Grid g, byte[] levels, int i, int l) {
        int s = 1 << l;
        int x = i % g.sx(), z = (i / g.sx()) % g.sz(), y = i / (g.sx() * g.sz());
        for (int f = 0; f < 6; f++) {
            int axis = f >> 1;
            int px = axis == 0 ? ((f & 1) == 0 ? x - 1 : x + s) : x;
            int py = axis == 1 ? ((f & 1) == 0 ? y - 1 : y + s) : y;
            int pz = axis == 2 ? ((f & 1) == 0 ? z - 1 : z + s) : z;
            if (px < 0 || py < 0 || pz < 0 || px >= g.sx() || py >= g.sy() || pz >= g.sz()) {
                continue;
            }
            // The face strip lies in one aligned cube of this leaf's size: a leaf at least that big covers all of it.
            if (levels[g.index(px, py, pz)] >= l) {
                continue;
            }
            for (int a = 0; a < s; a++) {
                for (int b = 0; b < s; b++) {
                    int qx = px, qy = py, qz = pz;
                    if (axis == 0) { qy += a; qz += b; } else if (axis == 1) { qx += a; qz += b; } else { qx += a; qy += b; }
                    int nl = levels[g.index(qx, qy, qz)];
                    if (nl >= 0 && nl < l - 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Distinct-leaf neighbour blocks (any one block per leaf is enough) of level at least {@code min}. */
    private static int neighbours(Grid g, byte[] levels, int x, int y, int z, int s, int min, int[] out) {
        int n = 0;
        for (int f = 0; f < 6; f++) {
            int axis = f >> 1;
            int px = axis == 0 ? ((f & 1) == 0 ? x - 1 : x + s) : x;
            int py = axis == 1 ? ((f & 1) == 0 ? y - 1 : y + s) : y;
            int pz = axis == 2 ? ((f & 1) == 0 ? z - 1 : z + s) : z;
            if (px < 0 || py < 0 || pz < 0 || px >= g.sx() || py >= g.sy() || pz >= g.sz()) {
                continue;
            }
            // A neighbour leaf bigger than this one covers the whole face strip: one block per face is enough.
            int nl = levels[g.index(px, py, pz)];
            if (nl >= min && n < out.length) {
                out[n++] = g.index(px, py, pz);
            }
        }
        return n;
    }

    private static boolean origin(Grid g, int i, int l) {
        int s = 1 << l;
        int x = i % g.sx(), z = (i / g.sx()) % g.sz(), y = i / (g.sx() * g.sz());
        return Math.floorMod(g.x0() + x, s) == 0 && Math.floorMod(g.y0() + y, s) == 0 && Math.floorMod(g.z0() + z, s) == 0;
    }

    private static int leafOrigin(Grid g, int i, int l) {
        int x = i % g.sx(), z = (i / g.sx()) % g.sz(), y = i / (g.sx() * g.sz());
        return g.index(x - Math.floorMod(g.x0() + x, 1 << l), y - Math.floorMod(g.y0() + y, 1 << l),
                z - Math.floorMod(g.z0() + z, 1 << l));
    }

    static Partition fromLevels(Grid g, byte[] levels) {
        Partition p = new Partition(g);
        for (int y = 0; y < g.sy(); y++) {
            for (int z = 0; z < g.sz(); z++) {
                for (int x = 0; x < g.sx(); x++) {
                    int i = g.index(x, y, z);
                    int l = levels[i];
                    if (l >= 0 && origin(g, i, l)) {
                        int s = 1 << l;
                        p.add(x, y, z, s, s, s, g.types()[i]);
                    }
                }
            }
        }
        return p;
    }

    private static int ceilTo(int v, int e) {
        return Math.floorDiv(v + e - 1, e) * e;
    }

    // ---- greedy ----

    /** Greedy cuboids; {@code maxEdge} and {@code maxAspect} of 0 mean unlimited. */
    public static Partition greedy(Grid g, boolean[] forced, int maxEdge, int maxAspect) {
        int edge = maxEdge <= 0 ? Integer.MAX_VALUE : maxEdge;
        Partition p = new Partition(g);
        int sx = g.sx(), sy = g.sy(), sz = g.sz();
        byte[] types = g.types();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int i = g.index(x, y, z);
                    if (!g.simulated(i) || p.label[i] >= 0) {
                        continue;
                    }
                    byte t = types[i];
                    if (forced != null && forced[i]) {
                        p.add(x, y, z, 1, 1, 1, t);
                        continue;
                    }
                    int dx = 1;
                    while (x + dx < sx && dx < edge && free(p, forced, types, i + dx, t)) {
                        dx++;
                    }
                    int dz = 1;
                    while (z + dz < sz && dz < edge && free(p, forced, types, g.index(x, y, z + dz), dx, 1, t)) {
                        dz++;
                    }
                    int dy = 1;
                    while (y + dy < sy && dy < edge && free(p, forced, types, g.index(x, y + dy, z), dx, dz, t)) {
                        dy++;
                    }
                    if (maxAspect > 0) {
                        long cap = (long) Math.min(dx, Math.min(dy, dz)) * maxAspect;
                        dx = (int) Math.min(dx, cap);
                        dy = (int) Math.min(dy, cap);
                        dz = (int) Math.min(dz, cap);
                    }
                    p.add(x, y, z, dx, dy, dz, t);
                }
            }
        }
        return p;
    }

    private static boolean free(Partition p, boolean[] forced, byte[] types, int i, byte t) {
        return types[i] == t && p.label[i] < 0 && (forced == null || !forced[i]);
    }

    private static boolean free(Partition p, boolean[] forced, byte[] types, int start, int dx, int dz, byte t) {
        int sx = p.grid.sx();
        for (int z = 0; z < dz; z++) {
            int row = start + z * sx;
            for (int x = 0; x < dx; x++) {
                if (!free(p, forced, types, row + x, t)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- active mask ----

    /** Blocks within {@code radius} (Chebyshev) of any point; points are box-local block indices. */
    public static boolean[] forcedNear(Grid g, int[] points, int radius) {
        boolean[] forced = new boolean[g.size()];
        for (int p : points) {
            forced[p] = true;
        }
        // A Chebyshev ball is a cube: dilate along one axis at a time.
        int sx = g.sx(), sy = g.sy(), sz = g.sz();
        int[] scratch = new int[Math.max(sx, Math.max(sy, sz))];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                dilate(forced, g.index(0, y, z), 1, sx, radius, scratch);
            }
        }
        for (int y = 0; y < sy; y++) {
            for (int x = 0; x < sx; x++) {
                dilate(forced, g.index(x, y, 0), sx, sz, radius, scratch);
            }
        }
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                dilate(forced, g.index(x, 0, z), sx * sz, sy, radius, scratch);
            }
        }
        return forced;
    }

    /** Marks every position of one line within {@code radius} of a marked position. */
    private static void dilate(boolean[] mask, int start, int stride, int length, int radius, int[] nearest) {
        int last = Integer.MIN_VALUE / 2;
        for (int k = 0; k < length; k++) {
            if (mask[start + k * stride]) {
                last = k;
            }
            nearest[k] = k - last;
        }
        last = Integer.MAX_VALUE / 2;
        for (int k = length - 1; k >= 0; k--) {
            if (mask[start + k * stride]) {
                last = k;
            }
            mask[start + k * stride] = Math.min(nearest[k], last - k) <= radius;
        }
    }

    // ---- metrics ----

    /** Counts interfaces, mismatches, the largest and the median cell of a partition. */
    public static Metrics measure(String scheme, Partition p, double buildMs) {
        Grid g = p.grid;
        int n = p.count();
        int[] stamp = new int[n];
        Arrays.fill(stamp, -1);
        int[] volumes = new int[n];
        long interfaces = 0, mismatched = 0;
        int[] largest = {0, 0, 0, 0};
        for (int c = 0; c < n; c++) {
            int v = p.volume(c);
            volumes[c] = v;
            if (v > largest[0]) {
                largest = new int[] {v, p.dx(c), p.dy(c), p.dz(c)};
            }
            int x = p.x(c), y = p.y(c), z = p.z(c), dx = p.dx(c), dy = p.dy(c), dz = p.dz(c);
            // Disjoint boxes touch across at most one plane, so scanning only the + faces finds each pair once.
            if (x + dx < g.sx()) {
                for (int yy = y; yy < y + dy; yy++) {
                    for (int zz = z; zz < z + dz; zz++) {
                        int o = p.label[g.index(x + dx, yy, zz)];
                        if (o >= 0 && stamp[o] != c) {
                            stamp[o] = c;
                            interfaces++;
                            if (!p.sameDims(c, o)) { mismatched++; }
                        }
                    }
                }
            }
            if (y + dy < g.sy()) {
                for (int zz = z; zz < z + dz; zz++) {
                    for (int xx = x; xx < x + dx; xx++) {
                        int o = p.label[g.index(xx, y + dy, zz)];
                        if (o >= 0 && stamp[o] != c) {
                            stamp[o] = c;
                            interfaces++;
                            if (!p.sameDims(c, o)) { mismatched++; }
                        }
                    }
                }
            }
            if (z + dz < g.sz()) {
                for (int yy = y; yy < y + dy; yy++) {
                    for (int xx = x; xx < x + dx; xx++) {
                        int o = p.label[g.index(xx, yy, z + dz)];
                        if (o >= 0 && stamp[o] != c) {
                            stamp[o] = c;
                            interfaces++;
                            if (!p.sameDims(c, o)) { mismatched++; }
                        }
                    }
                }
            }
        }
        Arrays.sort(volumes);
        double median = n == 0 ? 0 : (n % 2 == 1 ? volumes[n / 2] : (volumes[n / 2 - 1] + volumes[n / 2]) / 2.0);
        return new Metrics(scheme, n, interfaces, mismatched, largest, median, buildMs);
    }

    private CellCountStudy() {
    }
}
