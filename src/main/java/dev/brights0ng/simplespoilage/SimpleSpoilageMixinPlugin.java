package dev.brights0ng.simplespoilage;

import dev.brights0ng.paradynelib.GatedMixinPlugin;
import dev.brights0ng.paradynelib.MixinGate;

/**
 * Simple Spoilage's mixin config plugin (split step 1b: one mixin config per mod). Its mixins target vanilla and
 * NeoForge, so they always apply.
 */
public final class SimpleSpoilageMixinPlugin extends GatedMixinPlugin {

    public SimpleSpoilageMixinPlugin() {
        super(Gates.GATE);
    }

    /** Which mixins need which mod. Kept apart from the plugin so unit tests can read it without Mixin's classes. */
    public static final class Gates {
        public static final MixinGate GATE = MixinGate.NONE;
    }
}
