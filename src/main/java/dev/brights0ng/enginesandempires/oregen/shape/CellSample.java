package dev.brights0ng.enginesandempires.oregen.shape;

/**
 * Where a shape reports its answer for one block. A single mutable instance is reused for every block
 * of a deposit, so asking a shape about a block never allocates.
 */
public final class CellSample {

    /** Not part of the deposit at all. */
    public static final byte OUTSIDE = 0;
    /** Inside the deposit's footprint but never ore: the rock the deposit sits in. */
    public static final byte HOST = 1;
    /** Could be ore. The highest-scoring candidates in a deposit become ore. */
    public static final byte CANDIDATE = 2;
    /** A hollow cavity. */
    public static final byte CAVITY = 3;

    private byte kind = OUTSIDE;
    private double score;

    public void outside() {
        kind = OUTSIDE;
    }

    public void host() {
        kind = HOST;
    }

    public void candidate(double score) {
        this.kind = CANDIDATE;
        this.score = score;
    }

    public void cavity() {
        kind = CAVITY;
    }

    public byte kind() {
        return kind;
    }

    /** Only meaningful when {@link #kind()} is {@link #CANDIDATE}. */
    public double score() {
        return score;
    }
}
