package dev.brights0ng.sablefixes;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.loading.LoadingModList;

/**
 * Sable Fixes' mixin config plugin (split step 1b: one mixin config per mod). Both mixins fix Sable's behaviour, so
 * both apply only when Sable is loaded. Sable Fixes imports nothing of ours, so this does not use the library's
 * {@code GatedMixinPlugin}; mixin configs load before {@code ModList} exists, so it reads FML's {@link LoadingModList}.
 */
public final class SableFixesMixinPlugin implements IMixinConfigPlugin {

    /** Which mixins need which mod. Kept apart from the plugin so unit tests can read it without Mixin's classes. */
    public static final class Gates {
        public static final Gates GATE = new Gates();

        /** The id of the mod a mixin needs, by its package-relative name: every mixin here needs Sable. */
        public String requiredMod(String mixinName) {
            return "sable";
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        LoadingModList mods = LoadingModList.get();
        return mods != null && mods.getModFileById(Gates.GATE.requiredMod(mixinClassName)) != null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
