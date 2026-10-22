// Written by Claude.
package dev.brights0ng.empire.sacrifice;

import net.minecraft.world.item.ItemStack;

/**
 * How fresh the units of a stack are that a sacrifice of {@code units} of it would take (the freshest ones: see
 * {@link SacrificeInteractions}). Without a spoilage mod every unit is fresh.
 */
public interface MeatFreshness {

    /** The freshest stage among those units. */
    MeatStage freshest(ItemStack stack, int units);

    /** The stalest stage among those units. */
    MeatStage stalest(ItemStack stack, int units);

    MeatFreshness ALWAYS_FRESH = new MeatFreshness() {
        @Override
        public MeatStage freshest(ItemStack stack, int units) {
            return MeatStage.FRESH;
        }

        @Override
        public MeatStage stalest(ItemStack stack, int units) {
            return MeatStage.FRESH;
        }
    };
}
