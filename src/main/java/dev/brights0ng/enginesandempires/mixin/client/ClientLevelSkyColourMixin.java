package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.fog.client.FogEffects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the sky toward the rain's grey under rain falling on the camera, by the same amount and to the same grey as
 * the rain fog ({@link FogEffects#rainTinted}), as vanilla greys its sky in rain. The game builds its fog colour from
 * the sky colour, so this also keeps the two matched at the horizon.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSkyColourMixin {

    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void engines_and_empires$rainSky(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        if (FogEffects.rainTint() > 1e-4) {
            cir.setReturnValue(FogEffects.rainTinted((ClientLevel) (Object) this, partialTick, cir.getReturnValue()));
        }
    }
}
