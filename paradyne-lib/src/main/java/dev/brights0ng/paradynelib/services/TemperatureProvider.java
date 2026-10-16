package dev.brights0ng.paradynelib.services;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Temperatures in the world, for mods that react to them (split seam S1: food ages faster in the warm). Meteodyne
 * answers with its climate, underground and heat simulation; with nobody registered, every temperature is unknown (NaN),
 * so food ages by the clock alone.
 */
public interface TemperatureProvider {

    /** Nobody to ask: every temperature is unknown. */
    TemperatureProvider NONE = new TemperatureProvider() {
    };

    Services.Key<TemperatureProvider> KEY = Services.key("temperature", TemperatureProvider.class, NONE);

    /** The registered provider, or {@link #NONE}. */
    static TemperatureProvider get() {
        return Services.get(KEY);
    }

    /**
     * The air temperature at block {@code pos}, C: outdoors or underground; on a ship, the air where the ship is. Not
     * wind chill, sun or radiant warmth, and not the room (see {@link #room}). NaN where unknown.
     */
    default double air(ServerLevel level, BlockPos pos) {
        return Double.NaN;
    }

    /** How much warmer than the air the room around {@code at} is, C (negative: colder). 0 where there is none. */
    default double room(ServerLevel level, Vec3 at) {
        return 0;
    }

    /**
     * The day's mean air temperature at block {@code pos} for this time of year, C, without the passing weather:
     * underground included; on a ship, the mean where the ship is. Not the room. NaN where unknown.
     */
    default double seasonal(ServerLevel level, BlockPos pos) {
        return Double.NaN;
    }
}
