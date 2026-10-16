package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.server.MinecraftServer;

/** R1 prototype: a second mod injecting into the same method as Sparse Ores' mixin (the S4 shape). */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerCoreBrandMixin {

    // @ModifyReturnValue chains across mods. A cancellable @Inject at RETURN that sets the value returns at once, so a
    // second mod's callback at the same point never runs (seen in this prototype's first run).
    @ModifyReturnValue(method = "getServerModName", at = @At("RETURN"))
    private String enginesandempires$brand(String original) {
        return original + "+engines_and_empires";
    }
}
