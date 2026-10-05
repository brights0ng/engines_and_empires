package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.fog.client.FogEffects;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;

/**
 * Hides the sky (sun, moon, stars, the sky dome) while the camera is inside a cloud, the way blindness does: fog
 * doesn't cover the sun and stars, and a cloud should. The game then shows its clear colour there, which is the fog
 * colour, so the cloud's grey fills the whole view.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSkyMixin {

    @Inject(method = "doesMobEffectBlockSky", at = @At("RETURN"), cancellable = true)
    private void engines_and_empires$cloudBlocksSky(Camera camera, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && FogEffects.insideCloud() != null) {
            cir.setReturnValue(true);
        }
    }
}
