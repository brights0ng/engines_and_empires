package dev.brights0ng.sparseores.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.server.MinecraftServer;

/** R1 prototype: proves this module's mixin config is applied in every run; core injects into the same method. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerBrandMixin {

    // @ModifyReturnValue chains across mods. A cancellable @Inject at RETURN that sets the value returns at once, so a
    // second mod's callback at the same point never runs (seen in this prototype's first run).
    @ModifyReturnValue(method = "getServerModName", at = @At("RETURN"))
    private String sparseores$brand(String original) {
        return original + "+sparse_ores";
    }
}
