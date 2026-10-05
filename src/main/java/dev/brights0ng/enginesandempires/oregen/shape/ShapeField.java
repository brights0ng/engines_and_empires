package dev.brights0ng.enginesandempires.oregen.shape;

/**
 * The geometry of one deposit, as a function of the offset (dx, dy, dz) from the deposit's centre
 * block. A shape builds one of these per deposit, and the deposit builder asks it about every block in
 * its bounding box.
 *
 * <p>Instances belong to a single deposit's construction and are used by one thread, so they may keep
 * scratch state.
 */
public interface ShapeField {

    /** Every block of the deposit lies within this many blocks of the centre along x. */
    int reachX();

    /** Every block of the deposit lies within this many blocks of the centre along y. */
    int reachY();

    /** Every block of the deposit lies within this many blocks of the centre along z. */
    int reachZ();

    /** Says what the block at this offset is, and if it is an ore candidate, how good a candidate. */
    void sample(int dx, int dy, int dz, CellSample out);

    /**
     * How favourable the deposit's internal structure (layering, shoots, zoning) makes this spot, from
     * 0 to 1, or NaN if the shape has no such notion. For tests and diagnostics only.
     */
    default double structure(int dx, int dy, int dz) {
        return Double.NaN;
    }
}
