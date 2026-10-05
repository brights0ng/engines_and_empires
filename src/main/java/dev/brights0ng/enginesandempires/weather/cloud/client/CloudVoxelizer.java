package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.List;

/**
 * Turns one cloud formation (a PA region's clusters) into a voxel mesh. Pure Java (no Minecraft rendering), so it runs
 * on a worker thread and in tests.
 *
 * <h2>Steps</h2>
 * <ol>
 *   <li><b>Field.</b> {@link CloudField} gives the formation's density: its envelope (smoothly blended domes, towers
 *       and one anvil) plus churning noise.</li>
 *   <li><b>Sampling.</b> The density is evaluated on a coarse lattice (every 8 blocks, or every voxel for voxels of
 *       8+), then interpolated to each voxel. Cells whose eight corners are all inside or all outside are filled or
 *       skipped without interpolating, which is most of them.</li>
 *   <li><b>Mesh.</b> Faces between solid and empty voxels, merged into rectangles per slice (greedy meshing). Colour
 *       is baked per vertex: a face shade (tops brightest, bottoms darkest) times a darkening toward the base from
 *       PA's base darkness and storm tier. The renderer multiplies it by the sky's cloud colour.</li>
 * </ol>
 *
 * <p>Vertices are local: x and z relative to the anchor's centre the mesh was built from, y in world height. Each quad
 * is four consecutive vertices, counter-clockwise seen from outside. All members share one grid, so their voxels line
 * up.
 */
public final class CloudVoxelizer {

    /** Receives the mesh's vertices, four per quad. */
    public interface VertexSink {
        void vertex(float x, float y, float z, int argb);
    }

    /** What a build produced. Bounds are local, like the vertices. */
    public record Result(int quads, int voxelSize, float minX, float maxX, float minY, float maxY, float minZ,
                         float maxZ) {
        static final Result EMPTY = new Result(0, 1, 0, 0, 0, 0, 0, 0);

        public boolean isEmpty() {
            return quads == 0;
        }
    }

    /**
     * Most voxels one formation's padded grid may have; past this the voxel size is doubled. The padded grid is a
     * generous box around everything the cloud could reach; only the part the coarse pass finds near the cloud is ever
     * allocated, typically a third of it or less.
     */
    public static final int MAX_VOXELS = 24_000_000;

    /** Ticks for the billows to change completely. */
    public static final double PUFF_PERIOD_TICKS = CloudField.BILLOW_PERIOD_TICKS;

    /** Voxels along one side of the grid at most. */
    private static final int MAX_SIDE = 4000;

    private static final float SHADE_TOP = 1.0f;
    private static final float SHADE_BOTTOM = 0.75f;
    private static final float SHADE_X = 0.88f;
    private static final float SHADE_Z = 0.8f;

    /**
     * How far a face is stretched past an edge, in voxels, where it continues into a coplanar face of the same
     * direction. Greedy meshing leaves T-junctions (a big face's edge running past a smaller neighbour's corner), where
     * the GPU can leave sub-pixel cracks; the overlap seals those that lie inside a flat surface, invisibly, since it
     * only ever slides under a face in the same plane. Faces are never stretched past a silhouette or convex edge, nor
     * across a section border. Whatever cracks remain show the cloud's own back faces, which the renderer draws (cull
     * off), not the sky. Tests set it to 0 to check that meshes are closed exactly.
     */
    static volatile float seamOverlap = 0.003f;

    /** The grid: voxel (i, v, j) spans x from {@code (gx0 + i) * s}, y from {@code (gy0 + v) * s}, z likewise. */
    record Grid(int gx0, int gy0, int gz0, int nx, int ny, int nz) {
        long voxels() {
            return (long) nx * ny * nz;
        }
    }

    static Grid grid(CloudField field, int s) {
        double[] b = field.bounds();
        int gx0 = (int) Math.floor(b[0] / s);
        int gy0 = (int) Math.floor(b[2] / s);
        int gz0 = (int) Math.floor(b[4] / s);
        int nx = Math.max(1, (int) Math.ceil(b[1] / s) - gx0);
        int ny = Math.max(1, (int) Math.ceil(b[3] / s) - gy0);
        int nz = Math.max(1, (int) Math.ceil(b[5] / s) - gz0);
        return new Grid(gx0, gy0, gz0, nx, ny, nz);
    }

    /** The voxel size to use: {@code wanted}, doubled until the formation fits in {@link #MAX_VOXELS}. */
    public static int voxelSizeFor(CloudFormation f, int wanted) {
        CloudField field = CloudField.of(f);
        int s = Math.max(1, wanted);
        if (field == null) {
            return s;
        }
        while (s < 256) {
            Grid g = grid(field, s);
            if (g.voxels() <= MAX_VOXELS && g.nx <= MAX_SIDE && g.ny <= MAX_SIDE && g.nz <= MAX_SIDE) {
                break;
            }
            s *= 2;
        }
        return s;
    }

    public static int voxelSizeFor(CloudShape c, int wanted) {
        return voxelSizeFor(CloudFormation.of(c.regionId(), List.of(c)), wanted);
    }

    /** Builds a lone cloud at time 0. */
    public static Result build(CloudShape c, int s, VertexSink sink) {
        return build(CloudFormation.of(c.regionId(), List.of(c)), s, 0, sink);
    }

    /**
     * Builds formation {@code f} with voxels {@code s} blocks wide, as it looks at game time {@code time} (ticks), into
     * {@code sink}.
     */
    public static Result build(CloudFormation f, int s, double time, VertexSink sink) {
        CloudField field = CloudField.of(f);
        if (field == null) {
            return Result.EMPTY;
        }
        Grid g = grid(field, s);
        Cropped sampled = sample(field, g, s, time);
        if (sampled == null) {
            return Result.EMPTY;
        }
        Cropped c = crop(sampled.solid, sampled.grid);
        if (c == null) {
            return Result.EMPTY;
        }
        return mesh(c.solid, c.grid, s, field, time, sink, null);
    }

    // ---- sections ------------------------------------------------------------------------------------------------

    /**
     * Builds one section of a formation: the box from {@code (sx, sy, sz) * size} to {@code (sx + 1, sy + 1, sz + 1)
     * * size} (anchor-local x and z, world y). {@code size} must be a multiple of {@code s}.
     *
     * <p>Sections are meshed separately, each at its own voxel size, so a big storm can be fine near the camera and
     * coarse far away. The voxels just outside the box (the apron) are sampled too:
     * <ul>
     *   <li>Faces are only made for the box's own voxels, so neighbouring sections never draw the same face.</li>
     *   <li>An own voxel facing the apron gets a face unless both it and the apron voxel are well inside the cloud
     *       ({@link #deepThreshold}). Deep inside, the neighbour is solid at any voxel size, so no face is needed; near
     *       the surface both sections close themselves, so a neighbour at another voxel size can never leave a
     *       hole.</li>
     *   <li>The lattice is aligned to the world grid, not to the section, so two sections at the same voxel size and
     *       time sample identical densities and meet seamlessly.</li>
     * </ul>
     */
    public static Result buildSection(CloudField field, int s, double time, int sx, int sy, int sz, int size,
                                      VertexSink sink) {
        if (field == null) {
            return Result.EMPTY;
        }
        SectionSetup st = sectionSetup(field, s, sx, sy, sz, size);
        if (st == null) {
            return Result.EMPTY;
        }
        Cropped sampled = sample(field, st.grid(), s, time, st.own(), deepThreshold(field), st.k());
        if (sampled == null) {
            return Result.EMPTY;
        }
        Cropped c = crop(sampled.solid, sampled.grid);
        if (c == null) {
            return Result.EMPTY;
        }
        return mesh(c.solid, c.grid, s, field, time, sink, st.own());
    }

    /** What a section samples: its grid (own box plus apron, lattice-aligned), lattice step and own box. */
    record SectionSetup(Grid grid, int k, int[] own) {
    }

    /** The sampling setup of section (sx, sy, sz), or null if it lies outside the formation's grid. */
    static SectionSetup sectionSetup(CloudField field, int s, int sx, int sy, int sz, int size) {
        int per = size / s;
        boolean fine = field.needsFineLattice((double) sx * size, (double) (sx + 1) * size, (double) sy * size,
                (double) (sy + 1) * size, (double) sz * size, (double) (sz + 1) * size);
        int k = latticeK(field, s, fine);
        int[] own = {sx * per, (sx + 1) * per, sy * per, (sy + 1) * per, sz * per, (sz + 1) * per};
        Grid whole = grid(field, s);
        int x0 = Math.max(Math.floorDiv(own[0] - 1, k) * k, Math.floorDiv(whole.gx0, k) * k);
        int x1 = Math.min(own[1] + 1, whole.gx0 + whole.nx);
        int y0 = Math.max(Math.floorDiv(own[2] - 1, k) * k, Math.floorDiv(whole.gy0, k) * k);
        int y1 = Math.min(own[3] + 1, whole.gy0 + whole.ny);
        int z0 = Math.max(Math.floorDiv(own[4] - 1, k) * k, Math.floorDiv(whole.gz0, k) * k);
        int z1 = Math.min(own[5] + 1, whole.gz0 + whole.nz);
        if (x1 <= x0 || y1 <= y0 || z1 <= z0 || x1 <= own[0] || y1 <= own[2] || z1 <= own[4]) {
            return null;
        }
        return new SectionSetup(new Grid(x0, y0, z0, x1 - x0, y1 - y0, z1 - z0), k, own);
    }

    /**
     * Whether section (sx, sy, sz) at voxel size {@code s} might hold cloud at {@code time}: runs only the coarse
     * pass of its build, so it is false exactly for the sections whose build would stop there with nothing. Cheap
     * (a few hundred envelope samples), so empty sky can be dropped before it is scheduled.
     */
    static boolean sectionMayHaveCloud(CloudField field, int s, double time, int sx, int sy, int sz, int size) {
        if (field == null) {
            return false;
        }
        SectionSetup st = sectionSetup(field, s, sx, sy, sz, size);
        return st != null && coarse(field, st.grid(), s, time, st.own(), deepThreshold(field), st.k(),
                field.newColumn()).any();
    }

    /** How far inside the cloud (density units) an apron voxel must be to count as solid: about 96 blocks. */
    static double deepThreshold(CloudField field) {
        return Math.max(0.05, 96.0 / field.rf);
    }

    /**
     * Voxels per lattice step (the full density is computed every lattice step and interpolated in between).
     * <ul>
     *   <li>{@code fine}, where a cloud has small details (the flanking towers' bubbles, the shelf's tiers): the
     *       field's own step ({@link CloudField#latticeStep}), but no more than 16 blocks for voxels of 8 or less, so
     *       the details keep their creases up close.</li>
     *   <li>Elsewhere: the field's own step (up to 32 blocks for storms, whose churn is hundreds of blocks across),
     *       and at least two voxels, so far-away sections sample on a 64- or 128-block lattice.</li>
     * </ul>
     */
    static int latticeK(CloudField field, int s, boolean fine) {
        int step = fine ? Math.min(field.latticeStep(), Math.max(16, 2 * s)) : Math.max(field.latticeStep(), 2 * s);
        return Math.max(1, step / s);
    }

    /**
     * The density the mesh was built from for the voxel holding (x, y, z) (anchor-local x and z, world y), in a section
     * (sx, sy, sz) of side {@code size} built at voxel size {@code s} and {@code time}: interpolated from the lattice
     * exactly as {@link #sample} does, so it is positive just where that section's mesh has a solid voxel. Used to
     * tell whether the camera is inside a cloud as drawn (the in-cloud fog), not inside the smooth field, which can
     * differ from the voxels by half a voxel.
     *
     * <p>Lattice points outside a column's possible range count as outside (-1), as in {@link #sample}. The one
     * difference: the coarse pass's shortcuts aren't taken, which can only matter far from the cloud's surface, where
     * the answer is the same anyway.
     */
    static double voxelDensity(CloudField field, int s, double time, int sx, int sy, int sz, int size, double x,
                               double y, double z) {
        if (field == null) {
            return -1;
        }
        long i = (long) Math.floor(x / s);
        long v = (long) Math.floor(y / s);
        long j = (long) Math.floor(z / s);
        Grid whole = grid(field, s);
        if (i < whole.gx0 || i >= whole.gx0 + whole.nx || v < whole.gy0 || v >= whole.gy0 + whole.ny
                || j < whole.gz0 || j >= whole.gz0 + whole.nz) {
            return -1;
        }
        boolean fine = field.needsFineLattice((double) sx * size, (double) (sx + 1) * size, (double) sy * size,
                (double) (sy + 1) * size, (double) sz * size, (double) (sz + 1) * size);
        int k = latticeK(field, s, fine);
        double cs = (double) s * k;
        long a = Math.floorDiv(i, k);
        long b = Math.floorDiv(v, k);
        long c = Math.floorDiv(j, k);
        double fx = (i + 0.5) / k - a;
        double fy = (v + 0.5) / k - b;
        double fz = (j + 0.5) / k - c;
        CloudField.Column col = field.newColumn();
        double[] range = new double[2];
        double[] d = new double[8];
        for (int dc = 0; dc < 2; dc++) {
            for (int da = 0; da < 2; da++) {
                double lx = (a + da) * cs;
                double lz = (c + dc) * cs;
                field.column(lx, lz, time, col);
                boolean any = field.columnRange(col, range);
                for (int db = 0; db < 2; db++) {
                    double ly = (b + db) * cs;
                    d[(dc * 2 + db) * 2 + da] = any && ly >= range[0] && ly <= range[1]
                            ? (float) field.density(col, lx, ly, lz, time, s <= 4) : -1f;
                }
            }
        }
        // Same order as sample(): along x, then y, then z.
        double x00 = d[0] + (d[1] - d[0]) * fx;
        double x10 = d[2] + (d[3] - d[2]) * fx;
        double x01 = d[4] + (d[5] - d[4]) * fx;
        double x11 = d[6] + (d[7] - d[6]) * fx;
        double y0 = x00 + (x10 - x00) * fy;
        double y1 = x01 + (x11 - x01) * fy;
        return y0 + (y1 - y0) * fz;
    }

    /**
     * A cloud's brightness (0-1, before the face shade) under {@code depthBlocks} of cloud holding {@code water} g/m^3
     * (Bright, 2026-10-04: colour from thickness and water). Real optics at the pack's x0.2 scale:
     * <ol>
     *   <li>Optical depth {@code tau = 3 LWP / (2 rho r)}: with 10 um droplets, 0.15 per g/m^2 of liquid water path
     *       (water x real metres of cloud above).</li>
     *   <li>Sunlight getting through: {@code 1 / (1 + 0.75 (1 - g) tau)}, the two-stream estimate for cloud droplets
     *       (g = 0.85, mostly forward scattering).</li>
     *   <li>Seen as brightness: the transmitted light to the power {@link CloudTuning#shadeContrast} (the eye sees
     *       ratios; this keeps thin clouds white and saves the deep grey for storms), lifted by
     *       {@link CloudTuning#baseLight}, the sky light that reaches any cloud's underside.</li>
     * </ol>
     * A fair-weather cumulus's base comes out a soft grey (about 0.6), a nimbostratus's about 0.45, a storm's about 0.35;
     * any top is white. Shared by the mesh's vertex colours and the in-cloud fog, so inside a cloud looks like its
     * outside.
     */
    static double brightness(double depthBlocks, double water) {
        double metres = Math.max(0, depthBlocks) / dev.brights0ng.enginesandempires.weather.cloud.CloudScale.SCALE;
        double tau = 0.15 * Math.max(0, water) * CloudTuning.waterContent * metres;
        double through = 1 / (1 + 0.1125 * tau);
        double floor = CloudTuning.baseLight;
        return floor + (1 - floor) * Math.pow(through, CloudTuning.shadeContrast);
    }

    // ---- sampling ------------------------------------------------------------------------------------------------

    /** Coarse cells are this many lattice steps on a side (32 blocks at the usual 8-block lattice). */
    private static final int COARSE = 4;

    /**
     * Stage timings and counts of the last {@link #sample}, for the profile test. Diagnostic only: shared between the
     * mesher threads without synchronisation.
     */
    static long tCoarse, tLattice, tFill;
    static int nearCells, totalCells, densityPoints;

    /**
     * Samples the density into solid voxels, over only the part of grid {@code g} where the cloud can be.
     *
     * <ol>
     *   <li><b>Coarse pass.</b> The cheap envelope (no billows) every {@link #COARSE} lattice steps. A coarse cell can
     *       hold cloud only if one of its corners is within reach of the surface: the billows' strength, plus how
     *       far the envelope can change from a point in the cell to its nearest corner, plus some for the warp
     *       differing within the cell. Everything else is left out, and the fine arrays only cover the box around the
     *       cells that are left.</li>
     *   <li><b>Lattice.</b> The full density, only at lattice points in those cells.</li>
     *   <li><b>Voxels.</b> Interpolated from the lattice; cells all inside or all outside are filled or skipped.</li>
     * </ol>
     *
     * @return the solid voxels of a sub-grid of {@code g}, or null if the cloud is nowhere
     */
    static Cropped sample(CloudField field, Grid g, int s, double time) {
        return sample(field, g, s, time, null, 0, latticeK(field, s, true));
    }

    /**
     * As {@link #sample(CloudField, Grid, int, double)}, for a section: {@code own} is its box in absolute voxel
     * indices (x from, x to, y from, y to, z from, z to; null for a whole formation), and voxels above {@code deep}
     * are marked deep inside (2) as well as solid (1). The lattice is every {@code k} voxels (see {@link #latticeK}).
     */
    static Cropped sample(CloudField field, Grid g, int s, double time, int[] own, double deep, int k) {
        long t0 = System.nanoTime();
        double cs = (double) s * k;
        double ox = (double) g.gx0 * s;
        double oy = (double) g.gy0 * s;
        double oz = (double) g.gz0 * s;
        CloudField.Column col = field.newColumn();
        double[] range = new double[2];
        boolean detail = s <= 4;

        // ---- coarse pass
        Coarse co = coarse(field, g, s, time, own, deep, k, col);
        int lx = co.lx, ly = co.ly, lz = co.lz;
        int ccx = co.ccx, ccy = co.ccy, ccz = co.ccz;
        boolean[] near = co.near;
        boolean[] inside = co.inside;
        byte insideValue = co.insideValue;
        int minA = co.minA, maxA = co.maxA, minB = co.minB, maxB = co.maxB, minC = co.minC, maxC = co.maxC;
        long t1 = System.nanoTime();
        totalCells = near.length;
        nearCells = 0;
        for (boolean n : near) {
            if (n) {
                nearCells++;
            }
        }
        densityPoints = 0;
        if (maxA < 0) {
            tCoarse = t1 - t0;
            return null;
        }

        // ---- the sub-grid covered by the near cells: lattice ranges, then voxel ranges
        int la0 = minA * COARSE, la1 = Math.min(lx - 1, (maxA + 1) * COARSE);
        int lb0 = minB * COARSE, lb1 = Math.min(ly - 1, (maxB + 1) * COARSE);
        int lc0 = minC * COARSE, lc1 = Math.min(lz - 1, (maxC + 1) * COARSE);
        int slx = la1 - la0 + 1, sly = lb1 - lb0 + 1, slz = lc1 - lc0 + 1;
        int i0 = la0 * k, i1 = Math.min(g.nx - 1, la1 * k - 1);
        int v0 = lb0 * k, v1 = Math.min(g.ny - 1, lb1 * k - 1);
        int j0 = lc0 * k, j1 = Math.min(g.nz - 1, lc1 * k - 1);
        if (i0 > i1 || v0 > v1 || j0 > j1) {
            tCoarse = t1 - t0;
            return null;
        }
        int snx = i1 - i0 + 1, sny = v1 - v0 + 1, snz = j1 - j0 + 1;

        // ---- lattice, only where the coarse pass found something
        float[] lat = new float[slx * sly * slz];
        java.util.Arrays.fill(lat, -1f);
        for (int c = lc0; c <= lc1; c++) {
            double z = oz + c * cs;
            int cc = Math.min(c / COARSE, ccz - 1);
            for (int a = la0; a <= la1; a++) {
                int ca = Math.min(a / COARSE, ccx - 1);
                boolean columnNear = false;
                for (int cb = minB; cb <= maxB && !columnNear; cb++) {
                    columnNear = near[(cc * ccy + cb) * ccx + ca];
                }
                if (!columnNear) {
                    continue;
                }
                double x = ox + a * cs;
                field.column(x, z, time, col);
                if (!field.columnRange(col, range)) {
                    continue;
                }
                int base = (c - lc0) * sly * slx + (a - la0);
                for (int b = lb0; b <= lb1; b++) {
                    if (!needed(near, inside, a, b, c, ccx, ccy, ccz)) {
                        continue;
                    }
                    double y = oy + b * cs;
                    if (y >= range[0] && y <= range[1]) {
                        lat[base + (b - lb0) * slx] = (float) field.density(col, x, y, z, time, detail);
                        densityPoints++;
                    }
                }
            }
        }
        long t2 = System.nanoTime();

        // ---- voxels
        // 0 empty, 1 solid, 2 deep inside (only told apart for sections: see open()).
        double deepT = own == null ? Double.MAX_VALUE : deep;
        byte[] solid = new byte[snx * sny * snz];
        for (int c = lc0; c < lc1; c++) {
            int jA = Math.max(j0, c * k), jB = Math.min(j1, (c + 1) * k - 1);
            if (jA > jB) {
                continue;
            }
            for (int b = lb0; b < lb1; b++) {
                int vA = Math.max(v0, b * k), vB = Math.min(v1, (b + 1) * k - 1);
                if (vA > vB) {
                    continue;
                }
                for (int a = la0; a < la1; a++) {
                    int iA = Math.max(i0, a * k), iB = Math.min(i1, (a + 1) * k - 1);
                    if (iA > iB) {
                        continue;
                    }
                    int cellIdx = (Math.min(c / COARSE, ccz - 1) * ccy + Math.min(b / COARSE, ccy - 1)) * ccx
                            + Math.min(a / COARSE, ccx - 1);
                    if (inside[cellIdx]) {
                        for (int j = jA; j <= jB; j++) {
                            for (int v = vA; v <= vB; v++) {
                                int row = ((v - v0) * snz + (j - j0)) * snx - i0;
                                java.util.Arrays.fill(solid, row + iA, row + iB + 1, insideValue);
                            }
                        }
                        continue;
                    }
                    int p = ((c - lc0) * sly + (b - lb0)) * slx + (a - la0);
                    float d000 = lat[p];
                    float d100 = lat[p + 1];
                    float d010 = lat[p + slx];
                    float d110 = lat[p + slx + 1];
                    float d001 = lat[p + sly * slx];
                    float d101 = lat[p + sly * slx + 1];
                    float d011 = lat[p + sly * slx + slx];
                    float d111 = lat[p + sly * slx + slx + 1];
                    float lo = Math.min(Math.min(Math.min(d000, d100), Math.min(d010, d110)),
                            Math.min(Math.min(d001, d101), Math.min(d011, d111)));
                    float hi = Math.max(Math.max(Math.max(d000, d100), Math.max(d010, d110)),
                            Math.max(Math.max(d001, d101), Math.max(d011, d111)));
                    if (hi <= 0) {
                        continue;
                    }
                    byte all = lo > deepT ? (byte) 2 : lo > 0 && hi <= deepT ? (byte) 1 : (byte) 0;
                    for (int j = jA; j <= jB; j++) {
                        double fz = (j + 0.5) / k - c;
                        for (int v = vA; v <= vB; v++) {
                            double fy = (v + 0.5) / k - b;
                            int row = ((v - v0) * snz + (j - j0)) * snx - i0;
                            for (int i = iA; i <= iB; i++) {
                                if (all != 0) {
                                    solid[row + i] = all;
                                    continue;
                                }
                                double fx = (i + 0.5) / k - a;
                                double x00 = d000 + (d100 - d000) * fx;
                                double x10 = d010 + (d110 - d010) * fx;
                                double x01 = d001 + (d101 - d001) * fx;
                                double x11 = d011 + (d111 - d011) * fx;
                                double y0 = x00 + (x10 - x00) * fy;
                                double y1 = x01 + (x11 - x01) * fy;
                                double d = y0 + (y1 - y0) * fz;
                                solid[row + i] = d > deepT ? (byte) 2 : d > 0 ? (byte) 1 : (byte) 0;
                            }
                        }
                    }
                }
            }
        }
        long t3 = System.nanoTime();
        tCoarse = t1 - t0;
        tLattice = t2 - t1;
        tFill = t3 - t2;
        return new Cropped(solid, new Grid(g.gx0 + i0, g.gy0 + v0, g.gz0 + j0, snx, sny, snz));
    }

    /** A solid array cut down to the box around its solid voxels (plus nothing: faces are made at the box's edge). */
    record Cropped(byte[] solid, Grid grid) {
    }

    /**
     * The coarse pass over grid {@code g}: which coarse cells (every {@link #COARSE} lattice steps) can hold cloud
     * ({@code near}), which are certainly inside ({@code inside}), and the range of near cells. The lattice is
     * {@code lx} by {@code ly} by {@code lz} points.
     */
    static final class Coarse {
        int lx, ly, lz;
        int ccx, ccy, ccz;
        boolean[] near;
        boolean[] inside;
        byte insideValue;
        int minA = Integer.MAX_VALUE, maxA = -1, minB = Integer.MAX_VALUE, maxB = -1, minC = Integer.MAX_VALUE,
                maxC = -1;

        boolean any() {
            return maxA >= 0;
        }
    }

    /**
     * The cheap envelope (no billows) every {@link #COARSE} lattice steps. A coarse cell can hold cloud only if one of
     * its corners is within reach of the surface: the billows' strength, plus how far the envelope can change from a
     * point in the cell to its nearest corner, plus some for the warp differing within the cell.
     */
    static Coarse coarse(CloudField field, Grid g, int s, double time, int[] own, double deep, int k,
                         CloudField.Column col) {
        Coarse co = new Coarse();
        double cs = (double) s * k;
        co.lx = (int) ((g.nx - 0.5) / k) + 2;
        co.ly = (int) ((g.ny - 0.5) / k) + 2;
        co.lz = (int) ((g.nz - 0.5) / k) + 2;
        double ox = (double) g.gx0 * s;
        double oy = (double) g.gy0 * s;
        double oz = (double) g.gz0 * s;
        int cxN = (co.lx - 1) / COARSE + 2;
        int cyN = (co.ly - 1) / COARSE + 2;
        int czN = (co.lz - 1) / COARSE + 2;
        double cstep = cs * COARSE;
        double margin = field.noiseReach + field.maxSlope() * cstep * 0.87 + 0.15;
        float[] values = new float[cxN * cyN * czN];
        for (int c = 0; c < czN; c++) {
            double z = oz + c * cstep;
            for (int a = 0; a < cxN; a++) {
                double x = ox + a * cstep;
                field.column(x, z, time, col);
                for (int b = 0; b < cyN; b++) {
                    values[(c * cyN + b) * cxN + a] = (float) field.envelope(col, oy + b * cstep);
                }
            }
        }
        int ccx = cxN - 1, ccy = cyN - 1, ccz = czN - 1;
        co.ccx = ccx;
        co.ccy = ccy;
        co.ccz = ccz;
        co.near = new boolean[ccx * ccy * ccz];
        // Cells certainly inside the cloud (the noise can't carve them out) are filled without sampling. For a section
        // they must also be certainly deep inside, since the seam rule needs to know.
        co.inside = new boolean[co.near.length];
        double insideAt = margin + (own == null ? 0 : deep);
        co.insideValue = own == null ? (byte) 1 : (byte) 2;
        for (int c = 0; c < ccz; c++) {
            for (int b = 0; b < ccy; b++) {
                for (int a = 0; a < ccx; a++) {
                    float hi = -Float.MAX_VALUE;
                    float lo = Float.MAX_VALUE;
                    for (int q = 0; q < 8; q++) {
                        float v = values[((c + (q >> 2)) * cyN + b + ((q >> 1) & 1)) * cxN + a + (q & 1)];
                        hi = Math.max(hi, v);
                        lo = Math.min(lo, v);
                    }
                    if (hi <= -margin) {
                        continue;
                    }
                    co.near[(c * ccy + b) * ccx + a] = true;
                    co.inside[(c * ccy + b) * ccx + a] = lo > insideAt;
                    co.minA = Math.min(co.minA, a);
                    co.maxA = Math.max(co.maxA, a);
                    co.minB = Math.min(co.minB, b);
                    co.maxB = Math.max(co.maxB, b);
                    co.minC = Math.min(co.minC, c);
                    co.maxC = Math.max(co.maxC, c);
                }
            }
        }
        return co;
    }

    /**
     * Whether lattice point (a, b, c) must be sampled: some coarse cell it is a corner of is near the surface and not
     * certainly inside. (Points used only by cells certainly inside or certainly outside are skipped.)
     */
    private static boolean needed(boolean[] near, boolean[] inside, int a, int b, int c, int ccx, int ccy, int ccz) {
        int a1 = Math.min(a / COARSE, ccx - 1), a0 = a % COARSE == 0 && a > 0 ? a1 - 1 : a1;
        int b1 = Math.min(b / COARSE, ccy - 1), b0 = b % COARSE == 0 && b > 0 ? b1 - 1 : b1;
        int c1 = Math.min(c / COARSE, ccz - 1), c0 = c % COARSE == 0 && c > 0 ? c1 - 1 : c1;
        for (int cc = c0; cc <= c1; cc++) {
            for (int cb = b0; cb <= b1; cb++) {
                for (int ca = a0; ca <= a1; ca++) {
                    int idx = (cc * ccy + cb) * ccx + ca;
                    if (near[idx] && !inside[idx]) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static Cropped crop(byte[] solid, Grid g) {
        int minI = Integer.MAX_VALUE, maxI = -1, minV = Integer.MAX_VALUE, maxV = -1, minJ = Integer.MAX_VALUE, maxJ = -1;
        for (int v = 0; v < g.ny; v++) {
            for (int j = 0; j < g.nz; j++) {
                int row = (v * g.nz + j) * g.nx;
                for (int i = 0; i < g.nx; i++) {
                    if (solid[row + i] != 0) {
                        if (i < minI) {
                            minI = i;
                        }
                        if (i > maxI) {
                            maxI = i;
                        }
                        if (v < minV) {
                            minV = v;
                        }
                        maxV = v;
                        if (j < minJ) {
                            minJ = j;
                        }
                        if (j > maxJ) {
                            maxJ = j;
                        }
                    }
                }
            }
        }
        if (maxI < 0) {
            return null;
        }
        int nx = maxI - minI + 1, ny = maxV - minV + 1, nz = maxJ - minJ + 1;
        byte[] out = new byte[nx * ny * nz];
        for (int v = 0; v < ny; v++) {
            for (int j = 0; j < nz; j++) {
                System.arraycopy(solid, ((v + minV) * g.nz + j + minJ) * g.nx + minI, out, (v * nz + j) * nx, nx);
            }
        }
        return new Cropped(out, new Grid(g.gx0 + minI, g.gy0 + minV, g.gz0 + minJ, nx, ny, nz));
    }

    // ---- mesh ----------------------------------------------------------------------------------------------------

    private interface RectConsumer {
        void accept(int i, int j, int w, int h);
    }

    static Result mesh(byte[] solid, Grid g, int s, CloudField field, VertexSink sink) {
        return mesh(solid, g, s, field, 0, sink, null);
    }

    /**
     * Meshes {@code solid} (0 empty, 1 solid, 2 deep inside); only voxels inside {@code own} (absolute voxel indices,
     * as in {@link #sample}; null for all) get faces. The rest are only neighbours.
     *
     * <h2>Binary greedy meshing</h2>
     * The voxels are packed into 64-bit rows twice: along x (one row of bits per y and z) and along z (one per x and
     * y). Each face direction then works on whole rows at a time: a row's faces toward a neighbouring layer are
     * {@code mine & ~neighbour}, and rectangles are merged straight from the bits (a run of ones in a row, grown over
     * the following rows while they hold the same run). Runs don't cross 64-voxel word boundaries, which costs a few
     * extra faces and is sealed by {@link #seamOverlap} like any other T-junction.
     *
     * <p>Faces toward the apron (voxels outside {@code own}, belonging to the neighbouring section) follow the
     * section rule: there is a face unless both voxels are deep inside the cloud, so both sections agree.
     */
    static Result mesh(byte[] solid, Grid g, int s, CloudField field, double time, VertexSink sink, int[] own) {
        int nx = g.nx, ny = g.ny, nz = g.nz;
        boolean[] ownX = ownRange(g.gx0, nx, own, 0);
        boolean[] ownY = ownRange(g.gy0, ny, own, 2);
        boolean[] ownZ = ownRange(g.gz0, nz, own, 4);
        Emitter e = new Emitter(sink, s, g, field, time);
        int wx = (nx + 63) >>> 6;
        int wz = (nz + 63) >>> 6;
        boolean apron = own != null;
        // Rows along x, indexed (v * nz + j) * wx + word; rows along z, indexed (i * ny + v) * wz + word.
        long[] rx = new long[ny * nz * wx];
        long[] rz = new long[nx * ny * wz];
        long[] dx = apron ? new long[rx.length] : null;
        long[] dz = apron ? new long[rz.length] : null;
        for (int v = 0; v < ny; v++) {
            for (int j = 0; j < nz; j++) {
                int base = (v * nz + j) * nx;
                int row = (v * nz + j) * wx;
                int zWord = j >>> 6;
                long zBit = 1L << j;
                for (int i = 0; i < nx; i++) {
                    byte b = solid[base + i];
                    if (b == 0) {
                        continue;
                    }
                    rx[row + (i >>> 6)] |= 1L << i;
                    int zr = (i * ny + v) * wz + zWord;
                    rz[zr] |= zBit;
                    if (b == 2 && apron) {
                        dx[row + (i >>> 6)] |= 1L << i;
                        dz[zr] |= zBit;
                    }
                }
            }
        }
        long[] ownXm = bitMask(ownX, wx);
        long[] ownZm = bitMask(ownZ, wz);
        long[] plane = new long[Math.max(nz * wx, Math.max(ny * wx, ny * wz))];
        // The plane before greedyBits consumes it: which cells of this layer face the same way, for the seam overlap.
        long[] full = new long[plane.length];

        // Tops and bottoms: layer v, plane rows j, bits along x.
        for (int v = 0; v < ny; v++) {
            if (!ownY[v]) {
                continue;
            }
            for (int dir = 1; dir >= -1; dir -= 2) {
                int n = v + dir;
                boolean in = n >= 0 && n < ny;
                boolean nOwn = in && ownY[n];
                boolean any = false;
                for (int j = 0; j < nz; j++) {
                    int p = j * wx;
                    if (!ownZ[j]) {
                        java.util.Arrays.fill(plane, p, p + wx, 0L);
                        continue;
                    }
                    int r = (v * nz + j) * wx;
                    int nr = (n * nz + j) * wx;
                    for (int w = 0; w < wx; w++) {
                        long f = rx[r + w] & ownXm[w];
                        if (in && f != 0) {
                            f &= nOwn ? ~rx[nr + w] : ~(dx[nr + w] & dx[r + w]);
                        }
                        plane[p + w] = f;
                        any |= f != 0;
                    }
                }
                if (any) {
                    final int level = v;
                    System.arraycopy(plane, 0, full, 0, nz * wx);
                    if (dir > 0) {
                        greedyBits(plane, nz, wx, (i, j, w, h) -> e.top(i, i + w, j, j + h, level + 1,
                                seams(full, nz, wx, i, j, w, h)));
                    } else {
                        greedyBits(plane, nz, wx, (i, j, w, h) -> e.bottom(i, i + w, j, j + h, level,
                                seams(full, nz, wx, i, j, w, h)));
                    }
                }
            }
        }
        // +z / -z: layer j, plane rows v, bits along x.
        for (int j = 0; j < nz; j++) {
            if (!ownZ[j]) {
                continue;
            }
            for (int dir = 1; dir >= -1; dir -= 2) {
                int n = j + dir;
                boolean in = n >= 0 && n < nz;
                boolean nOwn = in && ownZ[n];
                boolean any = false;
                for (int v = 0; v < ny; v++) {
                    int p = v * wx;
                    if (!ownY[v]) {
                        java.util.Arrays.fill(plane, p, p + wx, 0L);
                        continue;
                    }
                    int r = (v * nz + j) * wx;
                    int nr = (v * nz + n) * wx;
                    for (int w = 0; w < wx; w++) {
                        long f = rx[r + w] & ownXm[w];
                        if (in && f != 0) {
                            f &= nOwn ? ~rx[nr + w] : ~(dx[nr + w] & dx[r + w]);
                        }
                        plane[p + w] = f;
                        any |= f != 0;
                    }
                }
                if (any) {
                    final boolean positive = dir > 0;
                    final int at = positive ? j + 1 : j;
                    System.arraycopy(plane, 0, full, 0, ny * wx);
                    greedyBits(plane, ny, wx, (i, v, w, h) -> e.sideZ(positive, at, i, i + w, v, v + h,
                            seams(full, ny, wx, i, v, w, h)));
                }
            }
        }
        // +x / -x: layer i, plane rows v, bits along z.
        for (int i = 0; i < nx; i++) {
            if (!ownX[i]) {
                continue;
            }
            for (int dir = 1; dir >= -1; dir -= 2) {
                int n = i + dir;
                boolean in = n >= 0 && n < nx;
                boolean nOwn = in && ownX[n];
                boolean any = false;
                for (int v = 0; v < ny; v++) {
                    int p = v * wz;
                    if (!ownY[v]) {
                        java.util.Arrays.fill(plane, p, p + wz, 0L);
                        continue;
                    }
                    int r = (i * ny + v) * wz;
                    int nr = (n * ny + v) * wz;
                    for (int w = 0; w < wz; w++) {
                        long f = rz[r + w] & ownZm[w];
                        if (in && f != 0) {
                            f &= nOwn ? ~rz[nr + w] : ~(dz[nr + w] & dz[r + w]);
                        }
                        plane[p + w] = f;
                        any |= f != 0;
                    }
                }
                if (any) {
                    final boolean positive = dir > 0;
                    final int at = positive ? i + 1 : i;
                    System.arraycopy(plane, 0, full, 0, ny * wz);
                    greedyBits(plane, ny, wz, (jz, v, w, h) -> e.sideX(positive, at, jz, jz + w, v, v + h,
                            seams(full, ny, wz, jz, v, w, h)));
                }
            }
        }
        if (e.quads == 0) {
            return Result.EMPTY;
        }
        return new Result(e.quads, s, e.minX, e.maxX, e.minY, e.maxY, e.minZ, e.maxZ);
    }

    /** Which of {@code n} voxels from absolute index {@code start} are inside {@code own}'s axis at {@code at}. */
    private static boolean[] ownRange(int start, int n, int[] own, int at) {
        boolean[] out = new boolean[n];
        for (int q = 0; q < n; q++) {
            out[q] = own == null || (start + q >= own[at] && start + q < own[at + 1]);
        }
        return out;
    }

    /** {@code flags} as bits in {@code words} longs. */
    private static long[] bitMask(boolean[] flags, int words) {
        long[] m = new long[words];
        for (int q = 0; q < flags.length; q++) {
            if (flags[q]) {
                m[q >>> 6] |= 1L << q;
            }
        }
        return m;
    }

    /**
     * Covers the set bits of a plane ({@code rows} rows of {@code words} longs) with rectangles, consuming them: each
     * run of ones in a row (which may span several words) is grown down the following rows while they hold the whole
     * run. Reports (bit, row, width, height).
     */
    static void greedyBits(long[] plane, int rows, int words, RectConsumer out) {
        for (int r = 0; r < rows; r++) {
            int base = r * words;
            for (int w = 0; w < words; w++) {
                while (plane[base + w] != 0) {
                    int start = w * 64 + Long.numberOfTrailingZeros(plane[base + w]);
                    int end = runEnd(plane, base, words, start);
                    clearRange(plane, base, start, end);
                    int height = 1;
                    while (r + height < rows && allSet(plane, (r + height) * words, start, end)) {
                        clearRange(plane, (r + height) * words, start, end);
                        height++;
                    }
                    out.accept(start, r, end - start, height);
                }
            }
        }
    }

    /** The first clear bit at or after set bit {@code start} in the row at {@code base}. */
    private static int runEnd(long[] plane, int base, int words, int start) {
        int w = start >>> 6;
        long inv = ~plane[base + w] & (-1L << (start & 63));
        while (inv == 0) {
            if (++w >= words) {
                return words * 64;
            }
            inv = ~plane[base + w];
        }
        return w * 64 + Long.numberOfTrailingZeros(inv);
    }

    /** The bits of word {@code w} that fall in [{@code from}, {@code to}). */
    private static long wordMask(int w, int from, int to) {
        int lo = Math.max(from, w * 64) - w * 64;
        int hi = Math.min(to, w * 64 + 64) - w * 64;
        return (hi == 64 ? -1L : (1L << hi) - 1) & (-1L << lo);
    }

    /** Whether bits [{@code from}, {@code to}) of the row at {@code base} are all set. */
    private static boolean allSet(long[] plane, int base, int from, int to) {
        for (int w = from >>> 6; w <= (to - 1) >>> 6; w++) {
            long m = wordMask(w, from, to);
            if ((plane[base + w] & m) != m) {
                return false;
            }
        }
        return true;
    }

    private static void clearRange(long[] plane, int base, int from, int to) {
        for (int w = from >>> 6; w <= (to - 1) >>> 6; w++) {
            plane[base + w] &= ~wordMask(w, from, to);
        }
    }

    /** Whether bit {@code b} of row {@code r} is set (false outside the plane). */
    private static boolean bit(long[] plane, int rows, int words, int r, int b) {
        if (r < 0 || r >= rows || b < 0 || b >= words * 64) {
            return false;
        }
        return (plane[r * words + (b >>> 6)] >>> (b & 63) & 1L) != 0;
    }

    /** Seam flags: stretch the low-bit edge. */
    static final int SEAM_B0 = 1;
    /** Stretch the high-bit edge. */
    static final int SEAM_B1 = 2;
    /** Stretch the low-row edge. */
    static final int SEAM_R0 = 4;
    /** Stretch the high-row edge. */
    static final int SEAM_R1 = 8;

    /**
     * Which edges of rectangle (bit {@code b}, row {@code r}, {@code w} by {@code h}) may be stretched by the seam
     * overlap: those whose whole neighbouring strip in {@code full} (the layer's faces of this direction) is faced
     * too, so the stretch only slides under coplanar faces. Where two stretched edges meet at a corner whose diagonal
     * neighbour isn't faced, the shorter edge's stretch is dropped so the corner can't poke out.
     */
    static int seams(long[] full, int rows, int words, int b, int r, int w, int h) {
        if (seamOverlap == 0) {
            return 0;
        }
        int f = 0;
        if (column(full, rows, words, b - 1, r, h)) {
            f |= SEAM_B0;
        }
        if (column(full, rows, words, b + w, r, h)) {
            f |= SEAM_B1;
        }
        if (r > 0 && b + w <= words * 64 && allSet(full, (r - 1) * words, b, b + w)) {
            f |= SEAM_R0;
        }
        if (r + h < rows && b + w <= words * 64 && allSet(full, (r + h) * words, b, b + w)) {
            f |= SEAM_R1;
        }
        f = corner(f, SEAM_B0, SEAM_R0, bit(full, rows, words, r - 1, b - 1), w, h);
        f = corner(f, SEAM_B1, SEAM_R0, bit(full, rows, words, r - 1, b + w), w, h);
        f = corner(f, SEAM_B0, SEAM_R1, bit(full, rows, words, r + h, b - 1), w, h);
        f = corner(f, SEAM_B1, SEAM_R1, bit(full, rows, words, r + h, b + w), w, h);
        return f;
    }

    private static boolean column(long[] full, int rows, int words, int b, int r, int h) {
        if (b < 0 || b >= words * 64) {
            return false;
        }
        for (int q = r; q < r + h; q++) {
            if (!bit(full, rows, words, q, b)) {
                return false;
            }
        }
        return true;
    }

    private static int corner(int f, int bitEdge, int rowEdge, boolean diagonal, int w, int h) {
        if ((f & bitEdge) == 0 || (f & rowEdge) == 0 || diagonal) {
            return f;
        }
        // The bit edge runs h long, the row edge w long; keep the longer one sealed.
        return f & ~(h < w ? bitEdge : rowEdge);
    }

    // ---- vertex output -------------------------------------------------------------------------------------------

    private static final class Emitter {
        final VertexSink sink;
        final int s;
        final Grid g;
        final CloudField field;
        final double time;
        /** Spacing of the shading columns (blocks), and their vertical step: aligned to the anchor-local grid. */
        final double shadeSpacing;
        final double shadeStep;
        /** Shading columns already worked out, by column index. */
        final java.util.Map<Long, CloudField.Profile> profiles = new java.util.HashMap<>();
        final CloudField.Column column;
        /** {@link #seamOverlap} in blocks. */
        final float e;
        int quads;
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;

        Emitter(VertexSink sink, int s, Grid g, CloudField field, double time) {
            this.sink = sink;
            this.s = s;
            this.g = g;
            this.field = field;
            this.time = time;
            // Cloud above a point changes slowly across a cloud (hundreds of blocks): columns every 32 blocks near the
            // camera, four voxels apart further out, sampled every two voxels up, and interpolated between, are plenty.
            this.shadeSpacing = Math.max(32, 4 * s);
            this.shadeStep = Math.max(16, 2 * s);
            this.column = field.newColumn();
            this.e = seamOverlap * s;
        }

        float x(int i) {
            return (g.gx0 + i) * (float) s;
        }

        float y(int v) {
            return (g.gy0 + v) * (float) s;
        }

        float z(int j) {
            return (g.gz0 + j) * (float) s;
        }

        /** The stretch for one edge: {@link #e} if {@code flag} is set in {@code seams}. */
        float stretch(int seams, int flag) {
            return (seams & flag) != 0 ? e : 0f;
        }

        /** Plane bits run along x, rows along z. */
        void top(int i0, int i1, int j0, int j1, int v, int seams) {
            float x0 = x(i0) - stretch(seams, SEAM_B0), x1 = x(i1) + stretch(seams, SEAM_B1);
            float z0 = z(j0) - stretch(seams, SEAM_R0), z1 = z(j1) + stretch(seams, SEAM_R1), y = y(v);
            quad(x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0, SHADE_TOP);
        }

        void bottom(int i0, int i1, int j0, int j1, int v, int seams) {
            float x0 = x(i0) - stretch(seams, SEAM_B0), x1 = x(i1) + stretch(seams, SEAM_B1);
            float z0 = z(j0) - stretch(seams, SEAM_R0), z1 = z(j1) + stretch(seams, SEAM_R1), y = y(v);
            quad(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, SHADE_BOTTOM);
        }

        /**
         * A face at x boundary {@code xi}, facing +x (or -x), over z from j0 to j1 and heights v0 to v1. Plane bits
         * run along z, rows along y.
         */
        void sideX(boolean positive, int xi, int j0, int j1, int v0, int v1, int seams) {
            float x = x(xi), z0 = z(j0) - stretch(seams, SEAM_B0), z1 = z(j1) + stretch(seams, SEAM_B1);
            float y0 = y(v0) - stretch(seams, SEAM_R0), y1 = y(v1) + stretch(seams, SEAM_R1);
            if (positive) {
                quad(x, y0, z0, x, y1, z0, x, y1, z1, x, y0, z1, SHADE_X);
            } else {
                quad(x, y0, z0, x, y0, z1, x, y1, z1, x, y1, z0, SHADE_X);
            }
        }

        /**
         * A face at z boundary {@code zj}, facing +z (or -z), over x from i0 to i1 and heights v0 to v1. Plane bits
         * run along x, rows along y.
         */
        void sideZ(boolean positive, int zj, int i0, int i1, int v0, int v1, int seams) {
            float z = z(zj), x0 = x(i0) - stretch(seams, SEAM_B0), x1 = x(i1) + stretch(seams, SEAM_B1);
            float y0 = y(v0) - stretch(seams, SEAM_R0), y1 = y(v1) + stretch(seams, SEAM_R1);
            if (positive) {
                quad(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, SHADE_Z);
            } else {
                quad(x0, y0, z, x0, y1, z, x1, y1, z, x1, y0, z, SHADE_Z);
            }
        }

        /** One quad, each vertex coloured by the cloud above it, times the face's {@code shade}. */
        void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                  float dx, float dy, float dz, float shade) {
            vertex(ax, ay, az, colour(ax, ay, az, shade));
            vertex(bx, by, bz, colour(bx, by, bz, shade));
            vertex(cx, cy, cz, colour(cx, cy, cz, shade));
            vertex(dx, dy, dz, colour(dx, dy, dz, shade));
            quads++;
        }

        void vertex(float x, float y, float z, int argb) {
            sink.vertex(x, y, z, argb);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }

        /** Grey, a touch blue: {@link #brightness} under the cloud above (x, y, z), times the face shade. */
        int colour(float x, float y, float z, float shade) {
            double b = brightness(depthAbove(x, y, z), field.water) * shade;
            int r = (int) Math.round(b * 0.96 * 255);
            int gr = (int) Math.round(b * 0.97 * 255);
            int bl = (int) Math.round(b * 255);
            return 0xFF000000 | (r << 16) | (gr << 8) | bl;
        }

        /** Blocks of cloud above (x, y, z), interpolated between the four shading columns around it. */
        double depthAbove(double x, double y, double z) {
            double fx = x / shadeSpacing;
            double fz = z / shadeSpacing;
            long ix = (long) Math.floor(fx);
            long iz = (long) Math.floor(fz);
            double tx = fx - ix;
            double tz = fz - iz;
            double d00 = profile(ix, iz).depthAbove(y);
            double d10 = profile(ix + 1, iz).depthAbove(y);
            double d01 = profile(ix, iz + 1).depthAbove(y);
            double d11 = profile(ix + 1, iz + 1).depthAbove(y);
            double a = d00 + (d10 - d00) * tx;
            double b = d01 + (d11 - d01) * tx;
            return a + (b - a) * tz;
        }

        CloudField.Profile profile(long ix, long iz) {
            long key = (ix << 32) ^ (iz & 0xFFFFFFFFL);
            CloudField.Profile p = profiles.get(key);
            if (p == null) {
                p = field.profile(ix * shadeSpacing, iz * shadeSpacing, time, shadeStep, column);
                profiles.put(key, p);
            }
            return p;
        }
    }

    private CloudVoxelizer() {
    }
}
