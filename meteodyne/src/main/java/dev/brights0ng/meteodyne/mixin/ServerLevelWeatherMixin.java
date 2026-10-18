package dev.brights0ng.meteodyne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.brights0ng.meteodyne.WeatherOwnership;
import dev.brights0ng.meteodyne.surface.SurfaceWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * Holds vanilla's global weather clear wherever the pack owns the weather ({@link WeatherOwnership}): rain and thunder
 * then come only from the pack's clouds, by position.
 *
 * <p>Done the way {@code /weather clear} does it, so vanilla's own cycle code takes its clear branch: before each cycle
 * step the level is set not raining and not thundering, with a long clear spell ahead. Vanilla then eases its rain and
 * thunder levels down and tells clients as usual (so a world that was raining when this was installed clears within a
 * few seconds), and sleeping, {@code /weather} and the weather gamerule can't bring global rain back.
 *
 * <p>Phase 5b (2026-10-08): vanilla's precipitation tick (snow, freezing, cauldrons) is replaced there by the pack's
 * ({@link SurfaceWeather}), run at the end of each chunk tick.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelWeatherMixin {

    /** The clear spell kept ahead, ticks (half a day; topped up every tick). */
    private static final int HOLD_TICKS = 12_000;

    @Shadow
    @Final
    private ServerLevelData serverLevelData;

    @Inject(method = "advanceWeatherCycle", at = @At("HEAD"))
    private void meteodyne$holdClear(CallbackInfo ci) {
        if (WeatherOwnership.owns((ServerLevel) (Object) this)) {
            this.serverLevelData.setRaining(false);
            this.serverLevelData.setThundering(false);
            this.serverLevelData.setClearWeatherTime(Math.max(this.serverLevelData.getClearWeatherTime(), HOLD_TICKS));
        }
    }

    @Inject(method = "tickPrecipitation", at = @At("HEAD"), cancellable = true)
    private void meteodyne$skipVanillaPrecipitation(BlockPos pos, CallbackInfo ci) {
        if (WeatherOwnership.owns((ServerLevel) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "tickChunk", at = @At("TAIL"))
    private void meteodyne$surfaceWeather(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        SurfaceWeather.tickChunk((ServerLevel) (Object) this, chunk);
    }
}
