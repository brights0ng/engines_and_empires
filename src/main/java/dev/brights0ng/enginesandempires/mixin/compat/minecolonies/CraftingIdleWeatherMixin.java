package dev.brights0ng.enginesandempires.mixin.compat.minecolonies;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.checks.ColonyWeather;
import net.minecraft.world.level.Level;

/** A crafter picks an indoor or outdoor idle spot by the rain where the worker is (weather phase 6a). */
@Pseudo
@Mixin(targets = "com.minecolonies.core.entity.ai.workers.crafting.AbstractEntityAICrafting", remap = false)
public abstract class CraftingIdleWeatherMixin {

    @WrapOperation(method = "idle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
    private boolean engines_and_empires$rainHere(Level level, Operation<Boolean> original) {
        var worker = ((AISkeletonAccessor) this).engines_and_empires$worker();
        return ColonyWeather.rainingAt(level, worker == null ? null : worker.blockPosition(), original);
    }
}
