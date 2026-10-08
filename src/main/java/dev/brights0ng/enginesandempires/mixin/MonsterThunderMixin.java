package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Monster;

/**
 * Thunder darkens the sky for spawning (vanilla: skylight counted as 10 at most) only under a thunderstorm (phase 6a).
 * Vanilla only gets this far after its light tests pass, so the positional check is rare.
 */
@Mixin(Monster.class)
public abstract class MonsterThunderMixin {

    @WrapOperation(method = "isDarkEnoughToSpawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;isThundering()Z"))
    private static boolean engines_and_empires$thunderHere(ServerLevel level, Operation<Boolean> original,
                                                         @Local(argsOnly = true) BlockPos pos) {
        if (WeatherOwnership.owns(level)) {
            return WeatherQueries.thunderOver(level, pos);
        }
        return original.call(level);
    }
}
