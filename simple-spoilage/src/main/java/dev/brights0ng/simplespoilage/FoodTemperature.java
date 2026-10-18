package dev.brights0ng.simplespoilage;

import dev.brights0ng.paradynelib.services.TemperatureProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The temperature food sees (food by temperature, 2026-10-15; {@code claude/food-temperature.md}): the air where it is
 * (underground included) plus the room there; on a ship, the air where the ship is and the ship's room. Not wind chill,
 * sun or radiant warmth. Asked of the library's {@link TemperatureProvider} (split seam S1; Meteodyne answers in the
 * pack). NaN where nobody knows (no provider, or other dimensions): food there ages by the clock alone.
 */
public final class FoodTemperature {

    /** Game tests: temperatures used everywhere instead of the world's (NaN: none). */
    private static volatile double overrideNow = Double.NaN;
    private static volatile double overrideSeasonal = Double.NaN;

    /** Game tests: every place reads {@code now} C, and {@code seasonal} C for long gaps, until cleared. */
    public static void setOverride(double now, double seasonal) {
        overrideNow = now;
        overrideSeasonal = seasonal;
    }

    public static void clearOverride() {
        setOverride(Double.NaN, Double.NaN);
    }

    /** The temperature at block {@code pos} (a container, a belt), C, or NaN. */
    public static double at(ServerLevel level, BlockPos pos) {
        if (!Double.isNaN(overrideNow)) {
            return overrideNow;
        }
        TemperatureProvider temperatures = TemperatureProvider.get();
        double air = temperatures.air(level, pos);
        return Double.isNaN(air) ? Double.NaN : air + temperatures.room(level, Vec3.atCenterOf(pos));
    }

    /** The temperature at an entity (a player, an item on the ground), C, or NaN. */
    public static double at(ServerLevel level, Entity entity) {
        if (!Double.isNaN(overrideNow)) {
            return overrideNow;
        }
        TemperatureProvider temperatures = TemperatureProvider.get();
        double air = temperatures.air(level, entity.blockPosition());
        return Double.isNaN(air) ? Double.NaN
                : air + temperatures.room(level, entity.position().add(0, Math.min(0.9, entity.getBbHeight() / 2), 0));
    }

    /**
     * The temperature food at {@code pos} sat at while nobody was there to see (an unloaded chunk, a long gap): the day's
     * mean for this time of year, without air masses (decision 8), underground included, plus the room as it is now.
     */
    public static double seasonal(ServerLevel level, BlockPos pos) {
        if (!Double.isNaN(overrideSeasonal)) {
            return overrideSeasonal;
        }
        TemperatureProvider temperatures = TemperatureProvider.get();
        double t = temperatures.seasonal(level, pos);
        return Double.isNaN(t) ? Double.NaN : t + temperatures.room(level, Vec3.atCenterOf(pos));
    }

    private FoodTemperature() {
    }
}
