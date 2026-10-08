package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.level.Level;

/** Pandas are scared of thunder where the panda is (phase 6a). */
@Mixin(Panda.class)
public abstract class PandaWeatherMixin {

    @WrapOperation(method = {"isScared", "tick"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isThundering()Z"))
    private boolean engines_and_empires$thunderHere(Level level, Operation<Boolean> original) {
        if (WeatherOwnership.owns(level)) {
            return WeatherQueries.thunderOver(level, ((Panda) (Object) this).blockPosition());
        }
        return original.call(level);
    }
}
