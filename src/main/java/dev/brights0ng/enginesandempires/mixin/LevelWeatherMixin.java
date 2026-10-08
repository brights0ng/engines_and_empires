package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Answers {@code Level.isRainingAt} by position from the pack's clouds ({@link WeatherQueries}) wherever the pack owns
 * the weather, on both sides: wet entities, fires going out, tridents (Riptide), enderman and undead behaviour.
 * Vanilla's own answer would always be "no", since its global rain is held clear.
 *
 * <p>Serene Seasons also answers {@code isRainingAt} at its head, always, from vanilla's global rain (which is held
 * clear here), so ours must run first: the lower priority value applies it earlier, putting its callback ahead of
 * Serene Seasons'. {@code WeatherGameTests.noRainWhereThereAreNoClouds} checks the order with Serene Seasons loaded.
 */
@Mixin(value = Level.class, priority = 500)
public abstract class LevelWeatherMixin {

    @Inject(method = "isRainingAt", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$rainingHere(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (WeatherOwnership.owns(self)) {
            cir.setReturnValue(WeatherQueries.isRainingAt(self, pos));
        }
    }
}
