package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.surface.SurfaceWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

/**
 * Vanilla's biome temperature from the pack's air temperature (phase 5b, 2026-10-08), so vanilla's cold checks
 * ({@code coldEnoughToSnow}, {@code shouldFreeze}, snow golems, mods) agree with the weather: on the server thread,
 * for Overworld biomes, where the pack owns the weather ({@link SurfaceWeather#vanillaTemperature}).
 */
@Mixin(Biome.class)
public abstract class BiomeTemperatureMixin {

    @Inject(method = "getTemperature(Lnet/minecraft/core/BlockPos;)F", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$packTemperature(BlockPos pos, CallbackInfoReturnable<Float> cir) {
        Float t = SurfaceWeather.vanillaTemperature((Biome) (Object) this, pos);
        if (t != null) {
            cir.setReturnValue(t);
        }
    }
}
