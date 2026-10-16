package dev.brights0ng.paradynelib.services;

import dev.brights0ng.paradynelib.wind.WindColumn;
import net.minecraft.server.level.ServerLevel;

/**
 * The wind, for what it pushes (split seam S2: the library's wind on Sable's physics objects). Meteodyne answers with
 * its atmosphere; with nobody registered it is calm everywhere: there is no wind, so nothing is pushed.
 */
public interface WindProvider {

    /** Nobody to ask: calm everywhere. */
    WindProvider NONE = new WindProvider() {
    };

    Services.Key<WindProvider> KEY = Services.key("wind", WindProvider.class, NONE);

    /** The registered provider, or {@link #NONE}. */
    static WindProvider get() {
        return Services.get(KEY);
    }

    /** The wind over (x, z), or null where there is none (calm: nothing is pushed). */
    default WindColumn column(ServerLevel level, double x, double z) {
        return null;
    }

    /** Where the wind comes from now, for debug output. */
    default String describe() {
        return "nothing (no wind provider: calm everywhere)";
    }

    /**
     * Forces the same wind everywhere, at every height, until cleared with null (the debug command). Without a
     * provider there is nothing to force, and it stays calm.
     */
    default void setOverride(WindColumn wind) {
    }
}
