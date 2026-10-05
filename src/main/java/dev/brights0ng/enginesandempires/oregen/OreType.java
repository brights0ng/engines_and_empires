package dev.brights0ng.enginesandempires.oregen;

import java.util.Objects;
import java.util.List;

import dev.brights0ng.enginesandempires.oregen.shape.BodyShape;

/**
 * Everything that defines one ore: where its deposits are, how they are shaped, how big, and how deep.
 *
 * @param layer     the ore's name and spacing (S)
 * @param realm     the dimension the ore generates in
 * @param shape     the kind of body its deposits take
 * @param size      how many ore blocks a shallow deposit holds
 * @param depth     how high or low its deposits sit, and how they grow with depth
 * @param richShare the fraction of a median-sized deposit's ore blocks that are rich. Smaller deposits
 *                  are richer and larger ones leaner, as {@link #richShareFor(int)} works out
 * @param biomes    how the surface biome above a shallow deposit scales its size; empty for none
 */
public record OreType(OreLayer layer, Realm realm, BodyShape shape, SizeProfile size, DepthProfile depth,
                      double richShare, List<BiomeRule> biomes) {

    /** Hard limit on how far, horizontally, any deposit may reach from its centre. */
    static final int REACH_LIMIT = 128;

    private static final double MAX_RICH_SHARE = 0.6;

    public OreType {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(depth, "depth");
        biomes = List.copyOf(Objects.requireNonNull(biomes, "biomes"));
        if (richShare < 0.0 || richShare > MAX_RICH_SHARE) {
            throw new IllegalArgumentException("richShare must be between 0 and " + MAX_RICH_SHARE);
        }
        if (depth.minY() < realm.lowestY() || depth.maxY() > realm.highestY()) {
            throw new IllegalArgumentException("Depth range of '" + layer.id() + "' lies outside the "
                    + realm + " (" + realm.lowestY() + " to " + realm.highestY() + ")");
        }
        for (BiomeRule rule : biomes) {
            if (rule.raisesHeight() && (rule.maxY() <= depth.maxY() || rule.maxY() > realm.highestY())) {
                throw new IllegalArgumentException("Biome rule " + rule.selector() + " of '" + layer.id()
                        + "' raises the top to " + rule.maxY() + ", which must be above " + depth.maxY()
                        + " and at most " + realm.highestY());
            }
        }
    }

    /** An ore whose size does not depend on biome. */
    public OreType(OreLayer layer, Realm realm, BodyShape shape, SizeProfile size, DepthProfile depth, double richShare) {
        this(layer, realm, shape, size, depth, richShare, List.of());
    }

    public String id() {
        return layer.id();
    }

    /**
     * The most ore blocks any deposit of this ore can hold: the size cap, scaled up by the largest multiplier
     * a deposit can get, from depth (deep deposits) or from biome (shallow ones). The two never combine.
     */
    public int maxOre() {
        double biome = 1.0;
        for (BiomeRule rule : biomes) {
            if (rule.depthFor(depth).isShallow(rule.depthFor(depth).maxY())) {
                biome = Math.max(biome, rule.factor());
            }
        }
        return (int) Math.ceil(size.max() * Math.max(depth.maxMultiplier(), biome));
    }

    /** True if this ore's size depends on biome at all. */
    public boolean hasBiomeRules() {
        return !biomes.isEmpty();
    }

    /**
     * The furthest, horizontally, that any deposit of this ore can reach from its centre. Worldgen looks
     * this far beyond a chunk for deposits that could reach into it.
     */
    public int maxHorizontalReach() {
        return Math.min(REACH_LIMIT, shape.maxReach(maxOre(), size.median()));
    }

    /** The fraction of a deposit's ore that is rich, given how many ore blocks it holds. Bigger deposits are leaner. */
    double richShareFor(int oreBlocks) {
        return Math.min(MAX_RICH_SHARE, richShare * StrictMath.sqrt(size.median() / (double) oreBlocks));
    }
}
