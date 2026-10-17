package dev.brights0ng.paradynelib;

import java.util.Map;

/**
 * Paradyne Lib's mixin config plugin (split step 1b: one mixin config per mod). Its one mixin,
 * {@code sable.SubLevelSilhouetteMixin}, targets Sable's physics: every mixin in the {@code sable} sub-package applies
 * only when Sable is loaded.
 */
public final class ParadyneLibMixinPlugin extends GatedMixinPlugin {

    public ParadyneLibMixinPlugin() {
        super(Gates.GATE);
    }

    /** Which mixins need which mod. Kept apart from the plugin so unit tests can read it without Mixin's classes. */
    public static final class Gates {
        public static final MixinGate GATE = new MixinGate(Map.of("sable.", "sable"));
    }
}
