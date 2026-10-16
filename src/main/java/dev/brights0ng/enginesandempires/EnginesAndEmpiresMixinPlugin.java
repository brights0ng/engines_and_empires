package dev.brights0ng.enginesandempires;

import java.util.Map;

import dev.brights0ng.paradynelib.GatedMixinPlugin;
import dev.brights0ng.paradynelib.MixinGate;

/**
 * The core mod's mixin config plugin (split step 1b: one mixin config per mod). The MineColonies and Cold Sweat mixins
 * apply only when that mod is loaded; the rest target Create, Encumbered (both required by the core) or vanilla and
 * always apply, as does {@code compat.cloudworks.CloudWorksDebugDumpMixin}, which is {@code @Pseudo} with
 * {@code require = 0} and so does nothing without CloudWorks.
 */
public final class EnginesAndEmpiresMixinPlugin extends GatedMixinPlugin {

    public EnginesAndEmpiresMixinPlugin() {
        super(Gates.GATE);
    }

    /** Which mixins need which mod. Kept apart from the plugin so unit tests can read it without Mixin's classes. */
    public static final class Gates {
        public static final MixinGate GATE = new MixinGate(Map.of(
                "compat.minecolonies.", "minecolonies",
                "compat.coldsweat.", "cold_sweat"));
    }
}
