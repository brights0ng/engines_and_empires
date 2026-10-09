package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.fog.client.FogEffects;
import dev.brights0ng.enginesandempires.weather.sky.client.ClientStorm;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;

/**
 * Vanilla darkens the fog colour in rain and thunder on top of the sky's greying; under the pack's storms the fog
 * colour comes from the storm's colour alone ({@code FogEffects}), so vanilla's darkening is left out (weather phase
 * 6d). With the pack's fog off, vanilla's stays.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererStormMixin {

    @WrapOperation(method = "setupColor", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F")})
    private static float engines_and_empires$noVanillaDarkening(ClientLevel level, float partial,
                                                              Operation<Float> original) {
        return FogEffects.enabled() && ClientStorm.active() ? 0f : original.call(level, partial);
    }
}
