package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.fog.client.FogEffects;
import dev.brights0ng.enginesandempires.weather.sky.client.ClientStorm;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The sky's colour under the pack's storms (weather phase 6d): vanilla's own rain and thunder greying is left out, and
 * the sky is turned toward the storm's colour instead, by the same amount and to the same colour as the fog
 * ({@link FogEffects#stormTinted}). The game builds its fog colour from the sky colour, so this also keeps the two
 * matched at the horizon. With the pack's fog off, vanilla's greying stays.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSkyColourMixin {

    @WrapOperation(method = "getSkyColor", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F")})
    private float engines_and_empires$noVanillaGrey(ClientLevel level, float partial, Operation<Float> original) {
        return FogEffects.enabled() && ClientStorm.active() ? 0f : original.call(level, partial);
    }

    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void engines_and_empires$stormSky(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        if (FogEffects.skyTint(partialTick) > 1e-4) {
            cir.setReturnValue(FogEffects.stormTinted((ClientLevel) (Object) this, partialTick, cir.getReturnValue()));
        }
    }
}
