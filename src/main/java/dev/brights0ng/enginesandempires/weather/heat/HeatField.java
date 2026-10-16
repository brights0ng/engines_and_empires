package dev.brights0ng.enginesandempires.weather.heat;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * The local heat and cold of the world's blocks, as the air that snow, ice and water see (weather phase 8e-3; Bright,
 * 2026-10-15): "how much warmer or colder than the outside air is it here, because of nearby fires, lava, ice...".
 *
 * <p>The sources are the blocks of the {@link HeatSourceData} data map. Each chunk's list of them is built lazily by
 * looking only at the chunk sections whose palette holds one, and rebuilt every {@link #TTL} ticks (fires start and
 * die, campfires are lit and put out). {@link #apply} adds up the sources within reach of a position, in the 3x3 chunks
 * around it, by {@link HeatMath}. Walls and roofs don't matter yet (8f's rooms will); ships' blocks are not looked at.
 * Server side, on the server thread.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class HeatField {

    public static final DataMapType<Block, HeatSourceData> DATA_MAP = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(Meteodyne.MODID, "heat_sources"), Registries.BLOCK,
            HeatSourceData.CODEC).build();

    /** Ticks a chunk's list of sources is kept. */
    public static final int TTL = 100;

    /** A heat or cold source block found in a chunk. */
    public record Source(int x, int y, int z, HeatSourceData.Variant variant) {
    }

    private record Entry(long tick, List<Source> sources) {
    }

    private static final Map<ServerLevel, Map<Long, Entry>> CACHE = new WeakHashMap<>();
    /** Block to its data (a shared empty value for blocks that are not sources). */
    private static final Map<Block, HeatSourceData> BLOCKS = new IdentityHashMap<>();
    private static final HeatSourceData NONE = new HeatSourceData(List.of());

    /** Registers the data map (mod bus; added in the mod class). */
    public static void register(RegisterDataMapTypesEvent event) {
        event.register(DATA_MAP);
    }

    @SubscribeEvent
    static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        BLOCKS.clear();
        CACHE.clear();
    }

    /** Forgets the sources found so far in {@code level} (after changing blocks, for the game tests). */
    public static void invalidate(ServerLevel level) {
        CACHE.remove(level);
    }

    @SuppressWarnings("deprecation")
    private static HeatSourceData data(Block block) {
        HeatSourceData d = BLOCKS.get(block);
        if (d == null) {
            d = block.builtInRegistryHolder().getData(DATA_MAP);
            if (d == null) {
                d = NONE;
            }
            BLOCKS.put(block, d);
        }
        return d;
    }

    /** What {@code state} is as a source, or null. */
    public static HeatSourceData.Variant variantOf(BlockState state) {
        HeatSourceData d = data(state.getBlock());
        return d == NONE ? null : d.find(state);
    }

    /**
     * The air temperature, C, at {@code pos} once the nearby sources have warmed or cooled {@code airC}. {@code airC}
     * itself where there are none.
     */
    public static double apply(ServerLevel level, BlockPos pos, double airC) {
        double px = pos.getX() + 0.5;
        double py = pos.getY() + 0.5;
        double pz = pos.getZ() + 0.5;
        Map<HeatSourceData.Variant, double[]> sums = null;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (Source s : sources(level, cx + dx, cz + dz)) {
                    double ddx = s.x + 0.5 - px;
                    double ddy = s.y + 0.5 - py;
                    double ddz = s.z + 0.5 - pz;
                    double d = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                    if (d >= s.variant.range()) {
                        continue;
                    }
                    if (sums == null) {
                        sums = new IdentityHashMap<>();
                    }
                    sums.computeIfAbsent(s.variant, k -> new double[1])[0] += HeatMath.fade(s.variant.change(),
                            s.variant.range(), d);
                }
            }
        }
        if (sums == null) {
            return airC;
        }
        List<HeatMath.Group> groups = new ArrayList<>(sums.size());
        for (Map.Entry<HeatSourceData.Variant, double[]> e : sums.entrySet()) {
            HeatSourceData.Variant v = e.getKey();
            groups.add(new HeatMath.Group(e.getValue()[0], v.maxEffect(), v.limit()));
        }
        return HeatMath.apply(airC, groups);
    }

    /** The heat or cold sources in chunk ({@code cx}, {@code cz}), as of the last {@link #TTL} ticks; none if it isn't loaded. */
    public static List<Source> sources(ServerLevel level, int cx, int cz) {
        Map<Long, Entry> cache = CACHE.computeIfAbsent(level, k -> new java.util.HashMap<>());
        long key = ChunkPos.asLong(cx, cz);
        long now = level.getGameTime();
        Entry e = cache.get(key);
        if (e != null && now >= e.tick && now - e.tick < TTL) {
            return e.sources;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            return List.of();
        }
        List<Source> found = scan(level, chunk);
        cache.put(key, new Entry(now, found));
        return found;
    }

    private static List<Source> scan(ServerLevel level, LevelChunk chunk) {
        List<Source> found = null;
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir() || !section.maybeHas(s -> data(s.getBlock()) != NONE)) {
                continue;
            }
            int baseY = level.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        HeatSourceData.Variant v = variantOf(section.getBlockState(x, y, z));
                        if (v != null) {
                            if (found == null) {
                                found = new ArrayList<>();
                            }
                            found.add(new Source(baseX + x, baseY + y, baseZ + z, v));
                        }
                    }
                }
            }
        }
        return found == null ? List.of() : found;
    }

    private HeatField() {
    }
}
