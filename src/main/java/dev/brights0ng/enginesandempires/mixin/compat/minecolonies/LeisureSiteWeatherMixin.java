package dev.brights0ng.enginesandempires.mixin.compat.minecolonies;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecolonies.core.colony.Colony;

import dev.brights0ng.enginesandempires.weather.checks.ColonyWeather;
import net.minecraft.server.level.ServerLevel;

/** Leisure sites are chosen by the rain over the colony (its centre), not the global rain (weather phase 6a). */
@Pseudo
@Mixin(targets = "com.minecolonies.core.colony.managers.RegisteredStructureManager", remap = false)
public abstract class LeisureSiteWeatherMixin {

    @Shadow
    @Final
    private Colony colony;

    @WrapOperation(method = "getRandomLeisureSite", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;isRaining()Z"))
    private boolean engines_and_empires$rainOverColony(ServerLevel level, Operation<Boolean> original) {
        return ColonyWeather.rainingAt(level, colony == null ? null : colony.getCenter(), original);
    }
}
