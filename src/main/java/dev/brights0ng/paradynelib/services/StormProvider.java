package dev.brights0ng.paradynelib.services;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Storms by position, for what the dark under them changes (split seam S3: monsters spawn under a thunderstorm). Meteodyne
 * answers where it owns the weather (the Overworld, with its localized weather on). With nobody registered there is no
 * storm of ours anywhere: {@link #owns} is false, so the asker falls back on vanilla's own weather.
 */
public interface StormProvider {

    /** Nobody to ask: no storm of ours anywhere. */
    StormProvider NONE = new StormProvider() {
    };

    Services.Key<StormProvider> KEY = Services.key("storm", StormProvider.class, NONE);

    /** The registered provider, or {@link #NONE}. */
    static StormProvider get() {
        return Services.get(KEY);
    }

    /**
     * Whether this provider decides the weather in {@code level}. Where it does not, ask vanilla ({@code isThundering},
     * {@code getSkyDarken}); the other two methods are only asked where it does.
     */
    default boolean owns(Level level) {
        return false;
    }

    /** Whether a thunderstorm is over {@code pos}. */
    default boolean thunderOver(ServerLevel level, BlockPos pos) {
        return false;
    }

    /**
     * How far the sky at {@code pos} is darkened, on vanilla's {@code getSkyDarken} scale (0 at noon in clear weather,
     * 11 at its darkest): vanilla's darkening, with a storm overhead dimming it further. 0 with no storm of ours.
     */
    default int skyDarken(ServerLevel level, BlockPos pos) {
        return 0;
    }
}
