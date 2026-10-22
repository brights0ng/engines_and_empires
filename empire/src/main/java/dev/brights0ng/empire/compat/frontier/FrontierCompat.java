// Written by Gemini.
package dev.brights0ng.empire.compat.frontier;

import dev.brights0ng.empire.tier.TierChecks;
import net.neoforged.fml.ModList;

public final class FrontierCompat {

    public static final String MODID = "frontier";

    public static void registerIfLoaded() {
        if (ModList.get().isLoaded(MODID)) {
            Hook.register();
        }
    }

    private static final class Hook {
        static void register() {
            TierChecks.install(FrontierTierCheck::create);
        }
    }

    private FrontierCompat() {
    }
}
