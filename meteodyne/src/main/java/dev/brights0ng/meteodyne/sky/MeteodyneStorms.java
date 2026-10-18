package dev.brights0ng.meteodyne.sky;

import dev.brights0ng.meteodyne.WeatherOwnership;
import dev.brights0ng.meteodyne.rain.WeatherQueries;
import dev.brights0ng.paradynelib.services.StormProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Meteodyne's answer to the library's {@link StormProvider} (split seam S3): where the pack owns the weather
 * ({@link WeatherOwnership}), thunder only under a thunderstorm (weather phase 6a, {@link WeatherQueries#thunderOver}) and
 * the sky dimmed under a dark storm (weather phase 6d, {@link StormLight#skyDarken}).
 */
public final class MeteodyneStorms implements StormProvider {

    public static final MeteodyneStorms INSTANCE = new MeteodyneStorms();

    @Override
    public boolean owns(Level level) {
        return WeatherOwnership.owns(level);
    }

    @Override
    public boolean thunderOver(ServerLevel level, BlockPos pos) {
        return WeatherQueries.thunderOver(level, pos);
    }

    @Override
    public int skyDarken(ServerLevel level, BlockPos pos) {
        return StormLight.skyDarken(level, pos);
    }

    private MeteodyneStorms() {
    }
}
