package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.sky.client.ClientStorm;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;

/**
 * The sun, moon and stars fade behind thick cloud overhead even without rain (weather phase 6d; Bright, 2026-10-09).
 * Vanilla fades them by the rain level when drawing the sky; here by whichever is more, the rain or how hidden they are
 * by the clouds ({@link ClientStorm#skyHidden}).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSunMixin {

    @WrapOperation(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float engines_and_empires$cloudHidesSun(ClientLevel level, float partial, Operation<Float> original) {
        float rain = original.call(level, partial);
        return ClientStorm.active() ? Math.max(rain, (float) ClientStorm.skyHidden(partial)) : rain;
    }
}
