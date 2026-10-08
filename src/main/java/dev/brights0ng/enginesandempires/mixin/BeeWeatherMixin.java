package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.Level;

/** Bees head home in rain where the bee is, not by the (held clear) global rain (phase 6a). */
@Mixin(Bee.class)
public abstract class BeeWeatherMixin {

    @WrapOperation(method = "wantsToEnterHive", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
    private boolean engines_and_empires$rainHere(Level level, Operation<Boolean> original) {
        if (WeatherOwnership.owns(level)) {
            return WeatherQueries.precipitationOver(level, ((Bee) (Object) this).blockPosition());
        }
        return original.call(level);
    }
}
