package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps the GPU meshes of the cloud formations (PA regions). Client (render) thread, except the meshing itself, which
 * runs on background threads.
 *
 * <h2>Sections</h2>
 * A formation is cut into cubes ({@link CloudTuning#sectionSize} blocks; anchor-local x and z, world y), each meshed
 * on its own at its own voxel size (see {@link CloudVoxelizer#buildSection}). At real proportions a storm is
 * kilometres tall and wide, so the part near the camera is fine while its anvil and top, always far away, are coarse.
 * Sections past the draw distance (horizontally) aren't built.
 *
 * <h2>Detail</h2>
 * By the distance from the camera to the section (3D): the configured voxel size, doubled past each of
 * {@link CloudTuning#lodDistances}, never past {@link CloudTuning#maxVoxel}. Then neighbouring sections are evened out
 * so that no two differ by more than 2x (the coarser one is refined).
 *
 * <h2>Generations</h2>
 * A formation is rebuilt as a whole: every section for the same moment (a generation), uploaded as each finishes but
 * kept hidden, then all swapped in at once. So neighbouring sections always show the cloud at the same moment and
 * meet exactly, and a half-built storm is never drawn. A new generation starts once the last is done and at least
 * {@link CloudConfig#rebuildTicks()} after it started.
 *
 * <p>Building shares a CPU budget ({@link CloudTuning#rebuildBudget} cores), nearest sections first: a big storm
 * simply changes in slower steps instead of the mesher falling behind. A formation's first generation doesn't wait for
 * the budget. Moving costs nothing: meshes are built around the anchor and drawn wherever it is.
 */
public final class CloudMeshes {

    /**
     * How much estimated work (in ticks of every mesher thread's time) may be queued or running at once. Finished
     * builds are only collected once a tick, so the queue must hold more than a tick's work or the threads go idle
     * between ticks; much more and the queue reacts slowly when the camera moves (it is filled nearest first).
     */
    private static final double WORK_WINDOW_TICKS = 2;
    /**
     * The share of that window rebuilds may fill; the rest is kept for first builds, so a new storm always has room
     * to start even while other formations are rebuilding.
     */
    private static final double REBUILD_SHARE = 0.75;
    /** A hard limit on builds in flight, whatever their estimates, as a safety net. */
    private static final int MAX_IN_FLIGHT = 512;
    /** The cost assumed for a section never built before, nanoseconds. */
    private static final long DEFAULT_COST = 2_000_000L;

    private static ThreadPoolExecutor mesher;

    private static final Map<UUID, Formation> FORMATIONS = new HashMap<>();

    /** Unspent build budget, seconds of build time; refills by the budget's share of each tick. */
    private static double budget;

    /** Build time finished in each of the last {@link #LOAD_WINDOW} ticks, for the mesher load. */
    private static final int LOAD_WINDOW = 200;
    private static final long[] LOAD = new long[LOAD_WINDOW];
    private static int loadTick;

    // ---- diagnostics for /eae clouds info, over the same window
    /** Estimated and actual build time of the budgeted (rebuild) builds finished in each tick. */
    private static final long[] EST = new long[LOAD_WINDOW];
    private static final long[] ACTUAL = new long[LOAD_WINDOW];
    /** What the scheduler did each tick: see the TICK_ constants. */
    private static final byte[] TICK_STATE = new byte[LOAD_WINDOW];
    /** Nothing waiting to start. */
    private static final byte TICK_IDLE = 0;
    /** Everything waiting that could start did. */
    private static final byte TICK_FREE = 1;
    /** Work held back because the budget ran out. */
    private static final byte TICK_BUDGET = 2;
    /** Work held back because the work window ({@link #WORK_WINDOW_TICKS}) was full. */
    private static final byte TICK_CAP = 3;
    /** Queue wait, wall time and CPU time of every build finished in each tick, and how many there were. */
    private static final long[] WAIT = new long[LOAD_WINDOW];
    private static final long[] WALL = new long[LOAD_WINDOW];
    private static final long[] CPU = new long[LOAD_WINDOW];
    private static final int[] BUILDS = new int[LOAD_WINDOW];
    /** Builds of dropped generations still queued or running: work the pool does that nothing will use. */
    private static final AtomicInteger ORPHANS = new AtomicInteger();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME = THREADS.isCurrentThreadCpuTimeSupported();

    /** One formation: its drawn meshes and the generation being built. */
    static final class Formation {
        UUID anchorId;
        /**
         * The cluster the drawn meshes ({@link #live}) are laid out around, and the tick their generation started. Can
         * differ from {@link #anchorId} for a while: when the anchor changes (PA absorbed it, or merged groups), the old
         * meshes stay drawn around the old anchor (tracked even as a ghost) until the new ones are built.
         */
        UUID liveFrame;
        long liveStart = Long.MIN_VALUE;
        /**
         * When its PA region disappeared (merged into another), or MIN_VALUE: its meshes stay drawn until the formations
         * that took its clusters have rebuilt with them, or {@link #ORPHAN_TICKS} pass.
         */
        long orphanedAt = Long.MIN_VALUE;
        Set<UUID> orphanMembers = Set.of();
        /** The formation data when the shape last changed noticeably; {@link #version} counts those changes. */
        CloudFormation shapeRef;
        int version;
        /** The {@link CloudTuning#version} {@link #bounds} were worked out with. */
        int tuning = -1;
        /** Anchor-local bounds of the field at {@link #version}: min x, max x, min y, max y, min z, max z. */
        double[] bounds;
        /** Drawn now. */
        List<Section> live = new ArrayList<>();
        Generation gen;
        long lastGenStart = Long.MIN_VALUE;
        /** Real time the last finished generation took. */
        long lastGenNanos;
        long slowestNanos;
        /** Sections the last finished generation planned, and how many of them came out with a mesh. */
        int lastPlanned;
        int lastMeshed;
        /** Sections the last generation's box held before empty sky was culled, and how long the cull took. */
        int lastRaw;
        long lastCullNanos;
        /** Whether a generation has ever finished (until the meshes are thrown away), so rebuilds wait their gap. */
        boolean built;
        /** Last build time per section key, to spend the budget by. */
        final Map<Long, Long> costs = new HashMap<>();

        // ---- the drawn generation, for the in-cloud probe (CloudInterior)
        /** The formation data, game time and section side the drawn meshes were built from. */
        CloudFormation liveFrom;
        double liveTime;
        int liveSectionSize;
        /** Its field (built on the mesher while planning), or null. */
        CloudField liveField;
        /** The voxel size of every section it planned (empty sky culled), by section key. */
        Map<Long, Integer> liveVoxels = Map.of();
    }

    /** One section's mesh. */
    static final class Section {
        final int sx;
        final int sy;
        final int sz;
        final int size;
        VertexBuffer buffer;
        CloudVoxelizer.Result result = CloudVoxelizer.Result.EMPTY;

        Section(int sx, int sy, int sz, int size) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.size = size;
        }
    }

    /** A section planned for a generation. */
    record Planned(int sx, int sy, int sz, int voxel, double distance) {
    }

    /**
     * A generation being built: every planned section at the same moment. It starts by culling empty sky on the
     * mesher ({@link #planning}); {@link #todo} is null until that is done.
     */
    static final class Generation {
        final CloudFormation  from;
        final double time;
        final int sectionSize;
        final boolean first;
        final long startTick;
        final long startNanos = System.nanoTime();
        /** Sections in the box before culling. */
        final int raw;
        final CompletableFuture<Culled> planning;
        List<Planned> todo;
        /** The field the generation was culled with (the same every section builds from). */
        CloudField field;
        long cullNanos;
        int next;
        final List<CompletableFuture<Built>> running = new ArrayList<>();
        /** Each running build's estimated cost (nanoseconds), for the work window. */
        final Map<CompletableFuture<Built>, Long> work = new IdentityHashMap<>();
        final List<Section> done = new ArrayList<>();
        long slowestNanos;

        Generation(CloudFormation from, double time, int sectionSize, boolean first, long startTick,
                   int raw, CompletableFuture<Culled> planning) {
            this.from = from;
            this.time = time;
            this.sectionSize = sectionSize;
            this.first = first;
            this.startTick = startTick;
            this.raw = raw;
            this.planning = planning;
        }

        boolean planned() {
            return todo != null;
        }

        boolean finished() {
            return todo != null && next >= todo.size() && running.isEmpty();
        }
    }

    /** A generation's plan with empty sky culled, the field it was culled with, and how long culling took. */
    record Culled(List<Planned> todo, CloudField field, long nanos) {
    }

    /**
     * A finished background build, waiting to be uploaded on the render thread. {@code estimate} is the cost the
     * budget charged for it (nanoseconds), or -1 for a first build, which isn't budgeted. {@code queued} is how long it
     * sat in the pool's queue, {@code cpu} the CPU time it used (-1 if unknown); {@code nanos} is its wall time.
     */
    record Built(Planned plan, CloudVoxelizer.Result result, MeshData mesh, ByteBufferBuilder memory, long nanos,
                 long estimate, long queued, long cpu) {
        void discard() {
            if (mesh != null) {
                mesh.close();
            }
            memory.close();
        }
    }

    /**
     * What {@link CloudRenderer} draws: sections' uploaded meshes, and where their local origin (the anchor they were
     * built around, as drawn) is now.
     */
    record Drawable(double x, double z, List<Section> sections) {
    }

    /** How long an orphaned formation's meshes are kept at most (ticks). */
    static final int ORPHAN_TICKS = 200;

    private static ThreadPoolExecutor mesher() {
        if (mesher == null) {
            int threads = CloudConfig.mesherThreads();
            mesher = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "Engines and Empires cloud mesher");
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    });
        }
        return mesher;
    }

    /** Once per client tick: collects finished builds, swaps finished generations in, starts new work. */
    public static void tick(long gameTime, Vec3 camera) {
        lastSeenTick = gameTime;
        loadTick = (loadTick + 1) % LOAD_WINDOW;
        LOAD[loadTick] = 0;
        EST[loadTick] = 0;
        ACTUAL[loadTick] = 0;
        WAIT[loadTick] = 0;
        WALL[loadTick] = 0;
        CPU[loadTick] = 0;
        BUILDS[loadTick] = 0;
        TICK_STATE[loadTick] = TICK_IDLE;
        collectFinished();

        List<CloudFormation> formations = CloudTracker.formations();
        Set<UUID> present = new HashSet<>();
        for (CloudFormation f : formations) {
            present.add(f.regionId());
        }
        removeMissing(present, formations, gameTime);
        if (!CloudConfig.enabled()) {
            return;
        }

        int voxel = CloudConfig.voxelSize();
        long minGap = CloudConfig.rebuildTicks();
        for (CloudFormation f : formations) {
            Formation fe = FORMATIONS.computeIfAbsent(f.regionId(), id -> new Formation());
            fe.orphanedAt = Long.MIN_VALUE;
            if (!f.anchor().id().equals(fe.anchorId)) {
                // Meshes are laid out around the anchor; a new anchor means a new generation. The drawn meshes stay
                // (around their own frame) until it is built, and it is built first, not on the rebuild budget.
                dropGeneration(fe);
                fe.anchorId = f.anchor().id();
                fe.shapeRef = null;
                fe.lastGenStart = Long.MIN_VALUE;
            }
            if (fe.tuning != CloudTuning.version && fe.tuning != -1) {
                // The settings changed: drop the generation being built with the old ones and start over now,
                // instead of finishing it first.
                dropGeneration(fe);
                fe.lastGenStart = Long.MIN_VALUE;
            }
            if (fe.shapeRef == null || changed(fe.shapeRef, f, voxel) || fe.tuning != CloudTuning.version) {
                fe.shapeRef = f;
                fe.version++;
                fe.tuning = CloudTuning.version;
                CloudField field = CloudField.of(f);
                fe.bounds = field == null ? null : field.bounds();
            }
            if (fe.gen != null || fe.bounds == null) {
                continue;
            }
            if (fe.built && gameTime - fe.lastGenStart < minGap) {
                continue;
            }
            double ax = CloudTracker.x(f.anchor(), gameTime) + f.originX();
            double az = CloudTracker.z(f.anchor(), gameTime) + f.originZ();
            int sectionSize = CloudTuning.sectionSize;
            List<Planned> plan = plan(fe.bounds, camera.x - ax, camera.y, camera.z - az, sectionSize, voxel,
                    CloudConfig.drawDistance());
            // Empty sky is culled on the mesher, so the client tick never pays for it.
            CloudFormation from = fe.shapeRef;
            double time = gameTime;
            CompletableFuture<Culled> planning = CompletableFuture.supplyAsync(
                    () -> cull(from, plan, sectionSize, time), mesher());
            boolean first = fe.live.isEmpty() || !f.anchor().id().equals(fe.liveFrame);
            fe.gen = new Generation(from, time, sectionSize, first, gameTime, plan.size(), planning);
            fe.lastGenStart = gameTime;
        }

        // Start builds: first generations before rebuilds, then nearest first across all formations, while the work
        // window has room, and rebuilds within the budget (first generations don't wait for it, and have a share of
        // the window of their own).
        budget = Math.min(budget + CloudTuning.rebuildBudget / 20, Math.max(1.0, CloudTuning.rebuildBudget));
        double window = WORK_WINDOW_TICKS * 50_000_000L * CloudConfig.mesherThreads();
        int inFlight = 0;
        long inFlightWork = 0;
        record Next(Formation fe, Planned p) {
        }
        List<Next> next = new ArrayList<>();
        for (Formation fe : FORMATIONS.values()) {
            if (fe.gen != null) {
                inFlight += fe.gen.running.size();
                for (long w : fe.gen.work.values()) {
                    inFlightWork += w;
                }
                if (!fe.gen.planned()) {
                    continue;
                }
                for (int k = fe.gen.next; k < fe.gen.todo.size(); k++) {
                    next.add(new Next(fe, fe.gen.todo.get(k)));
                }
            }
        }
        next.sort(Comparator.<Next>comparingInt(n -> n.fe.gen.first ? 0 : 1)
                .thenComparingDouble(n -> n.p.distance()));
        Set<Formation> blocked = new HashSet<>();
        boolean outOfBudget = false;
        boolean capped = false;
        for (Next n : next) {
            Generation g = n.fe.gen;
            long est = n.fe.costs.getOrDefault(key(n.p.sx(), n.p.sy(), n.p.sz()), DEFAULT_COST);
            // (Sorted first builds first, so once a rebuild hits its limit, nothing after it can start. An idle mesher
            // always takes one build, however big its estimate.)
            double limit = g.first ? window : window * REBUILD_SHARE;
            if (inFlight >= MAX_IN_FLIGHT || (inFlight > 0 && inFlightWork + est > limit)) {
                capped = true;
                break;
            }
            if (blocked.contains(n.fe) || g.todo.get(g.next) != n.p) {
                // Each generation is started in its own order (nearest first); wait for its turn.
                blocked.add(n.fe);
                continue;
            }
            long estimate = -1;
            if (!g.first) {
                double cost = est / 1e9;
                // (A section slower than the whole budget still gets its turn once the budget is full.)
                if (outOfBudget || budget < Math.min(cost, 0.9)) {
                    // Out of budget: rebuilds wait for the next tick; first builds of new clouds carry on.
                    outOfBudget = true;
                    blocked.add(n.fe);
                    continue;
                }
                budget -= cost;
                estimate = est;
            }
            g.next++;
            Planned p = n.p;
            CloudFormation from = g.from;
            double time = g.time;
            int sectionSize = g.sectionSize;
            long charged = estimate;
            long submitted = System.nanoTime();
            CompletableFuture<Built> fut = CompletableFuture.supplyAsync(
                    () -> build(from, p, sectionSize, time, charged, submitted), mesher());
            g.running.add(fut);
            g.work.put(fut, est);
            inFlight++;
            inFlightWork += est;
        }
        TICK_STATE[loadTick] = next.isEmpty() ? TICK_IDLE : capped ? TICK_CAP : outOfBudget ? TICK_BUDGET : TICK_FREE;
    }

    /**
     * The sections a generation builds: every section of the bounds within the draw distance (horizontally), with its
     * voxel size by distance, then evened out so neighbours differ by at most 2x. Nearest first. Camera coordinates
     * are anchor-local x and z, world y.
     */
    static List<Planned> plan(double[] b, double camX, double camY, double camZ, int sectionSize, int voxel,
                              double drawDistance) {
        Map<Long, Planned> plan = new HashMap<>();
        int x0 = Math.floorDiv((int) Math.floor(b[0]), sectionSize), x1 = Math.floorDiv((int) Math.ceil(b[1]), sectionSize);
        int y0 = Math.floorDiv((int) Math.floor(b[2]), sectionSize), y1 = Math.floorDiv((int) Math.ceil(b[3]), sectionSize);
        int z0 = Math.floorDiv((int) Math.floor(b[4]), sectionSize), z1 = Math.floorDiv((int) Math.ceil(b[5]), sectionSize);
        for (int sx = x0; sx <= x1; sx++) {
            for (int sz = z0; sz <= z1; sz++) {
                double hx = axisGap(camX, sx, sectionSize), hz = axisGap(camZ, sz, sectionSize);
                double horizontal = Math.sqrt(hx * hx + hz * hz);
                if (horizontal > drawDistance) {
                    continue;
                }
                for (int sy = y0; sy <= y1; sy++) {
                    double hy = axisGap(camY, sy, sectionSize);
                    double dist = Math.sqrt(horizontal * horizontal + hy * hy);
                    int size = Math.min(CloudTuning.voxelSizeAt(dist, voxel), sectionSize);
                    plan.put(key(sx, sy, sz), new Planned(sx, sy, sz, size, dist));
                }
            }
        }
        // 2:1 balance: refine any section more than twice as coarse as a neighbour, until none is.
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        boolean changed = true;
        for (int pass = 0; changed && pass < 8; pass++) {
            changed = false;
            for (Map.Entry<Long, Planned> e : plan.entrySet()) {
                Planned p = e.getValue();
                int limit = p.voxel();
                for (int[] d : dirs) {
                    Planned n = plan.get(key(p.sx() + d[0], p.sy() + d[1], p.sz() + d[2]));
                    if (n != null) {
                        limit = Math.min(limit, n.voxel() * 2);
                    }
                }
                if (limit < p.voxel()) {
                    e.setValue(new Planned(p.sx(), p.sy(), p.sz(), limit, p.distance()));
                    changed = true;
                }
            }
        }
        List<Planned> out = new ArrayList<>(plan.values());
        out.sort(Comparator.comparingDouble(Planned::distance));
        return out;
    }

    /** Blocks from {@code c} to section {@code index}'s span along one axis (0 inside). */
    private static double axisGap(double c, int index, int size) {
        double lo = (double) index * size;
        double hi = lo + size;
        return c < lo ? lo - c : c > hi ? c - hi : 0;
    }

    static long key(int sx, int sy, int sz) {
        return ((long) (sx & 0x1FFFFF) << 42) | ((long) (sy & 0x1FFFFF) << 21) | (sz & 0x1FFFFF);
    }

    /**
     * Formations whose PA region is gone: keeps their meshes until every formation that took one of their clusters
     * has finished a generation since (so the clusters never blink out), or {@link #ORPHAN_TICKS} pass.
     */
    private static void removeMissing(Set<UUID> present, List<CloudFormation> formations, long gameTime) {
        Map<UUID, UUID> regionOf = new HashMap<>();
        for (CloudFormation f : formations) {
            for (CloudShape m : f.members()) {
                regionOf.put(m.id(), f.regionId());
            }
        }
        Iterator<Map.Entry<UUID, Formation>> it = FORMATIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Formation> me = it.next();
            if (present.contains(me.getKey())) {
                continue;
            }
            Formation fe = me.getValue();
            if (fe.orphanedAt == Long.MIN_VALUE) {
                dropGeneration(fe);
                fe.orphanedAt = gameTime;
                Set<UUID> ids = new HashSet<>();
                if (fe.shapeRef != null) {
                    for (CloudShape m : fe.shapeRef.members()) {
                        ids.add(m.id());
                    }
                }
                fe.orphanMembers = ids;
            }
            boolean covered = true;
            for (UUID id : fe.orphanMembers) {
                UUID region = regionOf.get(id);
                if (region == null) {
                    continue;
                }
                Formation taker = FORMATIONS.get(region);
                if (taker == null || taker.liveStart < fe.orphanedAt) {
                    covered = false;
                    break;
                }
            }
            if (fe.live.isEmpty() || covered || gameTime - fe.orphanedAt > ORPHAN_TICKS || gameTime < fe.orphanedAt) {
                releaseAll(fe);
                it.remove();
            }
        }
    }

    /** The formations with meshes, at their frames' drawn positions at time {@code time}. */
    static List<Drawable> drawables(double time) {
        List<Drawable> out = new ArrayList<>();
        for (Formation fe : FORMATIONS.values()) {
            if (fe.live.isEmpty() || fe.liveFrame == null) {
                continue;
            }
            double x = CloudTracker.drawnX(fe.liveFrame, time);
            double z = CloudTracker.drawnZ(fe.liveFrame, time);
            if (!Double.isFinite(x) || !Double.isFinite(z)) {
                continue;
            }
            out.add(new Drawable(x, z, fe.live));
        }
        return out;
    }

    /** Frees every mesh (leaving a world, turning the renderer off). */
    public static void clear() {
        for (Formation fe : FORMATIONS.values()) {
            releaseAll(fe);
        }
        FORMATIONS.clear();
    }

    /**
     * For {@code /eae clouds info}: formations drawn, sections drawn, their quads, sections still to build, the slowest
     * section of the last generations (ms), the mesher's load over the last 10 seconds (% of one core), the longest
     * last finished generation (ms, real time), and the longest-running generation in progress (ms, real time).
     */
    static long[] stats() {
        long formations = 0, sections = 0, quads = 0, pending = 0, slowest = 0, longest = 0, running = 0;
        long now = System.nanoTime();
        for (Formation fe : FORMATIONS.values()) {
            if (!fe.live.isEmpty()) {
                formations++;
            }
            for (Section s : fe.live) {
                sections++;
                quads += s.result.quads();
            }
            if (fe.gen != null) {
                pending += (fe.gen.planned() ? fe.gen.todo.size() - fe.gen.next : fe.gen.raw) + fe.gen.running.size();
                running = Math.max(running, now - fe.gen.startNanos);
            }
            slowest = Math.max(slowest, fe.slowestNanos);
            longest = Math.max(longest, fe.lastGenNanos);
        }
        long busy = 0;
        for (long l : LOAD) {
            busy += l;
        }
        long load = Math.round(busy / (LOAD_WINDOW * 50e6) * 100);
        return new long[]{formations, sections, quads, pending, slowest / 1_000_000, load, longest / 1_000_000,
                running / 1_000_000};
    }

    /**
     * For {@code /eae clouds info}: why rebuilds take as long as they do. Over the last {@link #LOAD_WINDOW} ticks,
     * the share of ticks the scheduler was idle, started everything it could, or was held back by the budget or the
     * in-flight cap; the budgeted builds' estimated against actual cost; and per formation, how many sections its
     * last generation planned and how many came out with a mesh (the rest were empty sky), plus the generation in
     * progress.
     */
    static List<String> diagnostics() {
        int[] states = new int[4];
        long est = 0, actual = 0, wait = 0, wall = 0, cpu = 0;
        int builds = 0;
        for (int k = 0; k < LOAD_WINDOW; k++) {
            states[TICK_STATE[k]]++;
            est += EST[k];
            actual += ACTUAL[k];
            wait += WAIT[k];
            wall += WALL[k];
            cpu += CPU[k];
            builds += BUILDS[k];
        }
        List<String> out = new ArrayList<>();
        out.add(String.format("Scheduler, last %d ticks: idle %d%%, free %d%%, held by budget %d%%, work window full %d%%."
                        + " Budget %.0f ms banked. Rebuild cost charged %.0f ms vs actual %.0f ms (%s). Threads %d.",
                LOAD_WINDOW, pct(states[TICK_IDLE]), pct(states[TICK_FREE]), pct(states[TICK_BUDGET]),
                pct(states[TICK_CAP]), budget * 1000, est / 1e6, actual / 1e6,
                actual > 0 ? String.format("%.1fx overcharged", (double) est / actual) : "no rebuilds",
                CloudConfig.mesherThreads()));
        ThreadPoolExecutor pool = mesher;
        out.add(String.format("Builds, last %d ticks: %d finished; average queue wait %.1f ms, wall %.2f ms, CPU %s"
                        + " (%s of wall). Pool: %d of %d threads busy, %d queued, %d orphaned builds still pending.",
                LOAD_WINDOW, builds, builds == 0 ? 0 : wait / 1e6 / builds, builds == 0 ? 0 : wall / 1e6 / builds,
                !CPU_TIME ? "n/a" : String.format("%.2f ms", builds == 0 ? 0 : cpu / 1e6 / builds),
                !CPU_TIME || wall == 0 ? "n/a" : Math.round(100.0 * cpu / wall) + "%",
                pool == null ? 0 : pool.getActiveCount(), CloudConfig.mesherThreads(),
                pool == null ? 0 : pool.getQueue().size(), ORPHANS.get()));
        for (CloudFormation f : CloudTracker.formations()) {
            Formation fe = FORMATIONS.get(f.regionId());
            if (fe == null) {
                continue;
            }
            String now = fe.gen == null ? "idle"
                    : !fe.gen.planned() ? String.format("%s generation culling %d sections",
                    fe.gen.first ? "first" : "rebuild", fe.gen.raw)
                    : String.format("%s generation %d/%d started, %d done, %d with mesh",
                    fe.gen.first ? "first" : "rebuild", fe.gen.next, fe.gen.todo.size(),
                    fe.gen.next - fe.gen.running.size(), fe.gen.done.size());
            out.add(String.format("  %s: last generation %d in box, culled to %d in %.0f ms, %d with mesh (%d%% of"
                            + " the built ones empty); now %s",
                    f.anchor().typeId(), fe.lastRaw, fe.lastPlanned, fe.lastCullNanos / 1e6, fe.lastMeshed,
                    fe.lastPlanned == 0 ? 0 : Math.round(100.0 * (fe.lastPlanned - fe.lastMeshed) / fe.lastPlanned),
                    now));
        }
        return out;
    }

    private static long pct(int ticks) {
        return Math.round(100.0 * ticks / LOAD_WINDOW);
    }

    private static void collectFinished() {
        for (Formation fe : FORMATIONS.values()) {
            Generation g = fe.gen;
            if (g == null) {
                continue;
            }
            if (!g.planned()) {
                if (!g.planning.isDone()) {
                    continue;
                }
                try {
                    Culled c = g.planning.join();
                    g.todo = c.todo();
                    g.field = c.field();
                    g.cullNanos = c.nanos();
                } catch (RuntimeException ex) {
                    EnginesAndEmpiresMod.LOGGER.warn("Clouds: planning a cloud's sections failed", ex);
                    fe.gen = null;
                    continue;
                }
            }
            Iterator<CompletableFuture<Built>> it = g.running.iterator();
            while (it.hasNext()) {
                CompletableFuture<Built> fut = it.next();
                if (!fut.isDone()) {
                    continue;
                }
                it.remove();
                g.work.remove(fut);
                Built b;
                try {
                    b = fut.join();
                } catch (RuntimeException ex) {
                    EnginesAndEmpiresMod.LOGGER.warn("Clouds: building a cloud mesh failed", ex);
                    continue;
                }
                LOAD[loadTick] += b.nanos();
                WALL[loadTick] += b.nanos();
                WAIT[loadTick] += b.queued();
                CPU[loadTick] += Math.max(0, b.cpu());
                BUILDS[loadTick]++;
                if (b.estimate() >= 0) {
                    EST[loadTick] += b.estimate();
                    ACTUAL[loadTick] += b.nanos();
                }
                g.slowestNanos = Math.max(g.slowestNanos, b.nanos());
                Planned p = b.plan();
                fe.costs.put(key(p.sx(), p.sy(), p.sz()), b.nanos());
                if (b.mesh() == null) {
                    b.discard();
                    continue;
                }
                Section s = new Section(p.sx(), p.sy(), p.sz(), p.voxel());
                s.result = b.result();
                s.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                s.buffer.bind();
                s.buffer.upload(b.mesh()); // closes the mesh
                VertexBuffer.unbind();
                b.memory().close();
                g.done.add(s);
            }
            if (g.finished()) {
                // Swap the whole formation at once.
                for (Section s : fe.live) {
                    close(s);
                }
                fe.live = g.done;
                fe.liveFrame = g.from.anchor().id();
                fe.liveStart = g.startTick;
                fe.liveFrom = g.from;
                fe.liveTime = g.time;
                fe.liveSectionSize = g.sectionSize;
                fe.liveField = g.field;
                Map<Long, Integer> voxels = new HashMap<>();
                for (Planned p : g.todo) {
                    voxels.put(key(p.sx(), p.sy(), p.sz()), p.voxel());
                }
                fe.liveVoxels = voxels;
                fe.lastGenNanos = System.nanoTime() - g.startNanos;
                fe.slowestNanos = g.slowestNanos;
                fe.lastPlanned = g.todo.size();
                fe.lastMeshed = g.done.size();
                fe.lastRaw = g.raw;
                fe.lastCullNanos = g.cullNanos;
                fe.built = true;
                fe.gen = null;
            }
        }
    }

    private static long lastSeenTick;

    private static void releaseAll(Formation fe) {
        for (Section s : fe.live) {
            close(s);
        }
        fe.live = new ArrayList<>();
        fe.built = false;
        fe.liveFrom = null;
        fe.liveField = null;
        fe.liveVoxels = Map.of();
        dropGeneration(fe);
    }

    /** Throws away the generation being built, if any (its finished meshes, and its running builds once done). */
    private static void dropGeneration(Formation fe) {
        if (fe.gen != null) {
            for (Section s : fe.gen.done) {
                close(s);
            }
            for (CompletableFuture<Built> fut : fe.gen.running) {
                // Let the build finish on its thread, then free what it made. Until then it's an orphan: work the
                // pool does that nothing will use.
                boolean counted = !fut.isDone();
                if (counted) {
                    ORPHANS.incrementAndGet();
                }
                fut.whenComplete((b, ex) -> {
                    if (counted) {
                        ORPHANS.decrementAndGet();
                    }
                    if (b != null) {
                        b.discard();
                    }
                });
            }
            fe.gen = null;
        }
    }

    private static void close(Section s) {
        if (s.buffer != null) {
            s.buffer.close();
            s.buffer = null;
        }
    }

    /** Runs on the mesher thread. */
    private static Built build(CloudFormation f, Planned p, int sectionSize, double time, long estimate,
                               long submitted) {
        long start = System.nanoTime();
        long cpuStart = CPU_TIME ? THREADS.getCurrentThreadCpuTime() : -1;
        ByteBufferBuilder memory = new ByteBufferBuilder(1 << 14);
        try {
            BufferBuilder builder = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            CloudVoxelizer.Result result = CloudVoxelizer.buildSection(CloudField.of(f), p.voxel(), time, p.sx(), p.sy(),
                    p.sz(), sectionSize, (x, y, z, argb) -> builder.addVertex(x, y, z).setColor(argb));
            MeshData mesh = builder.build();
            long cpu = CPU_TIME ? THREADS.getCurrentThreadCpuTime() - cpuStart : -1;
            return new Built(p, result, mesh, memory, System.nanoTime() - start, estimate, start - submitted, cpu);
        } catch (RuntimeException ex) {
            memory.close();
            throw ex;
        }
    }

    /**
     * Runs on the mesher thread: drops the planned sections that can't hold any cloud, by running just the coarse
     * pass of each one's build ({@link CloudVoxelizer#sectionMayHaveCloud}). Exact: a dropped section's build would
     * have come out empty anyway. Keeps the plan's order (nearest first).
     */
    private static Culled cull(CloudFormation f, List<Planned> plan, int sectionSize, double time) {
        long start = System.nanoTime();
        CloudField field = CloudField.of(f);
        List<Planned> kept = new ArrayList<>();
        for (Planned p : plan) {
            if (CloudVoxelizer.sectionMayHaveCloud(field, p.voxel(), time, p.sx(), p.sy(), p.sz(), sectionSize)) {
                kept.add(p);
            }
        }
        return new Culled(kept, field, System.nanoTime() - start);
    }

    /** The formations with meshes drawn, for the in-cloud probe. Render thread. */
    static List<Formation> live() {
        List<Formation> out = new ArrayList<>();
        for (Formation fe : FORMATIONS.values()) {
            if (!fe.live.isEmpty() && fe.liveFrame != null && fe.liveFrom != null && fe.liveField != null) {
                out.add(fe);
            }
        }
        return out;
    }

    /** Whether formation {@code now} differs enough from {@code was} to be worth rebuilding with the new data. */
    static boolean changed(CloudFormation was, CloudFormation now, int voxelSize) {
        if (was.members().size() != now.members().size()) {
            return true;
        }
        for (int k = 0; k < now.members().size(); k++) {
            CloudShape a = was.members().get(k);
            CloudShape b = now.members().get(k);
            if (!a.id().equals(b.id()) || shapeChanged(a, b, voxelSize)) {
                return true;
            }
            // Members of one formation normally move together; if one drifts relative to the anchor (or its layout
            // eases, after a type change), rebuild.
            double dax = was.offsetX(a) - now.offsetX(b);
            double daz = was.offsetZ(a) - now.offsetZ(b);
            if (dax * dax + daz * daz > voxelSize * voxelSize / 4.0) {
                return true;
            }
        }
        return false;
    }

    /** Whether one cluster's shape changed noticeably. */
    static boolean shapeChanged(CloudShape was, CloudShape now, int voxelSize) {
        return Math.abs(was.radius() - now.radius()) > Math.max(voxelSize, 0.03f * was.radius())
                || Math.abs(CloudFormation.spread(was) - CloudFormation.spread(now)) > 0.02 * CloudFormation.spread(was)
                || Math.abs(was.baseY() - now.baseY()) > voxelSize / 2f
                || Math.abs(was.topY() - now.topY()) > Math.max(voxelSize / 2f, 0.01f * (was.topY() - was.baseY()))
                || Math.abs(was.effectiveCoverage() - now.effectiveCoverage()) > 0.03f
                || Math.abs(was.effectiveDensity() - now.effectiveDensity()) > 0.05f
                || Math.abs(was.growth() - now.growth()) > 0.03f
                || Math.abs(was.decay() - now.decay()) > 0.03f
                || Math.abs(was.anvilDecay() - now.anvilDecay()) > 0.03f
                || Math.abs(was.towerStrength() - now.towerStrength()) > 0.05f
                || Math.abs(was.anvilStrength() - now.anvilStrength()) > 0.05f
                || Math.abs(was.edgeSoftness() - now.edgeSoftness()) > 0.05f
                || Math.abs(was.stormDarkness() - now.stormDarkness()) > 0.01f
                || Math.abs(was.baseDarkness() - now.baseDarkness()) > 0.02f
                || was.seed() != now.seed()
                || !Objects.equals(was.typeId(), now.typeId());
    }

    private CloudMeshes() {
    }
}
