package dev.brights0ng.paradynelib;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.neoforged.fml.loading.LoadingModList;

/**
 * A mixin config plugin that applies a mixin only when the mod it targets is loaded (split step 1b). Each mod's
 * mixin config names a subclass that hands over its {@link MixinGate}; every mixin the gate does not list (vanilla and
 * NeoForge targets) always applies.
 *
 * <p>Mixin configs load before {@code ModList} exists, so the check reads FML's {@link LoadingModList}.
 */
public abstract class GatedMixinPlugin implements IMixinConfigPlugin {

    private final MixinGate gate;
    private String mixinPackage = "";

    protected GatedMixinPlugin(MixinGate gate) {
        this.gate = gate;
    }

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage.endsWith(".") ? mixinPackage : mixinPackage + ".";
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.startsWith(mixinPackage)
                ? mixinClassName.substring(mixinPackage.length()) : mixinClassName;
        String mod = gate.requiredMod(name);
        return mod == null || isLoaded(mod);
    }

    private static boolean isLoaded(String modId) {
        LoadingModList mods = LoadingModList.get();
        return mods != null && mods.getModFileById(modId) != null;
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
