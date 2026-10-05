package dev.brights0ng.enginesandempires.oregen;

import java.util.Objects;

/**
 * The tuning for one ore type.
 *
 * @param id    a stable name for the ore (for example {@code "iron"}). It salts the noise, so renaming
 *              it moves every deposit of this ore.
 * @param scale "S": there is about one deposit per {@code scale x scale} blocks where
 *              {@link Abundance} is 1. Anywhere it is lower, deposits are sparser than that.
 */
public record OreLayer(String id, int scale) {

    public OreLayer {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (scale < 16) {
            throw new IllegalArgumentException("scale must be at least 16 blocks, got " + scale);
        }
    }
}
