// Extracted from TalcBorderGeometry.Mesh (written by Claude); generalized for shared boundary and region rendering by Gemini.
package dev.brights0ng.empire.client;

import java.util.Arrays;
import java.util.Set;

/**
 * A cached set of untextured chalk-line and shimmering-veil quads, stored relative to an integer {@code (originX, originZ)}
 * so float precision holds far from the world origin. Shared by both claim border rendering ({@link TalcBorderGeometry})
 * and 3D region rendering ({@link RegionGeometry}) and drawn by {@link VeilMeshRenderer}.
 * <p>
 * Pure Java (no Minecraft imports) so geometry builders can be unit-tested directly.
 */
public final class VeilMesh {

    /** Solid chalk ribbon quad with distance fade. */
    public static final int CHALK = 0;
    /** Vertical veil curtain quad: vertices 0 and 3 at the bottom shimmer; vertices 1 and 2 at the top fade to alpha 0. */
    public static final int VEIL_GRADIENT = 1;
    /** Full-face shimmering veil quad: all four vertices shimmer uniformly (used for 3D region faces). */
    public static final int VEIL_UNIFORM = 2;

    /** Half the width of a chalk ribbon, in blocks. */
    public static final double HALF_W = 0.11;
    /** Small geometric nudge off block faces; {@link EmpireRenderTypes#BORDER_VEIL}'s polygon offset does the real work. */
    public static final double LIFT = 0.002;

    private final int originX;
    private final int originZ;
    private float[] pos = new float[256 * 12];
    private byte[] kinds = new byte[256];
    private byte[] styles = new byte[256];
    private int quads;
    private Set<Long> columns = Set.of();

    public VeilMesh(int originX, int originZ) {
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

    /** Records which (x, z) block columns this mesh depended on during construction. */
    public void setColumns(Set<Long> columns) {
        this.columns = columns;
    }

    /** Whether a block change in column (x, z) can change this mesh: the column was read while building it. */
    public boolean readsColumn(int x, int z) {
        return columns.contains(columnKey(x, z));
    }

    public static long columnKey(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
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

    /** Adds a horizontal quad on plane {@code y} spanning {@code [x0, x1] x [z0, z1]}. */
    public void horizontal(int kind, int style, double x0, double z0, double x1, double z1, double y) {
        addQuad(kind, style, x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0);
    }

    /** Adds a vertical quad on plane {@code x = plane} (if {@code normalX}) or {@code z = plane}, spanning {@code s0..s1} along the other horizontal axis. */
    public void vertical(int kind, int style, boolean normalX, double plane, double s0, double s1, double yLo, double yHi) {
        if (normalX) {
            addQuad(kind, style, plane, yLo, s0, plane, yHi, s0, plane, yHi, s1, plane, yLo, s1);
        } else {
            addQuad(kind, style, s0, yLo, plane, s0, yHi, plane, s1, yHi, plane, s1, yLo, plane);
        }
    }

    /** Adds an arbitrary quad from four world-space vertices. */
    public void addQuad(int kind, int style,
                        double x0, double y0, double z0,
                        double x1, double y1, double z1,
                        double x2, double y2, double z2,
                        double x3, double y3, double z3) {
        if (quads == kinds.length) {
            int n = quads * 2;
            pos = Arrays.copyOf(pos, n * 12);
            kinds = Arrays.copyOf(kinds, n);
            styles = Arrays.copyOf(styles, n);
        }
        int o = quads * 12;
        pos[o] = (float) (x0 - originX);
        pos[o + 1] = (float) y0;
        pos[o + 2] = (float) (z0 - originZ);
        pos[o + 3] = (float) (x1 - originX);
        pos[o + 4] = (float) y1;
        pos[o + 5] = (float) (z1 - originZ);
        pos[o + 6] = (float) (x2 - originX);
        pos[o + 7] = (float) y2;
        pos[o + 8] = (float) (z2 - originZ);
        pos[o + 9] = (float) (x3 - originX);
        pos[o + 10] = (float) y3;
        pos[o + 11] = (float) (z3 - originZ);
        kinds[quads] = (byte) kind;
        styles[quads] = (byte) style;
        quads++;
    }
}
