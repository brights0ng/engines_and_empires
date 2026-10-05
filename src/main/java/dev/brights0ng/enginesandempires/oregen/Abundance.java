package dev.brights0ng.enginesandempires.oregen;

/**
 * Lets the surrounding game thin deposits out in some places (a biome that suits the ore less, a
 * region that is barren) without this package knowing anything about Minecraft.
 *
 * <p>The answer is the fraction of candidate deposits that survive at that spot: {@code 1} keeps every
 * one, {@code 0.25} keeps about a quarter, {@code 0} keeps none. Values outside 0..1 behave like the
 * nearest end, and NaN keeps nothing. That makes a layer's scale "S" the <em>tightest</em> spacing the
 * ore can have; abundance can only make it sparser.
 *
 * <p>Deposits are thinned by a fixed random roll per deposit, so lowering abundance only ever removes
 * deposits and raising it only ever brings back the same ones. Nothing shifts around.
 *
 * <p>Implementations must be deterministic (same inputs, same answer, every time) and safe to call
 * from any thread. The ore map calls this once per candidate deposit, at the deposit's centre.
 */
@FunctionalInterface
public interface Abundance {

    /** Every candidate deposit survives, everywhere. */
    Abundance FULL = (x, z) -> 1.0;

    double at(int x, int z);
}
