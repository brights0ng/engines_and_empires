package dev.brights0ng.meteodyne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.brights0ng.meteodyne.WeatherOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Vanilla's light melt of ice (phase 8e-1, Bright, 2026-10-15): a block of ice melts when a torch or other block light
 * beside it is bright enough, whatever the weather. Where the pack owns the weather the pack owns melting
 * ({@code SurfaceWeather}: exposed ice thaws with the air temperature), so this does nothing there. Heat from blocks
 * comes back with the local heat cache (8f). Other dimensions keep vanilla's.
 *
 * <p>Priority below Cold Sweat's own {@code IceBlock.randomTick} replacement (default 1000; it is also off, see
 * {@code ColdSweatWater}), so this cancels first and later handlers see it cancelled.
 */
@Mixin(value = IceBlock.class, priority = 500)
public abstract class IceLightMeltMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void engines_and_empires$noLightMelt(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                                 CallbackInfo ci) {
        if (WeatherOwnership.owns(level)) {
            ci.cancel();
        }
    }
}
