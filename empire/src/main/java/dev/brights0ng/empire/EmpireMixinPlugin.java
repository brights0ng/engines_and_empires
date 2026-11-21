// Written by Gemini.
package dev.brights0ng.empire;

import dev.brights0ng.paradynelib.GatedMixinPlugin;
import dev.brights0ng.paradynelib.MixinGate;

/**
 * Empire's mixin config plugin (one mixin config per mod). Its mixins target vanilla, so they always apply.
 */
public final class EmpireMixinPlugin extends GatedMixinPlugin {

    public EmpireMixinPlugin() {
        super(Gates.GATE);
    }

    /** Which mixins need which mod. Kept apart from the plugin so unit tests can read it without Mixin's classes. */
    public static final class Gates {
        public static final MixinGate GATE = MixinGate.NONE;
    }
}
