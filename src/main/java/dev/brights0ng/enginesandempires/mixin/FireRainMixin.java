package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FireBlock;

/**
 * Fire going out in rain, and not spreading into rain (phase 6a). Vanilla asks the global rain first and then
 * {@code isNearRain} (which is already answered by position); the global gate is held clear, so it opens here whenever
 * the level has clouds and {@code isNearRain} decides. This also puts out fire on ship decks (phase 5d leftover).
 */
@Mixin(FireBlock.class)
public abstract class FireRainMixin {

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
    private boolean engines_and_empires$rainMayBeNear(ServerLevel level, Operation<Boolean> original) {
        if (WeatherOwnership.owns(level)) {
            return WeatherQueries.anyClouds(level);
        }
        return original.call(level);
    }
}
