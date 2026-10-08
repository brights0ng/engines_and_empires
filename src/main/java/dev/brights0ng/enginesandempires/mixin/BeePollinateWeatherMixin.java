package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.Level;

/** Bees stop and don't start pollinating in rain where the bee is (phase 6a). */
@Mixin(targets = "net.minecraft.world.entity.animal.Bee$BeePollinateGoal")
public abstract class BeePollinateWeatherMixin {

    @Shadow
    @Final
    Bee this$0;

    @WrapOperation(method = {"canBeeUse", "canBeeContinueToUse"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
    private boolean engines_and_empires$rainHere(Level level, Operation<Boolean> original) {
        if (WeatherOwnership.owns(level)) {
            return WeatherQueries.precipitationOver(level, this$0.blockPosition());
        }
        return original.call(level);
    }
}
