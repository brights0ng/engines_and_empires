package dev.brights0ng.enginesandempires.mixin.compat.minecolonies;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecolonies.api.colony.buildings.IBuilding;

import dev.brights0ng.enginesandempires.weather.checks.ColonyWeather;
import net.minecraft.world.level.Level;

/** The restaurant seats diners indoors when it rains on the restaurant (weather phase 6a). */
@Pseudo
@Mixin(targets = "com.minecolonies.core.colony.buildings.workerbuildings.BuildingCook", remap = false)
public abstract class CookSeatingWeatherMixin {

    @WrapOperation(method = "getNextSittingPosition", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
    private boolean engines_and_empires$rainHere(Level level, Operation<Boolean> original) {
        Object self = this;
        return ColonyWeather.rainingAt(level, self instanceof IBuilding b ? b.getPosition() : null, original);
    }
}
