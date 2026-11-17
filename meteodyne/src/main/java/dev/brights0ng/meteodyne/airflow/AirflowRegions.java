// Written by Claude (GreedyFluids M7b b3); edited by Claude (b4: ids, timing, the recorder; only assigned regions
// stay alive); edited by Claude (M7d d1: fixed tiles replace player-centred windows; d2: saving and restoring; M7c:
// the background field, driven sources, limits and body heat; M7e: the generator's terrain off the server thread,
// the speed limit and tile cap; M7f: the heat diffusivity from the config; the outside air sent ahead of edits; M8b:
// each region reads its running neighbours' frames for its borders; M8b b4: secondaries, the tiles that run beside a
// player's where enclosed air crosses a border).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import dev.brights0ng.greedyfluids.grid.CellGrid3D.Side;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.heat.world.HeatShips;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.wind.WindColumn;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The region manager (M7b b3, tiles since M7d d1): GreedyFluids regions on the fixed tile grid of the Overworld, on
 * the server thread, when the engine is {@link Airflow.Engine#AIRFLOW}.
 * <ul>
 *   <li><b>Tiles:</b> {@link AirflowTiles}, 64×32×64 (Bright, 2026-11-15). Tiles never overlap, so every block
 *       belongs to at most one region and lookups go by tile.</li>
 *   <li><b>Assignment:</b> each player keeps the tile holding them alive (only players are readers that do;
 *       Bright, 2026-11-15). Where a border cuts enclosed air, no air crosses and heat goes to the neighbouring
 *       tile's air while that tile runs, else nowhere (M8b, {@link AirflowBorders}).</li>
 *   <li><b>Secondaries (M8b b4, B3; Bright, 2026-11-17):</b> a player's tile also keeps running the tiles of the
 *       3×3×3 block around it that enclosed air connects to it across borders ({@link AirflowNeighbourTiles}): at
 *       least {@code airflowNeighbourMinCells} enclosed cell pairs across a face, counted from the one-section strip
 *       on each side of it, which the manager wants from the section cache. The counts are refreshed every
 *       {@link #FLOOD_TICKS} (sooner while a strip is still being read). A tile in its grace keeps no secondaries, so
 *       a secondary goes into its grace with the player's tile. {@code airflowNeighbourTiles} REPORT counts what
 *       would run (at 2, 4 and 8 cells, in every mode but OFF) without running it.</li>
 *   <li><b>Limits ({@link AirflowLimits}; Bright, 2026-11-15):</b> a player moving faster than
 *       {@code airflowSpeedLimit} (5.5 blocks a second) opens no new tile, though the tile they are in keeps running;
 *       past {@code airflowTileCap} tiles (12 on the server, or per player) the tiles empty longest close early, then
 *       secondaries. A fast player opens no new secondaries either, and one opens only under the cap.</li>
 *   <li><b>Life:</b> a region wants its 32 sections from {@link AirflowSections} and is built once all are cached.
 *       It is retired after {@link #GRACE_TICKS} with no player in it (so stepping back and forth across a border
 *       rebuilds nothing), or when one of its sections is dropped (a chunk unload, or a data-map or tag reload). A
 *       failed region is rebuilt from fresh codes after
 *       {@link AirflowRuntime#backoffTicks}. No window for a player aboard a ship, or spectating.</li>
 *   <li><b>Events:</b> block edits and source changes in a built region's tile go to its queue as they happen.</li>
 *   <li><b>Inputs (M7c):</b> the background field ({@link AirflowBackground}: the tile's lattice of Meteodyne's air
 *       temperature, read before the tile is built, and its column tops for the follow weights) and the surface
 *       wind at the tile's centre, re-sent when a lattice point moves by {@link #BACKGROUND_STEP} °C, the wind by
 *       {@link #WIND_STEP} m/s, or after an edit (checked every {@link #WEATHER_TICKS}). An edit first sends the
 *       lattice last read, if it hasn't gone yet, so the region shifts the air up to the edit with the follow
 *       weights it had (M7f; Bright, 2026-11-16). Placed sources'
 *       watts and limits ({@link AirflowSources}); driven sources polled at the same cadence; each player in the
 *       tile's body heat, {@link AirflowSources#PLAYER_WATTS} W.</li>
 *   <li><b>Saving (M7d d2):</b> a running region saves its temperatures into its chunks ({@link AirflowChunkData})
 *       every {@link #SAVE_TICKS}, when it retires and when the server stops; a region is built from what its
 *       chunks hold, decayed toward the background ({@link AirflowSaved}). A save that arrives after its chunk
 *       unloaded is lost; the chunk keeps the save before it.</li>
 * </ul>
 * Started at {@code ServerStartedEvent} in airflow mode, stopped (air thread joined) at {@code ServerStoppingEvent}.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class AirflowRegions implements AirflowSections.Listener {

    /** A region's size: one tile. */
    public static final int WX = AirflowTiles.TX, WY = AirflowTiles.TY, WZ = AirflowTiles.TZ;
    /** How often players are matched to regions, ticks. */
    static final int SCAN_TICKS = 10;
    /** How long a region lives with no player in its tile, ticks (Claude's b3 lean: 30 s). */
    public static final long GRACE_TICKS = 600;
    /** How often each region's weather is checked, ticks. */
    static final int WEATHER_TICKS = 20;
    public static final double BACKGROUND_STEP = 0.25, WIND_STEP = 0.25;
    /** Server-thread time per tick spent reading new tiles' ground samples, ns. */
    static final long BACKGROUND_BUDGET_NANOS = 500_000L;
    /** How often a running region saves its temperatures, ticks (60 s). */
    static final long SAVE_TICKS = 1200;
    /** How far outside a ship's bounds a player still counts as aboard, blocks. */
    static final double ABOARD_MARGIN = 1;
    /** How often a player's tile's secondaries are worked out again, ticks (M8b b4). */
    static final int FLOOD_TICKS = 40;

    private static volatile AirflowRegions running;

    /** One region: its tile and its slot on the air thread. Server thread. */
    public static final class Region {
        private final int id;
        private final int x0, y0, z0;
        private final long[] sections;
        private AirflowRuntime.Slot slot;
        private boolean occupied;
        /** M8b b4: whether a player's tile keeps it running (at the last match), and its strongest link, cells. */
        private boolean wanted;
        private int link;
        /** The player last in its tile, and when (the cap's order and owner). */
        private UUID owner;
        private long lastOccupied;
        private long emptySince = -1, retryAt = -1, built = -1;
        private double sentT = Double.NaN, sentWindX, sentWindZ;
        private AirflowBackground background;
        private float[] sentLattice;
        /** The lattice read at the last weather check (M7f: sent ahead of an edit when newer than the one sent). */
        private float[] readLattice;
        private boolean geometryChanged, hadBodies;
        /** Driven sources in the tile, with the power last sent for each. */
        private final Long2ObjectOpenHashMap<AirflowSources.Power> driven = new Long2ObjectOpenHashMap<>();

        private Region(int id, int x0, int y0, int z0) {
            this.id = id;
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            sections = new long[(WX / 16) * (WY / 16) * (WZ / 16)];
            int n = 0;
            for (int j = 0; j < WY / 16; j++) {
                for (int k = 0; k < WZ / 16; k++) {
                    for (int i = 0; i < WX / 16; i++) {
                        sections[n++] = SectionPos.asLong((x0 >> 4) + i, (y0 >> 4) + j, (z0 >> 4) + k);
                    }
                }
            }
        }

        /** A number for status and records, unique on this server run. */
        public int id() {
            return id;
        }

        public int x0() {
            return x0;
        }

        public int y0() {
            return y0;
        }

        public int z0() {
            return z0;
        }

        /** Its slot on the air thread, or null while its sections are being read. */
        public AirflowRuntime.Slot slot() {
            return slot;
        }

        /** Whether a player was in its tile at the last match. */
        public boolean occupied() {
            return occupied;
        }

        /** Whether a player's tile kept it running at the last match (M8b b4); a player may be in it too. */
        public boolean wanted() {
            return wanted;
        }

        /** Whether it runs as a secondary: kept by a player's tile, with no player in it (M8b b4). */
        public boolean secondary() {
            return wanted && !occupied;
        }

        public boolean contains(double x, double y, double z) {
            return x >= x0 && x < x0 + WX && y >= y0 && y < y0 + WY && z >= z0 && z < z0 + WZ;
        }

        boolean holds(long section) {
            for (long s : sections) {
                if (s == section) {
                    return true;
                }
            }
            return false;
        }
    }

    private final ServerLevel level;
    private final AirflowRuntime runtime;
    /** The generator's terrain at chunk corners, shared by every tile (M7e). */
    private final AirflowGround ground;
    private final List<Region> regions = new ArrayList<>();
    private final Map<Long, Region> byTile = new HashMap<>();
    /** M8b: each tile's current slot, written on the server thread and read on the air thread for the borders. */
    private final Map<Long, AirflowRuntime.Slot> live = new ConcurrentHashMap<>();
    private AirflowSections sections;
    private Function<ServerLevel, List<Anchor>> anchors = AirflowRegions::players;
    private final AirflowLimits.Speeds speeds = new AirflowLimits.Speeds(AirflowLimits.WINDOW_TICKS);
    private int nextId = 1;
    private long managerNanos;
    private long savesWritten, sectionsLost, restores, heldForSpeed, closedByCap;
    /** M8b b4: the mode and threshold game tests set (null and 0: the config's). */
    private AirflowNeighbourTiles.Mode neighbourMode;
    private int neighbourMin;
    /** M8b b4: each player's tile's latest flood, by tile key. */
    private final Map<Long, Flood> floods = new HashMap<>();
    /** M8b b4: the border strips' sections this manager wants (one want each). */
    private final LongOpenHashSet stripWanted = new LongOpenHashSet();
    /** M8b b4: secondaries now, and their tile-ticks so far; the same for what would run at each reported threshold. */
    private int secondariesNow;
    private long secondaryTicks, secondariesOpened, secondariesRefused;
    private final int[] wouldNow = new int[AirflowNeighbourTiles.REPORTED.length];
    private final long[] wouldTicks = new long[AirflowNeighbourTiles.REPORTED.length];
    /** The tile grid's shift (game tests only, so a test's structure never straddles a border), blocks. */
    private int shiftX, shiftY, shiftZ;
    private AirflowRecorder recorder;

    AirflowRegions(ServerLevel level) {
        this.level = level;
        this.runtime = new AirflowRuntime((slot, e) -> Meteodyne.LOGGER.warn("Airflow: region {} failed ({} so far)",
                slot == null ? "runtime" : slot.id(), slot == null ? "-" : slot.failures(), e));
        this.ground = new AirflowGround(terrain(level), Util.backgroundExecutor(), level.getMaxBuildHeight(),
                e -> Meteodyne.LOGGER.warn("Airflow: the world generator's terrain height failed; the heightmap alone"
                        + " gives the cover there", e));
    }

    /**
     * The world generator's terrain over (x, z), as {@code UndergroundProbe.terrainHeight} takes it, without its
     * server-thread cache: the generator and its noise are safe off the server thread (world generation runs there).
     */
    private static java.util.function.IntBinaryOperator terrain(ServerLevel level) {
        var source = level.getChunkSource();
        var generator = source.getGenerator();
        var random = source.randomState();
        LevelHeightAccessor heights = LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight());
        return (x, z) -> generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heights, random);
    }

    /** The running server's regions, or null when the engine isn't AIRFLOW. */
    public static AirflowRegions get() {
        return running;
    }

    public ServerLevel level() {
        return level;
    }

    public AirflowRuntime runtime() {
        return runtime;
    }

    /** The level's section cache. */
    public AirflowSections sections() {
        return sections;
    }

    /** Server-thread time this manager's ticks have taken, ns. */
    public long managerNanos() {
        return managerNanos;
    }

    /** Tile saves written into chunks so far. */
    public long savesWritten() {
        return savesWritten;
    }

    /** Saved sections that found their chunk unloaded. */
    public long sectionsLost() {
        return sectionsLost;
    }

    /** Regions built from saved state so far. */
    public long restores() {
        return restores;
    }

    /** Matches where a player was too fast to open the tile they were in. */
    public long heldForSpeed() {
        return heldForSpeed;
    }

    /** Regions closed early by the tile cap. */
    public long closedByCap() {
        return closedByCap;
    }

    /** The neighbour-tile mode in force (M8b b4). */
    public AirflowNeighbourTiles.Mode neighbourMode() {
        return neighbourMode != null ? neighbourMode : WeatherConfig.airflowNeighbourTiles();
    }

    /** The enclosed cells a border must cut before the tile across runs (M8b b4). */
    public int neighbourMinCells() {
        return neighbourMin > 0 ? neighbourMin : WeatherConfig.airflowNeighbourMinCells();
    }

    /** Game tests: the neighbour-tile mode and threshold, instead of the config's (null, 0: the config's again). */
    void neighbourTiles(AirflowNeighbourTiles.Mode mode, int minCells) {
        this.neighbourMode = mode;
        this.neighbourMin = minCells;
    }

    /** Secondaries at the last match (M8b b4). */
    public int secondariesNow() {
        return secondariesNow;
    }

    /** Secondaries' tile-ticks so far: each match's secondaries times its interval. */
    public long secondaryTicks() {
        return secondaryTicks;
    }

    /** Secondaries opened so far, and openings the cap refused (one per tile per match). */
    public long secondariesOpened() {
        return secondariesOpened;
    }

    public long secondariesRefused() {
        return secondariesRefused;
    }

    /** Tiles that would run as secondaries at the last match, at {@link AirflowNeighbourTiles#REPORTED}[i] cells. */
    public int wouldNow(int i) {
        return wouldNow[i];
    }

    /** The same, as tile-ticks so far. */
    public long wouldTicks(int i) {
        return wouldTicks[i];
    }

    /** Border strip sections wanted now. */
    public int stripSections() {
        return stripWanted.size();
    }

    /** The running recorder, or null. */
    public AirflowRecorder recorder() {
        return recorder;
    }

    /** Starts recording into {@code dir} (server thread); a running recording is stopped first. */
    public AirflowRecorder record(java.nio.file.Path dir) {
        stopRecording();
        recorder = new AirflowRecorder(this, dir);
        return recorder;
    }

    /** Stops the recording, writing its summary; returns it, or null if none ran. */
    public AirflowRecorder stopRecording() {
        AirflowRecorder r = recorder;
        recorder = null;
        if (r != null) {
            r.close();
        }
        return r;
    }

    /** The regions, read-only (server thread). */
    public List<Region> regions() {
        return Collections.unmodifiableList(regions);
    }

    /** What keeps a tile alive: a player (or a game test's point) and where it is. */
    record Anchor(UUID id, Vec3 pos) {
    }

    /**
     * Replaces which points keep tiles alive (the players by default; game tests use fixed points, each its own
     * anchor by its place in the list).
     */
    void anchors(Function<ServerLevel, List<Vec3>> points) {
        this.anchors = l -> {
            List<Vec3> ps = points.apply(l);
            List<Anchor> out = new ArrayList<>(ps.size());
            for (int i = 0; i < ps.size(); i++) {
                out.add(new Anchor(new UUID(0L, i), ps.get(i)));
            }
            return out;
        };
    }

    /**
     * Game tests only, before any region opens: shifts the tile grid by whole sections so that the section holding
     * {@code at} is the second along x and z of its tile and the lower of its band. A 7-block test structure from
     * {@code at} then lies wholly in one tile, at least 16 blocks from its sides.
     */
    void alignForTests(BlockPos at) {
        if (!regions.isEmpty()) {
            throw new IllegalStateException("align the grid before any region opens");
        }
        int sx = SectionPos.blockToSectionCoord(at.getX()), sy = SectionPos.blockToSectionCoord(at.getY());
        int sz = SectionPos.blockToSectionCoord(at.getZ());
        shiftX = 16 * Math.floorMod(sx - 1, WX / 16);
        shiftZ = 16 * Math.floorMod(sz - 1, WZ / 16);
        shiftY = 16 * Math.floorMod(sy - SectionPos.blockToSectionCoord(level.getMinBuildHeight()), WY / 16);
    }

    /** The latest frame of the region whose tile holds (x, y, z), world blocks; or null. */
    public AirflowRuntime.Frame frameAt(double x, double y, double z) {
        Region r = regionAt(x, y, z);
        if (r == null || r.slot == null) {
            return null;
        }
        AirflowRuntime.Frame f = r.slot.frame();
        return f == null || f.frame() == null ? null : f;
    }

    /** The region whose tile holds (x, y, z), or null (none, or the point is outside the world's height). */
    public Region regionAt(double x, double y, double z) {
        int[] o = tileOf(x, y, z);
        Region r = byTile.get(AirflowTiles.key(o[0], o[1], o[2]));
        return r != null && r.contains(x, y, z) ? r : null;
    }

    void start() {
        sections = AirflowSections.open(level);
        sections.addListener(this);
        runtime.start();
    }

    void stop() {
        stopRecording();
        if (!runtime.stop()) {
            Meteodyne.LOGGER.warn("Airflow: the air thread did not stop within 5 s");
        }
        writeSaves(); // the thread packs every running region as it stops; the chunks are still loaded
        for (Region r : List.copyOf(regions)) {
            retire(r);
        }
        floods.clear();
        wantStrips(new LongOpenHashSet());
        sections.removeListener(this);
    }

    /** One server tick. */
    void tick() {
        long start = System.nanoTime();
        long now = level.getGameTime();
        runtime.tick(now);
        writeSaves();
        fillBackgrounds(start + BACKGROUND_BUDGET_NANOS);
        if (now % SCAN_TICKS == 0) {
            match();
        }
        for (Region r : List.copyOf(regions)) {
            service(r, now);
        }
        managerNanos += System.nanoTime() - start;
    }

    /**
     * Marks the region of each anchor's tile occupied, opening it if it isn't there and the anchor is slow enough;
     * then (M8b b4) marks or opens the secondaries those tiles keep; every other region starts (or continues) its
     * grace. Then closes regions past the tile cap.
     */
    private void match() {
        long now = level.getGameTime();
        double limit = WeatherConfig.airflowSpeedLimit();
        for (Region r : regions) {
            r.occupied = false;
            r.wanted = false;
            r.link = 0;
        }
        Set<UUID> seen = new HashSet<>();
        List<Sponsor> sponsors = new ArrayList<>();
        for (Anchor a : anchors.apply(level)) {
            Vec3 p = a.pos();
            seen.add(a.id());
            double speed = speeds.record(a.id(), now, p.x, p.y, p.z);
            boolean slow = AirflowLimits.mayOpen(speed, limit);
            int[] o = tileOf(p.x, p.y, p.z);
            Region home = byTile.get(AirflowTiles.key(o[0], o[1], o[2]));
            if (home == null) {
                if (!slow) {
                    heldForSpeed++;
                    continue;
                }
                home = open(o[0], o[1], o[2]);
            }
            home.occupied = true;
            home.owner = a.id();
            home.lastOccupied = now;
            sponsors.add(new Sponsor(a.id(), home, slow));
        }
        speeds.keep(seen);
        neighbours(now, sponsors);
        for (Region r : AirflowLimits.overCap(capView(), WeatherConfig.airflowCapMode(), WeatherConfig.airflowTileCap())) {
            retire(r);
            closedByCap++;
        }
    }

    /** The regions as the cap sees them. */
    private List<AirflowLimits.Tile<Region>> capView() {
        List<AirflowLimits.Tile<Region>> tiles = new ArrayList<>(regions.size());
        for (Region r : regions) {
            tiles.add(new AirflowLimits.Tile<>(r, r.owner, r.lastOccupied, r.occupied, r.secondary()));
        }
        return tiles;
    }

    /** A player's tile at this match, whether they may open tiles, and its flood once worked out (M8b b4). */
    private static final class Sponsor {
        final UUID id;
        final Region home;
        final boolean slow;
        Flood flood;

        Sponsor(UUID id, Region home, boolean slow) {
            this.id = id;
            this.home = home;
            this.slow = slow;
        }
    }

    /**
     * One player's tile's flood (M8b b4): the tiles reached at each reported threshold and at the one in force, the
     * strip sections it read, and whether every face it asked could be counted.
     */
    private record Flood(long tick, int min, boolean complete, List<List<AirflowNeighbourTiles.Reached>> reported,
            List<AirflowNeighbourTiles.Reached> reached, LongOpenHashSet sections) {
    }

    /**
     * M8b b4: works out each player's tile's secondaries (refreshed every {@link #FLOOD_TICKS}, or at once while
     * incomplete), wants the strips they read and releases the rest, counts what would run, and under ON marks
     * existing secondaries then opens missing ones within the cap and the speed limit.
     */
    private void neighbours(long now, List<Sponsor> sponsors) {
        AirflowNeighbourTiles.Mode mode = neighbourMode();
        if (mode == AirflowNeighbourTiles.Mode.OFF) {
            floods.clear();
            wantStrips(new LongOpenHashSet());
            java.util.Arrays.fill(wouldNow, 0);
            secondariesNow = 0;
            return;
        }
        int min = neighbourMinCells();
        Map<Long, Flood> next = new HashMap<>();
        LongOpenHashSet needed = new LongOpenHashSet();
        LongOpenHashSet homes = new LongOpenHashSet();
        for (Sponsor s : sponsors) {
            long key = AirflowTiles.key(s.home.x0, s.home.y0, s.home.z0);
            homes.add(key);
            Flood f = next.get(key); // two players in one tile share its flood
            if (f == null) {
                f = floods.get(key);
                if (f == null || f.min() != min || !f.complete() || now - f.tick() >= FLOOD_TICKS) {
                    f = flood(s.home, min, now);
                }
                next.put(key, f);
                needed.addAll(f.sections());
            }
            s.flood = f;
        }
        floods.clear();
        floods.putAll(next);
        wantStrips(needed);
        for (int i = 0; i < AirflowNeighbourTiles.REPORTED.length; i++) {
            LongOpenHashSet would = new LongOpenHashSet();
            for (Sponsor s : sponsors) {
                for (AirflowNeighbourTiles.Reached r : s.flood.reported().get(i)) {
                    long k = keyAt(s.home, r);
                    if (!homes.contains(k)) {
                        would.add(k);
                    }
                }
            }
            wouldNow[i] = would.size();
            wouldTicks[i] += (long) would.size() * SCAN_TICKS;
        }
        if (mode == AirflowNeighbourTiles.Mode.ON) {
            List<Object[]> missing = new ArrayList<>(); // {sponsor, reached}: tiles to open once the existing are marked
            for (Sponsor s : sponsors) {
                for (AirflowNeighbourTiles.Reached r : s.flood.reached()) {
                    long k = keyAt(s.home, r);
                    if (homes.contains(k)) {
                        continue; // a player's own tile runs anyway
                    }
                    Region g = byTile.get(k);
                    if (g == null) {
                        missing.add(new Object[] {s, r});
                    } else {
                        keep(g, s, r, now);
                    }
                }
            }
            AirflowLimits.CapMode capMode = WeatherConfig.airflowCapMode();
            int cap = WeatherConfig.airflowTileCap();
            for (Object[] m : missing) {
                Sponsor s = (Sponsor) m[0];
                AirflowNeighbourTiles.Reached r = (AirflowNeighbourTiles.Reached) m[1];
                Region g = byTile.get(keyAt(s.home, r)); // another player's flood may have opened it just now
                if (g == null) {
                    if (!s.slow) {
                        continue;
                    }
                    if (!AirflowLimits.roomFor(capView(), capMode, cap, s.id)) {
                        secondariesRefused++;
                        continue;
                    }
                    g = open(s.home.x0 + r.dx() * WX, s.home.y0 + r.dy() * WY, s.home.z0 + r.dz() * WZ);
                    secondariesOpened++;
                }
                keep(g, s, r, now);
            }
        }
        int n = 0;
        for (Region r : regions) {
            n += r.secondary() ? 1 : 0;
        }
        secondariesNow = n;
        secondaryTicks += (long) n * SCAN_TICKS;
    }

    /** Marks {@code g} as kept by {@code s}'s tile at this match. */
    private static void keep(Region g, Sponsor s, AirflowNeighbourTiles.Reached r, long now) {
        g.wanted = true;
        if (!g.occupied) {
            g.owner = s.id;
        }
        g.lastOccupied = now;
        g.link = Math.max(g.link, r.link());
    }

    /** The key of the tile {@code r} reaches from {@code home}. */
    private static long keyAt(Region home, AirflowNeighbourTiles.Reached r) {
        return AirflowTiles.key(home.x0 + r.dx() * WX, home.y0 + r.dy() * WY, home.z0 + r.dz() * WZ);
    }

    /** Floods the block around {@code home} at every reported threshold and at {@code min}, one count per face. */
    private Flood flood(Region home, int min, long now) {
        LongOpenHashSet read = new LongOpenHashSet();
        boolean[] complete = {true};
        AirflowNeighbourTiles.Faces faces = AirflowNeighbourTiles.memo((dx, dy, dz, side) -> {
            int c = sharedCells(home.x0 + dx * WX, home.y0 + dy * WY, home.z0 + dz * WZ, side, read);
            if (c < 0) {
                complete[0] = false;
            }
            return c;
        });
        List<List<AirflowNeighbourTiles.Reached>> reported = new ArrayList<>();
        for (int n : AirflowNeighbourTiles.REPORTED) {
            reported.add(AirflowNeighbourTiles.reach(faces, n));
        }
        List<AirflowNeighbourTiles.Reached> reached = AirflowNeighbourTiles.reach(faces, min);
        return new Flood(now, min, complete[0], reported, reached, read);
    }

    /** Wants the sections in {@code needed} not wanted yet, and releases the strips no longer needed. */
    private void wantStrips(LongOpenHashSet needed) {
        for (LongIterator it = needed.iterator(); it.hasNext(); ) {
            long s = it.nextLong();
            if (!stripWanted.contains(s)) {
                sections.want(s);
            }
        }
        for (LongIterator it = stripWanted.iterator(); it.hasNext(); ) {
            long s = it.nextLong();
            if (!needed.contains(s)) {
                sections.release(s);
            }
        }
        stripWanted.clear();
        stripWanted.addAll(needed);
    }

    /**
     * M8b b4 (T1; Bright, 2026-11-17): the enclosed cell pairs across {@code side} of the tile at (x0, y0, z0), one
     * cell each side of the border, both air (doors count) and both below their own column's open height (the
     * heightmap, as {@link AirflowBackground#columnTops} takes it). 0 where no tile of the world lies across; −1 while
     * a strip section isn't read or a column's chunk isn't loaded. Adds the strip's sections, the one-section layer
     * each side of the face, to {@code read}.
     */
    int sharedCells(int x0, int y0, int z0, Side side, LongOpenHashSet read) {
        int a = side.axis(), d = side.high() ? 1 : -1;
        int[] size = {WX, WY, WZ}, o = {x0, y0, z0}, n = {x0, y0, z0};
        n[a] += d * size[a];
        int[] check = tileOf(n[0] + 0.5, n[1] + 0.5, n[2] + 0.5);
        if (check[0] != n[0] || check[1] != n[1] || check[2] != n[2]) {
            return 0; // above or below the world
        }
        int edge = side.high() ? o[a] + size[a] - 1 : o[a], across = edge + d;
        int u = a == 0 ? 2 : 0, v = a == 1 ? 2 : 1; // the face's own axes: (z, y), (x, z) or (x, y)
        int nu = size[u], nv = size[v], su = nu >> 4, sv = nv >> 4;
        AirflowSections.Codes[] mine = new AirflowSections.Codes[su * sv], theirs = new AirflowSections.Codes[su * sv];
        boolean ready = true;
        int[] p = new int[3];
        for (int i = 0; i < su; i++) {
            for (int j = 0; j < sv; j++) {
                p[u] = o[u] + 16 * i;
                p[v] = o[v] + 16 * j;
                p[a] = edge;
                long sm = SectionPos.asLong(p[0] >> 4, p[1] >> 4, p[2] >> 4);
                p[a] = across;
                long st = SectionPos.asLong(p[0] >> 4, p[1] >> 4, p[2] >> 4);
                read.add(sm);
                read.add(st);
                mine[i * sv + j] = sections.codes(sm);
                theirs[i * sv + j] = sections.codes(st);
                ready &= mine[i * sv + j] != null && theirs[i * sv + j] != null;
            }
        }
        if (!ready) {
            return -1;
        }
        AirflowCells.Table table = AirflowCells.table(); // after the codes: it holds every code they use
        boolean[] fluid = new boolean[table.size()];
        for (int c = 0; c < fluid.length; c++) {
            fluid[c] = table.cell((byte) c).fluid();
        }
        for (AirflowSections.Codes c : mine) {
            if (c.generation() != table.generation()) {
                return -1;
            }
        }
        // column tops, world y: per (x, z) over the face for a band border, per column along it for a side border
        int[] topMine, topTheirs;
        if (a == 1) {
            topMine = new int[nu * nv];
            if (!tops(o[0], o[2], nu, nv, topMine)) {
                return -1;
            }
            topTheirs = topMine;
        } else {
            topMine = new int[nu];
            topTheirs = new int[nu];
            boolean ok = a == 0 ? tops(edge, o[2], 1, nu, topMine) && tops(across, o[2], 1, nu, topTheirs)
                    : tops(o[0], edge, nu, 1, topMine) && tops(o[0], across, nu, 1, topTheirs);
            if (!ok) {
                return -1;
            }
        }
        int count = 0;
        for (int i = 0; i < nu; i++) {
            for (int j = 0; j < nv; j++) {
                p[u] = o[u] + i;
                p[v] = o[v] + j;
                int s = (i >> 4) * sv + (j >> 4);
                p[a] = edge;
                int ym = p[1];
                byte cm = mine[s].code(AirflowSections.Codes.index(p[0] & 15, p[1] & 15, p[2] & 15));
                if ((cm & 0xFF) >= fluid.length || !fluid[cm & 0xFF]) {
                    continue;
                }
                p[a] = across;
                int yt = p[1];
                byte ct = theirs[s].code(AirflowSections.Codes.index(p[0] & 15, p[1] & 15, p[2] & 15));
                if ((ct & 0xFF) >= fluid.length || !fluid[ct & 0xFF]) {
                    continue;
                }
                int column = a == 1 ? i * nv + j : i;
                if (ym < topMine[column] && yt < topTheirs[column]) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Each column's first open height, world y (the heightmap's {@code MOTION_BLOCKING_NO_LEAVES} plus 1), over x in
     * [x0, x0 + nx) and z in [z0, z0 + nz), into {@code out[(x - x0) * nz + (z - z0)]}; false if a chunk isn't loaded.
     */
    private boolean tops(int x0, int z0, int nx, int nz, int[] out) {
        for (int cx = x0 >> 4; cx <= (x0 + nx - 1) >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= (z0 + nz - 1) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    return false;
                }
                int xa = Math.max(x0, cx << 4), xb = Math.min(x0 + nx - 1, (cx << 4) + 15);
                int za = Math.max(z0, cz << 4), zb = Math.min(z0 + nz - 1, (cz << 4) + 15);
                for (int x = xa; x <= xb; x++) {
                    for (int z = za; z <= zb; z++) {
                        out[(x - x0) * nz + (z - z0)] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                x & 15, z & 15) + 1;
                    }
                }
            }
        }
        return true;
    }

    /** The origin of the tile holding the point (x, y, z), world blocks. */
    public int[] tileOf(double x, double y, double z) {
        int[] o = AirflowTiles.origin(level.getMinBuildHeight() + shiftY, level.getMaxBuildHeight(), x - shiftX, y,
                z - shiftZ);
        o[0] += shiftX;
        o[2] += shiftZ;
        return o;
    }

    private Region open(int x0, int y0, int z0) {
        Region r = new Region(nextId++, x0, y0, z0);
        r.background = new AirflowBackground(level, ground, x0, y0, z0, WX, WY, WZ);
        for (long s : r.sections) {
            sections.want(s);
        }
        regions.add(r);
        byTile.put(AirflowTiles.key(x0, y0, z0), r);
        return r;
    }

    private void retire(Region r) {
        if (!regions.remove(r)) {
            return;
        }
        byTile.remove(AirflowTiles.key(r.x0, r.y0, r.z0));
        live.remove(AirflowTiles.key(r.x0, r.y0, r.z0));
        if (r.slot != null) {
            runtime.retire(r.slot);
        }
        for (long s : r.sections) {
            sections.release(s);
        }
    }

    private void service(Region r, long now) {
        if (r.occupied || r.wanted) {
            r.emptySince = -1;
        } else if (r.emptySince < 0) {
            r.emptySince = now;
        } else if (now - r.emptySince >= GRACE_TICKS) {
            retire(r);
            return;
        }
        if (r.slot == null) {
            if (ready(r)) {
                r.slot = runtime.create(factory(r));
                live.put(AirflowTiles.key(r.x0, r.y0, r.z0), r.slot);
                r.built = now;
            }
            return;
        }
        switch (r.slot.status()) {
            case FAILED -> {
                if (r.retryAt < 0) {
                    r.retryAt = now + AirflowRuntime.backoffTicks(r.slot.failures());
                } else if (now >= r.retryAt && ready(r)) {
                    r.slot = runtime.replace(r.slot, factory(r));
                    live.put(AirflowTiles.key(r.x0, r.y0, r.z0), r.slot);
                    r.retryAt = -1;
                    r.built = now;
                }
            }
            case RUNNING -> {
                if ((now - r.built) % WEATHER_TICKS == 0) {
                    weather(r);
                    pollDriven(r);
                    bodies(r);
                }
                if (now > r.built && (now - r.built) % SAVE_TICKS == 0) {
                    r.slot.requestSave();
                }
            }
            default -> {
            }
        }
    }

    private boolean ready(Region r) {
        if (!r.background.ready()) {
            return false;
        }
        for (long s : r.sections) {
            if (!sections.ready(s)) {
                return false;
            }
        }
        return true;
    }

    /** Reads new tiles' ground samples until {@code deadline}, in the order the tiles opened. */
    private void fillBackgrounds(long deadline) {
        for (Region r : regions) {
            if (!r.background.ready()) {
                r.background.fill(deadline);
                if (System.nanoTime() >= deadline) {
                    return;
                }
            }
        }
    }

    /** Gathers what the region is built from (server thread) into a factory run on the air thread. */
    private AirflowRuntime.Factory factory(Region r) {
        AirflowSections.Codes[] codes = new AirflowSections.Codes[r.sections.length];
        AirflowSaved.Section[] saved = new AirflowSaved.Section[r.sections.length];
        boolean any = false;
        List<Integer> cells = new ArrayList<>();
        List<Double> watts = new ArrayList<>(), limits = new ArrayList<>();
        r.driven.clear();
        for (int i = 0; i < codes.length; i++) {
            codes[i] = sections.codes(r.sections[i]);
            saved[i] = savedSection(r.sections[i]);
            any |= saved[i] != null;
            AirflowSections.Sources src = sections.sources(r.sections[i]);
            for (int s = 0; s < src.positions().length; s++) {
                if (src.driven().length > 0 && contains(src.driven(), src.positions()[s])) {
                    continue; // a driven block's whole power comes from the poll
                }
                BlockPos p = BlockPos.of(src.positions()[s]);
                cells.add(((p.getY() - r.y0) * WZ + p.getZ() - r.z0) * WX + p.getX() - r.x0);
                watts.add(src.watts()[s]);
                limits.add(src.limits()[s]);
            }
            for (long d : src.driven()) {
                BlockPos p = BlockPos.of(d);
                AirflowSources.Power power = drivenPower(p);
                r.driven.put(d, power);
                if (!power.isNone()) {
                    cells.add(((p.getY() - r.y0) * WZ + p.getZ() - r.z0) * WX + p.getX() - r.x0);
                    watts.add(power.watts());
                    limits.add(power.limit());
                }
            }
        }
        double[] w = windAt(r);
        float[] lattice = r.background.lattice();
        int[] tops = r.background.columnTops();
        r.sentLattice = lattice;
        r.sentT = lattice[0];
        r.sentWindX = w[0];
        r.sentWindZ = w[1];
        r.geometryChanged = false;
        if (any) {
            restores++;
        }
        AirflowRegionModel.Spec spec = new AirflowRegionModel.Spec(r.x0, r.y0, r.z0, WX, WY, WZ, codes,
                AirflowCells.table(), lattice[0], w[0], w[1], cells.stream().mapToInt(Integer::intValue).toArray(),
                watts.stream().mapToDouble(Double::doubleValue).toArray(),
                limits.stream().mapToDouble(Double::doubleValue).toArray(), any ? saved : null, level.getGameTime(),
                lattice, tops, WeatherConfig.airflowHeatDiffusivity());
        AirflowBorders.Neighbours neighbours = neighboursOf(r.x0, r.y0, r.z0);
        return () -> {
            AirflowRegionModel m = AirflowRegionModel.build(spec);
            m.neighbours(neighbours);
            return m;
        };
    }

    /**
     * M8b: the tiles beside the one at (x0, y0, z0), as its borders read them on the air thread: each side's latest
     * frame while the tile there runs, else null.
     */
    private AirflowBorders.Neighbours neighboursOf(int x0, int y0, int z0) {
        long[] keys = new long[6];
        for (Side side : Side.values()) {
            int a = side.axis(), d = side.high() ? 1 : -1;
            keys[side.ordinal()] = AirflowTiles.key(x0 + (a == 0 ? d * WX : 0), y0 + (a == 1 ? d * WY : 0),
                    z0 + (a == 2 ? d * WZ : 0));
        }
        return side -> {
            AirflowRuntime.Slot s = live.get(keys[side.ordinal()]);
            if (s == null || s.status() != AirflowRuntime.Status.RUNNING) {
                return null;
            }
            AirflowRuntime.Frame f = s.frame();
            return f == null ? null : f.frame();
        };
    }

    private static boolean contains(long[] values, long v) {
        for (long x : values) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    /** A driven block's whole power now: its state's part and its drivers' (server thread). */
    private AirflowSources.Power drivenPower(BlockPos p) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(p);
        AirflowSources.Power byState = AirflowSources.power(state), byDriver = AirflowSources.driven(level, p, state);
        double watts = byState.watts() + byDriver.watts();
        double limit = Double.isNaN(byDriver.limit()) ? byState.limit() : byDriver.limit();
        return watts == 0 ? AirflowSources.Power.NONE : new AirflowSources.Power(watts, limit);
    }

    /** Polls a running region's driven sources and sends those whose power changed. */
    private void pollDriven(Region r) {
        for (var e : r.driven.long2ObjectEntrySet()) {
            BlockPos p = BlockPos.of(e.getLongKey());
            AirflowSources.Power now = drivenPower(p);
            if (!now.equals(e.getValue())) {
                e.setValue(now);
                r.slot.post(new AirflowRegionModel.SourceChange(p.getX() - r.x0, p.getY() - r.y0, p.getZ() - r.z0,
                        now.watts(), now.limit()));
            }
        }
    }

    /** Sends a running region the body heat of every player in its tile. */
    private void bodies(Region r) {
        List<Double> xyzw = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            Vec3 at = p.position().add(0, 0.9, 0);
            if (!p.isSpectator() && r.contains(at.x, at.y, at.z)) {
                xyzw.add(at.x - r.x0);
                xyzw.add(at.y - r.y0);
                xyzw.add(at.z - r.z0);
                xyzw.add(AirflowSources.PLAYER_WATTS);
            }
        }
        if (!xyzw.isEmpty() || r.hadBodies) {
            r.hadBodies = !xyzw.isEmpty();
            r.slot.post(new AirflowRegionModel.Bodies(xyzw.stream().mapToDouble(Double::doubleValue).toArray()));
        }
    }

    /** What a section's chunk holds for it, or null (nothing saved, or the chunk isn't loaded). */
    private AirflowSaved.Section savedSection(long section) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.x(section), SectionPos.z(section));
        if (chunk == null || !chunk.hasData(AirflowContent.CHUNK_DATA)) {
            return null;
        }
        return chunk.getData(AirflowContent.CHUNK_DATA).section(SectionPos.y(section));
    }

    /** Writes every save the air thread has packed into the chunks it covers (server thread). */
    private void writeSaves() {
        for (AirflowRuntime.Save s; (s = runtime.pollSave()) != null; ) {
            write(s.tile());
        }
    }

    private void write(AirflowSaved.Tile t) {
        for (int j = 0; j < t.nsy(); j++) {
            for (int k = 0; k < t.nsz(); k++) {
                for (int i = 0; i < t.nsx(); i++) {
                    AirflowSaved.Section s = t.sections()[(j * t.nsz() + k) * t.nsx() + i];
                    int sx = (t.x0() >> 4) + i, sy = (t.y0() >> 4) + j, sz = (t.z0() >> 4) + k;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
                    if (chunk == null) {
                        sectionsLost++;
                        continue;
                    }
                    if (s.isEmpty() && !chunk.hasData(AirflowContent.CHUNK_DATA)) {
                        continue;
                    }
                    chunk.getData(AirflowContent.CHUNK_DATA).put(sy, s);
                    chunk.setUnsaved(true);
                }
            }
        }
        savesWritten++;
    }

    private void weather(Region r) {
        double[] w = windAt(r);
        float[] lattice = r.background.lattice();
        r.readLattice = lattice;
        boolean moved = false;
        for (int i = 0; i < lattice.length && !moved; i++) {
            moved = Math.abs(lattice[i] - r.sentLattice[i]) >= BACKGROUND_STEP;
        }
        if (moved || r.geometryChanged || Math.hypot(w[0] - r.sentWindX, w[1] - r.sentWindZ) >= WIND_STEP) {
            if (moved) {
                r.sentLattice = lattice;
                r.sentT = lattice[0];
            }
            r.sentWindX = w[0];
            r.sentWindZ = w[1];
            r.geometryChanged = false;
            r.slot.post(new AirflowRegionModel.Weather(r.sentT, w[0], w[1], r.sentLattice,
                    r.background.columnTops()));
        }
    }

    /** The surface wind at the tile's centre: x, z, m/s. */
    private double[] windAt(Region r) {
        double x = r.x0 + WX / 2.0, z = r.z0 + WZ / 2.0;
        WindColumn wind = WindSources.column(level, x, z);
        return new double[] {wind == null ? 0 : wind.surfaceX(), wind == null ? 0 : wind.surfaceZ()};
    }

    @Override
    public void cellChanged(BlockPos pos, byte before, byte after) {
        Region r = regionAt(pos.getX(), pos.getY(), pos.getZ());
        if (r != null && r.slot != null) {
            if (r.readLattice != null && r.readLattice != r.sentLattice
                    && !java.util.Arrays.equals(r.readLattice, r.sentLattice)) {
                // an edit can change the follow weights: the outside air up to it goes first, with the old ones
                r.sentLattice = r.readLattice;
                r.sentT = r.sentLattice[0];
                r.slot.post(new AirflowRegionModel.Weather(r.sentT, r.sentWindX, r.sentWindZ, r.sentLattice,
                        r.background.columnTops()));
            }
            AirflowCells.Table table = AirflowCells.table();
            r.slot.post(new AirflowBatch.Change(pos.getX() - r.x0, pos.getY() - r.y0, pos.getZ() - r.z0,
                    table.cell(before), table.cell(after)));
            r.geometryChanged = true;
        }
    }

    @Override
    public void sourceChanged(BlockPos pos, double before, double after, double limit) {
        Region r = regionAt(pos.getX(), pos.getY(), pos.getZ());
        if (r != null && r.slot != null && !r.driven.containsKey(pos.asLong())) {
            r.slot.post(new AirflowRegionModel.SourceChange(pos.getX() - r.x0, pos.getY() - r.y0,
                    pos.getZ() - r.z0, after, limit));
        }
    }

    @Override
    public void drivenChanged(BlockPos pos, boolean isDriven) {
        Region r = regionAt(pos.getX(), pos.getY(), pos.getZ());
        if (r == null || r.slot == null) {
            return;
        }
        if (isDriven) {
            r.driven.put(pos.asLong(), AirflowSources.Power.NONE); // the next poll sends its power
        } else if (r.driven.remove(pos.asLong()) != null) {
            AirflowSources.Power byState = AirflowSources.power(level.getBlockState(pos));
            r.slot.post(new AirflowRegionModel.SourceChange(pos.getX() - r.x0, pos.getY() - r.y0,
                    pos.getZ() - r.z0, byState.watts(), byState.limit()));
        }
    }

    @Override
    public void sectionDropped(long section) {
        for (Region r : List.copyOf(regions)) {
            if (r.holds(section)) {
                retire(r);
            }
        }
    }

    /** The players that keep tiles alive: non-spectators not aboard a ship. */
    private static List<Anchor> players(ServerLevel level) {
        List<Anchor> out = new ArrayList<>();
        HeatShips ships = HeatShips.get();
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && !ships.aboard(level, p.position(), ABOARD_MARGIN)) {
                out.add(new Anchor(p.getUUID(), p.position()));
            }
        }
        return out;
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        if (Airflow.active()) {
            AirflowRegions r = new AirflowRegions(event.getServer().overworld());
            r.start();
            running = r;
        }
    }

    /** Makes {@code regions} the server's (or none, with null) for game tests; the caller starts and stops it. */
    static void useForTests(AirflowRegions regions) {
        running = regions;
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        AirflowRegions r = running;
        running = null;
        if (r != null) {
            r.stop();
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        AirflowRegions r = running;
        if (r != null && event.getLevel() == r.level) {
            r.tick();
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        AirflowRegions r = running;
        if (r != null && r.recorder != null) {
            r.recorder.tick();
        }
    }
}
