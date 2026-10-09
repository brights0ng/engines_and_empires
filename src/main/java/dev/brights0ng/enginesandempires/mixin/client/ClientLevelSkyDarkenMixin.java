package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.sky.StormShade;
import dev.brights0ng.enginesandempires.weather.sky.client.ClientStorm;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The world's light under the pack's storms (weather phase 6d; Bright, 2026-10-09: darker than vanilla for the
 * strongest storms). Vanilla dims the day by the rain and thunder levels; here the storm's darkness at the camera
 * ({@link ClientStorm}) dims it instead ({@link StormShade#lightFactor}), so the light falls as a storm's dark base
 * comes overhead, before any rain, and gloomier as a storm gathers nearby. The lightmap, the sky's brightness and
 * everything built on this follow. The server uses the same model for gameplay light ({@code StormLight}).
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSkyDarkenMixin {

    @WrapOperation(method = "getSkyDarken(F)F", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F")})
    private float engines_and_empires$noVanillaDimming(ClientLevel level, float partial, Operation<Float> original) {
        return ClientStorm.active() ? 0f : original.call(level, partial);
    }

    @Inject(method = "getSkyDarken(F)F", at = @At("RETURN"), cancellable = true)
    private void engines_and_empires$stormDimming(float partial, CallbackInfoReturnable<Float> cir) {
        if (!ClientStorm.active()) {
            return;
        }
        double factor = StormShade.lightFactor(ClientStorm.darkness(partial));
        if (factor < 1) {
            // Vanilla's value is 0.2 at night up to 1 at noon; the storm scales the daylight part.
            float v = cir.getReturnValueF();
            cir.setReturnValue((float) (0.2 + (v - 0.2) * factor));
        }
    }
}
