// Written by Claude (GreedyFluids M7b b3); edited by Claude (b4: ids, timing, the recorder; only assigned regions
// stay alive); edited by Claude (M7d d1: fixed tiles replace player-centred windows).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.climate.Climate;
import dev.brights0ng.meteodyne.heat.world.HeatShips;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.wind.WindColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 *       Bright, 2026-11-15). Only that tile runs: a room crossing a border sees the background past it until tiles
 *       exchange heat (M8).</li>
 *   <li><b>Life:</b> a region wants its 32 sections from {@link AirflowSections} and is built once all are cached.
 *       It is retired after {@link #GRACE_TICKS} with no player in it (so stepping back and forth across a border
 *       rebuilds nothing), or when one of its sections is dropped (a chunk unload, or a data-map or tag reload). A
 *       failed region is rebuilt from fresh codes after
 *       {@link AirflowRuntime#backoffTicks}. No window for a player aboard a ship, or spectating.</li>
 *   <li><b>Events:</b> block edits and source changes in a built region's tile go to its queue as they happen.</li>
 *   <li><b>Interim inputs (until part c):</b> uniform background and surface wind from the climate at the window
 *       centre, re-sent when they move by {@link #BACKGROUND_STEP} °C or {@link #WIND_STEP} m/s (checked every
 *       {@link #WEATHER_TICKS}); placed-source powers from {@link AirflowSources}.</li>
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
    /** How far outside a ship's bounds a player still counts as aboard, blocks. */
    static final double ABOARD_MARGIN = 1;

    private static volatile AirflowRegions running;

    /** One region: its tile and its slot on the air thread. Server thread. */
    public static final class Region {
        private final int id;
        private final int x0, y0, z0;
        private final long[] sections;
        private AirflowRuntime.Slot slot;
        private boolean occupied;
        private long emptySince = -1, retryAt = -1, built = -1;
        private double sentT = Double.NaN, sentWindX, sentWindZ;

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
    private final List<Region> regions = new ArrayList<>();
    private final Map<Long, Region> byTile = new HashMap<>();
    private AirflowSections sections;
    private Function<ServerLevel, List<Vec3>> anchors = AirflowRegions::players;
    private int nextId = 1;
    private long managerNanos;
    private AirflowRecorder recorder;

    AirflowRegions(ServerLevel level) {
        this.level = level;
        this.runtime = new AirflowRuntime((slot, e) -> Meteodyne.LOGGER.warn("Airflow: region {} failed ({} so far)",
                slot == null ? "runtime" : slot.id(), slot == null ? "-" : slot.failures(), e));
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

    /** Replaces which points keep tiles alive (the players by default; game tests use fixed points). */
    void anchors(Function<ServerLevel, List<Vec3>> anchors) {
        this.anchors = anchors;
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
        int[] o = tile(level, x, y, z);
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
        for (Region r : List.copyOf(regions)) {
            retire(r);
        }
        sections.removeListener(this);
    }

    /** One server tick. */
    void tick() {
        long start = System.nanoTime();
        long now = level.getGameTime();
        runtime.tick(now);
        if (now % SCAN_TICKS == 0) {
            match();
        }
        for (Region r : List.copyOf(regions)) {
            service(r, now);
        }
        managerNanos += System.nanoTime() - start;
    }

    /**
     * Marks the region of each anchor's tile occupied, opening it if it isn't there; every other region starts (or
     * continues) its grace.
     */
    private void match() {
        for (Region r : regions) {
            r.occupied = false;
        }
        for (Vec3 p : anchors.apply(level)) {
            int[] o = tile(level, p.x, p.y, p.z);
            Region home = byTile.get(AirflowTiles.key(o[0], o[1], o[2]));
            if (home == null) {
                home = open(o[0], o[1], o[2]);
            }
            home.occupied = true;
        }
    }

    /** The origin of the tile holding the point (x, y, z), world blocks. */
    static int[] tile(ServerLevel level, double x, double y, double z) {
        return AirflowTiles.origin(level.getMinBuildHeight(), level.getMaxBuildHeight(), x, y, z);
    }

    private Region open(int x0, int y0, int z0) {
        Region r = new Region(nextId++, x0, y0, z0);
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
        if (r.slot != null) {
            runtime.retire(r.slot);
        }
        for (long s : r.sections) {
            sections.release(s);
        }
    }

    private void service(Region r, long now) {
        if (r.occupied) {
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
                    r.retryAt = -1;
                    r.built = now;
                }
            }
            case RUNNING -> {
                if ((now - r.built) % WEATHER_TICKS == 0) {
                    weather(r);
                }
            }
            default -> {
            }
        }
    }

    private boolean ready(Region r) {
        for (long s : r.sections) {
            if (!sections.ready(s)) {
                return false;
            }
        }
        return true;
    }

    /** Gathers what the region is built from (server thread) into a factory run on the air thread. */
    private AirflowRuntime.Factory factory(Region r) {
        AirflowSections.Codes[] codes = new AirflowSections.Codes[r.sections.length];
        List<Integer> cells = new ArrayList<>();
        List<Double> watts = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            codes[i] = sections.codes(r.sections[i]);
            AirflowSections.Sources src = sections.sources(r.sections[i]);
            for (int s = 0; s < src.positions().length; s++) {
                BlockPos p = BlockPos.of(src.positions()[s]);
                cells.add(((p.getY() - r.y0) * WZ + p.getZ() - r.z0) * WX + p.getX() - r.x0);
                watts.add(src.watts()[s]);
            }
        }
        double[] w = weatherAt(r);
        r.sentT = w[0];
        r.sentWindX = w[1];
        r.sentWindZ = w[2];
        AirflowRegionModel.Spec spec = new AirflowRegionModel.Spec(r.x0, r.y0, r.z0, WX, WY, WZ, codes,
                AirflowCells.table(), w[0], w[1], w[2], cells.stream().mapToInt(Integer::intValue).toArray(),
                watts.stream().mapToDouble(Double::doubleValue).toArray());
        return () -> AirflowRegionModel.build(spec);
    }

    private void weather(Region r) {
        double[] w = weatherAt(r);
        if (Math.abs(w[0] - r.sentT) >= BACKGROUND_STEP
                || Math.hypot(w[1] - r.sentWindX, w[2] - r.sentWindZ) >= WIND_STEP) {
            r.sentT = w[0];
            r.sentWindX = w[1];
            r.sentWindZ = w[2];
            r.slot.post(new AirflowRegionModel.Weather(w[0], w[1], w[2]));
        }
    }

    /** The interim outside air at the window centre: temperature, C, and surface wind x, z, m/s. */
    private double[] weatherAt(Region r) {
        double x = r.x0 + WX / 2.0, y = r.y0 + WY / 2.0, z = r.z0 + WZ / 2.0;
        double t = Climate.sample(level, x, y, z).temperature();
        WindColumn wind = WindSources.column(level, x, z);
        return new double[] {t, wind == null ? 0 : wind.surfaceX(), wind == null ? 0 : wind.surfaceZ()};
    }

    @Override
    public void cellChanged(BlockPos pos, byte before, byte after) {
        Region r = regionAt(pos.getX(), pos.getY(), pos.getZ());
        if (r != null && r.slot != null) {
            AirflowCells.Table table = AirflowCells.table();
            r.slot.post(new AirflowBatch.Change(pos.getX() - r.x0, pos.getY() - r.y0, pos.getZ() - r.z0,
                    table.cell(before), table.cell(after)));
        }
    }

    @Override
    public void sourceChanged(BlockPos pos, double before, double after) {
        Region r = regionAt(pos.getX(), pos.getY(), pos.getZ());
        if (r != null && r.slot != null) {
            r.slot.post(new AirflowRegionModel.SourceChange(pos.getX() - r.x0, pos.getY() - r.y0,
                    pos.getZ() - r.z0, after));
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
    private static List<Vec3> players(ServerLevel level) {
        List<Vec3> out = new ArrayList<>();
        HeatShips ships = HeatShips.get();
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && !ships.aboard(level, p.position(), ABOARD_MARGIN)) {
                out.add(p.position());
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
