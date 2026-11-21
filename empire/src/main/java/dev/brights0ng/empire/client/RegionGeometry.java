// Written by Gemini.
package dev.brights0ng.empire.client;

import java.util.HashSet;
import java.util.Set;

import dev.brights0ng.empire.rules.BlockKeys;
import dev.brights0ng.empire.rules.RegionType;

/**
 * Builds a {@link VeilMesh} for an arbitrary 3D region of packed {@link BlockKeys} coordinates.
 * <p>
 * Every exposed outer face of the 3D volume is nudged inward by {@link VeilMesh#LIFT} so it sits flush inside
 * surrounding walls, floors and ceilings without z-fighting. Geometric crease edges (where two perpendicular outer
 * faces meet at a 90° outer corner or 270° inner corner) are framed with {@link VeilMesh#HALF_W}-wide
 * {@link VeilMesh#CHALK} ribbons, with squared-off corner caps where perpendicular creases meet, and the remaining
 * face area is filled with non-overlapping {@link VeilMesh#VEIL_UNIFORM} quads.
 * <p>
 * Pure Java (no Minecraft or fastutil imports) so it is unit-tested directly.
 */
public final class RegionGeometry {

    /** Style id used for invalid regions (drawn in crimson/red). Valid regions use {@code type.ordinal() + 1}. */
    public static final int STYLE_INVALID = 0;

    private static final double[] CUTS = {0.0, VeilMesh.HALF_W, 1.0 - VeilMesh.HALF_W, 1.0};

    /** Returns the mesh style id for a region of {@code type} and validity {@code valid}. */
    public static int styleFor(RegionType type, boolean valid) {
        if (!valid || type == null) {
            return STYLE_INVALID;
        }
        return type.ordinal() + 1;
    }

    /** Resolves a valid style id back to its {@link RegionType}, or {@code null} for {@link #STYLE_INVALID}. */
    public static RegionType typeForStyle(int style) {
        if (style <= STYLE_INVALID) {
            return null;
        }
        RegionType[] values = RegionType.values();
        int idx = style - 1;
        return idx >= 0 && idx < values.length ? values[idx] : null;
    }

    /** Builds a {@link VeilMesh} for {@code blocks} with coordinates relative to {@code (originX, originZ)}. */
    public static VeilMesh build(long[] blocks, int style, int originX, int originZ) {
        Set<Long> set = new HashSet<>(Math.max(16, (int) (blocks.length / 0.75f) + 1));
        for (long key : blocks) {
            set.add(key);
        }
        return build(set, style, originX, originZ);
    }

    /** Builds a {@link VeilMesh} for {@code blocks} with coordinates relative to {@code (originX, originZ)}. */
    public static VeilMesh build(Set<Long> blocks, int style, int originX, int originZ) {
        VeilMesh mesh = new VeilMesh(originX, originZ);
        if (blocks.isEmpty()) {
            return mesh;
        }
        boolean[][] chalk = new boolean[3][3];
        boolean[][] done = new boolean[3][3];

        for (long key : blocks) {
            int x = BlockKeys.getX(key);
            int y = BlockKeys.getY(key);
            int z = BlockKeys.getZ(key);

            // -X (West): u = +Z, v = +Y
            emitFace(mesh, blocks, style, x, y, z, -1, 0, 0, 0, 0, 1, 0, 1, 0, FaceKind.NEG_X, chalk, done);
            // +X (East): u = +Z, v = +Y
            emitFace(mesh, blocks, style, x, y, z, 1, 0, 0, 0, 0, 1, 0, 1, 0, FaceKind.POS_X, chalk, done);
            // -Z (North): u = +X, v = +Y
            emitFace(mesh, blocks, style, x, y, z, 0, 0, -1, 1, 0, 0, 0, 1, 0, FaceKind.NEG_Z, chalk, done);
            // +Z (South): u = +X, v = +Y
            emitFace(mesh, blocks, style, x, y, z, 0, 0, 1, 1, 0, 0, 0, 1, 0, FaceKind.POS_Z, chalk, done);
            // -Y (Down): u = +X, v = +Z
            emitFace(mesh, blocks, style, x, y, z, 0, -1, 0, 1, 0, 0, 0, 0, 1, FaceKind.NEG_Y, chalk, done);
            // +Y (Up): u = +X, v = +Z
            emitFace(mesh, blocks, style, x, y, z, 0, 1, 0, 1, 0, 0, 0, 0, 1, FaceKind.POS_Y, chalk, done);
        }
        return mesh;
    }

    private enum FaceKind {
        NEG_X, POS_X, NEG_Z, POS_Z, NEG_Y, POS_Y
    }

    private static void emitFace(VeilMesh mesh, Set<Long> blocks, int style,
                                 int x, int y, int z,
                                 int nx, int ny, int nz,
                                 int ux, int uy, int uz,
                                 int vx, int vy, int vz,
                                 FaceKind face, boolean[][] chalk, boolean[][] done) {
        if (blocks.contains(BlockKeys.pack(x + nx, y + ny, z + nz))) {
            return;
        }

        boolean edgeU0 = isCrease(blocks, x, y, z, nx, ny, nz, -ux, -uy, -uz);
        boolean edgeU1 = isCrease(blocks, x, y, z, nx, ny, nz, ux, uy, uz);
        boolean edgeV0 = isCrease(blocks, x, y, z, nx, ny, nz, -vx, -vy, -vz);
        boolean edgeV1 = isCrease(blocks, x, y, z, nx, ny, nz, vx, vy, vz);

        boolean c00 = edgeU0 || edgeV0 || isCrease(blocks, x, y, z, nx, ny, nz, -ux - vx, -uy - vy, -uz - vz);
        boolean c10 = edgeU1 || edgeV0 || isCrease(blocks, x, y, z, nx, ny, nz, ux - vx, uy - vy, uz - vz);
        boolean c01 = edgeU0 || edgeV1 || isCrease(blocks, x, y, z, nx, ny, nz, -ux + vx, -uy + vy, -uz + vz);
        boolean c11 = edgeU1 || edgeV1 || isCrease(blocks, x, y, z, nx, ny, nz, ux + vx, uy + vy, uz + vz);

        if (!edgeU0 && !edgeU1 && !edgeV0 && !edgeV1 && !c00 && !c10 && !c01 && !c11) {
            emitRect(mesh, VeilMesh.VEIL_UNIFORM, style, face, x, y, z, 0.0, 1.0, 0.0, 1.0);
            return;
        }

        chalk[0][0] = c00;
        chalk[0][1] = edgeV0;
        chalk[0][2] = c10;
        chalk[1][0] = edgeU0;
        chalk[1][1] = false;
        chalk[1][2] = edgeU1;
        chalk[2][0] = c01;
        chalk[2][1] = edgeV1;
        chalk[2][2] = c11;

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                done[r][c] = false;
            }
        }

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (done[r][c]) {
                    continue;
                }
                boolean isChalk = chalk[r][c];
                int cEnd = c + 1;
                while (cEnd < 3 && !done[r][cEnd] && chalk[r][cEnd] == isChalk) {
                    cEnd++;
                }
                int rEnd = r + 1;
                outer:
                while (rEnd < 3) {
                    for (int cc = c; cc < cEnd; cc++) {
                        if (done[rEnd][cc] || chalk[rEnd][cc] != isChalk) {
                            break outer;
                        }
                    }
                    rEnd++;
                }
                for (int rr = r; rr < rEnd; rr++) {
                    for (int cc = c; cc < cEnd; cc++) {
                        done[rr][cc] = true;
                    }
                }
                emitRect(mesh, isChalk ? VeilMesh.CHALK : VeilMesh.VEIL_UNIFORM, style, face, x, y, z,
                        CUTS[c], CUTS[cEnd], CUTS[r], CUTS[rEnd]);
            }
        }
    }

    /**
     * True if moving by {@code (dx, dy, dz)} along the face leaves the coplanar outer shell: either the adjacent
     * block is outside the region (convex 90° corner) or the diagonally outside block is inside the region
     * (concave 270° inner corner).
     */
    private static boolean isCrease(Set<Long> blocks, int x, int y, int z,
                                    int nx, int ny, int nz,
                                    int dx, int dy, int dz) {
        return !blocks.contains(BlockKeys.pack(x + dx, y + dy, z + dz))
                || blocks.contains(BlockKeys.pack(x + dx + nx, y + dy + ny, z + dz + nz));
    }

    private static void emitRect(VeilMesh mesh, int kind, int style, FaceKind face,
                                 int x, int y, int z,
                                 double u0, double u1, double v0, double v1) {
        switch (face) {
            case NEG_X -> mesh.vertical(kind, style, true, x + VeilMesh.LIFT, z + u0, z + u1, y + v0, y + v1);
            case POS_X -> mesh.vertical(kind, style, true, x + 1.0 - VeilMesh.LIFT, z + u0, z + u1, y + v0, y + v1);
            case NEG_Z -> mesh.vertical(kind, style, false, z + VeilMesh.LIFT, x + u0, x + u1, y + v0, y + v1);
            case POS_Z -> mesh.vertical(kind, style, false, z + 1.0 - VeilMesh.LIFT, x + u0, x + u1, y + v0, y + v1);
            case NEG_Y -> mesh.horizontal(kind, style, x + u0, z + v0, x + u1, z + v1, y + VeilMesh.LIFT);
            case POS_Y -> mesh.horizontal(kind, style, x + u0, z + v0, x + u1, z + v1, y + 1.0 - VeilMesh.LIFT);
        }
    }

    private RegionGeometry() {
    }
}
