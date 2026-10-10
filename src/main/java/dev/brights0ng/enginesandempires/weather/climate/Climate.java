package dev.brights0ng.enginesandempires.weather.climate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;

/**
 * The climate baseline in the world (phase 1 of {@code claude/weather-backbone-plan.md}): gathers the regional field,
 * the biome underfoot, the season, the climate band, the time of day and the height, and puts them through
 * {@link Baseline}. Server side.
 *
 * <h2>Which biome counts</h2>
 * Always the <b>surface</b> biome: where the chunk is loaded, the biome at the top of the column; elsewhere the biome
 * source is asked at sea level, and if that lands in a cave biome (under a mountain) it asks higher up. Unloaded
 * columns are cached per chunk column. The lattice nodes of the regional field ({@link ClimateField}) are sampled the
 * same way.
 *
 * <h2>Where the climate comes from (2026-10-10)</h2>
 * The world's own temperature and humidity noise at the spot ({@link #noise}, the fields the world places biomes
 * with), turned into a climate by {@link NoiseClimate} and adjusted by the biome there ({@link #climateOf},
 * {@link BiomeAdjust}). Both the regional field's nodes and the biome underfoot are worked out this way, so the climate
 * changes smoothly across biome borders and always agrees with where the biomes are.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class Climate {

    /** Heights above sea level tried, in order, when looking for the surface biome without a loaded chunk. */
    private static final int[] PROBE_HEIGHTS = {0, 48, 112, 176};
    private static final int COLUMN_CACHE = 1 << 15;

    private static final Map<ServerLevel, ClimateField> FIELDS = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<Long, BiomeClimate>> COLUMNS = new WeakHashMap<>();
    private static final Map<ServerLevel, Map<Long, double[]>> NOISE = new WeakHashMap<>();

    /** The full baseline sample at (x, y, z). */
    public static Baseline.Sample sample(ServerLevel level, double x, double y, double z) {
        return sample(level, x, y, z, localClimate(level, x, z));
    }

    /** The baseline at (x, y, z) with the biome underfoot given (the map uses cheaper local samples). */
    public static Baseline.Sample sample(ServerLevel level, double x, double y, double z, BiomeClimate local) {
        return sample(level, x, y, z, local, field(level).regional(x, z));
    }

    /**
     * The baseline at (x, y, z) with the biome underfoot and the regional climate given (the map, zoomed far out, uses
     * raw lattice nodes rather than smoothing: its pixels are already coarser than the smoothing).
     */
    public static Baseline.Sample sample(ServerLevel level, double x, double y, double z, BiomeClimate local,
                                         ClimateField.Regional regional) {
        double season = ClimateCurves.season(SeasonSource.yearFraction(level));
        double diurnal = ClimateCurves.diurnal(level.getDayTime());
        double height = Temperature.heightCorrection(level.getSeaLevel(), y);
        var sim = dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim.of(level);
        double anomaly = sim == null ? 0 : sim.anomaly(x, z);
        return Baseline.combine(regional, local, WeatherConfig.localBiomeWeight(), season, bandOffset(z), anomaly,
                diurnal, height);
    }

    /**
     * The climate's normal at sea level over (x, z) for the atmosphere field: the day's normal temperature (season and
     * band, no day-night swing, no air masses), the humidity and the surface ordinal. The biome underfoot is the nearest
     * lattice node's (the field's cells are 512 blocks).
     */
    public static double[] normal(ServerLevel level, double x, double z) {
        ClimateField f = field(level);
        ClimateField.Regional regional = f.regional(x, z);
        BiomeClimate local = f.nearest(x, z);
        double season = ClimateCurves.season(SeasonSource.yearFraction(level));
        Baseline.Sample s = Baseline.combine(regional, local, WeatherConfig.localBiomeWeight(), season, bandOffset(z),
                0, 0, 0);
        return new double[]{s.temperature(), s.humidity(), local.surface().ordinal()};
    }

    /** The climate band's offset to the temperature at {@code z}, C (0 in biome mode). */
    public static double bandOffset(double z) {
        if (WeatherConfig.airMassSource() != WeatherConfig.AirMassSource.BANDS) {
            return 0;
        }
        return WeatherConfig.bandWeight()
                * ClimateCurves.band(z, WeatherConfig.bandPeriod(), WeatherConfig.bandAmplitude());
    }

    /** The regional field for {@code level}. */
    public static ClimateField field(ServerLevel level) {
        ClimateField f = FIELDS.get(level);
        if (f == null) {
            f = new ClimateField((ix, iz) -> {
                int x = ix * ClimateField.SPACING;
                int z = iz * ClimateField.SPACING;
                return climateAt(level, x, z, probeSurfaceBiome(level, x, z));
            });
            FIELDS.put(level, f);
        }
        return f;
    }

    /** The climate of the surface biome over (x, z): the world's noise there, adjusted by that biome. */
    public static BiomeClimate localClimate(ServerLevel level, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        BlockPos column = new BlockPos(bx, level.getSeaLevel(), bz);
        if (level.hasChunkAt(column)) {
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) - 1;
            return climateAt(level, x, z,
                    level.getBiome(new BlockPos(bx, Math.max(top, level.getMinBuildHeight()), bz)));
        }
        return columnClimate(level, bx >> 4, bz >> 4);
    }

    /** The surface biome's climate for chunk column ({@code cx}, {@code cz}), cached; for unloaded land and the map. */
    public static BiomeClimate columnClimate(ServerLevel level, int cx, int cz) {
        Map<Long, BiomeClimate> cache = COLUMNS.get(level);
        if (cache == null) {
            cache = lru();
            COLUMNS.put(level, cache);
        }
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        BiomeClimate c = cache.get(key);
        if (c == null) {
            int x = (cx << 4) + 8;
            int z = (cz << 4) + 8;
            c = climateAt(level, x, z, probeSurfaceBiome(level, x, z));
            cache.put(key, c);
        }
        return c;
    }

    /** The climate at (x, z) for biome {@code biome} standing there: the world's noise, adjusted by the biome. */
    public static BiomeClimate climateAt(ServerLevel level, double x, double z, Holder<Biome> biome) {
        double[] n = noise(level, x, z);
        return NoiseClimate.resolve(n[0], n[1], climateOf(biome));
    }

    /**
     * The world's temperature and humidity noise at (x, z), {temperature, humidity}, each -1 to 1: the fields the world
     * places biomes with (vanilla's multi-noise climate; Tectonic keeps them). Cached per 16 blocks.
     */
    public static double[] noise(ServerLevel level, double x, double z) {
        int qx = net.minecraft.core.QuartPos.fromBlock((int) Math.floor(x));
        int qz = net.minecraft.core.QuartPos.fromBlock((int) Math.floor(z));
        Map<Long, double[]> cache = NOISE.get(level);
        if (cache == null) {
            cache = lru();
            NOISE.put(level, cache);
        }
        long key = ((long) (qx >> 2) << 32) | ((qz >> 2) & 0xFFFFFFFFL);
        double[] n = cache.get(key);
        if (n == null) {
            net.minecraft.world.level.biome.Climate.TargetPoint p = level.getChunkSource().randomState().sampler()
                    .sample(qx, net.minecraft.core.QuartPos.fromBlock(level.getSeaLevel()), qz);
            n = new double[]{net.minecraft.world.level.biome.Climate.unquantizeCoord(p.temperature()),
                    net.minecraft.world.level.biome.Climate.unquantizeCoord(p.humidity())};
            cache.put(key, n);
        }
        return n;
    }

    /**
     * How a biome adjusts the climate: its data map entry, or (for biomes it leaves out, such as modded ones) no
     * adjustment, the ground from its tags, and never thawing if it is tagged icy. The ground is always filled in.
     */
    public static BiomeAdjust climateOf(Holder<Biome> biome) {
        BiomeAdjust a = biome.getData(ClimateDataMaps.BIOME_CLIMATE);
        if (a == null) {
            a = biome.is(Tags.Biomes.IS_ICY) ? BiomeAdjust.NONE.asFrozen() : BiomeAdjust.NONE;
        }
        return a.withSurface(surfaceOf(biome));
    }

    /** The surface biome over (x, z) from the biome source, stepping up out of cave biomes. */
    public static Holder<Biome> probeSurfaceBiome(ServerLevel level, int x, int z) {
        Holder<Biome> biome = null;
        for (int dy : PROBE_HEIGHTS) {
            biome = level.getBiome(new BlockPos(x, level.getSeaLevel() + dy, z));
            if (!isCave(biome)) {
                return biome;
            }
        }
        return biome;
    }

    private static boolean isCave(Holder<Biome> biome) {
        return biome.is(Tags.Biomes.IS_CAVE) || biome.is(Biomes.LUSH_CAVES) || biome.is(Biomes.DRIPSTONE_CAVES)
                || biome.is(Biomes.DEEP_DARK);
    }

    private static BiomeClimate.Surface surfaceOf(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
            return BiomeClimate.Surface.WATER;
        }
        if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_TAIGA)) {
            return BiomeClimate.Surface.FOREST;
        }
        return BiomeClimate.Surface.LAND;
    }

    /** Datapack reloads can change biome climates: start the caches again. */
    @SubscribeEvent
    static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        FIELDS.values().forEach(ClimateField::clear);
        COLUMNS.values().forEach(Map::clear);
    }

    private static <V> Map<Long, V> lru() {
        return new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                return size() > COLUMN_CACHE;
            }
        };
    }

    private Climate() {
    }
}
