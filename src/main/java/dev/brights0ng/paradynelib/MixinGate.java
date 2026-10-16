package dev.brights0ng.paradynelib;

import java.util.Map;

/**
 * Which of a mixin config's mixins need another mod loaded (split step 1b), for {@link GatedMixinPlugin}. Kept free
 * of Mixin's classes so unit tests, which run without Mixin, can check each mod's table.
 */
public final class MixinGate {

    /** A config whose mixins all target vanilla or NeoForge: every one always applies. */
    public static final MixinGate NONE = new MixinGate(Map.of());

    /**
     * Mixin class name relative to the config's package (e.g. {@code "SubLevelSilhouetteMixin"}), or a sub-package
     * prefix ending in a dot (e.g. {@code "compat.minecolonies."}), to the id of the mod it needs.
     */
    private final Map<String, String> requiredMods;

    public MixinGate(Map<String, String> requiredMods) {
        this.requiredMods = Map.copyOf(requiredMods);
    }

    /** The id of the mod a mixin needs, or null when it always applies. Takes the package-relative class name. */
    public String requiredMod(String mixinName) {
        String found = requiredMods.get(mixinName);
        if (found != null) {
            return found;
        }
        for (Map.Entry<String, String> e : requiredMods.entrySet()) {
            if (e.getKey().endsWith(".") && mixinName.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
