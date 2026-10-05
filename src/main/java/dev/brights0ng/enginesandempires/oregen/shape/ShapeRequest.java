package dev.brights0ng.enginesandempires.oregen.shape;

import dev.brights0ng.enginesandempires.oregen.DepositRandom;

/**
 * What a shape is asked to build.
 *
 * @param rng          the deposit's random stream; a shape draws whatever else it needs from it
 * @param wantedOre    how many ore blocks the deposit should hold
 * @param referenceOre how many ore blocks a typical shallow deposit of this ore holds, so a shape can
 *                     tell a small deposit from a large one
 */
public record ShapeRequest(DepositRandom rng, int wantedOre, int referenceOre) {

    public ShapeRequest {
        if (wantedOre < 1 || referenceOre < 1) {
            throw new IllegalArgumentException("ore counts must be at least 1");
        }
    }
}
