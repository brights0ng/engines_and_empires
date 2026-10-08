package dev.brights0ng.enginesandempires.mixin.compat.minecolonies;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import dev.brights0ng.enginesandempires.weather.checks.ColonyWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** A colonist's rainy-day grumbles follow the rain where the sound plays (weather phase 6a). */
@Pseudo
@Mixin(targets = "com.minecolonies.api.util.SoundUtils", remap = false)
public abstract class ColonySoundWeatherMixin {

    @WrapOperation(method = "playRandomSound", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
    private static boolean engines_and_empires$rainHere(Level level, Operation<Boolean> original,
                                                      @Local(argsOnly = true) BlockPos pos) {
        return ColonyWeather.rainingAt(level, pos, original);
    }
}
