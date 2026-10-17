package dev.brights0ng.sparseores;

import dev.brights0ng.paradynelib.GatedMixinPlugin;
import dev.brights0ng.paradynelib.MixinGate;

/**
 * Sparse Ores's mixin config plugin (split step 1b: one mixin config per mod). Its one mixin targets vanilla, so it
 * always applies.
 */
public final class SparseOresMixinPlugin extends GatedMixinPlugin {

    public SparseOresMixinPlugin() {
        super(Gates.GATE);
    }

    /** Which mixins need which mod. Kept apart from the plugin so unit tests can read it without Mixin's classes. */
    public static final class Gates {
        public static final MixinGate GATE = MixinGate.NONE;
    }
}
