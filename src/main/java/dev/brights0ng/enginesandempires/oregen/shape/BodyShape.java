package dev.brights0ng.enginesandempires.oregen.shape;

/**
 * A way of shaping deposits: stratified lump, seam, vein, disseminated body or pocket cluster. A
 * shape is a set of tunable limits; each deposit draws its own numbers within them.
 */
public interface BodyShape {

    /** A short lower-case name, for commands and logs. */
    String name();

    /** Builds the geometry of one deposit that should hold {@code request.wantedOre()} ore blocks. */
    ShapeField create(ShapeRequest request);

    /**
     * The furthest, horizontally, that any deposit of this shape can reach from its centre when it holds
     * at most {@code maxOre} ore blocks, given that a typical shallow deposit holds {@code referenceOre}.
     * Worldgen relies on this to know how far beyond a chunk to look for deposits that could reach into
     * it, so it must be a true upper bound.
     */
    int maxReach(int maxOre, int referenceOre);
}
