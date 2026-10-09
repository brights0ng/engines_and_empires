package dev.brights0ng.enginesandempires.weather.sky;

import java.util.Map;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.cloud.CloudSources;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * The storm's darkness as gameplay light (weather phase 6d; Bright, 2026-10-09: gameplay too). Vanilla darkens the
 * sky for everything at once by its global rain and thunder (held clear here); this darkens it at each place by the
 * storm over it, with the same model and numbers the client's light uses ({@link StormShade}).
 *
 * <p>It is the sky darkening (vanilla's {@code skyDarken}, 0 at noon to 11 at night) worked out per place: vanilla's
 * formula with the storm's {@link StormShade#lightFactor} in place of the rain and thunder dimming. Read by monster
 * spawning, undead burning and anything else that asks a block's light with the sky dimmed
 * ({@code getMaxLocalRawBrightness}), daylight detectors, and the Frontier's darkness. Under the darkest storm at noon
 * this is about 7 (vanilla's full thunderstorm, 5).
 *
 * <p>Cheap to ask often: the darkness is kept per {@value #CELL}-block cell and worked out again at most every
 * {@value #MAX_AGE} ticks, when the clouds are bucketed afresh.
 */
public final class StormLight {

    static final int CELL = 32;
    static final int MAX_AGE = 40;

    private static final class Cache {
        StormShade.Index index;
        long indexTick = Long.MIN_VALUE;
        final Long2FloatOpenHashMap cells = new Long2FloatOpenHashMap();
    }

    private static final Map<ServerLevel, Cache> CACHES = new WeakHashMap<>();

    /** The storm's darkness over (x, z) in {@code level} (0-1); 0 where the pack doesn't own the weather. */
    public static synchronized double darkness(ServerLevel level, int x, int z) {
        if (!WeatherOwnership.owns(level)) {
            return 0;
        }
        Cache c = CACHES.computeIfAbsent(level, l -> new Cache());
        long now = level.getGameTime();
        if (c.index == null || now - c.indexTick >= MAX_AGE || now < c.indexTick) {
            c.index = new StormShade.Index(CloudSources.server(level), now);
            c.indexTick = now;
            c.cells.clear();
        }
        if (c.index.isEmpty()) {
            return 0;
        }
        int cx = Math.floorDiv(x, CELL);
        int cz = Math.floorDiv(z, CELL);
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        if (c.cells.containsKey(key)) {
            return c.cells.get(key);
        }
        float d = (float) StormShade.sample(c.index, cx * CELL + CELL / 2.0, cz * CELL + CELL / 2.0).darkness();
        c.cells.put(key, d);
        return d;
    }

    /**
     * The sky darkening at {@code pos}: vanilla's (time of day, and its own rain and thunder, held clear) with the
     * storm over {@code pos} dimming the daylight too. Never lighter than vanilla's.
     */
    public static int skyDarken(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || !WeatherOwnership.owns(server)) {
            return level.getSkyDarken();
        }
        double dark = darkness(server, pos.getX(), pos.getZ());
        if (dark <= 1e-4) {
            return level.getSkyDarken();
        }
        double rain = 1 - level.getRainLevel(1f) * 5 / 16.0;
        double thunder = 1 - level.getThunderLevel(1f) * 5 / 16.0;
        double day = 0.5 + 2 * Mth.clamp(Mth.cos(level.getTimeOfDay(1f) * (float) (Math.PI * 2)), -0.25, 0.25);
        int ours = (int) ((1 - day * rain * thunder * StormShade.lightFactor(dark)) * 11);
        return Math.max(level.getSkyDarken(), ours);
    }

    /** Forgets the cached darkness (after clouds are changed by a command or a test). */
    public static synchronized void invalidate(ServerLevel level) {
        CACHES.remove(level);
    }

    private StormLight() {
    }
}
