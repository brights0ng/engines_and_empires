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
 * {@link CloudConfig#rebuildTicks()} after it started, when it is due by its refresh target ({@link RebuildSchedule}:
 * 2 s near and for clouds being born or dying, up to 30 s for steady clouds far away).
 *
 * <p>Building shares a CPU budget ({@link CloudTuning#rebuildBudget} cores), charged what each build actually used.
 * Rebuilds go most overdue for their size first ({@link RebuildSchedule#score}): a big storm simply changes in slower
 * steps instead of holding up the small clouds. Sections that came out empty are left out for a few generations.
 * A formation's first generation doesn't wait for the budget. Moving costs nothing: meshes are built around the
 * anchor and drawn wherever it is.
 */
public final class CloudMeshes {

    /**
     * The meshes' vertices: position, the packed light ({@link CloudVoxelizer#pack}) and the surface's direction, for
     * the shader's live lighting (2026-10-07 evening; 20 bytes a vertex, was 16).
     */
    public static final VertexFormat FORMAT = DefaultVertexFormat.POSITION_COLOR_NORMAL;

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
        /** Sections that came out empty, by key, to leave out for a while ({@link RebuildSchedule#skip}). */
        final Map<Long, RebuildSchedule.Empty> empties = new HashMap<>();
        /** Generations started (numbers them, for the empty sections' rechecks). */
        long genCount;
        /** The refresh target (seconds) and distance to the camera (blocks) last worked out. */
        double targetSeconds = RebuildSchedule.NEAR_SECONDS;
        double distance;
        /** Build time (CPU, seconds) of the last finished generation, and how many sections it left out as empty. */
        double lastGenCost;
        int lastSkipped;

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
        /** The sun or moon every section of this generation is lit by (fixed for the generation, so they match). */
        CloudLight light = CloudLight.at(0.9);
        /** The other clouds' shadows every section is shaded with (fixed for the generation). */
        CloudShadows shadows = CloudShadows.EMPTY;
        /** Its number among the formation's generations. */
        long index;
        /** Sections left out as remembered empty, and planned sections that came out empty. */
        int skipped;
        final List<Planned> emptyBuilt = new ArrayList<>();
        /** Build time (CPU, seconds) of its finished builds. */
        double cost;

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
    record Drawable(double x, double z, List<Section> sections, int depthLayer) {
    }

    /** How long an orphaned formation's meshes are kept at most (ticks). */
    static final int ORPHAN_TICKS = 200;
    /**
     * Where a section build goes in the order (lower first): first generations ahead of everything, nearest first;
     * then rebuilds by {@link RebuildSchedule#score} (2026-10-08; replaced "older than 4 rebuild gaps first"). Every
     * section of one formation gets the same value, so a formation's sections keep their own nearest-first order.
     */
    static double priority(Formation fe, Planned p, long gameTime, double remainingSeconds) {
        if (fe.gen.first || fe.liveStart == Long.MIN_VALUE) {
            return -1e18 + p.distance();
        }
        return -RebuildSchedule.score((gameTime - fe.liveStart) / 20.0, fe.targetSeconds, remainingSeconds);
    }
    /** A rebuild running longer than this is taken as stuck (see the watchdog in {@link #tick}). */
    static final long STUCK_NANOS = 60_000_000_000L;

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
                // Sections are anchor-local: what was empty around the old anchor says nothing now.
                fe.empties.clear();
            }
            if (fe.tuning != CloudTuning.version && fe.tuning != -1) {
                // The settings changed: drop the generation being built with the old ones and start over now,
                // instead of finishing it first.
                dropGeneration(fe);
                fe.lastGenStart = Long.MIN_VALUE;
                fe.empties.clear();
            }
            if (fe.shapeRef == null || changed(fe.shapeRef, f, voxel) || fe.tuning != CloudTuning.version) {
                fe.shapeRef = f;
                fe.version++;
                fe.tuning = CloudTuning.version;
                CloudField field = CloudField.of(f);
                fe.bounds = field == null ? null : field.bounds();
            }
            if (fe.bounds == null && fe.gen == null && !fe.live.isEmpty()) {
                // Nothing in it is visible any more (a layer thinned away): take its meshes down instead of leaving
                // the last ones drawn for good (2026-10-08: a dissolved stratocumulus stayed up, 21 s stale).
                releaseAll(fe);
            }
            if (fe.gen != null || fe.bounds == null) {
                if (fe.gen != null && System.nanoTime() - fe.gen.startNanos > STUCK_NANOS) {
                    // Watchdog (Bright, 2026-10-06: clouds stopped churning after a while): a generation this old is
                    // stuck; say why in the log and start over.
                    Generation g = fe.gen;
                    EnginesAndEmpiresMod.LOGGER.warn("Clouds: a rebuild of {} has run for {} s ({}; {} of {} sections "
                                    + "started, {} running, {} done; budget {} ms, mesher queue {}); starting over",
                            f.regionId(), (System.nanoTime() - g.startNanos) / 1_000_000_000L,
                            g.planned() ? "planned" : g.planning.isDone() ? "planning done" : "still planning",
                            g.next, g.todo == null ? -1 : g.todo.size(), g.running.size(), g.done.size(),
                            Math.round(budget * 1000), mesher == null ? 0 : mesher.getQueue().size());
                    dropGeneration(fe);
                    fe.lastGenStart = Long.MIN_VALUE;
                }
                continue;
            }
            double ax = CloudTracker.x(f.anchor(), gameTime);
            double az = CloudTracker.z(f.anchor(), gameTime);
            boolean changing = changing(f);
            fe.distance = RebuildSchedule.boxDistance(fe.bounds, camera.x - ax, camera.y, camera.z - az);
            fe.targetSeconds = RebuildSchedule.targetSeconds(fe.distance, changing, fe.lastGenCost,
                    CloudTuning.rebuildBudget, minGap / 20.0);
            if (fe.built && gameTime < RebuildSchedule.dueTick(fe.liveStart, fe.lastGenStart,
                    Math.round(fe.targetSeconds * 20), Math.round(fe.lastGenNanos / 5e7), minGap)) {
                continue;
            }
            int sectionSize = CloudTuning.sectionSize;
            List<Planned> plan = plan(fe.bounds, camera.x - ax, camera.y, camera.z - az, sectionSize, voxel,
                    CloudConfig.drawDistance());
            // A first generation (unbudgeted, ahead of rebuilds) is only for a formation never built, or laid out
            // around a new anchor. One whose last build came out empty (a cloud still forming, or too thin to show) is
            // a rebuild like any other: before 2026-10-07 those counted as first builds every time, and with dozens
            // of forming clouds they filled the work window and starved every rebuild (clouds stopped churning).
            boolean first = !fe.built || (!fe.live.isEmpty() && !f.anchor().id().equals(fe.liveFrame));
            long index = ++fe.genCount;
            int raw = plan.size();
            int skipped = 0;
            if (!first && !fe.empties.isEmpty()) {
                // Leave out sections that came out empty lately (rechecked now and then: RebuildSchedule).
                List<Planned> kept = new ArrayList<>(plan.size());
                for (Planned p : plan) {
                    if (RebuildSchedule.skip(fe.empties.get(key(p.sx(), p.sy(), p.sz())), p.voxel(), index, gameTime,
                            changing)) {
                        skipped++;
                    } else {
                        kept.add(p);
                    }
                }
                plan = kept;
            }
            List<Planned> toCull = plan;
            // Empty sky is culled on the mesher, so the client tick never pays for it.
            CloudFormation from = fe.shapeRef;
            double time = gameTime;
            CompletableFuture<Culled> planning = CompletableFuture.supplyAsync(
                    () -> cull(from, toCull, sectionSize, time), mesher());
            fe.gen = new Generation(from, time, sectionSize, first, gameTime, raw, planning);
            fe.gen.index = index;
            fe.gen.skipped = skipped;
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level != null) {
                // The shadowing through the cloud is baked for the light from above; the lit side is the shader's.
                fe.gen.light = CloudLight.bakeAt(level.getTimeOfDay(1f));
            }
            fe.gen.shadows = CloudShadows.current();
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
        // The build time (estimated, seconds) each formation still needs to finish its generation.
        Map<Formation, Double> remaining = new IdentityHashMap<>();
        for (Formation fe : FORMATIONS.values()) {
            if (fe.gen != null) {
                inFlight += fe.gen.running.size();
                long left = 0;
                for (long w : fe.gen.work.values()) {
                    inFlightWork += w;
                    left += w;
                }
                if (!fe.gen.planned()) {
                    continue;
                }
                for (int k = fe.gen.next; k < fe.gen.todo.size(); k++) {
                    Planned p = fe.gen.todo.get(k);
                    next.add(new Next(fe, p));
                    left += fe.costs.getOrDefault(key(p.sx(), p.sy(), p.sz()), DEFAULT_COST);
                }
                remaining.put(fe, left / 1e9);
            }
        }
        // The order (2026-10-08): first generations, then the formations most overdue for their refresh target over
        // the work they still need (RebuildSchedule). The sort is stable, so each formation's sections stay nearest
        // first.
        next.sort(Comparator.comparingDouble(n -> priority(n.fe, n.p, gameTime, remaining.getOrDefault(n.fe, 0.0))));
        Set<Formation> blocked = new HashSet<>();
        boolean outOfBudget = false;
        boolean capped = false;
        int threads = CloudConfig.mesherThreads();
        for (Next n : next) {
            Generation g = n.fe.gen;
            long est = n.fe.costs.getOrDefault(key(n.p.sx(), n.p.sy(), n.p.sz()), DEFAULT_COST);
            double limit = g.first ? window : window * REBUILD_SHARE;
            if (inFlight >= MAX_IN_FLIGHT) {
                capped = true;
                break;
            }
            // Every mesher thread always gets a build, however big its estimate; past that the window decides. A
            // rebuild that doesn't fit leaves room for first builds after it (their share of the window is bigger).
            if (inFlight >= threads && inFlightWork + est > limit) {
                capped = true;
                if (g.first) {
                    break;
                }
                blocked.add(n.fe);
                continue;
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
            CloudLight light = g.light;
            CloudShadows shadows = g.shadows;
            long charged = estimate;
            long submitted = System.nanoTime();
            CompletableFuture<Built> fut = CompletableFuture.supplyAsync(
                    () -> build(from, p, sectionSize, time, light, shadows, charged, submitted), mesher());
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
        return RebuildSchedule.plan(b, camX, camY, camZ, sectionSize, voxel, drawDistance);
    }

    static long key(int sx, int sy, int sz) {
        return RebuildSchedule.key(sx, sy, sz);
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
            // Overlapping clouds' faces at the same height would z-fight: each formation gets one of a few depth
            // layers from its id, so one consistently wins (CloudRenderer, clouds.vsh).
            int layer = (int) Math.floorMod(fe.liveFrame.getLeastSignificantBits() ^ fe.liveFrame.getMostSignificantBits(),
                    (long) DEPTH_LAYERS);
            out.add(new Drawable(x, z, fe.live, layer));
        }
        return out;
    }

    /** How many depth layers overlapping formations are spread over (see {@link Drawable#depthLayer}). */
    static final int DEPTH_LAYERS = 4;

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
                        + " Budget %.0f ms banked. Rebuild estimates %.0f ms vs %.0f ms used (%s; charged what was"
                        + " used). Threads %d.",
                LOAD_WINDOW, pct(states[TICK_IDLE]), pct(states[TICK_FREE]), pct(states[TICK_BUDGET]),
                pct(states[TICK_CAP]), budget * 1000, est / 1e6, actual / 1e6,
                actual > 0 ? String.format("estimates %.2fx", (double) est / actual) : "no rebuilds",
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
            String age = fe.liveStart == Long.MIN_VALUE ? "nothing shown yet"
                    : String.format("shown one from %.1f s ago", (lastSeenTick - fe.liveStart) / 20.0);
            out.add(String.format("  %s, %.0f blocks away, refresh every %.1f s: last generation %d in box, %d left"
                            + " out as empty before, culled to %d in %.0f ms, %d with mesh (%d%% of the built ones"
                            + " empty), %.0f ms CPU, %s; now %s",
                    f.anchor().typeId(), fe.distance, fe.targetSeconds, fe.lastRaw, fe.lastSkipped, fe.lastPlanned,
                    fe.lastCullNanos / 1e6, fe.lastMeshed,
                    fe.lastPlanned == 0 ? 0 : Math.round(100.0 * (fe.lastPlanned - fe.lastMeshed) / fe.lastPlanned),
                    fe.lastGenCost * 1000, age, now));
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
                long spent = spent(b);
                if (b.estimate() >= 0) {
                    EST[loadTick] += b.estimate();
                    ACTUAL[loadTick] += spent;
                    // The estimate was only held back; charge what the build really used (2026-10-08: estimates ran
                    // 1.2x high, wasting a sixth of the budget).
                    budget = Math.max(-MAX_DEBT, budget + (b.estimate() - spent) / 1e9);
                }
                g.cost += spent / 1e9;
                g.slowestNanos = Math.max(g.slowestNanos, b.nanos());
                Planned p = b.plan();
                // Smoothed, so one slow or quick build doesn't swing the next estimate.
                fe.costs.merge(key(p.sx(), p.sy(), p.sz()), spent, (old, now) -> (old + now) / 2);
                if (b.mesh() == null) {
                    g.emptyBuilt.add(p);
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
                if (g.field != null) {
                    // Lit and shaded as the meshes were (the wisps read their light from it).
                    g.field.withLight(g.light.x(), g.light.y(), g.light.z(), g.light.strength());
                    g.field.withShadows(g.shadows, g.from, g.time);
                }
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
                fe.lastGenCost = g.cost;
                fe.lastSkipped = g.skipped;
                rememberEmpties(fe, g);
                fe.built = true;
                fe.gen = null;
            }
        }
    }

    private static long lastSeenTick;

    /** The most the budget may be overdrawn by builds that took longer than estimated, seconds. */
    private static final double MAX_DEBT = 2;

    /** What a build used, nanoseconds: its CPU time where the JVM measures it, else its wall time. */
    private static long spent(Built b) {
        return b.cpu() >= 0 ? b.cpu() : b.nanos();
    }

    /** Whether any visible cloud of the formation is being born or dying. */
    private static boolean changing(CloudFormation f) {
        for (CloudShape m : f.members()) {
            if (m.visible() && (m.growth() < 1 || m.decay() > 0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * After generation {@code g} is swapped in: remembers the sections that came out empty, forgets the ones with
     * cloud, and marks which empty ones touch cloud (rechecked sooner: {@link RebuildSchedule}).
     */
    private static void rememberEmpties(Formation fe, Generation g) {
        Set<Long> meshed = new HashSet<>();
        for (Section s : g.done) {
            long k = key(s.sx, s.sy, s.sz);
            meshed.add(k);
            fe.empties.remove(k);
        }
        for (Planned p : g.emptyBuilt) {
            long k = key(p.sx(), p.sy(), p.sz());
            fe.empties.put(k, RebuildSchedule.emptied(k, p.voxel(), g.index, g.startTick, false));
        }
        Iterator<Map.Entry<Long, RebuildSchedule.Empty>> it = fe.empties.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, RebuildSchedule.Empty> e = it.next();
            if (g.startTick - e.getValue().checkedTick() > 4 * RebuildSchedule.EMPTY_MAX_TICKS) {
                // Long out of the plan (left behind as the camera moved): forget it.
                it.remove();
                continue;
            }
            long k = e.getKey();
            int sx = RebuildSchedule.keyX(k), sy = RebuildSchedule.keyY(k), sz = RebuildSchedule.keyZ(k);
            boolean near = meshed.contains(key(sx + 1, sy, sz)) || meshed.contains(key(sx - 1, sy, sz))
                    || meshed.contains(key(sx, sy + 1, sz)) || meshed.contains(key(sx, sy - 1, sz))
                    || meshed.contains(key(sx, sy, sz + 1)) || meshed.contains(key(sx, sy, sz - 1));
            e.setValue(RebuildSchedule.touching(e.getValue(), near, g.index));
        }
    }

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
    private static Built build(CloudFormation f, Planned p, int sectionSize, double time, CloudLight light,
                               CloudShadows shadows, long estimate, long submitted) {
        long start = System.nanoTime();
        long cpuStart = CPU_TIME ? THREADS.getCurrentThreadCpuTime() : -1;
        ByteBufferBuilder memory = new ByteBufferBuilder(1 << 14);
        try {
            BufferBuilder builder = new BufferBuilder(memory, VertexFormat.Mode.QUADS, FORMAT);
        CloudField field = CloudField.of(f);
        if (field != null) {
            field.withLight(light.x(), light.y(), light.z(), light.strength());
            field.withShadows(shadows, f, time);
        }
        CloudVoxelizer.Result result = CloudVoxelizer.buildSection(field, p.voxel(), time, p.sx(), p.sy(),
                    p.sz(), sectionSize, new CloudVoxelizer.VertexSink() {
                        @Override
                        public void vertex(float x, float y, float z, int argb) {
                            builder.addVertex(x, y, z).setColor(argb).setNormal(0, 1, 0);
                        }

                        @Override
                        public void vertex(float x, float y, float z, int argb, float nx, float ny, float nz) {
                            builder.addVertex(x, y, z).setColor(argb).setNormal(nx, ny, nz);
                        }
                    });
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
