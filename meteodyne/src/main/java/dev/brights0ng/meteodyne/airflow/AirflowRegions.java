// Written by Claude (GreedyFluids M7b b3); edited by Claude (b4: ids, timing, the recorder; only assigned regions
// stay alive); edited by Claude (M7d d1: fixed tiles replace player-centred windows; d2: saving and restoring; M7c:
// the background field, driven sources, limits and body heat; M7e: the generator's terrain off the server thread,
// the speed limit and tile cap; M7f: the heat diffusivity from the config; the outside air sent ahead of edits; M8b:
// each region reads its running neighbours' frames for its borders; M8b b4: secondaries, the tiles that run beside a
// player's where enclosed air crosses a border; M8b5b: tiles merged into one region, and split again; M8c c0: the
// recorder's background dump).
// Edited by Claude: the source-velocity radius from [heat] into each region's spec (GreedyFluids M8c c3).
// Edited by Claude: stand-ins, fixed points that keep tiles alive as players do (GreedyFluids M8c c4).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
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
 *   <li><b>Secondaries (M8b b4, B3 with G2; Bright, 2026-11-17):</b> a player standing in enclosed air also keeps
 *       running the tiles that air reaches across a border, at least {@code airflowNeighbourMinCells} cell pairs
 *       across a face, within {@code airflowNeighbourReach} steps of them ({@link AirflowTrace}, walked on the
 *       server thread within {@link #TRACE_BUDGET_NANOS} a tick, from the section cache; sections it misses are
 *       wanted and it is walked again). Shut doors stop it; a player outdoors keeps none. Traces are refreshed every
 *       {@link #FLOOD_TICKS}. A tile in its grace keeps no secondaries, so a secondary goes into its grace with the
 *       player's tile. {@code airflowNeighbourTiles} REPORT counts what would run (at 2, 4 and 8 cells, in every
 *       mode but OFF) without running it.</li>
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
 *   <li><b>Merging (M8b5b, M1; Bright, 2026-11-17 and 11-18):</b> a box of 2 to 4 tiles can run as one region, so
 *       air crosses the borders inside it ({@link AirflowMerge}: the tiles the enclosed air a player is in links,
 *       under {@code airflowMergeTiles} LINKED; any running neighbours under ADJACENT). A box merges once chosen for
 *       {@code airflowMergeSettleSeconds} with every tile running, and a merged region splits into the tiles still
 *       kept once its box hasn't been chosen for as long. Either way the regions running ask for a save, the new
 *       ones are built from it (heat kept, motion from still) while the old keep running, and they swap when the new
 *       are running ({@link Transition}). Edits and sources reach both meanwhile. A merged region counts as its tiles
 *       toward the cap, keeps its tiles' keys, and retires whole.</li>
 *   <li><b>Saving (M7d d2):</b> a running region saves its temperatures into its chunks ({@link AirflowChunkData})
 *       every {@link #SAVE_TICKS}, when it retires and when the server stops; a region is built from what its
 *       chunks hold, decayed toward the background ({@link AirflowSaved}). A save that arrives after its chunk
 *       unloaded is lost; the chunk keeps the save before it.</li>
 *   <li><b>Stand-ins (M8c c4; Bright, 2026-11-20):</b> named fixed points that keep tiles alive as a player standing
 *       there would (traces, secondaries, merging, 500 W of body heat), so one player can profile several sites at
 *       once. Each holds a chunk ticket over the 3×3 tiles around it while it exists ({@link #STAND_IN_RADIUS}); not
 *       saved, gone at a restart. Debug only: {@code /meteodyne airflow standin}.</li>
 * </ul>
 * Started at {@code ServerStartedEvent} in airflow mode, stopped (air thread joined) at {@code ServerStoppingEvent}.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class AirflowRegions implements AirflowSections.Listener {

    /** One tile's size; a merged region is a box of them (M8b5b). */
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
    /** Server-thread time per tick for walking traces, ns (M8b b4). */
    static final long TRACE_BUDGET_NANOS = 200_000L;
    /** Secondaries opened per match at most, so a corner's tiles don't all build at once (M8b b4). */
    static final int MAX_OPENS_PER_MATCH = 1;
    /** Merges and splits under way at once, at most (M8b5b). */
    static final int MAX_TRANSITIONS = 2;
    /** A merge or split not done in this long is given up, ticks (M8b5b; 2 min). */
    static final long TRANSITION_TIMEOUT_TICKS = 2400;
    /**
     * M8c c4: a stand-in's chunk ticket reaches this many chunks from its chunk, so the 3×3 tiles around its tile
     * are loaded (its tile is 4 chunks wide; its neighbours' far sides are at most 7 chunks away).
     */
    static final int STAND_IN_RADIUS = 7;
    private static final TicketType<ChunkPos> STAND_IN_TICKET = TicketType.create("meteodyne_airflow_stand_in",
            Comparator.comparingLong(ChunkPos::toLong));

    private static volatile AirflowRegions running;

    /** One region: its box of tiles (one, or a merged box) and its slot on the air thread. Server thread. */
    public static final class Region {
        private final int id;
        private final int x0, y0, z0;
        /** M8b5b: its size in tiles along each axis (1, 1, 1: one tile), and in cells. */
        private final int tx, ty, tz, nx, ny, nz;
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
        /** M8c c0: the spec it was last built from (the recorder's snapshot of regions already running). */
        private AirflowRegionModel.Spec spec;
        /** The lattice read at the last weather check (M7f: sent ahead of an edit when newer than the one sent). */
        private float[] readLattice;
        private boolean geometryChanged, hadBodies;
        /** Driven sources in the tile, with the power last sent for each. */
        private final Long2ObjectOpenHashMap<AirflowSources.Power> driven = new Long2ObjectOpenHashMap<>();
        /** M8b5b: its tiles that a player or a player's tile kept at the last match (a split keeps these). */
        private final LongOpenHashSet keptTiles = new LongOpenHashSet();
        /** M8b5b: when a merged region's box stopped being chosen (-1: chosen); when its last save was written. */
        private long unchosenSince = -1, savedAt = -1;
        /** M8b5b: the merge or split it is being replaced in, or null. */
        private Transition transition;

        private Region(int id, int x0, int y0, int z0, int tx, int ty, int tz) {
            this.id = id;
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
            nx = tx * WX;
            ny = ty * WY;
            nz = tz * WZ;
            sections = new long[(nx / 16) * (ny / 16) * (nz / 16)];
            int n = 0;
            for (int j = 0; j < ny / 16; j++) {
                for (int k = 0; k < nz / 16; k++) {
                    for (int i = 0; i < nx / 16; i++) {
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

        /** Its size, cells (M8b5b: a merged region's whole box). */
        public int nx() {
            return nx;
        }

        public int ny() {
            return ny;
        }

        public int nz() {
            return nz;
        }

        /** How many tiles it runs (M8b5b: more than 1 when merged). */
        public int tiles() {
            return tx * ty * tz;
        }

        /** Its tiles' keys ({@link AirflowTiles#key}). */
        long[] tileKeys() {
            return AirflowRegions.tileKeys(x0, y0, z0, tx, ty, tz);
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
            return x >= x0 && x < x0 + nx && y >= y0 && y < y0 + ny && z >= z0 && z < z0 + nz;
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
    /**
     * M8b: each tile's current slot and its region's origin (M8b5b: a merged region's under each of its tiles), written
     * on the server thread and read on the air thread for the borders.
     */
    private final Map<Long, Live> live = new ConcurrentHashMap<>();
    private AirflowSections sections;
    private Function<ServerLevel, List<Anchor>> anchors = this::playersAndStandIns;
    /** M8c c4: the stand-ins, by name, in the order added. */
    private final Map<String, StandIn> standIns = new LinkedHashMap<>();
    private final AirflowLimits.Speeds speeds = new AirflowLimits.Speeds(AirflowLimits.WINDOW_TICKS);
    private int nextId = 1;
    private long managerNanos;
    private long savesWritten, sectionsLost, restores, heldForSpeed, closedByCap;
    /** M8b b4: the mode and threshold game tests set (null and 0: the config's). */
    private AirflowNeighbourTiles.Mode neighbourMode;
    private int neighbourMin;
    /** M8b b4: each player's latest finished trace, and the traces under way, by player. */
    private final Map<UUID, Flood> floods = new HashMap<>();
    private final Map<UUID, Job> jobs = new java.util.LinkedHashMap<>();
    /** M8b b4: the sections traces found missing, which this manager wants (one want each). */
    private final LongOpenHashSet stripWanted = new LongOpenHashSet();
    /** M8b b4: secondaries now, and their tile-ticks so far; the same for what would run at each reported threshold. */
    private int secondariesNow;
    private long secondaryTicks, secondariesOpened, secondariesRefused;
    private final int[] wouldNow = new int[AirflowNeighbourTiles.REPORTED.length];
    private final long[] wouldTicks = new long[AirflowNeighbourTiles.REPORTED.length];
    /** The tile grid's shift (game tests only, so a test's structure never straddles a border), blocks. */
    private int shiftX, shiftY, shiftZ;
    private AirflowRecorder recorder;
    /** M8b5b: merges and splits under way, when each chosen box was first chosen, the settings tests set, counts. */
    private final List<Transition> transitions = new ArrayList<>();
    private final Map<BoxKey, Long> chosenSince = new HashMap<>();
    private AirflowMerge.Mode mergeMode;
    private double mergeSettle = Double.NaN;
    private long merges, splits, transitionsAbandoned;

    /** A region's slot and origin, as a neighbour's borders read it (M8b5b). */
    private record Live(AirflowRuntime.Slot slot, int x0, int y0, int z0) {
    }

    /** A box of tiles, as a key: its origin and its size in tiles (M8b5b). */
    private record BoxKey(int x0, int y0, int z0, int tx, int ty, int tz) {
        BoxKey(Region r) {
            this(r.x0, r.y0, r.z0, r.tx, r.ty, r.tz);
        }
    }

    /**
     * A merge or split under way (M8b5b): the regions running now ({@code from}, which asked for a save at
     * {@code started}) and the ones that replace them once those are written and the new ones run ({@code to}).
     */
    private static final class Transition {
        final List<Region> from, to;
        final long started;

        Transition(List<Region> from, List<Region> to, long started) {
            this.from = from;
            this.to = to;
            this.started = started;
        }
    }

    /** The keys of the tiles of the box at (x0, y0, z0), tx × ty × tz tiles. */
    static long[] tileKeys(int x0, int y0, int z0, int tx, int ty, int tz) {
        long[] out = new long[tx * ty * tz];
        int n = 0;
        for (int j = 0; j < ty; j++) {
            for (int k = 0; k < tz; k++) {
                for (int i = 0; i < tx; i++) {
                    out[n++] = AirflowTiles.key(x0 + i * WX, y0 + j * WY, z0 + k * WZ);
                }
            }
        }
        return out;
    }

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

    /** How far a trace walks from the player, steps (M8b b4, G2). */
    public int neighbourReach() {
        return WeatherConfig.airflowNeighbourReach();
    }

    /** Game tests: the neighbour-tile mode and threshold, instead of the config's (null, 0: the config's again). */
    void neighbourTiles(AirflowNeighbourTiles.Mode mode, int minCells) {
        this.neighbourMode = mode;
        this.neighbourMin = minCells;
    }

    /** What merges in force (M8b5b). */
    public AirflowMerge.Mode mergeMode() {
        return mergeMode != null ? mergeMode : WeatherConfig.airflowMergeTiles();
    }

    /** How long a merge or split waits, seconds (M8b5b). */
    public double mergeSettleSeconds() {
        return !Double.isNaN(mergeSettle) ? mergeSettle : WeatherConfig.airflowMergeSettleSeconds();
    }

    /** Game tests: the merge mode and settle time, instead of the config's (null, NaN: the config's again). */
    void mergeTiles(AirflowMerge.Mode mode, double settleSeconds) {
        this.mergeMode = mode;
        this.mergeSettle = settleSeconds;
    }

    /** Merges and splits done, and given up, so far; and under way now (M8b5b). */
    public long merges() {
        return merges;
    }

    public long splits() {
        return splits;
    }

    public long transitionsAbandoned() {
        return transitionsAbandoned;
    }

    public int transitionsNow() {
        return transitions.size();
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

    /** Sections wanted now for traces. */
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
        // M8c c0: the regions already running go into the background dump as built, then with the weather sent last
        for (Region r : regions) {
            if (r.spec != null && r.slot != null && r.slot.status() == AirflowRuntime.Status.RUNNING) {
                recorder.regionBuilt(r.id, r.spec, true);
                if (r.sentLattice != null) {
                    recorder.weatherSent(r.id, new AirflowRegionModel.Weather(r.sentT, r.sentWindX, r.sentWindZ,
                            r.sentLattice, r.background.columnTops()));
                }
            }
        }
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

    /** M8c c4: a stand-in: its name, the id it keeps tiles under, where it stands, and the chunk its ticket is on. */
    public record StandIn(String name, UUID id, Vec3 pos, ChunkPos ticket) {
    }

    /**
     * M8c c4: puts a stand-in named {@code name} at {@code pos} (server thread), moving it if one by that name exists.
     * Returns true when it is new.
     */
    public boolean addStandIn(String name, Vec3 pos) {
        StandIn old = standIns.remove(name);
        if (old != null) {
            level.getChunkSource().removeRegionTicket(STAND_IN_TICKET, old.ticket(), STAND_IN_RADIUS, old.ticket());
        }
        ChunkPos chunk = new ChunkPos(BlockPos.containing(pos));
        level.getChunkSource().addRegionTicket(STAND_IN_TICKET, chunk, STAND_IN_RADIUS, chunk);
        UUID id = UUID.nameUUIDFromBytes(("meteodyne:airflow_stand_in/" + name)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        standIns.put(name, new StandIn(name, id, pos, chunk));
        if (recorder != null) {
            recorder.mark(String.format(java.util.Locale.ROOT, "stand-in %s at %.1f %.1f %.1f", name, pos.x, pos.y,
                    pos.z));
        }
        return old == null;
    }

    /** M8c c4: removes the stand-in named {@code name}; false if there is none. Its tiles go into their grace. */
    public boolean removeStandIn(String name) {
        StandIn s = standIns.remove(name);
        if (s == null) {
            return false;
        }
        level.getChunkSource().removeRegionTicket(STAND_IN_TICKET, s.ticket(), STAND_IN_RADIUS, s.ticket());
        if (recorder != null) {
            recorder.mark("stand-in " + name + " removed");
        }
        return true;
    }

    /** M8c c4: removes every stand-in; returns how many there were. */
    public int clearStandIns() {
        List<String> names = new ArrayList<>(standIns.keySet());
        for (String name : names) {
            removeStandIn(name);
        }
        return names.size();
    }

    /** M8c c4: the stand-ins, in the order added (server thread). */
    public List<StandIn> standIns() {
        return List.copyOf(standIns.values());
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
        clearStandIns();
        stopRecording();
        for (Transition t : List.copyOf(transitions)) {
            abandon(t, false);
        }
        if (!runtime.stop()) {
            Meteodyne.LOGGER.warn("Airflow: the air thread did not stop within 5 s");
        }
        writeSaves(); // the thread packs every running region as it stops; the chunks are still loaded
        for (Region r : List.copyOf(regions)) {
            retire(r);
        }
        floods.clear();
        jobs.clear();
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
        advanceTraces(System.nanoTime() + TRACE_BUDGET_NANOS);
        advanceTransitions(now);
        for (Region r : List.copyOf(regions)) {
            service(r, now);
        }
        managerNanos += System.nanoTime() - start;
    }

    /**
     * Marks the region of each anchor's tile occupied, opening it if it isn't there and the anchor is slow enough;
     * then (M8b b4) marks or opens the secondaries those tiles keep; every other region starts (or continues) its
     * grace. Then (M8b5b) starts the merges and splits due, and closes regions past the tile cap.
     */
    private void match() {
        long now = level.getGameTime();
        double limit = WeatherConfig.airflowSpeedLimit();
        for (Region r : regions) {
            r.occupied = false;
            r.wanted = false;
            r.link = 0;
            r.keptTiles.clear();
        }
        Set<UUID> seen = new HashSet<>();
        List<Sponsor> sponsors = new ArrayList<>();
        for (Anchor a : anchors.apply(level)) {
            Vec3 p = a.pos();
            seen.add(a.id());
            double speed = speeds.record(a.id(), now, p.x, p.y, p.z);
            boolean slow = AirflowLimits.mayOpen(speed, limit);
            int[] o = tileOf(p.x, p.y, p.z);
            long key = AirflowTiles.key(o[0], o[1], o[2]);
            Region home = byTile.get(key);
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
            home.keptTiles.add(key);
            sponsors.add(new Sponsor(a.id(), home, o, p, slow));
        }
        speeds.keep(seen);
        neighbours(now, sponsors);
        merges(now, sponsors);
        for (Region r : AirflowLimits.overCap(capView(), WeatherConfig.airflowCapMode(), WeatherConfig.airflowTileCap())) {
            retire(r);
            closedByCap++;
        }
    }

    /** The regions as the cap sees them. */
    private List<AirflowLimits.Tile<Region>> capView() {
        List<AirflowLimits.Tile<Region>> tiles = new ArrayList<>(regions.size());
        for (Region r : regions) {
            tiles.add(new AirflowLimits.Tile<>(r, r.owner, r.lastOccupied, r.occupied, r.secondary(), r.tiles()));
        }
        return tiles;
    }

    /**
     * A player at this match: the region holding their tile, their tile's origin, where they stand, and whether they
     * may open tiles (M8b b4).
     */
    private static final class Sponsor {
        final UUID id;
        final Region home;
        final int[] tile;
        final Vec3 pos;
        final boolean slow;
        Flood flood;

        Sponsor(UUID id, Region home, int[] tile, Vec3 pos, boolean slow) {
            this.id = id;
            this.home = home;
            this.tile = tile;
            this.pos = pos;
            this.slow = slow;
        }
    }

    /**
     * One player's latest finished trace (M8b b4, G2): the tile it started in (origin), the tiles reached at each
     * reported threshold and at the one in force (relative to that tile), whether the player stood in enclosed air and
     * every cell was known, the cells walked, and the sections it found missing (wanted until a trace needs them no
     * more).
     */
    private record Flood(long tick, int[] home, int min, int reach, boolean enclosed, boolean complete, int walked,
            List<List<AirflowNeighbourTiles.Reached>> reported, List<AirflowNeighbourTiles.Reached> reached,
            LongOpenHashSet sections) {
    }

    /** A trace under way for one player (M8b b4). */
    private record Job(AirflowTrace trace, LiveSpace space, int[] home, int min, int reach, long started) {
    }

    /**
     * M8b b4 (G2): starts a trace for each player whose flood is missing, incomplete, stale ({@link #FLOOD_TICKS}),
     * from another tile or other settings; wants the sections traces found missing and releases the rest; counts what
     * would run; and under ON marks existing secondaries, then opens missing ones within the cap, the speed limit and
     * {@link #MAX_OPENS_PER_MATCH}. A player's latest flood stays in force until the next one finishes.
     */
    private void neighbours(long now, List<Sponsor> sponsors) {
        AirflowNeighbourTiles.Mode mode = neighbourMode();
        if (mode == AirflowNeighbourTiles.Mode.OFF) {
            jobs.clear();
            floods.clear();
            wantStrips(new LongOpenHashSet());
            java.util.Arrays.fill(wouldNow, 0);
            secondariesNow = 0;
            return;
        }
        int min = neighbourMinCells(), reach = neighbourReach();
        Set<UUID> present = new HashSet<>();
        LongOpenHashSet needed = new LongOpenHashSet();
        LongOpenHashSet homes = new LongOpenHashSet();
        for (Sponsor s : sponsors) {
            present.add(s.id);
            int[] home = s.tile; // the player's own tile (M8b5b: its region may be a merged box)
            homes.add(AirflowTiles.key(home[0], home[1], home[2]));
            Flood f = floods.get(s.id);
            Job j = jobs.get(s.id);
            if (j != null && (!java.util.Arrays.equals(j.home(), home) || j.min() != min || j.reach() != reach)) {
                jobs.remove(s.id); // moved to another tile, or the settings changed: start again
                j = null;
            }
            if (j == null && (f == null || !f.complete() || now - f.tick() >= FLOOD_TICKS
                    || !java.util.Arrays.equals(f.home(), home) || f.min() != min || f.reach() != reach)) {
                j = startTrace(s.pos, home, min, reach, now);
                jobs.put(s.id, j);
            }
            if (j != null) {
                needed.addAll(j.space().missing);
            }
            if (f != null) {
                needed.addAll(f.sections());
            }
            s.flood = f;
        }
        jobs.keySet().retainAll(present);
        floods.keySet().retainAll(present);
        wantStrips(needed);
        for (int i = 0; i < AirflowNeighbourTiles.REPORTED.length; i++) {
            LongOpenHashSet would = new LongOpenHashSet();
            for (Sponsor s : sponsors) {
                if (s.flood == null) {
                    continue;
                }
                for (AirflowNeighbourTiles.Reached r : s.flood.reported().get(i)) {
                    long k = keyAt(s.flood.home(), r);
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
                if (s.flood == null) {
                    continue;
                }
                for (AirflowNeighbourTiles.Reached r : s.flood.reached()) {
                    long k = keyAt(s.flood.home(), r);
                    if (homes.contains(k)) {
                        continue; // a player's own tile runs anyway
                    }
                    Region g = byTile.get(k);
                    if (g == null) {
                        missing.add(new Object[] {s, r});
                    } else {
                        keep(g, k, s, r, now);
                    }
                }
            }
            AirflowLimits.CapMode capMode = WeatherConfig.airflowCapMode();
            int cap = WeatherConfig.airflowTileCap(), opened = 0;
            for (Object[] m : missing) {
                Sponsor s = (Sponsor) m[0];
                AirflowNeighbourTiles.Reached r = (AirflowNeighbourTiles.Reached) m[1];
                int[] h = s.flood.home();
                Region g = byTile.get(keyAt(h, r)); // another player's flood may have opened it just now
                if (g == null) {
                    if (!s.slow || opened >= MAX_OPENS_PER_MATCH) {
                        continue;
                    }
                    if (!AirflowLimits.roomFor(capView(), capMode, cap, s.id)) {
                        secondariesRefused++;
                        continue;
                    }
                    g = open(h[0] + r.dx() * WX, h[1] + r.dy() * WY, h[2] + r.dz() * WZ);
                    secondariesOpened++;
                    opened++;
                }
                keep(g, keyAt(h, r), s, r, now);
            }
        }
        int n = 0;
        for (Region r : regions) {
            n += r.secondary() ? 1 : 0;
        }
        secondariesNow = n;
        secondaryTicks += (long) n * SCAN_TICKS;
    }

    /** Marks {@code g} (its tile {@code tile}) as kept by {@code s}'s tile at this match. */
    private static void keep(Region g, long tile, Sponsor s, AirflowNeighbourTiles.Reached r, long now) {
        g.wanted = true;
        if (!g.occupied) {
            g.owner = s.id;
        }
        g.lastOccupied = now;
        g.link = Math.max(g.link, r.link());
        g.keptTiles.add(tile);
    }

    /** The key of the tile {@code r} reaches from the tile at {@code home} (origin). */
    private static long keyAt(int[] home, AirflowNeighbourTiles.Reached r) {
        return AirflowTiles.key(home[0] + r.dx() * WX, home[1] + r.dy() * WY, home[2] + r.dz() * WZ);
    }

    /**
     * M8b5b: chooses each player's box ({@link AirflowMerge}; a tile claimed by an earlier player's box is out for the
     * later ones), splits merged regions whose box hasn't been chosen for the settle time into the tiles still kept,
     * and merges boxes chosen for the settle time once every tile in them runs, inside the box.
     */
    private void merges(long now, List<Sponsor> sponsors) {
        AirflowMerge.Mode mode = mergeMode();
        long settle = Math.round(mergeSettleSeconds() / AirflowRuntime.TICK_SECONDS);
        Map<BoxKey, BoxKey> chosen = new java.util.LinkedHashMap<>();
        if (mode != AirflowMerge.Mode.OFF) {
            LongOpenHashSet claimed = new LongOpenHashSet();
            for (Sponsor s : sponsors) {
                AirflowMerge.Box b = AirflowMerge.choose(candidates(s, mode, claimed), current(s));
                if (b == null) {
                    continue;
                }
                BoxKey box = new BoxKey(s.tile[0] + b.dx() * WX, s.tile[1] + b.dy() * WY, s.tile[2] + b.dz() * WZ,
                        b.sx(), b.sy(), b.sz());
                for (long k : tileKeys(box.x0(), box.y0(), box.z0(), box.tx(), box.ty(), box.tz())) {
                    claimed.add(k);
                }
                chosen.put(box, box);
            }
        }
        for (Region r : List.copyOf(regions)) {
            if (r.tiles() < 2 || r.transition != null) {
                continue;
            }
            if (chosen.containsKey(new BoxKey(r))) {
                r.unchosenSince = -1;
            } else if (r.unchosenSince < 0) {
                r.unchosenSince = now;
            } else if (now - r.unchosenSince >= settle && !r.keptTiles.isEmpty()
                    && transitions.size() < MAX_TRANSITIONS) {
                split(r, now); // no tile kept: it goes into its grace whole instead
            }
        }
        chosenSince.keySet().retainAll(chosen.keySet());
        for (BoxKey box : chosen.keySet()) {
            Region g = byTile.get(AirflowTiles.key(box.x0(), box.y0(), box.z0()));
            if (g != null && new BoxKey(g).equals(box)) {
                chosenSince.remove(box); // already running as one
                continue;
            }
            long since = chosenSince.computeIfAbsent(box, k -> now);
            if (now - since >= settle && transitions.size() < MAX_TRANSITIONS) {
                List<Region> from = mergeable(box);
                if (from != null) {
                    Region to = box(box.x0(), box.y0(), box.z0(), box.tx(), box.ty(), box.tz());
                    start(from, List.of(to), now);
                    merges++;
                }
            }
        }
    }

    /**
     * The tiles around {@code s}'s that may merge with it (M8b5b): those its enclosed air reaches (its latest flood,
     * if from this tile), and under ADJACENT every tile with a region joined to it face to face inside the block; each
     * only while its region runs and no earlier player's box claimed it.
     */
    private AirflowMerge.Tiles candidates(Sponsor s, AirflowMerge.Mode mode, LongOpenHashSet claimed) {
        LongOpenHashSet in = new LongOpenHashSet();
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap links = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        in.add(AirflowTiles.key(s.tile[0], s.tile[1], s.tile[2]));
        Flood f = s.flood;
        if (f != null && java.util.Arrays.equals(f.home(), s.tile)) {
            for (AirflowNeighbourTiles.Reached r : f.reached()) {
                long k = keyAt(f.home(), r);
                in.add(k);
                links.put(k, r.link());
            }
        }
        if (mode == AirflowMerge.Mode.ADJACENT) {
            java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
            boolean[] seen = new boolean[27];
            seen[13] = true;
            queue.add(new int[] {0, 0, 0});
            while (!queue.isEmpty()) {
                int[] t = queue.poll();
                for (Side side : Side.values()) {
                    int a = side.axis(), d = side.high() ? 1 : -1;
                    int x = t[0] + (a == 0 ? d : 0), y = t[1] + (a == 1 ? d : 0), z = t[2] + (a == 2 ? d : 0);
                    int i = ((y + 1) * 3 + (z + 1)) * 3 + (x + 1);
                    if (Math.abs(x) > 1 || Math.abs(y) > 1 || Math.abs(z) > 1 || seen[i]) {
                        continue;
                    }
                    seen[i] = true;
                    long k = AirflowTiles.key(s.tile[0] + x * WX, s.tile[1] + y * WY, s.tile[2] + z * WZ);
                    if (byTile.containsKey(k)) {
                        in.add(k);
                        queue.add(new int[] {x, y, z});
                    }
                }
            }
        }
        int[] o = s.tile;
        return new AirflowMerge.Tiles() {
            @Override
            public boolean in(int dx, int dy, int dz) {
                long k = AirflowTiles.key(o[0] + dx * WX, o[1] + dy * WY, o[2] + dz * WZ);
                if (!in.contains(k) || claimed.contains(k)) {
                    return false;
                }
                Region g = byTile.get(k);
                return g != null && g.slot != null && g.slot.status() == AirflowRuntime.Status.RUNNING;
            }

            @Override
            public int link(int dx, int dy, int dz) {
                return links.getOrDefault(AirflowTiles.key(o[0] + dx * WX, o[1] + dy * WY, o[2] + dz * WZ), 0);
            }
        };
    }

    /** The merged box {@code s}'s tile runs in now, relative to it, or null (it runs alone). */
    private AirflowMerge.Box current(Sponsor s) {
        Region h = s.home;
        if (h.tiles() < 2) {
            return null;
        }
        return new AirflowMerge.Box(Math.floorDiv(h.x0 - s.tile[0], WX), Math.floorDiv(h.y0 - s.tile[1], WY),
                Math.floorDiv(h.z0 - s.tile[2], WZ), h.tx, h.ty, h.tz);
    }

    /**
     * The regions running the tiles of {@code box}, if it can merge now: every tile's region running, inside the box,
     * and in no other merge or split; else null.
     */
    private List<Region> mergeable(BoxKey box) {
        List<Region> from = new ArrayList<>();
        for (long k : tileKeys(box.x0(), box.y0(), box.z0(), box.tx(), box.ty(), box.tz())) {
            Region g = byTile.get(k);
            if (g == null || g.transition != null || g.slot == null
                    || g.slot.status() != AirflowRuntime.Status.RUNNING || g.x0 < box.x0() || g.y0 < box.y0()
                    || g.z0 < box.z0() || g.x0 + g.nx > box.x0() + box.tx() * WX
                    || g.y0 + g.ny > box.y0() + box.ty() * WY || g.z0 + g.nz > box.z0() + box.tz() * WZ) {
                return null;
            }
            if (!from.contains(g)) {
                from.add(g);
            }
        }
        return from;
    }

    /** Splits a merged region into the tiles of it still kept (M8b5b). */
    private void split(Region r, long now) {
        List<Region> to = new ArrayList<>();
        for (int j = 0; j < r.ty; j++) {
            for (int k = 0; k < r.tz; k++) {
                for (int i = 0; i < r.tx; i++) {
                    int x = r.x0 + i * WX, y = r.y0 + j * WY, z = r.z0 + k * WZ;
                    if (r.keptTiles.contains(AirflowTiles.key(x, y, z))) {
                        to.add(box(x, y, z, 1, 1, 1));
                    }
                }
            }
        }
        start(List.of(r), to, now);
        splits++;
    }

    /** Begins replacing {@code from} with {@code to}: the regions running now ask for a save. */
    private void start(List<Region> from, List<Region> to, long now) {
        Transition t = new Transition(from, to, now);
        for (Region f : from) {
            f.transition = t;
            f.slot.requestSave();
        }
        transitions.add(t);
    }

    /**
     * M8b5b: moves each merge or split on: once the regions running now have written a save asked for at its start,
     * builds the new ones (from that save, as a tile reopening is); once they run, swaps them in. Gives up (the regions
     * running now carry on) when one of those stops running, a new one fails, or it takes longer than
     * {@link #TRANSITION_TIMEOUT_TICKS}.
     */
    private void advanceTransitions(long now) {
        for (Transition t : List.copyOf(transitions)) {
            boolean gone = false, saved = true;
            for (Region f : t.from) {
                gone |= !regions.contains(f) || f.slot == null || f.slot.status() != AirflowRuntime.Status.RUNNING;
                saved &= f.savedAt >= t.started;
            }
            if (gone || now - t.started > TRANSITION_TIMEOUT_TICKS) {
                abandon(t, true);
                continue;
            }
            if (!saved) {
                continue;
            }
            boolean running = true, failed = false;
            for (Region n : t.to) {
                if (n.slot == null) {
                    running = false;
                    if (ready(n)) {
                        n.slot = runtime.create(factory(n));
                        n.built = now;
                    }
                } else if (n.slot.status() == AirflowRuntime.Status.FAILED) {
                    failed = true;
                } else {
                    running &= n.slot.status() == AirflowRuntime.Status.RUNNING;
                }
            }
            if (failed) {
                abandon(t, true);
            } else if (running) {
                swap(t);
            }
        }
    }

    /** Gives up a merge or split: the new regions go, the ones running now carry on. */
    private void abandon(Transition t, boolean count) {
        transitions.remove(t);
        for (Region f : t.from) {
            if (f.transition == t) {
                f.transition = null;
            }
        }
        for (Region n : t.to) {
            if (n.slot != null) {
                runtime.retire(n.slot);
            }
            for (long s : n.sections) {
                sections.release(s);
            }
        }
        if (count) {
            transitionsAbandoned++;
        }
    }

    /** Swaps a finished merge or split in: the old regions retire, the new take their tiles. */
    private void swap(Transition t) {
        transitions.remove(t);
        UUID owner = null;
        long last = 0;
        for (Region f : t.from) {
            if (owner == null || f.occupied) {
                owner = f.owner;
            }
            last = Math.max(last, f.lastOccupied);
            f.transition = null;
            retire(f);
        }
        for (Region n : t.to) {
            n.owner = owner;
            n.lastOccupied = last;
            n.wanted = true; // until the next match says otherwise, so no grace starts in between
            regions.add(n);
            for (long k : n.tileKeys()) {
                byTile.put(k, n);
                live.put(k, new Live(n.slot, n.x0, n.y0, n.z0));
            }
        }
    }

    /**
     * A trace from where a player stands: their feet's cell, or their head's when the feet are in a block the cells
     * call solid (a slab, a carpet over snow) and the head's is open.
     */
    private Job startTrace(Vec3 pos, int[] home, int min, int reach, long now) {
        LiveSpace space = new LiveSpace();
        int x = (int) Math.floor(pos.x), y = (int) Math.floor(pos.y), z = (int) Math.floor(pos.z);
        if (space.at(x, y, z) == AirflowTrace.BLOCKED && space.at(x, y + 1, z) == AirflowTrace.OPEN) {
            y++;
        }
        return new Job(new AirflowTrace(x, y, z, reach, home, new int[] {WX, WY, WZ}, space), space, home, min, reach,
                now);
    }

    /** Walks the traces under way until {@code deadline}; a finished one becomes its player's flood. */
    private void advanceTraces(long deadline) {
        for (var it = jobs.entrySet().iterator(); it.hasNext() && System.nanoTime() < deadline; ) {
            var e = it.next();
            Job j = e.getValue();
            if (j.trace().advance(j.space(), deadline)) {
                it.remove();
                floods.put(e.getKey(), flood(j));
            }
        }
    }

    /** A finished trace's flood: the tiles its crossings reach at each threshold. */
    private static Flood flood(Job j) {
        AirflowNeighbourTiles.Faces faces = j.trace().faces();
        List<List<AirflowNeighbourTiles.Reached>> reported = new ArrayList<>();
        for (int n : AirflowNeighbourTiles.REPORTED) {
            reported.add(AirflowNeighbourTiles.reach(faces, n));
        }
        return new Flood(j.started(), j.home(), j.min(), j.reach(), j.trace().enclosed(), j.trace().complete(),
                j.trace().walked(), reported, AirflowNeighbourTiles.reach(faces, j.min()), j.space().missing);
    }

    /** Game tests: a whole trace from (x, y, z), as a player standing there would start one, with no deadline. */
    AirflowTrace traceFrom(double x, double y, double z) {
        int[] home = tileOf(x, y, z);
        Job j = startTrace(new Vec3(x, y, z), home, neighbourMinCells(), neighbourReach(), 0);
        j.trace().advance(j.space(), Long.MAX_VALUE);
        return j.trace();
    }

    /** Wants the sections in {@code needed} not wanted yet, and releases those no longer needed. */
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
     * The live world as a trace sees it (M8b b4, server thread): a cell is {@link AirflowTrace#OPEN} when it is air
     * or an open door (shut doors stop the trace; Bright, 2026-11-17) below its column's open height (the
     * heightmap's {@code MOTION_BLOCKING_NO_LEAVES} plus 1, as {@link AirflowBackground#columnTops} takes it);
     * {@link AirflowTrace#UNKNOWN} while its section isn't in the cache (it is added to {@link #missing}) or its chunk
     * isn't loaded. Section views and column heights are kept for the trace's life.
     */
    private final class LiveSpace implements AirflowTrace.Space {
        final LongOpenHashSet missing = new LongOpenHashSet();
        private final Long2ObjectOpenHashMap<AirflowSections.Codes> codes = new Long2ObjectOpenHashMap<>();
        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap tops =
                new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        private final int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();

        LiveSpace() {
            tops.defaultReturnValue(Integer.MIN_VALUE);
        }

        @Override
        public int at(int x, int y, int z) {
            if (y < minY || y >= maxY) {
                return AirflowTrace.BLOCKED;
            }
            long s = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
            AirflowSections.Codes c = codes.get(s);
            if (c == null) {
                if (missing.contains(s)) {
                    return AirflowTrace.UNKNOWN;
                }
                c = sections.codes(s);
                if (c == null) {
                    missing.add(s);
                    return AirflowTrace.UNKNOWN;
                }
                codes.put(s, c);
            }
            AirflowCells.Table table = AirflowCells.table();
            int code = c.code(AirflowSections.Codes.index(x & 15, y & 15, z & 15)) & 0xFF;
            if (c.generation() != table.generation() || code >= table.size()) {
                return AirflowTrace.UNKNOWN;
            }
            AirflowCells.Kind kind = table.cell((byte) code).kind();
            if (kind == AirflowCells.Kind.SOLID || kind == AirflowCells.Kind.DOOR_SHUT) {
                return AirflowTrace.BLOCKED;
            }
            long column = ((long) x << 32) | (z & 0xFFFFFFFFL);
            int top = tops.get(column);
            if (top == Integer.MIN_VALUE) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) {
                    return AirflowTrace.UNKNOWN;
                }
                top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
                tops.put(column, top);
            }
            return y < top ? AirflowTrace.OPEN : AirflowTrace.BLOCKED;
        }
    }

    /** The origin of the tile holding the point (x, y, z), world blocks. */
    public int[] tileOf(double x, double y, double z) {
        int[] o = AirflowTiles.origin(level.getMinBuildHeight() + shiftY, level.getMaxBuildHeight(), x - shiftX, y,
                z - shiftZ);
        o[0] += shiftX;
        o[2] += shiftZ;
        return o;
    }

    /** Opens a one-tile region and gives it its tile. */
    private Region open(int x0, int y0, int z0) {
        Region r = box(x0, y0, z0, 1, 1, 1);
        regions.add(r);
        byTile.put(AirflowTiles.key(x0, y0, z0), r);
        return r;
    }

    /** A new region over a box of tiles, wanting its sections and reading its ground; not yet given its tiles. */
    private Region box(int x0, int y0, int z0, int tx, int ty, int tz) {
        Region r = new Region(nextId++, x0, y0, z0, tx, ty, tz);
        r.background = new AirflowBackground(level, ground, x0, y0, z0, r.nx, r.ny, r.nz);
        for (long s : r.sections) {
            sections.want(s);
        }
        return r;
    }

    private void retire(Region r) {
        if (!regions.remove(r)) {
            return;
        }
        if (recorder != null) {
            recorder.retired(r.id);
        }
        if (r.transition != null) {
            abandon(r.transition, true); // a region being replaced closed first
        }
        for (long k : r.tileKeys()) {
            byTile.remove(k, r);
            live.computeIfPresent(k, (key, l) -> l.slot() == r.slot ? null : l);
        }
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
                putLive(r);
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
                    putLive(r);
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

    /** Puts a region's slot under each of its tiles for its neighbours' borders. */
    private void putLive(Region r) {
        for (long k : r.tileKeys()) {
            live.put(k, new Live(r.slot, r.x0, r.y0, r.z0));
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

    /** Reads new regions' ground samples until {@code deadline}, in the order they opened (merges' and splits' last). */
    private void fillBackgrounds(long deadline) {
        List<Region> all = new ArrayList<>(regions);
        for (Transition t : transitions) {
            all.addAll(t.to);
        }
        for (Region r : all) {
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
                cells.add(((p.getY() - r.y0) * r.nz + p.getZ() - r.z0) * r.nx + p.getX() - r.x0);
                watts.add(src.watts()[s]);
                limits.add(src.limits()[s]);
            }
            for (long d : src.driven()) {
                BlockPos p = BlockPos.of(d);
                AirflowSources.Power power = drivenPower(p);
                r.driven.put(d, power);
                if (!power.isNone()) {
                    cells.add(((p.getY() - r.y0) * r.nz + p.getZ() - r.z0) * r.nx + p.getX() - r.x0);
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
        AirflowRegionModel.Spec spec = new AirflowRegionModel.Spec(r.x0, r.y0, r.z0, r.nx, r.ny, r.nz, codes,
                AirflowCells.table(), lattice[0], w[0], w[1], cells.stream().mapToInt(Integer::intValue).toArray(),
                watts.stream().mapToDouble(Double::doubleValue).toArray(),
                limits.stream().mapToDouble(Double::doubleValue).toArray(), any ? saved : null, level.getGameTime(),
                lattice, tops, WeatherConfig.airflowHeatDiffusivity(), WeatherConfig.airflowSourceVelocityRadius());
        r.spec = spec;
        if (recorder != null) {
            recorder.regionBuilt(r.id, spec, false);
        }
        AirflowBorders.Neighbours neighbours = neighboursOf(r);
        return () -> {
            AirflowRegionModel m = AirflowRegionModel.build(spec);
            m.neighbours(neighbours);
            return m;
        };
    }

    /**
     * M8b: the regions beside {@code r}, as its borders read them on the air thread: on each side, the latest frame of
     * each running region holding a tile across it (M8b5b: one region may hold several, and a merged region's side
     * may face several), with its origin relative to {@code r}'s.
     */
    private AirflowBorders.Neighbours neighboursOf(Region r) {
        long[][] keys = new long[6][];
        int[] o = {r.x0, r.y0, r.z0}, t = {r.tx, r.ty, r.tz}, w = {WX, WY, WZ};
        for (Side side : Side.values()) {
            int a = side.axis();
            int[] n = t.clone();
            n[a] = 1;
            long[] ks = new long[n[0] * n[1] * n[2]];
            int m = 0;
            for (int j = 0; j < n[1]; j++) {
                for (int k = 0; k < n[2]; k++) {
                    for (int i = 0; i < n[0]; i++) {
                        int[] c = {o[0] + i * WX, o[1] + j * WY, o[2] + k * WZ};
                        c[a] = side.high() ? o[a] + t[a] * w[a] : o[a] - w[a];
                        ks[m++] = AirflowTiles.key(c[0], c[1], c[2]);
                    }
                }
            }
            keys[side.ordinal()] = ks;
        }
        int x0 = r.x0, y0 = r.y0, z0 = r.z0;
        return side -> {
            List<AirflowBorders.Beside> out = new ArrayList<>(1);
            for (long k : keys[side.ordinal()]) {
                Live l = live.get(k);
                if (l == null || l.slot().status() != AirflowRuntime.Status.RUNNING) {
                    continue;
                }
                AirflowRuntime.Frame f = l.slot().frame();
                if (f == null || f.frame() == null) {
                    continue;
                }
                boolean seen = false;
                for (AirflowBorders.Beside b : out) {
                    seen |= b.frame() == f.frame();
                }
                if (!seen) {
                    out.add(new AirflowBorders.Beside(f.frame(), l.x0() - x0, l.y0() - y0, l.z0() - z0));
                }
            }
            return out;
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
                if (recorder != null) {
                    recorder.sourceSent(r.id, p.getX() - r.x0, p.getY() - r.y0, p.getZ() - r.z0, now.watts(),
                            now.limit());
                }
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
        for (StandIn s : standIns.values()) { // M8c c4: a stand-in is warm as a player is
            Vec3 at = s.pos().add(0, 0.9, 0);
            if (r.contains(at.x, at.y, at.z)) {
                xyzw.add(at.x - r.x0);
                xyzw.add(at.y - r.y0);
                xyzw.add(at.z - r.z0);
                xyzw.add(AirflowSources.PLAYER_WATTS);
            }
        }
        if (!xyzw.isEmpty() || r.hadBodies) {
            r.hadBodies = !xyzw.isEmpty();
            double[] packed = xyzw.stream().mapToDouble(Double::doubleValue).toArray();
            r.slot.post(new AirflowRegionModel.Bodies(packed));
            if (recorder != null) {
                recorder.bodiesSent(r.id, packed);
            }
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

    /**
     * Writes every save the air thread has packed into the chunks it covers (server thread), and notes when each
     * running region's was written (M8b5b: a merge or split waits for it).
     */
    private void writeSaves() {
        long now = level.getGameTime();
        for (AirflowRuntime.Save s; (s = runtime.pollSave()) != null; ) {
            write(s.tile());
            for (Region r : regions) {
                if (r.slot == s.slot()) {
                    r.savedAt = now;
                }
            }
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
            AirflowRegionModel.Weather sent = new AirflowRegionModel.Weather(r.sentT, w[0], w[1], r.sentLattice,
                    r.background.columnTops());
            r.slot.post(sent);
            if (recorder != null) {
                recorder.weatherSent(r.id, sent);
            }
        }
    }

    /** The surface wind at the region's centre: x, z, m/s. */
    private double[] windAt(Region r) {
        double x = r.x0 + r.nx / 2.0, z = r.z0 + r.nz / 2.0;
        WindColumn wind = WindSources.column(level, x, z);
        return new double[] {wind == null ? 0 : wind.surfaceX(), wind == null ? 0 : wind.surfaceZ()};
    }

    /**
     * The regions an event at {@code pos} goes to: the one running there and (M8b5b) any built to replace it, so the
     * new one misses nothing between its build and the swap.
     */
    private List<Region> receivers(BlockPos pos) {
        List<Region> out = new ArrayList<>(1);
        Region r = regionAt(pos.getX(), pos.getY(), pos.getZ());
        if (r != null && r.slot != null) {
            out.add(r);
        }
        for (Transition t : transitions) {
            for (Region n : t.to) {
                if (n.slot != null && n.contains(pos.getX(), pos.getY(), pos.getZ())) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    @Override
    public void cellChanged(BlockPos pos, byte before, byte after) {
        for (Region r : receivers(pos)) {
            if (r.readLattice != null && r.readLattice != r.sentLattice
                    && !java.util.Arrays.equals(r.readLattice, r.sentLattice)) {
                // an edit can change the follow weights: the outside air up to it goes first, with the old ones
                r.sentLattice = r.readLattice;
                r.sentT = r.sentLattice[0];
                AirflowRegionModel.Weather sent = new AirflowRegionModel.Weather(r.sentT, r.sentWindX, r.sentWindZ,
                        r.sentLattice, r.background.columnTops());
                r.slot.post(sent);
                if (recorder != null) {
                    recorder.weatherSent(r.id, sent);
                }
            }
            AirflowCells.Table table = AirflowCells.table();
            r.slot.post(new AirflowBatch.Change(pos.getX() - r.x0, pos.getY() - r.y0, pos.getZ() - r.z0,
                    table.cell(before), table.cell(after)));
            if (recorder != null) {
                recorder.editSent(r.id, pos.getX() - r.x0, pos.getY() - r.y0, pos.getZ() - r.z0, table.cell(after));
            }
            r.geometryChanged = true;
        }
    }

    @Override
    public void sourceChanged(BlockPos pos, double before, double after, double limit) {
        for (Region r : receivers(pos)) {
            if (!r.driven.containsKey(pos.asLong())) {
                r.slot.post(new AirflowRegionModel.SourceChange(pos.getX() - r.x0, pos.getY() - r.y0,
                        pos.getZ() - r.z0, after, limit));
                if (recorder != null) {
                    recorder.sourceSent(r.id, pos.getX() - r.x0, pos.getY() - r.y0, pos.getZ() - r.z0, after, limit);
                }
            }
        }
    }

    @Override
    public void drivenChanged(BlockPos pos, boolean isDriven) {
        for (Region r : receivers(pos)) {
            if (isDriven) {
                r.driven.put(pos.asLong(), AirflowSources.Power.NONE); // the next poll sends its power
            } else if (r.driven.remove(pos.asLong()) != null) {
                AirflowSources.Power byState = AirflowSources.power(level.getBlockState(pos));
                r.slot.post(new AirflowRegionModel.SourceChange(pos.getX() - r.x0, pos.getY() - r.y0,
                        pos.getZ() - r.z0, byState.watts(), byState.limit()));
                if (recorder != null) {
                    recorder.sourceSent(r.id, pos.getX() - r.x0, pos.getY() - r.y0, pos.getZ() - r.z0,
                            byState.watts(), byState.limit());
                }
            }
        }
    }

    @Override
    public void sectionDropped(long section) {
        for (Transition t : List.copyOf(transitions)) {
            for (Region n : t.to) {
                if (n.holds(section) && transitions.contains(t)) {
                    abandon(t, true);
                }
            }
        }
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

    /** What keeps tiles alive outside game tests: the players, then the stand-ins (M8c c4). */
    private List<Anchor> playersAndStandIns(ServerLevel level) {
        List<Anchor> out = players(level);
        for (StandIn s : standIns.values()) {
            out.add(new Anchor(s.id(), s.pos()));
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
