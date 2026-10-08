package dev.brights0ng.enginesandempires.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.WeatherCheck;
import net.minecraft.world.phys.Vec3;

/**
 * Loot's {@code weather_check} asked at the loot context's origin (phase 6a). In 1.21 Channeling is data-driven and
 * gated on {@code weather_check{thundering:true}} at the struck entity, so without this it never fires (the global
 * thunder is held clear). A context without an origin keeps vanilla's global answer.
 */
@Mixin(WeatherCheck.class)
public abstract class WeatherCheckMixin {

    @Inject(method = "test(Lnet/minecraft/world/level/storage/loot/LootContext;)Z", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$localWeather(LootContext context, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel level = context.getLevel();
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (origin == null || !WeatherOwnership.owns(level)) {
            return;
        }
        WeatherCheck self = (WeatherCheck) (Object) this;
        BlockPos pos = BlockPos.containing(origin);
        if (self.isRaining().isPresent() && self.isRaining().get() != WeatherQueries.precipitationOver(level, pos)) {
            cir.setReturnValue(false);
            return;
        }
        if (self.isThundering().isPresent() && self.isThundering().get() != WeatherQueries.thunderOver(level, pos)) {
            cir.setReturnValue(false);
            return;
        }
        cir.setReturnValue(true);
    }
}
