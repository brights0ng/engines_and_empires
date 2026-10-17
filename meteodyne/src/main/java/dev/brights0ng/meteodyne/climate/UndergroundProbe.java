package dev.brights0ng.meteodyne.climate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import dev.brights0ng.meteodyne.rain.WeatherConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Gathers {@link Underground}'s inputs from the world (weather phase 8c): the cover overhead, the sky light, the
 * rock texture's noise and the cave biome at a position. Server thread only. Overworld only: elsewhere every position
 * reads as open air.
 *
 * <h2>Cover</h2>
 * The ground above is the <b>lower</b> of the heightmap ({@code MOTION_BLOCKING_NO_LEAVES}, which counts water) and
 * the world generator's own terrain ({@code WORLD_SURFACE_WG}, also counting water), so:
 * <ul>
 *   <li>builds add no cover (the generator never saw them),</li>
 *   <li>open pits and quarries are open air (the heightmap sits on their floor),</li>
 *   <li>tunnels under mountains read as covered (both agree).</li>
 * </ul>
 * The generator's height is a noise evaluation, so it is cached per column. Where the chunk isn't loaded, the
 * generator's height alone and no sky light.
 *
 * <p>Callers pass the position of the air the temperature is wanted for (a player's feet, a chest's block): a
 * position inside a solid block reads sky light 0.
 */
public final class UndergroundProbe {

    private static final int CACHE = 1 << 14;
    /** Mixed into the world seed so the rock texture is its own pattern. */
    private static final long NOISE_SALT = 0x8C_7E3A_11D5L;
    private static final Map<ServerLevel, Map<Long, Integer>> TERRAIN = new WeakHashMap<>();
    private static final Map<ServerLevel, SimplexNoise> NOISE = new WeakHashMap<>();

    /** The temperature at {@code pos} given the outside air's {@code outside} there, with every part. */
    public static Underground.Reading read(ServerLevel level, BlockPos pos, double outside) {
        if (level.dimension() != Level.OVERWORLD) {
            return Underground.Reading.open(0, outside, 15);
        }
        return read(level, pos, outside, groundAbove(level, pos));
    }

    /**
     * The relative humidity (0-1) at {@code pos} given the outside air's {@code outside} there (weather phase 8c,
     * humidity added 2026-10-15): the same cover and sky light as the temperature, but without its noise and annual
     * mean, so it is cheap. The outside value in the open and in other dimensions.
     */
    public static double humidity(ServerLevel level, BlockPos pos, double outside) {
        if (level.dimension() != Level.OVERWORLD) {
            return outside;
        }
        return humidity(level, pos, outside, groundAbove(level, pos));
    }

    /** As {@link #humidity(ServerLevel, BlockPos, double)} with the ground above given (the game tests). */
    public static double humidity(ServerLevel level, BlockPos pos, double outside, int ground) {
        Underground.Settings s = WeatherConfig.underground();
        double depth = ground - pos.getY();
        int sky = level.hasChunkAt(pos) ? level.getBrightness(LightLayer.SKY, pos) : 0;
        double insulation = Underground.insulation(depth, sky, s);
        if (insulation <= 0) {
            return outside;
        }
        return Underground.humidity(outside, insulation, caveAt(level, pos, s), s);
    }

    /** The ground above {@code pos}: the lower of the heightmap and the world generator's terrain. */
    private static int groundAbove(ServerLevel level, BlockPos pos) {
        int ground = terrainHeight(level, pos.getX(), pos.getZ());
        if (level.hasChunkAt(pos)) {
            ground = Math.min(ground, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()));
        }
        return ground;
    }

    /**
     * As {@link #read(ServerLevel, BlockPos, double)} with the ground above given (the game tests: a superflat world
     * has no real depth to test with).
     */
    public static Underground.Reading read(ServerLevel level, BlockPos pos, double outside, int ground) {
        Underground.Settings s = WeatherConfig.underground();
        double depth = ground - pos.getY();
        int sky = level.hasChunkAt(pos) ? level.getBrightness(LightLayer.SKY, pos) : 0;
        if (Underground.insulation(depth, sky, s) <= 0) {
            return Underground.Reading.open(depth, outside, sky);
        }
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        // The surface's mean only counts above the band's bottom.
        double annual = Underground.pull(y, s) >= 1 ? 0 : Climate.annualMean(level, x, ground, z);
        return Underground.read(outside, annual, depth, sky, y, level.getMinBuildHeight(), noise(level, x, y, z, s),
                caveAt(level, pos, s), s);
    }

    /** The rock texture's raw noise at (x, y, z), -1 to 1. */
    public static double noise(ServerLevel level, double x, double y, double z, Underground.Settings s) {
        if (s.noiseAmp() <= 0) {
            return 0;
        }
        SimplexNoise n = NOISE.computeIfAbsent(level,
                l -> new SimplexNoise(RandomSource.create(l.getSeed() ^ NOISE_SALT)));
        double scale = Math.max(1, s.noiseScale());
        // Simplex features span about two units; a scale of ~50 blocks per feature.
        return n.getValue(2 * x / scale, 2 * y / scale, 2 * z / scale);
    }

    /** The world generator's terrain height over (x, z) (the first free block above it, water counted), cached. */
    public static int terrainHeight(ServerLevel level, int x, int z) {
        Map<Long, Integer> cache = TERRAIN.computeIfAbsent(level, k -> lru());
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        Integer h = cache.get(key);
        if (h == null) {
            var source = level.getChunkSource();
            h = source.getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                    LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight()), source.randomState());
            cache.put(key, h);
        }
        return h;
    }

    /** The cave biome rule at {@code pos}, or null. */
    public static Underground.CaveRule caveAt(ServerLevel level, BlockPos pos, Underground.Settings s) {
        if (s.caves().isEmpty()) {
            return null;
        }
        return level.getBiome(pos).unwrapKey().map(k -> s.caves().get(k.location().toString())).orElse(null);
    }

    private static Map<Long, Integer> lru() {
        return new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
                return size() > CACHE;
            }
        };
    }

    private UndergroundProbe() {
    }
}
