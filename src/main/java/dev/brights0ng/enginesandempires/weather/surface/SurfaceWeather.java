package dev.brights0ng.enginesandempires.weather.surface;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.climate.BiomeAdjust;
import dev.brights0ng.enginesandempires.weather.climate.BiomeClimate;
import dev.brights0ng.enginesandempires.weather.climate.Climate;
import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import dev.brights0ng.enginesandempires.weather.ships.ShipCover;
import dev.brights0ng.enginesandempires.weather.wind.WindColumn;
import dev.brights0ng.enginesandempires.weather.wind.WindSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Snow and ice on the ground where the pack owns the weather (phase 5b of the weather backbone, 2026-10-08). Replaces
 * vanilla's precipitation tick ({@code ServerLevel.tickPrecipitation}, cancelled by {@code ServerLevelWeatherMixin}),
 * with the numbers in {@link SurfaceRules}.
 *
 * <h2>How it runs</h2>
 * Every ticking chunk, once a second (staggered by chunk), visits {@link #COLUMNS} random columns: about one visit per
 * column per in-game hour, the cost of vanilla's own tick. What falls is asked once per visit at the chunk's centre
 * ({@link LocalWeather}); the air temperature is the chunk's at sea level (cached {@link #CACHE_TICKS}) plus each
 * column's height cooling, held below freezing over always-frozen biomes.
 *
 * <h2>Per column</h2>
 * <ul>
 *   <li><b>Snow</b> settles where it is snowing (vanilla's light rule: not where block light is 10+), up to a depth set
 *       by shelter: the nearest full block downwind (the wind piles snow against it; any side in calm air) lets it
 *       drift deeper ({@link SurfaceRules#driftCap}); on leaves at most 2 layers, and the ground under leaves gets
 *       at most 1. It melts above 0 C, faster in rain and sun. Worldgen snow melts too, except in
 *       always-frozen biomes, whose air never thaws. (Snow blocks and powder snow are left alone.)</li>
 *   <li><b>Ice</b> (plain ice only: packed and blue ice never melt) thaws above 0 C where it sees the sky, through
 *       leaves, glass and anything else that doesn't occlude; under a roof it stays.</li>
 *   <li><b>Water</b> freezes: lakes and rivers in from their shores, right across in a hard freeze; the sea only near
 *       land.</li>
 *   <li><b>Cauldrons</b> (and any block that takes precipitation) fill as vanilla's do.</li>
 *   <li><b>Glaze</b> (phase 5c): freezing rain on freezing ground coats the top ({@link GlazeBlock}): bare ground, snow
 *       (snow never settles on glaze) and leaves, where a full coat breaks them now and then. It thaws as ice does. Not
 *       under the canopy.</li>
 *   <li><b>Hail</b> (phase 5c) knocks exposed crops ({@link SurfaceContent#HAIL_DAMAGEABLE}) back a growth stage.</li>
 * </ul>
 *
 * <h2>Ships (phase 5d)</h2>
 * Ships' own chunks (Sable's plot grid) are skipped here: {@code ShipWeather} visits their decks at their real place in
 * the world. Ground under a ship is sheltered by it (nothing settles there; it melts in the shade).
 *
 * <h2>Vanilla's biome temperature</h2>
 * {@link #vanillaTemperature} answers {@code Biome.getTemperature} (via {@code BiomeTemperatureMixin}) on the server
 * thread for Overworld biomes from this same temperature, so vanilla's cold checks (snow golems, frost, mods asking
 * {@code coldEnoughToSnow}) agree with the pack's weather. Worldgen (worker threads) and the client keep vanilla's.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class SurfaceWeather {

    /** Ticks between visits to a chunk. */
    public static final int PERIOD = 20;
    /** Columns visited per chunk per visit (256 columns / 5 per second â‰ˆ once per in-game hour each). */
    public static final int COLUMNS = 5;
    /** How long a chunk's sea-level temperature is kept, ticks. */
    public static final int CACHE_TICKS = 100;
    private static final int CACHE_SIZE = 4096;
    /** Cover below which the sun shines on the snow. */
    private static final double SUNNY_COVER = 0.3;
    /** How far down through non-occluding blocks (leaves, glass) ice is looked for. */
    private static final int ICE_SCAN = 16;

    /**
     * What one column is going through this visit.
     *
     * @param t        air temperature at the column's surface, C
     * @param falling  what is falling there (null: nothing)
     * @param strength how hard, 0-1
     * @param sunny    whether the sun is on it (day, little cloud)
     * @param windX    the surface wind, m/s
     * @param windZ    the surface wind, m/s
     */
    public record Column(double t, Precip falling, double strength, boolean sunny, double windX, double windZ) {

        boolean snowing() {
            return falling == Precip.SNOW;
        }

        /** Whether it rains on it (rain, mixed or freezing rain; not snow, sleet or hail). */
        boolean wet() {
            return falling != null && !falling.frozen;
        }
    }

    private record Cached(long tick, double t) {
    }

    private static final Map<ServerLevel, Map<Long, Cached>> SEA_LEVEL = new WeakHashMap<>();

    /** Called at the end of vanilla's chunk tick. */
    public static void tickChunk(ServerLevel level, LevelChunk chunk) {
        if (!WeatherOwnership.owns(level)) {
            return;
        }
        ChunkPos cp = chunk.getPos();
        if (Math.floorMod(level.getGameTime() + stagger(cp), PERIOD) != 0) {
            return;
        }
        if (ShipCover.inPlot(level, cp)) {
            // A ship's blocks: ShipWeather does these where the ship really is.
            return;
        }
        double tSea = seaLevelTemperature(level, cp.x, cp.z);
        if (!Double.isFinite(tSea)) {
            return;
        }
        int cx = cp.getMiddleBlockX();
        int cz = cp.getMiddleBlockZ();
        int cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz);
        LocalWeather.Here here = LocalWeather.at(level, cx + 0.5, cy, cz + 0.5);
        WindColumn wind = WindSources.column(level, cx + 0.5, cz + 0.5);
        double windX = wind == null ? 0 : wind.surfaceX();
        double windZ = wind == null ? 0 : wind.surfaceZ();
        boolean sunny = level.isDay() && here.cover() < SUNNY_COVER;
        RandomSource random = level.random;
        int seaLevel = level.getSeaLevel();
        ShipCover.Area ships = ShipCover.area(level, cp.getMinBlockX(), cp.getMinBlockZ(), cp.getMaxBlockX() + 1,
                cp.getMaxBlockZ() + 1);
        for (int i = 0; i < COLUMNS; i++) {
            int x = cp.getMinBlockX() + random.nextInt(16);
            int z = cp.getMinBlockZ() + random.nextInt(16);
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
            if (!level.isAreaLoaded(top.below(), 1)) {
                continue;
            }
            BlockPos underCanopy = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(x, 0, z));
            Holder<Biome> biome = level.getBiome(top);
            double t = columnTemperature(tSea, seaLevel, top.getY(), Climate.climateOf(biome));
            Precip falling = null;
            // Under a ship: sheltered (and shaded).
            boolean underShip = !ships.isEmpty() && ships.top(x, z) > top.getY();
            if (here.falling() && !underShip) {
                falling = here.precip();
                if (LocalWeather.forced == null
                        && (falling == Precip.RAIN || falling == Precip.MIXED || falling == Precip.SNOW)) {
                    // Rain, mixed or snow by this column's own air (no warm layer aloft: that is the chunk's).
                    falling = Precip.decide(t, 0, 0);
                }
            }
            visit(level, top, underCanopy, new Column(t, falling, here.strength(), sunny && !underShip, windX, windZ),
                    random, true);
        }
    }

    /** The air temperature at height {@code y} over a column whose chunk is {@code tSea} at sea level, C. */
    public static double columnTemperature(double tSea, int seaLevel, int y, BiomeAdjust climate) {
        double t = tSea + Temperature.heightCorrection(seaLevel, y);
        return climate.frozen() ? Math.min(t, BiomeClimate.FROZEN_MAX) : t;
    }

    /**
     * One visit to the column whose first free block (above the motion-blocking surface) is {@code top}: snow, ice,
     * water and cauldrons, without a canopy. Public for the game tests.
     */
    public static void visit(ServerLevel level, BlockPos top, Column c, RandomSource random) {
        visit(level, top, top, c, random, true);
    }

    /** {@link #visit(ServerLevel, BlockPos, BlockPos, Column, RandomSource, boolean)} on the ground. */
    public static void visit(ServerLevel level, BlockPos top, BlockPos underCanopy, Column c, RandomSource random) {
        visit(level, top, underCanopy, c, random, true);
    }

    /**
     * One visit to a column: {@code top} is its first free block above the motion-blocking surface (on top of any
     * leaves), {@code underCanopy} the same ignoring leaves (the ground under a tree; {@code top} where there are no
     * leaves). {@code waterAndIce}: whether water freezes and ice thaws here (not on ships: Bright, 2026-10-08).
     */
    public static void visit(ServerLevel level, BlockPos top, BlockPos underCanopy, Column c, RandomSource random,
                             boolean waterAndIce) {
        BlockPos ground = top.below();
        BlockState atTop = level.getBlockState(top);
        BlockState below = level.getBlockState(ground);

        // Snow: settle, or melt. On leaves at most 2 layers; under them (what falls through the canopy) at most 1.
        snow(level, top, atTop, below.is(BlockTags.LEAVES) ? SurfaceRules.ON_LEAVES : 0, c, random);
        if (underCanopy.getY() < top.getY()) {
            snow(level, underCanopy, level.getBlockState(underCanopy), SurfaceRules.UNDER_LEAVES, c, random);
        }

        // Glaze: freezing rain on freezing ground coats the top; it thaws as ice does.
        glaze(level, top, atTop, c, random);

        // Hail: knocks exposed crops back a stage.
        if (c.falling() == Precip.HAIL && atTop.is(SurfaceContent.HAIL_DAMAGEABLE)
                && random.nextDouble() < SurfaceRules.HAIL_CROP) {
            BlockState hit = knockedBack(atTop);
            if (hit != null) {
                level.setBlockAndUpdate(top, hit);
            }
        }

        // Ice: thaw where it sees the sky.
        if (!waterAndIce) {
            // (Ships: no freezing or thawing.)
        } else if (c.t() > 0) {
            BlockPos ice = exposedIce(level, ground);
            if (ice != null && random.nextDouble() < SurfaceRules.thawChance(c.t(), c.wet())) {
                level.setBlockAndUpdate(ice, IceBlock.meltsInto());
                level.neighborChanged(ice, IceBlock.meltsInto().getBlock(), ice);
            }
        } else if (atTop.isAir()) {
            // Water: freeze.
            freeze(level, ground, below, c, random);
        }

        // Cauldrons and the like.
        if (c.falling() != null && random.nextDouble() < SurfaceRules.CAULDRON_SHARE) {
            Biome.Precipitation p = c.snowing() ? Biome.Precipitation.SNOW
                    : c.wet() ? Biome.Precipitation.RAIN : Biome.Precipitation.NONE;
            if (p != Biome.Precipitation.NONE) {
                below.getBlock().handlePrecipitation(below, level, ground, p);
            }
        }
    }

    /** Snow at {@code pos}: settles if snowing (up to {@code fixedCap} layers; 0: by shelter), melts otherwise. */
    private static void snow(ServerLevel level, BlockPos pos, BlockState state, int fixedCap, Column c,
                             RandomSource random) {
        if (c.snowing()) {
            settleSnow(level, pos, state, fixedCap, c, random);
        } else if (state.is(Blocks.SNOW)) {
            meltSnow(level, pos, state, c, random);
        }
    }

    private static void settleSnow(ServerLevel level, BlockPos top, BlockState atTop, int fixedCap, Column c,
                                   RandomSource random) {
        if (level.getGameRules().getInt(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT) <= 0) {
            return;
        }
        int layers = atTop.is(Blocks.SNOW) ? atTop.getValue(SnowLayerBlock.LAYERS) : 0;
        if (layers == 0 && !atTop.isAir()) {
            return;
        }
        if (top.getY() < level.getMinBuildHeight() || top.getY() >= level.getMaxBuildHeight()
                || level.getBrightness(LightLayer.BLOCK, top) >= 10
                || !Blocks.SNOW.defaultBlockState().canSurvive(level, top)) {
            return;
        }
        if (random.nextDouble() >= SurfaceRules.snowChance(c.strength(), c.t())) {
            return;
        }
        int cap = fixedCap > 0 ? fixedCap : SurfaceRules.driftCap(shelter(level, top, c.windX(), c.windZ()));
        if (layers >= cap) {
            return;
        }
        if (layers == 0) {
            level.setBlockAndUpdate(top, Blocks.SNOW.defaultBlockState());
        } else {
            BlockState next = atTop.setValue(SnowLayerBlock.LAYERS, layers + 1);
            Block.pushEntitiesUp(atTop, next, level, top);
            level.setBlockAndUpdate(top, next);
        }
    }

    private static void meltSnow(ServerLevel level, BlockPos top, BlockState snow, Column c, RandomSource random) {
        int n = SurfaceRules.layersThisVisit(SurfaceRules.meltRate(c.t(), c.wet(), c.sunny()), random.nextDouble());
        if (n <= 0) {
            return;
        }
        int layers = snow.getValue(SnowLayerBlock.LAYERS) - n;
        level.setBlockAndUpdate(top, layers <= 0 ? Blocks.AIR.defaultBlockState()
                : snow.setValue(SnowLayerBlock.LAYERS, layers));
    }

    /** Glaze at the top of a column: thaws above freezing, builds up under freezing rain below it. */
    private static void glaze(ServerLevel level, BlockPos top, BlockState atTop, Column c, RandomSource random) {
        boolean glazed = atTop.getBlock() instanceof GlazeBlock;
        if (glazed && c.t() > 0) {
            if (random.nextDouble() < SurfaceRules.thawChance(c.t(), c.wet())) {
                GlazeBlock.melt(level, top, atTop);
            }
            return;
        }
        if (c.falling() != Precip.FREEZING_RAIN || top.getY() < level.getMinBuildHeight()
                || top.getY() >= level.getMaxBuildHeight() - 1) {
            return;
        }
        if (random.nextDouble() >= SurfaceRules.glazeChance(c.strength(), c.t())) {
            return;
        }
        accrete(level, top, atTop, random);
    }

    /**
     * One more layer of glaze at {@code top}: a new sheet on bare ground or leaves, a coat on snow (on top of it, if the
     * snow is a full block deep), a second layer on the first; on leaves already fully glazed, the leaves (and the
     * glaze) break {@link SurfaceRules#LEAF_BREAK} of the time. Public for the game tests.
     */
    public static void accrete(ServerLevel level, BlockPos top, BlockState atTop, RandomSource random) {
        BlockState glaze = SurfaceContent.GLAZE.get().defaultBlockState();
        if (atTop.getBlock() instanceof GlazeBlock) {
            int layers = atTop.getValue(GlazeBlock.LAYERS);
            if (layers < GlazeBlock.MAX) {
                if (light(level, top)) {
                    level.setBlockAndUpdate(top, atTop.setValue(GlazeBlock.LAYERS, layers + 1));
                }
            } else if (atTop.getValue(GlazeBlock.SNOW) == 0 && level.getBlockState(top.below()).is(BlockTags.LEAVES)
                    && random.nextDouble() < SurfaceRules.LEAF_BREAK) {
                level.removeBlock(top, false);
                level.destroyBlock(top.below(), true);
            }
            return;
        }
        if (atTop.is(Blocks.SNOW)) {
            int snow = atTop.getValue(SnowLayerBlock.LAYERS);
            if (snow < 8) {
                if (light(level, top)) {
                    BlockState next = glaze.setValue(GlazeBlock.SNOW, snow);
                    Block.pushEntitiesUp(atTop, next, level, top);
                    level.setBlockAndUpdate(top, next);
                }
                return;
            }
            // A full block of snow: the glaze lies on top of it.
            top = top.above();
            atTop = level.getBlockState(top);
            if (atTop.getBlock() instanceof GlazeBlock) {
                accrete(level, top, atTop, random);
                return;
            }
        }
        if (atTop.isAir() && light(level, top) && glaze.canSurvive(level, top)) {
            level.setBlockAndUpdate(top, glaze);
        }
    }

    /** Whether the block light at {@code pos} lets glaze form (under {@link SurfaceRules#GLAZE_LIGHT}). */
    private static boolean light(ServerLevel level, BlockPos pos) {
        return level.getBrightness(LightLayer.BLOCK, pos) < SurfaceRules.GLAZE_LIGHT;
    }

    /**
     * {@code crop} one growth stage back (its {@code age} property, which vanilla, Farmer's Delight and MineColonies
     * crops all use), or null if it has none or is already at its first.
     */
    public static BlockState knockedBack(BlockState crop) {
        for (Property<?> p : crop.getProperties()) {
            if (p instanceof IntegerProperty age && "age".equals(p.getName())) {
                int now = crop.getValue(age);
                int min = age.getPossibleValues().stream().mapToInt(Integer::intValue).min().orElse(0);
                return now > min ? crop.setValue(age, now - 1) : null;
            }
        }
        return null;
    }

    /**
     * How far (blocks) the nearest full block is on the downwind side of {@code pos} at its height (the wind blows the
     * snow against it: Bright, 2026-10-08), up to {@link SurfaceRules#DRIFT_REACH}; in calm air on any side; 0 if none.
     */
    public static int shelter(ServerLevel level, BlockPos pos, double windX, double windZ) {
        double speed = Math.hypot(windX, windZ);
        for (int d = 1; d <= SurfaceRules.DRIFT_REACH; d++) {
            if (speed < SurfaceRules.CALM) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    if (shelters(level, pos.relative(dir, d))) {
                        return d;
                    }
                }
            } else {
                // Downwind: where the wind goes.
                int dx = (int) Math.round(windX / speed * d);
                int dz = (int) Math.round(windZ / speed * d);
                if (shelters(level, pos.offset(dx, 0, dz))) {
                    return d;
                }
            }
        }
        return 0;
    }

    /**
     * A full block. (Not drifted snow: a full drift sheltering the next would chain drifts downwind without end; and
     * 8 layers of snow are not a full collision shape anyway.)
     */
    private static boolean shelters(ServerLevel level, BlockPos p) {
        if (!level.hasChunkAt(p)) {
            return false;
        }
        return level.getBlockState(p).isCollisionShapeFullBlock(level, p);
    }

    /** Plain ice at or below {@code from} that sees the sky through nothing that occludes, or null. */
    public static BlockPos exposedIce(ServerLevel level, BlockPos from) {
        BlockPos.MutableBlockPos p = from.mutable();
        for (int i = 0; i < ICE_SCAN && p.getY() >= level.getMinBuildHeight(); i++) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.ICE)) {
                return p.immutable();
            }
            if (s.canOcclude() || !s.getFluidState().isEmpty()) {
                return null;
            }
            p.move(Direction.DOWN);
        }
        return null;
    }

    private static void freeze(ServerLevel level, BlockPos water, BlockState state, Column c, RandomSource random) {
        FluidState f = state.getFluidState();
        if (!state.is(Blocks.WATER) || f.getType() != Fluids.WATER || !f.isSource()
                || level.getBrightness(LightLayer.BLOCK, water) >= 10) {
            return;
        }
        boolean edge = false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = water.relative(dir);
            if (level.hasChunkAt(n) && !level.getFluidState(n).is(Fluids.WATER)) {
                edge = true;
                break;
            }
        }
        double chance;
        if (level.getBiome(water).is(BiomeTags.IS_OCEAN)) {
            chance = c.t() < SurfaceRules.SEA_FREEZE && edge ? SurfaceRules.seaFreezeChance(c.t(), true,
                    nearLand(level, water)) : 0;
        } else {
            chance = SurfaceRules.freshFreezeChance(c.t(), edge);
        }
        if (chance > 0 && random.nextDouble() < chance) {
            level.setBlockAndUpdate(water, Blocks.ICE.defaultBlockState());
        }
    }

    /** Whether land (not water, not ice) is within {@link SurfaceRules#SEA_ICE_REACH} blocks along a straight line. */
    private static boolean nearLand(ServerLevel level, BlockPos water) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int d = 1; d <= SurfaceRules.SEA_ICE_REACH; d++) {
                BlockPos p = water.relative(dir, d);
                if (!level.hasChunkAt(p)) {
                    break;
                }
                BlockState s = level.getBlockState(p);
                if (s.getFluidState().is(Fluids.WATER) || s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE)
                        || s.is(Blocks.BLUE_ICE) || s.is(Blocks.FROSTED_ICE)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** The air temperature at sea level over chunk ({@code cx}, {@code cz})'s centre, C, cached. */
    public static double seaLevelTemperature(ServerLevel level, int cx, int cz) {
        Map<Long, Cached> cache = SEA_LEVEL.computeIfAbsent(level, k -> lru());
        long key = ChunkPos.asLong(cx, cz);
        long now = level.getGameTime();
        Cached c = cache.get(key);
        if (c == null || now - c.tick() >= CACHE_TICKS || now < c.tick()) {
            c = new Cached(now, Temperature.atSeaLevel(level, (cx << 4) + 8, (cz << 4) + 8));
            cache.put(key, c);
        }
        return c.t();
    }

    private static int stagger(ChunkPos cp) {
        int h = cp.x * 0x45D9F3B ^ cp.z * 0x119DE1F3;
        return (h ^ (h >>> 16)) & 0x7FFFFFFF;
    }

    // ---- Vanilla's biome temperature ----

    private static WeakReference<MinecraftServer> biomesFor = new WeakReference<>(null);
    private static Map<Biome, BiomeAdjust> overworldBiomes = new IdentityHashMap<>();
    private static boolean answering;

    /**
     * {@code Biome.getTemperature(pos)} for {@code biome}, in vanilla's units, from the pack's temperature: on the
     * server thread, for Overworld biomes, where the pack owns the weather. Null to leave it to vanilla.
     */
    public static Float vanillaTemperature(Biome biome, BlockPos pos) {
        if (answering) {
            return null;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) {
            return null;
        }
        ServerLevel level = server.overworld();
        if (level == null || !WeatherOwnership.owns(level)) {
            return null;
        }
        if (biomesFor.get() != server) {
            Map<Biome, BiomeAdjust> map = new IdentityHashMap<>();
            for (Holder<Biome> h : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
                map.put(h.value(), Climate.climateOf(h));
            }
            overworldBiomes = map;
            biomesFor = new WeakReference<>(server);
        }
        answering = true;
        try {
            BlockPos at = pos;
            BiomeAdjust climate;
            if (ShipCover.inPlot(level, pos)) {
                // A ship's block: the air where the ship really is (phase 5d).
                net.minecraft.world.phys.Vec3 w = ShipCover.worldCentre(level, pos);
                if (w == null) {
                    return null;
                }
                at = BlockPos.containing(w);
                climate = Climate.climateOf(level.getBiome(at));
            } else {
                climate = overworldBiomes.get(biome);
                if (climate == null) {
                    return null;
                }
            }
            double tSea = seaLevelTemperature(level, at.getX() >> 4, at.getZ() >> 4);
            if (!Double.isFinite(tSea)) {
                return null;
            }
            return SurfaceRules.vanillaTemperature(columnTemperature(tSea, level.getSeaLevel(), at.getY(), climate));
        } finally {
            answering = false;
        }
    }

    /** The pack's air temperature at {@code pos} as this class sees it (for tests and the debug command), C. */
    public static double temperatureAt(ServerLevel level, BlockPos pos) {
        double tSea = seaLevelTemperature(level, pos.getX() >> 4, pos.getZ() >> 4);
        return columnTemperature(tSea, level.getSeaLevel(), pos.getY(), Climate.climateOf(level.getBiome(pos)));
    }

    @SubscribeEvent
    static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        biomesFor = new WeakReference<>(null);
    }

    private static Map<Long, Cached> lru() {
        return new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Cached> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    private SurfaceWeather() {
    }
}
