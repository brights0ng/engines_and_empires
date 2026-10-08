package dev.brights0ng.enginesandempires.mixin.compat.minecolonies;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minecolonies.core.entity.citizen.EntityCitizen;

import dev.brights0ng.enginesandempires.weather.checks.ColonyWeather;
import net.minecraft.world.level.Level;

/** Leisure wandering follows the rain where the colonist is (weather phase 6a). */
@Pseudo
@Mixin(targets = "com.minecolonies.core.entity.ai.minimal.EntityAICitizenWander", remap = false)
public abstract class CitizenWanderWeatherMixin {

    @Shadow
    @Final
    protected EntityCitizen citizen;

    @WrapOperation(method = "wanderAtLeisureSite", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isRaining()Z"))
    private boolean engines_and_empires$rainHere(Level level, Operation<Boolean> original) {
        return ColonyWeather.rainingAt(level, citizen.blockPosition(), original);
    }
}
