// Written by Claude.
package dev.brights0ng.empire.compat.meteodyne;

import dev.brights0ng.meteodyne.climate.SeasonSource;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/** With Meteodyne installed, a god's patience is Meteodyne's year. */
public final class MeteodyneCompat {

    public static final String MODID = "meteodyne";

    /** Meteodyne's year length in ticks, or 0 when Meteodyne is absent or has no seasons. */
    public static long yearTicks(Level level) {
        return ModList.get().isLoaded(MODID) ? Hook.yearTicks(level) : 0;
    }

    private static final class Hook {
        static long yearTicks(Level level) {
            return SeasonSource.yearTicks(level);
        }
    }

    private MeteodyneCompat() {
    }
}
