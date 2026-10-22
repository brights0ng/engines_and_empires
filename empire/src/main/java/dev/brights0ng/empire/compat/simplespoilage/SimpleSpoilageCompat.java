// Written by Claude.
package dev.brights0ng.empire.compat.simplespoilage;

import dev.brights0ng.empire.sacrifice.MeatFreshnessChecks;
import net.neoforged.fml.ModList;

/** With Simple Spoilage installed, the gods judge founding meat by its spoilage stage. */
public final class SimpleSpoilageCompat {

    public static final String MODID = "simple_spoilage";

    public static void registerIfLoaded() {
        if (ModList.get().isLoaded(MODID)) {
            Hook.register();
        }
    }

    private static final class Hook {
        static void register() {
            MeatFreshnessChecks.install(new SpoilageMeatFreshness());
        }
    }

    private SimpleSpoilageCompat() {
    }
}
