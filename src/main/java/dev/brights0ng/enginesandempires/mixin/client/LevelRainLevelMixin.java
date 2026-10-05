package dev.brights0ng.enginesandempires.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.client.ClientSky;
import net.minecraft.world.level.Level;

/**
 * The client's rain and thunder levels from the weather above the camera ({@link ClientSky}) wherever the pack owns the
 * weather, instead of vanilla's global levels (held at zero). Vanilla builds the sky darkening, its rain and thunder
 * checks ({@code isRaining}, {@code isThundering}) and more on these, and other mods read them too.
 *
 * <p>A client-only mixin on {@code Level}: in single player the integrated server's levels share the class, so the
 * client side is checked first and server levels are left alone.
 */
@Mixin(Level.class)
public abstract class LevelRainLevelMixin {

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$localRain(float delta, CallbackInfoReturnable<Float> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide() && WeatherOwnership.owns(self)) {
            cir.setReturnValue(ClientSky.rain(delta));
        }
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$localThunder(float delta, CallbackInfoReturnable<Float> cir) {
        Level self = (Level) (Object) this;
        if (self.isClientSide() && WeatherOwnership.owns(self)) {
            cir.setReturnValue(ClientSky.thunder(delta));
        }
    }
}
