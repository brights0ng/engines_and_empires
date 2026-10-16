package dev.brights0ng.meteodyne.heat.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.climate.Temperature;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.heat.air.AirGrid;
import dev.brights0ng.paradynelib.heat.air.AirParams;
import dev.brights0ng.paradynelib.heat.air.HeatClusters;
import dev.brights0ng.paradynelib.ships.ShipCover;
import dev.brights0ng.paradynelib.wind.Shelter;
import dev.brights0ng.paradynelib.wind.ShelterProbe;
import dev.brights0ng.paradynelib.wind.WindBlend;
import dev.brights0ng.paradynelib.wind.WindColumn;
import dev.brights0ng.paradynelib.wind.WindConfig;
import dev.brights0ng.paradynelib.wind.WindParams;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.joml.Vector3d;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The heat simulation in the Overworld (weather phase 8f-2): which blocks are sources, the regions around them, the
 * budgeted thread that steps them, and what is saved with the chunks. See {@code claude/weather-backbone-phase8f.md}.
 *
 * <ul>
 *   <li><b>Placed sources</b> (campfires, furnaces, burners...) are indexed per loaded chunk, from a scan when the
 *       chunk loads and from every block change after ({@code LevelChunkHeatMixin}).</li>
 *   <li><b>Natural sources</b> (lava, magma, fire) are looked for only near players and ships, and inside the boxes
 *       placed sources make (Bright's decisions 11 and 13).</li>
 *   <li><b>Plan</b> (once a second): the sources are grouped into regions ({@link HeatClusters}); a region whose box
 *       is unchanged carries on; a new one starts from the fields it replaces, or from what was saved; regions made of
 *       natural sources alone start at their steady state and are dropped when nobody is near; regions of placed
 *       sources whose sources went out cool down until nothing is left, then go.</li>
 *   <li><b>Steps</b> run on worker threads under a CPU budget ({@code [heat] budgetCores}); each advances its region
 *       by the game time since its last step: every second near a player or ship, every ten seconds otherwise.</li>
 *   <li><b>Saving</b>: every 30 s, when a chunk unloads and when the server stops, each saved region's offsets go into
 *       its chunks ({@link HeatChunkData}).</li>
 * </ul>
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class HeatWorld {

    /** Ticks between plans. */
    static final int PLAN_INTERVAL = 20;
    /** Ticks between saves. */
    static final int PERSIST_INTERVAL = 600;
    /** Ticks a chunk's natural sources are kept before they are looked for again. */
    static final int NATURAL_TTL = 100;
    /** Simulated seconds a region of natural sources is run for to reach its steady state at the start. */
    static final double SETTLE_SECONDS = 1500;
    /** The longest a single job advances a region, s (a region left long behind catches up only this much). */
    static final double MAX_CATCH_UP = 120;
    /** Blocks around a region within which a player or ship counts as near it. */
    static final int NEAR = 16;
    /** A source-less region whose field is nowhere stronger than this, C, is dropped. */
    static final double FADED = 0.2;

    private static final Map<ServerLevel, HeatWorld> WORLDS = new WeakHashMap<>();
    private static ExecutorService pool;
    private static int poolThreads;

    /** The simulation of {@code level} (made on first use), or null if it isn't the Overworld. */
    public static HeatWorld of(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) {
            return null;
        }
        return WORLDS.computeIfAbsent(level, HeatWorld::new);
    }

    /** One source found in the world. */
    record Found(int x, int y, int z, BlockState state, HeatPowerData.Variant variant) {
    }

    private record Naturals(long tick, List<Found> found) {
    }

    private record Job(HeatRegion region, HeatRegion.Geometry geometry, float[] initial, boolean settle,
                       List<AirGrid.Source> sources, double windX, double windZ, byte[] cover, double roofWeight,
                       double seconds, long tick) {
    }

    private record Done(HeatRegion region, HeatRegion.Published published, long nanos) {
    }

    final ServerLevel level;
    final AirParams params = AirParams.DEFAULT;
    /** Placed sources per chunk: position ({@code BlockPos.asLong}) to state. */
    private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<BlockState>> placed = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Naturals> naturals = new Long2ObjectOpenHashMap<>();
    /** Loaded chunks holding saved region data. */
    private final LongOpenHashSet savedChunks = new LongOpenHashSet();
    private List<HeatRegion> regions = new ArrayList<>();
    private final ConcurrentLinkedQueue<Done> done = new ConcurrentLinkedQueue<>();
    private int nextId = 1;
    private double budgetMs;
    private long lastPersist;
    private List<Vec3> extraAnchors = List.of();
    /** While set, the live tick leaves the regions alone (game tests drive them with {@link #runNow}). */
    private boolean paused;
    /** While set, only sources (and saved rooms) inside this box are planned (game tests, so neighbours don't mix). */
    private int[] testArea;
    /** CPU milliseconds the jobs finished in the last plan interval took (for the debug command). */
    private double usedMs;
    private double usedMsLast;

    private HeatWorld(ServerLevel level) {
        this.level = level;
    }

    // ---- events ----

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)
                || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (level.getServer().isSameThread()) {
            of(level).onChunkLoad(chunk);
        } else {
            level.getServer().execute(() -> {
                if (level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) == chunk) {
                    of(level).onChunkLoad(chunk);
                }
            });
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)
                || !level.getServer().isSameThread()) {
            return;
        }
        HeatWorld world = WORLDS.get(level);
        if (world != null) {
            world.onChunkUnload(chunk);
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            of(level).tick();
        }
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        for (HeatWorld world : WORLDS.values()) {
            world.persistAll();
        }
        WORLDS.clear();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    /** A block changed in a loaded chunk (from {@code LevelChunkHeatMixin}; any thread). */
    public static void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState before, BlockState after) {
        if (!(chunk.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (level.getServer().isSameThread()) {
            HeatWorld world = WORLDS.get(level);
            if (world != null) {
                world.blockChanged(pos, after);
            }
        } else {
            BlockPos at = pos.immutable();
            level.getServer().execute(() -> {
                HeatWorld world = WORLDS.get(level);
                if (world != null) {
                    world.blockChanged(at, level.getBlockState(at));
                }
            });
        }
    }

    // ---- sources ----

    void onChunkLoad(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        Long2ObjectOpenHashMap<BlockState> found = scanPlaced(chunk);
        if (found.isEmpty()) {
            placed.remove(key);
        } else {
            placed.put(key, found);
        }
        naturals.remove(key);
        if (chunk.hasData(HeatContent.CHUNK_DATA) && !chunk.getData(HeatContent.CHUNK_DATA).boxes().isEmpty()) {
            savedChunks.add(key);
        }
        touchChunk(chunk.getPos());
    }

    void onChunkUnload(LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        int[] chunkBox = {cp.getMinBlockX(), level.getMinBuildHeight(), cp.getMinBlockZ(), cp.getMaxBlockX(),
                level.getMaxBuildHeight() - 1, cp.getMaxBlockZ()};
        for (HeatRegion r : regions) {
            if (r.overlaps(chunkBox)) {
                persist(r);
                r.geometryDirty = true;
            }
        }
        long key = cp.toLong();
        placed.remove(key);
        naturals.remove(key);
        savedChunks.remove(key);
    }

    private void blockChanged(BlockPos pos, BlockState after) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        boolean isPlaced = placedCandidate(after);
        Long2ObjectOpenHashMap<BlockState> map = placed.get(key);
        if (isPlaced) {
            if (map == null) {
                map = new Long2ObjectOpenHashMap<>();
                placed.put(key, map);
            }
            map.put(pos.asLong(), after);
        } else if (map != null) {
            map.remove(pos.asLong());
            if (map.isEmpty()) {
                placed.remove(key);
            }
        }
        naturals.remove(key);
        for (HeatRegion r : regions) {
            if (r.contains(pos.getX(), pos.getY(), pos.getZ())) {
                r.geometryDirty = true;
            }
        }
    }

    private void touchChunk(ChunkPos cp) {
        int[] chunkBox = {cp.getMinBlockX(), level.getMinBuildHeight(), cp.getMinBlockZ(), cp.getMaxBlockX(),
                level.getMaxBuildHeight() - 1, cp.getMaxBlockZ()};
        for (HeatRegion r : regions) {
            if (r.overlaps(chunkBox)) {
                r.geometryDirty = true;
            }
        }
    }

    private static Long2ObjectOpenHashMap<BlockState> scanPlaced(LevelChunk chunk) {
        Long2ObjectOpenHashMap<BlockState> out = new Long2ObjectOpenHashMap<>();
        for (Found f : scan(chunk, false)) {
            out.put(BlockPos.asLong(f.x, f.y, f.z), f.state);
        }
        return out;
    }

    /** The sources in {@code chunk} that heat the air: placed ones, or natural ones. */
    private static List<Found> scan(LevelChunk chunk, boolean natural) {
        List<Found> out = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir() || !section.maybeHas(s -> natural ? wanted(HeatTables.power(s), true)
                    : placedCandidate(s))) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        HeatPowerData.Variant v = HeatTables.power(state);
                        if (natural ? wanted(v, true) : placedCandidate(state)) {
                            out.add(new Found(baseX + x, baseY + y, baseZ + z, state, v));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean wanted(HeatPowerData.Variant v, boolean natural) {
        return v != null && v.inAir() && v.natural() == natural;
    }

    /** Whether a block in this state is indexed as a placed source: one now, or one its block entity may switch on. */
    private static boolean placedCandidate(BlockState state) {
        return wanted(HeatTables.power(state), false) || !HeatTables.driven(state).isEmpty();
    }

    private List<Found> naturalsIn(long key, long now) {
        Naturals n = naturals.get(key);
        if (n != null && now - n.tick < NATURAL_TTL) {
            return n.found;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        List<Found> found = chunk == null ? List.of() : scan(chunk, true);
        naturals.put(key, new Naturals(now, found));
        return found;
    }

    /** Players (not spectators) and ships in the level, and test anchors. */
    List<Vec3> anchors() {
        List<Vec3> out = people();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                if (!ship.isRemoved()) {
                    BoundingBox3dc b = ship.boundingBox();
                    out.add(new Vec3((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2, (b.minZ() + b.maxZ()) / 2));
                }
            }
        }
        return out;
    }

    /** Players (not spectators) and test anchors. */
    private List<Vec3> people() {
        List<Vec3> out = new ArrayList<>(extraAnchors);
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator()) {
                out.add(p.position());
            }
        }
        return out;
    }

    // ---- ships (8f-5) ----

    /**
     * What a ship's regions take from where the ship is, once per plan per ship: the outside air at its world centre,
     * the wind against it in its own frame, whether a hangar roofs it, and whether a person is near.
     *
     * @param windX the wind over the ship, m/s, along its plot x (the world's wind at its height, less its own
     *              motion, cut by world blocks around it, turned into its frame)
     * @param roofed whether world blocks cover most of its top (a hangar): its open-sky cells act indoors
     */
    public record ShipAir(Vec3 worldCentre, double windX, double windZ, boolean roofed, boolean near) {
    }

    /** The ship whose plot holds the middle of {@code box}, or null for the world. */
    private SubLevel shipOf(int[] box) {
        int cx = ((box[0] + box[3]) / 2) >> 4;
        int cz = ((box[2] + box[5]) / 2) >> 4;
        if (!ShipCover.inPlot(level, new ChunkPos(cx, cz))) {
            return null;
        }
        SubLevel ship = Sable.HELPER.getContaining(level, cx, cz);
        return ship == null || ship.isRemoved() ? null : ship;
    }

    /** What {@code ship}'s regions take from where it is now (public for the game tests). */
    public ShipAir shipAir(SubLevel ship) {
        return shipAir(ship, List.of());
    }

    ShipAir shipAir(SubLevel ship, List<Vec3> anchors) {
        WindParams wp = WindConfig.params();
        BoundingBox3ic pb = ship.getPlot().getBoundingBox();
        Pose3dc pose = ship.logicalPose();
        Vector3d plotCentre = pb.volume() > 0
                ? new Vector3d((pb.minX() + pb.maxX() + 1) / 2.0, (pb.minY() + pb.maxY() + 1) / 2.0,
                (pb.minZ() + pb.maxZ() + 1) / 2.0)
                : new Vector3d(ship.getPlot().getCenterBlock().getX(), ship.getPlot().getCenterBlock().getY(),
                ship.getPlot().getCenterBlock().getZ());
        Vector3d world = pose.transformPosition(plotCentre, new Vector3d());

        // The wind at its height, against its own motion
        double wx = 0;
        double wz = 0;
        WindColumn column = WindSources.column(level, world.x, world.z);
        if (column != null) {
            BlockPos here = BlockPos.containing(world.x, world.y, world.z);
            double ground = level.hasChunkAt(here)
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, here.getX(), here.getZ())
                    : level.getSeaLevel();
            double[] w = column.at(WindBlend.aloftShare(world.y, ground, wp.surfaceLayer(), wp.aloftHeight()));
            wx = w[0];
            wz = w[1];
        }
        Vector3d velocity = Sable.HELPER.getVelocity(level, plotCentre, new Vector3d());
        double rx = wx - velocity.x;
        double rz = wz - velocity.z;

        // World blocks around it as a whole (a hangar, a dock wall)
        BoundingBox3dc wb = ship.boundingBox();
        double[] coverage = new double[Shelter.FACES];
        ShelterProbe.measure(level, wb.minX(), wb.minY(), wb.minZ(), wb.maxX(), wb.maxY(), wb.maxZ(),
                wp.sideReach(), wp.roofReach(), coverage);
        boolean roofed = coverage[Shelter.TOP] >= 0.5;
        double speed = Math.hypot(rx, rz);
        if (speed > 1e-6) {
            double exposure = Shelter.exposure(coverage, rx / speed, rz / speed, wp.roofWeight());
            rx *= exposure;
            rz *= exposure;
        }
        Vector3d local = pose.transformNormalInverse(new Vector3d(rx, 0, rz), new Vector3d());

        boolean near = false;
        for (Vec3 a : people()) {
            if (a.x >= wb.minX() - NEAR && a.x <= wb.maxX() + NEAR && a.y >= wb.minY() - NEAR
                    && a.y <= wb.maxY() + NEAR && a.z >= wb.minZ() - NEAR && a.z <= wb.maxZ() + NEAR) {
                near = true;
                break;
            }
        }
        return new ShipAir(new Vec3(world.x, world.y, world.z), local.x, local.z, roofed, near);
    }

    /**
     * Forgets ship chunks that are gone (8f-5): a ship's plot chunks go without the usual unload event when it is
     * removed or disassembled.
     */
    private void pruneShipChunks() {
        java.util.function.LongPredicate gone = key -> ShipCover.inPlot(level, new ChunkPos(key))
                && level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null;
        placed.keySet().removeIf(gone);
        naturals.keySet().removeIf(gone);
        savedChunks.removeIf(gone);
    }

    // ---- the tick ----

    void tick() {
        if (paused) {
            return;
        }
        HeatSettings s = WeatherConfig.heat();
        long now = level.getGameTime();
        collect();
        if (!s.enabled()) {
            if (!regions.isEmpty()) {
                persistAll();
                for (HeatRegion r : regions) {
                    r.removed = true;
                }
                regions = new ArrayList<>();
            }
            return;
        }
        if (now % PLAN_INTERVAL == 0) {
            plan(now, s);
            usedMsLast = usedMs;
            usedMs = 0;
        }
        dispatch(now, s);
        if (now - lastPersist >= PERSIST_INTERVAL) {
            persistAll();
            lastPersist = now;
        }
    }

    /** Groups the sources into regions, keeps or replaces the regions, and readies their next step's inputs. */
    void plan(long now, HeatSettings s) {
        pruneShipChunks();
        List<Vec3> anchors = anchors();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;

        // Placed sources everywhere loaded, and the boxes they make on their own
        List<Found> all = new ArrayList<>();
        for (Long2ObjectMap.Entry<Long2ObjectOpenHashMap<BlockState>> chunk : placed.long2ObjectEntrySet()) {
            for (Long2ObjectMap.Entry<BlockState> e : chunk.getValue().long2ObjectEntrySet()) {
                long p = e.getLongKey();
                BlockState state = e.getValue();
                List<HeatPowerData.Variant> driven = HeatTables.driven(state);
                if (!driven.isEmpty()) {
                    // On by the block entity (8f-3): every driven variant that runs now, at its share
                    BlockPos at = BlockPos.of(p);
                    for (HeatPowerData.Variant v : driven) {
                        double share = v.inAir() && !v.natural() ? HeatDrivers.share(level, at, state, v) : 0;
                        if (share > 0) {
                            all.add(new Found(at.getX(), at.getY(), at.getZ(), state, v.scaled(share)));
                        }
                    }
                    continue;
                }
                HeatPowerData.Variant v = HeatTables.power(state);
                if (v != null && v.inAir() && !v.natural()) {
                    all.add(new Found(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), state, v));
                }
            }
        }
        List<HeatClusters.Cluster> placedBoxes = clusters(all, s, minY, maxY);
        if (testArea != null) {
            all.removeIf(f -> !inside(testArea, f.x, f.y, f.z));
            placedBoxes = clusters(all, s, minY, maxY);
        }

        // Natural sources near people and ships, or inside what placed sources keep going
        LongOpenHashSet chunks = new LongOpenHashSet();
        int reach = s.naturalReach();
        for (Vec3 a : anchors) {
            int r = (reach >> 4) + 1;
            int cx = (int) Math.floor(a.x) >> 4;
            int cz = (int) Math.floor(a.z) >> 4;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    chunks.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
        }
        for (HeatClusters.Cluster c : placedBoxes) {
            int[] b = c.box();
            for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) {
                for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) {
                    chunks.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        // 8f-5: every loaded ship's chunks; natural sources aboard always count (a ship is its own anchor)
        LongOpenHashSet aboard = new LongOpenHashSet();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                if (ship.isRemoved()) {
                    continue;
                }
                for (var holder : ship.getPlot().getLoadedChunks()) {
                    aboard.add(holder.getPos().toLong());
                }
            }
        }
        chunks.addAll(aboard);
        double reach2 = (double) reach * reach;
        for (long key : chunks) {
            boolean onShip = aboard.contains(key);
            for (Found f : naturalsIn(key, now)) {
                boolean in = onShip;
                for (Vec3 a : anchors) {
                    double dx = f.x + 0.5 - a.x;
                    double dy = f.y + 0.5 - a.y;
                    double dz = f.z + 0.5 - a.z;
                    if (dx * dx + dy * dy + dz * dz <= reach2) {
                        in = true;
                        break;
                    }
                }
                if (!in) {
                    for (HeatClusters.Cluster c : placedBoxes) {
                        int[] b = c.box();
                        if (f.x >= b[0] && f.y >= b[1] && f.z >= b[2] && f.x <= b[3] && f.y <= b[4] && f.z <= b[5]) {
                            in = true;
                            break;
                        }
                    }
                }
                if (in) {
                    if (testArea != null && !inside(testArea, f.x, f.y, f.z)) {
                        continue;
                    }
                    all.add(f);
                }
            }
        }

        // The regions
        List<HeatClusters.Cluster> clusters = clusters(all, s, minY, maxY);
        List<HeatRegion> next = new ArrayList<>();
        Set<HeatRegion> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<HeatRegion> absorbed = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<HeatRegion, List<Found>> members = new IdentityHashMap<>();
        for (HeatClusters.Cluster c : clusters) {
            HeatRegion r = null;
            for (HeatRegion old : regions) {
                if (!kept.contains(old) && old.sameBox(c.box())) {
                    r = old;
                    break;
                }
            }
            List<Found> m = new ArrayList<>(c.members().length);
            boolean naturalOnly = true;
            for (int i : c.members()) {
                Found f = all.get(i);
                m.add(f);
                naturalOnly &= f.variant.natural();
            }
            if (r == null) {
                r = create(c.box(), absorbed);
                r.pendingSettle = naturalOnly && r.pendingInitial == null;
            }
            r.naturalOnly = naturalOnly;
            kept.add(r);
            members.put(r, m);
            next.add(r);
        }
        for (HeatRegion old : regions) {
            if (kept.contains(old)) {
                continue;
            }
            boolean overlapsNew = false;
            for (HeatRegion n : next) {
                if (n.overlaps(old.box)) {
                    overlapsNew = true;
                    break;
                }
            }
            if (absorbed.contains(old) || overlapsNew) {
                old.removed = true;
            } else if (old.naturalOnly) {
                old.removed = true;
            } else if (!allLoaded(old.box)) {
                persist(old);
                old.removed = true;
            } else if (old.published != null && old.published.maxMagnitude() < FADED) {
                clearSaved(old.box);
                old.removed = true;
            } else {
                members.put(old, List.of());
                next.add(old);
            }
        }

        // Saved rooms whose chunks are back but that no region covers yet (cooling ones, after a reload)
        for (long key : new LongOpenHashSet(savedChunks)) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null || !chunk.hasData(HeatContent.CHUNK_DATA)) {
                savedChunks.remove(key);
                continue;
            }
            for (int[] box : new ArrayList<>(chunk.getData(HeatContent.CHUNK_DATA).boxes())) {
                if (testArea != null && !HeatChunkData.overlaps(testArea, box)) {
                    continue;
                }
                boolean covered = false;
                for (HeatRegion n : next) {
                    if (n.overlaps(box)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered && allLoaded(box)) {
                    HeatRegion r = create(box, absorbed);
                    members.put(r, List.of());
                    next.add(r);
                }
            }
        }

        regions = next;
        Map<SubLevel, ShipAir> shipAir = new IdentityHashMap<>();
        for (HeatRegion r : regions) {
            r.ship = shipOf(r.box);
            ShipAir air = null;
            if (r.ship != null) {
                air = shipAir.computeIfAbsent(r.ship, ship -> shipAir(ship, anchors));
            }
            ready(r, members.getOrDefault(r, List.of()), anchors, air);
        }
    }

    /**
     * The sources grouped into region boxes (8f-5): the world's on their own, and each ship's on its own, its boxes
     * kept inside its plot so two ships (or a ship and the world) never share a region. Member indices are into
     * {@code found}. Sources in a plot with no ship are left out.
     */
    private List<HeatClusters.Cluster> clusters(List<Found> found, HeatSettings s, int minY, int maxY) {
        Map<Object, List<Integer>> homes = new java.util.LinkedHashMap<>();
        Map<Object, SubLevel> ships = new IdentityHashMap<>();
        for (int i = 0; i < found.size(); i++) {
            Found f = found.get(i);
            Object home = WORLD_HOME;
            if (ShipCover.inPlot(level, new ChunkPos(f.x >> 4, f.z >> 4))) {
                SubLevel ship = ShipCover.shipAt(level, new BlockPos(f.x, f.y, f.z));
                if (ship == null || ship.isRemoved()) {
                    continue;
                }
                home = ship;
                ships.put(ship, ship);
            }
            homes.computeIfAbsent(home, h -> new ArrayList<>()).add(i);
        }
        List<HeatClusters.Cluster> out = new ArrayList<>();
        for (Map.Entry<Object, List<Integer>> e : homes.entrySet()) {
            List<Integer> idx = e.getValue();
            int n = idx.size();
            int[] x = new int[n];
            int[] y = new int[n];
            int[] z = new int[n];
            for (int i = 0; i < n; i++) {
                Found f = found.get(idx.get(i));
                x[i] = f.x;
                y[i] = f.y;
                z[i] = f.z;
            }
            SubLevel ship = ships.get(e.getKey());
            for (HeatClusters.Cluster c : HeatClusters.cluster(x, y, z, s.margin(), s.maxSize(), minY, maxY)) {
                int[] members = new int[c.members().length];
                for (int i = 0; i < members.length; i++) {
                    members[i] = idx.get(c.members()[i]);
                }
                int[] box = c.box();
                if (ship != null) {
                    box = clipToPlot(box, ship);
                    if (box == null) {
                        continue;
                    }
                }
                out.add(new HeatClusters.Cluster(box, members));
            }
        }
        return out;
    }

    private static final Object WORLD_HOME = new Object();

    /** {@code box} cut to the ship's plot (its whole reserved grid), or null if too little is left. */
    private static int[] clipToPlot(int[] box, SubLevel ship) {
        ChunkPos min = ship.getPlot().getChunkMin();
        ChunkPos max = ship.getPlot().getChunkMax();
        int[] b = box.clone();
        b[0] = Math.max(b[0], min.getMinBlockX());
        b[2] = Math.max(b[2], min.getMinBlockZ());
        b[3] = Math.min(b[3], max.getMaxBlockX());
        b[5] = Math.min(b[5], max.getMaxBlockZ());
        return b[3] - b[0] >= 2 && b[5] - b[2] >= 2 ? b : null;
    }

    private static boolean inside(int[] b, int x, int y, int z) {
        return x >= b[0] && y >= b[1] && z >= b[2] && x <= b[3] && y <= b[4] && z <= b[5];
    }

    /** A new region over {@code box}, starting from the fields of the regions it overlaps, else from saved offsets. */
    private HeatRegion create(int[] box, Set<HeatRegion> absorbed) {
        HeatRegion r = new HeatRegion(nextId++, box, params);
        List<HeatRegion.Published> before = new ArrayList<>();
        for (HeatRegion old : regions) {
            if (old.overlaps(box)) {
                absorbed.add(old);
                if (old.published != null) {
                    before.add(old.published);
                }
            }
        }
        float[] initial = new float[r.nx * r.ny * r.nz];
        boolean any = false;
        Long2ObjectOpenHashMap<HeatChunkData> saved = new Long2ObjectOpenHashMap<>();
        for (int z = 0; z < r.nz; z++) {
            for (int x = 0; x < r.nx; x++) {
                int wx = box[0] + x;
                int wz = box[2] + z;
                long ck = ChunkPos.asLong(wx >> 4, wz >> 4);
                HeatChunkData data = saved.get(ck);
                if (data == null && !saved.containsKey(ck)) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
                    data = chunk != null && chunk.hasData(HeatContent.CHUNK_DATA) ? chunk.getData(HeatContent.CHUNK_DATA) : null;
                    saved.put(ck, data);
                }
                for (int y = 0; y < r.ny; y++) {
                    int wy = box[1] + y;
                    float v = Float.NaN;
                    for (HeatRegion.Published p : before) {
                        if (p.contains(wx, wy, wz)) {
                            v = p.offsets()[p.index(wx - p.minX(), wy - p.minY(), wz - p.minZ())];
                            break;
                        }
                    }
                    if (Float.isNaN(v) && data != null) {
                        v = data.value(BlockPos.asLong(wx, wy, wz));
                    }
                    if (!Float.isNaN(v) && v != 0) {
                        initial[r.index(x, y, z)] = v;
                        any = true;
                    }
                }
            }
        }
        r.pendingInitial = any ? initial : null;
        return r;
    }

    /**
     * Takes the region's blocks if they changed, and works out its sources, wind and whether anyone is near. A ship's
     * region ({@code air} non-null, 8f-5) takes its outside air, wind and nearness from where the ship is.
     */
    private void ready(HeatRegion r, List<Found> sources, List<Vec3> anchors, ShipAir air) {
        boolean roofed = air != null && air.roofed();
        if (roofed != r.roofed) {
            r.roofed = roofed;
            r.geometryDirty = true;
        }
        if (r.geometryDirty || r.geometry == null) {
            r.geometry = geometry(r);
            r.pendingGeometry = r.geometry;
            r.geometryDirty = false;
        }
        int[] b = r.box;
        BlockPos centre = new BlockPos((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
        double outside = sources.isEmpty() ? 0
                : Temperature.at(level, air != null ? BlockPos.containing(air.worldCentre()) : centre);
        byte[] kinds = r.geometry.kinds();
        List<AirGrid.Source> out = new ArrayList<>();
        int[] dx = {-1, 1, 0, 0, 0, 0};
        int[] dy = {0, 0, -1, 1, 0, 0};
        int[] dz = {0, 0, 0, 0, -1, 1};
        for (Found f : sources) {
            int lx = f.x - b[0];
            int ly = f.y - b[1];
            int lz = f.z - b[2];
            if (lx <= 0 || ly <= 0 || lz <= 0 || lx >= r.nx - 1 || ly >= r.ny - 1 || lz >= r.nz - 1) {
                continue;
            }
            double power = params.gridPower(f.variant.power(), f.variant.radiant());
            double limit = HeatTables.offsetLimit(f.state, f.variant, outside);
            int own = r.index(lx, ly, lz);
            List<Integer> targets = new ArrayList<>(6);
            if (isAirLike(kinds[own])) {
                targets.add(own);
            } else {
                for (int d = 0; d < 6; d++) {
                    int n = r.index(lx + dx[d], ly + dy[d], lz + dz[d]);
                    if (isAirLike(kinds[n])) {
                        targets.add(n);
                    }
                }
            }
            if (f.variant.perFace()) {
                power *= targets.size();
            }
            if (targets.isEmpty()) {
                if (f.variant.perFace()) {
                    continue;
                }
                targets.add(own);
            }
            double each = power / targets.size();
            for (int t : targets) {
                out.add(new AirGrid.Source(t, each, limit));
            }
        }
        r.sources = List.copyOf(out);
        if (air != null) {
            r.windX = air.windX();
            r.windZ = air.windZ();
            r.near = air.near();
            return;
        }
        WindColumn wind = WindSources.column(level, centre.getX() + 0.5, centre.getZ() + 0.5);
        double[] w = wind == null ? new double[]{0, 0} : wind.at(0);
        r.windX = w[0];
        r.windZ = w[1];
        r.near = false;
        for (Vec3 a : anchors) {
            if (a.x >= b[0] - NEAR && a.x <= b[3] + 1 + NEAR && a.y >= b[1] - NEAR && a.y <= b[4] + 1 + NEAR
                    && a.z >= b[2] - NEAR && a.z <= b[5] + 1 + NEAR) {
                r.near = true;
                break;
            }
        }
    }

    private static boolean isAirLike(byte k) {
        return k == AirGrid.AIR || k == AirGrid.EXPOSED;
    }

    /** The region's blocks as cells (unloaded chunks and the box's faces are boundary). Server thread. */
    private HeatRegion.Geometry geometry(HeatRegion r) {
        int n = r.nx * r.ny * r.nz;
        byte[] kinds = new byte[n];
        float[] cond = new float[n];
        float[] cap = new float[n];
        byte[] open = new byte[n];
        int[] b = r.box;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < r.nz; z++) {
            for (int x = 0; x < r.nx; x++) {
                int wx = b[0] + x;
                int wz = b[2] + z;
                LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
                int top = chunk == null ? 0 : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, wx & 15, wz & 15);
                for (int y = 0; y < r.ny; y++) {
                    int i = r.index(x, y, z);
                    if (chunk == null || x == 0 || y == 0 || z == 0 || x == r.nx - 1 || y == r.ny - 1 || z == r.nz - 1) {
                        kinds[i] = AirGrid.BOUNDARY;
                        continue;
                    }
                    int wy = b[1] + y;
                    HeatTables.Cell cell = HeatTables.cell(chunk.getBlockState(pos.set(wx, wy, wz)));
                    if (cell.kind() == AirGrid.AIR) {
                        // A ship under a hangar roof (8f-5): no open sky anywhere on it
                        kinds[i] = wy > top && !r.roofed ? AirGrid.EXPOSED : AirGrid.AIR;
                    } else {
                        kinds[i] = AirGrid.SOLID;
                        cond[i] = cell.conductance();
                        cap[i] = cell.capacity();
                        open[i] = (byte) cell.openFaces();
                    }
                }
            }
        }
        return new HeatRegion.Geometry(kinds, cond, cap, open, cover(r, kinds));
    }

    /**
     * Which faces of each air cell are sheltered: the ships' wind check ({@code ShelterProbe}) for a one-block box,
     * done as sweeps over a snapshot of the region's blocks widened by the side reach (16) instead of a probe walk per
     * cell. A side counts as covered when a solid block (not a fluid, not something open all round like a fence post)
     * lies within the side reach that way; the top, within the roof reach (32) above (the column's highest block, from
     * the heightmap, where nothing inside the box is above). Server thread.
     */
    private byte[] cover(HeatRegion r, byte[] kinds) {
        dev.brights0ng.paradynelib.wind.WindParams wp =
                dev.brights0ng.paradynelib.wind.WindConfig.params();
        int reach = wp.sideReach();
        int roofReach = wp.roofReach();
        int[] b = r.box;
        int ex = r.nx + 2 * reach;
        int ez = r.nz + 2 * reach;
        int ny = r.ny;
        boolean[] solid = new boolean[ex * ny * ez];
        int[] top = new int[r.nx * r.nz];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < ez; z++) {
            for (int x = 0; x < ex; x++) {
                int wx = b[0] - reach + x;
                int wz = b[2] - reach + z;
                LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
                if (chunk == null) {
                    continue;
                }
                int bx = x - reach;
                int bz = z - reach;
                if (bx >= 0 && bz >= 0 && bx < r.nx && bz < r.nz) {
                    top[bz * r.nx + bx] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, wx & 15, wz & 15);
                }
                for (int y = 0; y < ny; y++) {
                    BlockState state = chunk.getBlockState(pos.set(wx, b[1] + y, wz));
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        continue;
                    }
                    HeatTables.Cell cell = HeatTables.cell(state);
                    solid[(z * ny + y) * ex + x] = cell.kind() == AirGrid.SOLID && cell.openFaces() != AirGrid.ALL_FACES;
                }
            }
        }
        byte[] cover = new byte[r.nx * r.ny * r.nz];
        // Along x (west and east), then along z (north and south)
        for (int z = reach; z < reach + r.nz; z++) {
            for (int y = 0; y < ny; y++) {
                int last = Integer.MIN_VALUE / 2;
                for (int x = 0; x < ex; x++) {
                    if (solid[(z * ny + y) * ex + x]) {
                        last = x;
                    } else if (x >= reach && x < reach + r.nx && x - last <= reach) {
                        cover[r.index(x - reach, y, z - reach)] |= HeatRegion.COVER_WEST;
                    }
                }
                last = Integer.MAX_VALUE / 2;
                for (int x = ex - 1; x >= 0; x--) {
                    if (solid[(z * ny + y) * ex + x]) {
                        last = x;
                    } else if (x >= reach && x < reach + r.nx && last - x <= reach) {
                        cover[r.index(x - reach, y, z - reach)] |= HeatRegion.COVER_EAST;
                    }
                }
            }
        }
        for (int x = reach; x < reach + r.nx; x++) {
            for (int y = 0; y < ny; y++) {
                int last = Integer.MIN_VALUE / 2;
                for (int z = 0; z < ez; z++) {
                    if (solid[(z * ny + y) * ex + x]) {
                        last = z;
                    } else if (z >= reach && z < reach + r.nz && z - last <= reach) {
                        cover[r.index(x - reach, y, z - reach)] |= HeatRegion.COVER_NORTH;
                    }
                }
                last = Integer.MAX_VALUE / 2;
                for (int z = ez - 1; z >= 0; z--) {
                    if (solid[(z * ny + y) * ex + x]) {
                        last = z;
                    } else if (z >= reach && z < reach + r.nz && last - z <= reach) {
                        cover[r.index(x - reach, y, z - reach)] |= HeatRegion.COVER_SOUTH;
                    }
                }
            }
        }
        // Up: the nearest solid above in the box, else the column's highest block
        for (int z = 0; z < r.nz; z++) {
            for (int x = 0; x < r.nx; x++) {
                int last = Integer.MAX_VALUE / 2;
                int highest = top[z * r.nx + x];
                for (int y = ny - 1; y >= 0; y--) {
                    if (solid[((z + reach) * ny + y) * ex + x + reach]) {
                        last = y;
                    } else {
                        int wy = b[1] + y;
                        boolean covered = last - y <= roofReach
                                || (last > ny && highest > b[4] && highest - wy <= roofReach);
                        if (covered) {
                            cover[r.index(x, y, z)] |= HeatRegion.COVER_TOP;
                        }
                    }
                }
            }
        }
        // Only air cells need it
        for (int i = 0; i < cover.length; i++) {
            if (!isAirLike(kinds[i])) {
                cover[i] = 0;
            }
        }
        return cover;
    }

    /**
     * Each air cell's share of the wind, from its sheltered faces and the wind's direction, by the ships' formula
     * ({@code Shelter.exposure}). Worked out for the 32 face patterns once, then looked up.
     */
    static float[] windShares(byte[] cover, double windX, double windZ, double roofWeight) {
        float[] byPattern = new float[32];
        double[] coverage = new double[dev.brights0ng.paradynelib.wind.Shelter.FACES];
        for (int bits = 0; bits < 32; bits++) {
            coverage[dev.brights0ng.paradynelib.wind.Shelter.TOP] = (bits & HeatRegion.COVER_TOP) != 0 ? 1 : 0;
            coverage[dev.brights0ng.paradynelib.wind.Shelter.NORTH] = (bits & HeatRegion.COVER_NORTH) != 0 ? 1 : 0;
            coverage[dev.brights0ng.paradynelib.wind.Shelter.SOUTH] = (bits & HeatRegion.COVER_SOUTH) != 0 ? 1 : 0;
            coverage[dev.brights0ng.paradynelib.wind.Shelter.WEST] = (bits & HeatRegion.COVER_WEST) != 0 ? 1 : 0;
            coverage[dev.brights0ng.paradynelib.wind.Shelter.EAST] = (bits & HeatRegion.COVER_EAST) != 0 ? 1 : 0;
            byPattern[bits] = (float) dev.brights0ng.paradynelib.wind.Shelter.exposure(coverage, windX, windZ,
                    roofWeight);
        }
        float[] shares = new float[cover.length];
        for (int i = 0; i < cover.length; i++) {
            shares[i] = byPattern[cover[i] & 31];
        }
        return shares;
    }

    // ---- stepping ----

    private void dispatch(long now, HeatSettings s) {
        ensurePool(s.threads());
        double cores = s.budgetCores();
        budgetMs = Math.min(budgetMs + cores * 50, cores * 1000);
        int inFlight = 0;
        List<HeatRegion> due = new ArrayList<>();
        for (HeatRegion r : regions) {
            if (r.inFlight) {
                inFlight++;
            } else if (r.lastStep == Long.MIN_VALUE || now - r.lastStep >= interval(r, s)) {
                due.add(r);
            }
        }
        // Most overdue first (in units of their own interval), so near and far regions share fairly
        due.sort((a, c) -> Double.compare(overdue(c, now, s), overdue(a, now, s)));
        int max = s.threads() * 2;
        for (HeatRegion r : due) {
            if (budgetMs <= 0 || inFlight >= max) {
                break;
            }
            Job job = take(r, now);
            pool.execute(() -> done.add(run(job)));
            inFlight++;
        }
    }

    private static long interval(HeatRegion r, HeatSettings s) {
        return Math.round((r.near ? s.nearStep() : s.farStep()) * 20);
    }

    private static double overdue(HeatRegion r, long now, HeatSettings s) {
        if (r.lastStep == Long.MIN_VALUE) {
            return Double.MAX_VALUE;
        }
        return (now - r.lastStep) / (double) interval(r, s);
    }

    /** Hands the region's prepared inputs to a job. */
    private Job take(HeatRegion r, long now) {
        double seconds = r.lastStep == Long.MIN_VALUE ? 0 : Math.min(MAX_CATCH_UP, (now - r.lastStep) / 20.0);
        Job job = new Job(r, r.pendingGeometry, r.pendingInitial, r.pendingSettle, r.sources, r.windX, r.windZ,
                r.geometry == null ? null : r.geometry.cover(),
                dev.brights0ng.paradynelib.wind.WindConfig.params().roofWeight(), seconds, now);
        r.pendingGeometry = null;
        r.pendingInitial = null;
        r.pendingSettle = false;
        r.lastStep = now;
        r.inFlight = true;
        return job;
    }

    /** Runs on a worker (or inline in tests): applies the inputs, steps, and publishes. */
    private static Done run(Job job) {
        long start = System.nanoTime();
        HeatRegion r = job.region;
        try {
            AirGrid g = r.grid;
            if (job.geometry != null) {
                HeatRegion.Geometry geo = job.geometry;
                g.setCells(geo.kinds(), geo.conductances(), geo.capacities(), geo.openFaces());
            }
            if (job.initial != null) {
                g.setOffsets(job.initial);
            }
            g.clearSources();
            g.sources().addAll(job.sources);
            g.setWind(job.windX, job.windZ);
            if (job.cover != null) {
                g.setWindShares(windShares(job.cover, job.windX, job.windZ, job.roofWeight));
            }
            if (job.settle) {
                g.settle(SETTLE_SECONDS);
            }
            if (job.seconds > 0) {
                g.step(job.seconds);
            }
            int n = r.nx * r.ny * r.nz;
            float[] offsets = new float[n];
            byte[] kinds = new byte[n];
            float[] wind = new float[n];
            g.exportOffsets(offsets);
            g.exportKinds(kinds);
            g.exportWindShares(wind);
            int[] b = r.box;
            return new Done(r, new HeatRegion.Published(b[0], b[1], b[2], r.nx, r.ny, r.nz, offsets, kinds, wind,
                    job.tick), System.nanoTime() - start);
        } catch (Throwable t) {
            Meteodyne.LOGGER.error("Heat simulation step failed for region {}", r.id, t);
            return new Done(r, null, System.nanoTime() - start);
        }
    }

    private void collect() {
        Done d;
        while ((d = done.poll()) != null) {
            HeatRegion r = d.region;
            r.inFlight = false;
            r.lastNanos = d.nanos;
            double ms = d.nanos / 1e6;
            budgetMs -= ms;
            usedMs += ms;
            if (!r.removed && d.published != null) {
                r.published = d.published;
            }
        }
    }

    private static synchronized void ensurePool(int threads) {
        if (pool != null && poolThreads == threads) {
            return;
        }
        if (pool != null) {
            pool.shutdown();
        }
        poolThreads = threads;
        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "EAE Heat");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    // ---- saving ----

    /** Writes every saved region into its chunks. */
    public void persistAll() {
        for (HeatRegion r : regions) {
            persist(r);
        }
    }

    /** Writes a region's latest field into its loaded chunks (not natural-only ones). */
    private void persist(HeatRegion r) {
        HeatRegion.Published p = r.published;
        if (r.naturalOnly || p == null) {
            return;
        }
        int[] b = r.box;
        for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) {
            for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                HeatChunkData data = chunk.getData(HeatContent.CHUNK_DATA);
                data.clearBox(b);
                data.addBox(b);
                int x0 = Math.max(b[0] + 1, cx << 4);
                int x1 = Math.min(b[3] - 1, (cx << 4) + 15);
                int z0 = Math.max(b[2] + 1, cz << 4);
                int z1 = Math.min(b[5] - 1, (cz << 4) + 15);
                for (int wz = z0; wz <= z1; wz++) {
                    for (int wx = x0; wx <= x1; wx++) {
                        for (int wy = b[1] + 1; wy <= b[4] - 1; wy++) {
                            float v = p.offsets()[p.index(wx - b[0], wy - b[1], wz - b[2])];
                            data.put(BlockPos.asLong(wx, wy, wz), v);
                        }
                    }
                }
                chunk.setUnsaved(true);
                savedChunks.add(ChunkPos.asLong(cx, cz));
            }
        }
    }

    /** Forgets what was saved for {@code box} (and any saved box overlapping it) in its loaded chunks. */
    public void clearSaved(int[] box) {
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null || !chunk.hasData(HeatContent.CHUNK_DATA)) {
                    continue;
                }
                HeatChunkData data = chunk.getData(HeatContent.CHUNK_DATA);
                data.clearBox(box);
                chunk.setUnsaved(true);
                if (data.boxes().isEmpty()) {
                    savedChunks.remove(ChunkPos.asLong(cx, cz));
                }
            }
        }
    }

    private boolean allLoaded(int[] box) {
        for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
            for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- reading ----

    /** How much warmer (negative: colder) than the outside air the simulation makes it at a world point, C. */
    public double offsetAt(double x, double y, double z) {
        double v = regionOffset(x, y, z);
        return Double.isNaN(v) ? 0 : v;
    }

    /** The offset at a point (world or plot), or NaN if no region holds it. */
    private double regionOffset(double x, double y, double z) {
        for (HeatRegion r : regions) {
            HeatRegion.Published p = r.published;
            if (p != null && p.contains(x, y, z)) {
                return p.offsetAt(x, y, z);
            }
        }
        return Double.NaN;
    }

    /**
     * The room's warmth, C, for someone at {@code at} (8f-5): a point in a ship's plot reads that ship's rooms; a
     * world point inside a ship's bounds reads the ship's rooms there if one holds it, else the world's.
     */
    public double roomAt(Vec3 at) {
        if (ShipCover.inPlot(level, BlockPos.containing(at))) {
            return offsetAt(at.x, at.y, at.z);
        }
        Vec3 plot = ShipCover.plotPoint(level, at.x, at.y, at.z);
        if (plot != null) {
            double v = regionOffset(plot.x, plot.y, plot.z);
            if (!Double.isNaN(v)) {
                return v;
            }
        }
        return offsetAt(at.x, at.y, at.z);
    }

    public List<HeatRegion> regions() {
        return List.copyOf(regions);
    }

    /** CPU milliseconds the steps finished in the last second took. */
    public double usedMsLastSecond() {
        return usedMsLast;
    }

    public int placedSourceCount() {
        int n = 0;
        for (Long2ObjectOpenHashMap<BlockState> m : placed.values()) {
            n += m.size();
        }
        return n;
    }

    // ---- tests and debug ----

    /** Places that count as a player for natural sources and stepping (game tests, the climatology run). */
    public void setExtraAnchors(List<Vec3> anchors) {
        this.extraAnchors = List.copyOf(anchors);
    }

    /** Stops (or restarts) the live tick, for game tests that drive the regions themselves. */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /**
     * Plans only sources inside {@code box} ({minX, minY, minZ, maxX, maxY, maxZ}, world blocks; null for all), so a
     * game test's region doesn't take in its neighbours' fires.
     */
    public void setTestArea(int[] box) {
        this.testArea = box == null ? null : box.clone();
    }

    /** Plans now. */
    public void planNow() {
        plan(level.getGameTime(), WeatherConfig.heat());
    }

    /**
     * Plans, then runs every region {@code seconds} on this thread (waiting for any step already running). For game
     * tests and the debug command; outside the budget.
     */
    public void runNow(double seconds) {
        waitForJobs();
        long now = level.getGameTime();
        plan(now, WeatherConfig.heat());
        for (HeatRegion r : regions) {
            Job job = take(r, now);
            Job timed = new Job(job.region, job.geometry, job.initial, job.settle, job.sources, job.windX, job.windZ,
                    job.cover, job.roofWeight, seconds, job.tick);
            done.add(run(timed));
        }
        collect();
    }

    private void waitForJobs() {
        long until = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < until) {
            collect();
            boolean busy = false;
            for (HeatRegion r : regions) {
                busy |= r.inFlight;
            }
            if (!busy) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    /** Drops every region without saving (as if the world had just loaded; game tests). */
    public void forgetRegions() {
        waitForJobs();
        for (HeatRegion r : regions) {
            r.removed = true;
        }
        regions = new ArrayList<>();
        naturals.clear();
    }
}
