// Written by Claude.
package dev.brights0ng.empire.sacrifice;

import net.minecraft.world.item.ItemStack;

/** The {@link MeatFreshness} in use: always fresh unless a compat installs another (Simple Spoilage). */
public final class MeatFreshnessChecks {

    private static MeatFreshness delegate = MeatFreshness.ALWAYS_FRESH;

    public static void install(MeatFreshness freshness) {
        delegate = freshness;
    }

    public static MeatStage freshest(ItemStack stack, int units) {
        return delegate.freshest(stack, units);
    }

    public static MeatStage stalest(ItemStack stack, int units) {
        return delegate.stalest(stack, units);
    }

    private MeatFreshnessChecks() {
    }
}
