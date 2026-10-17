package dev.brights0ng.frontier;

import dev.brights0ng.paradynelib.GatedMixinPlugin;
import dev.brights0ng.paradynelib.MixinGate;

/**
 * Frontier's mixin config plugin (split step 1b: one mixin config per mod). Its mixins target vanilla, so they always
 * apply.
 */
public final class FrontierMixinPlugin extends GatedMixinPlugin {

    public FrontierMixinPlugin() {
        super(Gates.GATE);
    }

    /** Which mixins need which mod. Kept apart from the plugin so unit tests can read it without Mixin's classes. */
    public static final class Gates {
        public static final MixinGate GATE = MixinGate.NONE;
    }
}
