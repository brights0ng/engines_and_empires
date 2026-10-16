package dev.brights0ng.meteodyne;

import dev.brights0ng.paradynelib.GatedMixinPlugin;
import dev.brights0ng.paradynelib.MixinGate;

/**
 * Meteodyne's mixin config plugin (split step 1b: one mixin config per mod). Its mixins target vanilla, so they always
 * apply. {@code CloudWorksDebugDumpMixin} targets CloudWorks but is {@code @Pseudo} with {@code require = 0}: without
 * CloudWorks it does nothing, as before the split.
 */
public final class MeteodyneMixinPlugin extends GatedMixinPlugin {

    public MeteodyneMixinPlugin() {
        super(Gates.GATE);
    }

    /** Which mixins need which mod. Kept apart from the plugin so unit tests can read it without Mixin's classes. */
    public static final class Gates {
        public static final MixinGate GATE = MixinGate.NONE;
    }
}
