package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import dev.brights0ng.enginesandempires.weather.sky.StormLight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DaylightDetectorBlock;

/** A daylight detector reads lower under a dark storm, by the storm over it (weather phase 6d, {@link StormLight}). */
@Mixin(DaylightDetectorBlock.class)
public abstract class DaylightDetectorStormMixin {

    @WrapOperation(method = "updateSignalStrength", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getSkyDarken()I"))
    private static int engines_and_empires$stormOverDetector(Level level, Operation<Integer> original,
                                                           @Local(argsOnly = true) BlockPos pos) {
        return Math.max(original.call(level), StormLight.skyDarken(level, pos));
    }
}
