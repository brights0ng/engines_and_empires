// Written by Claude (GreedyFluids M7c c2); edited by Claude (M7e: the generator's terrain from AirflowGround; M8b5a: the
// outside air on the lattice, for the buoyancy reference).
package dev.brights0ng.meteodyne.airflow;

import dev.brights0ng.meteodyne.climate.Climate;
import dev.brights0ng.meteodyne.climate.Underground;
import dev.brights0ng.meteodyne.climate.UndergroundProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * One tile's background samples on the server thread (M7c c2; Bright, 2026-11-15): Meteodyne's air temperature
 * ({@code Temperature.at}) on the {@link AirflowField} lattice, split so that a refresh stays cheap.
 * <ul>
 *   <li><b>Ground part</b>, read once when the tile opens, a share of the lattice per tick within a time budget:
 *       each lattice point's insulation, underground value and cave rule ({@link UndergroundProbe}). The cover's
 *       top is the lower of the heightmap and the generator's terrain, which {@link AirflowGround} works out at
 *       chunk corners off the server thread first (M7e: the generator's height per column cost about 1.5 ms).</li>
 *   <li><b>Outside part</b>, every refresh: the outside air ({@link Climate#sample}) every 16 blocks, interpolated to
 *       the lattice. A point's temperature is outside + insulation × (underground − outside), within its cave
 *       rule's caps, as {@code Temperature.at} works it out.</li>
 *   <li><b>Column tops</b> for the sky light ({@link AirflowField#sky}): each column's
 *       {@code MOTION_BLOCKING_NO_LEAVES} height, from the chunks.</li>
 *   <li><b>Outside air</b> on the lattice (M8b5a): the same interpolated outside samples without the insulation blend,
 *       read with each {@link #lattice}, for the outside buoyancy modes ({@link AirflowBuoyancy}).</li>
 * </ul>
 * The ground part isn't read again after edits (M7: an edit changes the insulation only underground).
 */
final class AirflowBackground {

    /** Outside-air sample spacing, blocks. */
    static final int COARSE = 16;

    private final ServerLevel level;
    private final AirflowGround ground;
    private final boolean overworld;
    private final int x0, y0, z0, nx, ny, nz, lx, ly, lz;
    private final int cx0, cz0, ncx, ncz;
    private final float[] insulation, underground;
    private final Underground.CaveRule[] caves;
    /** The generator's terrain at the tile's chunk corners, once known. */
    private int[] corners;
    private int filled;
    /** The outside air on the lattice at the last {@link #lattice}, C (null before one). */
    private float[] outsideLattice;

    AirflowBackground(ServerLevel level, AirflowGround ground, int x0, int y0, int z0, int nx, int ny, int nz) {
        this.level = level;
        this.ground = ground;
        overworld = level.dimension() == Level.OVERWORLD;
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        lx = AirflowField.points(nx);
        ly = AirflowField.points(ny);
        lz = AirflowField.points(nz);
        cx0 = AirflowGround.corner(x0);
        cz0 = AirflowGround.corner(z0);
        ncx = AirflowGround.span(x0, x0 + AirflowField.STEP * (lx - 1));
        ncz = AirflowGround.span(z0, z0 + AirflowField.STEP * (lz - 1));
        insulation = new float[lx * ly * lz];
        underground = new float[insulation.length];
        caves = new Underground.CaveRule[insulation.length];
    }

    /** Whether the ground part is complete. */
    boolean ready() {
        return filled == insulation.length;
    }

    /**
     * Reads ground-part points until {@code deadline} ({@link System#nanoTime}), at least one once the corners'
     * terrain is known (none before); returns whether done.
     */
    boolean fill(long deadline) {
        if (corners == null) {
            corners = ground.corners(cx0, cz0, ncx, ncz);
            if (corners == null) {
                return false;
            }
        }
        while (filled < insulation.length) {
            int p = filled++;
            int i = p % lx, k = p / lx % lz, j = p / (lx * lz);
            BlockPos pos = new BlockPos(x0 + AirflowField.STEP * i, y0 + AirflowField.STEP * j, z0 + AirflowField.STEP * k);
            pos = new BlockPos(pos.getX(), Math.min(pos.getY(), level.getMaxBuildHeight() - 1), pos.getZ());
            Underground.Reading r = overworld ? UndergroundProbe.read(level, pos, 0, groundAbove(pos))
                    : Underground.Reading.open(0, 0, 15);
            insulation[p] = (float) r.insulation();
            underground[p] = r.insulation() > 0 ? (float) r.underground() : 0;
            caves[p] = r.insulation() > 0 ? r.cave() : null;
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        return ready();
    }

    /** The cover's top over {@code pos}: the lower of the generator's terrain (interpolated) and the heightmap. */
    private int groundAbove(BlockPos pos) {
        int g = (int) Math.round(AirflowGround.at(corners, cx0, cz0, ncx, pos.getX(), pos.getZ()));
        if (level.hasChunkAt(pos)) {
            g = Math.min(g, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()));
        }
        return g;
    }

    /** The lattice now, C (ground part complete). */
    float[] lattice() {
        int cx = nx / COARSE + 1, cy = ny / COARSE + 1, cz = nz / COARSE + 1;
        double[] outside = new double[cx * cy * cz];
        for (int j = 0; j < cy; j++) {
            for (int k = 0; k < cz; k++) {
                for (int i = 0; i < cx; i++) {
                    outside[(j * cz + k) * cx + i] = Climate.sample(level, x0 + COARSE * i + 0.5,
                            y0 + COARSE * j + 0.5, z0 + COARSE * k + 0.5).temperature();
                }
            }
        }
        float[] out = new float[insulation.length];
        float[] air = new float[insulation.length];
        int ratio = COARSE / AirflowField.STEP;
        for (int j = 0; j < ly; j++) {
            for (int k = 0; k < lz; k++) {
                for (int i = 0; i < lx; i++) {
                    double o = coarse(outside, cx, cz, (double) i / ratio, (double) j / ratio, (double) k / ratio);
                    int p = (j * lz + k) * lx + i;
                    air[p] = (float) o;
                    double t = o + insulation[p] * (underground[p] - o);
                    if (caves[p] != null) {
                        t = caves[p].cap(t);
                    }
                    out[p] = (float) t;
                }
            }
        }
        outsideLattice = air;
        return out;
    }

    /** The outside air on the lattice as the last {@link #lattice} read it, C (null before one). */
    float[] outsideLattice() {
        return outsideLattice;
    }

    /** Each column's first open height, region cells (the heightmap; may lie below or above the tile). */
    int[] columnTops() {
        int[] out = new int[nx * nz];
        for (int z = 0; z < nz; z++) {
            for (int x = 0; x < nx; x++) {
                int wx = x0 + x, wz = z0 + z;
                LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
                int h = chunk == null ? level.getMaxBuildHeight()
                        : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx & 15, wz & 15) + 1;
                out[z * nx + x] = h - y0;
            }
        }
        return out;
    }

    private static double coarse(double[] v, int cx, int cz, double fx, double fy, double fz) {
        int cy = v.length / (cx * cz);
        int i = Math.min((int) fx, cx - 2), j = Math.min((int) fy, cy - 2), k = Math.min((int) fz, cz - 2);
        double tx = fx - i, ty = fy - j, tz = fz - k;
        double c00 = lerp(v[(j * cz + k) * cx + i], v[(j * cz + k) * cx + i + 1], tx);
        double c01 = lerp(v[(j * cz + k + 1) * cx + i], v[(j * cz + k + 1) * cx + i + 1], tx);
        double c10 = lerp(v[((j + 1) * cz + k) * cx + i], v[((j + 1) * cz + k) * cx + i + 1], tx);
        double c11 = lerp(v[((j + 1) * cz + k + 1) * cx + i], v[((j + 1) * cz + k + 1) * cx + i + 1], tx);
        return lerp(lerp(c00, c01, tz), lerp(c10, c11, tz), ty);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
