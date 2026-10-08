package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.level.Level;

/** Foxes seek shelter from thunder where the fox is (phase 6a). */
@Mixin(targets = "net.minecraft.world.entity.animal.Fox$SeekShelterGoal")
public abstract class FoxShelterWeatherMixin {

    @Shadow
    @Final
    Fox this$0;

    @WrapOperation(method = "canUse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isThundering()Z"))
    private boolean engines_and_empires$thunderHere(Level level, Operation<Boolean> original) {
        if (WeatherOwnership.owns(level)) {
            return WeatherQueries.thunderOver(level, this$0.blockPosition());
        }
        return original.call(level);
    }
}
