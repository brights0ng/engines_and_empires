package dev.brights0ng.enginesandempires.mixin.client;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.client.LocalRainRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;

/**
 * Vanilla's rain and snow drawing, splashes and rain sound, replaced by the pack's wherever it owns the weather
 * ({@link WeatherOwnership}):
 * <ul>
 *   <li>{@code renderSnowAndRain}: {@link LocalRainRenderer} draws per-streak rain and snow from the pack's clouds
 *       instead of vanilla's camera-wide curtain.</li>
 *   <li>{@code tickRain}: vanilla places splashes and the rain sound anywhere near the camera by the biome's vanilla
 *       temperature; {@code LocalRainRenderer} makes them instead, only where rain reaches the ground.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererRainMixin {

    @Shadow
    @Nullable
    private ClientLevel level;

    @Inject(method = "tickRain", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$ownSplashes(Camera camera, CallbackInfo ci) {
        if (WeatherOwnership.owns(this.level)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$ownRain(LightTexture lightTexture, float partialTick, double camX, double camY,
                                             double camZ, CallbackInfo ci) {
        if (WeatherOwnership.owns(this.level)) {
            LocalRainRenderer.render(this.level, lightTexture, partialTick, camX, camY, camZ);
            ci.cancel();
        }
    }
}
