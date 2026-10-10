package dev.brights0ng.enginesandempires.weather.forecast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.climate.SeasonSource;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.sim.SimMath;
import dev.brights0ng.enginesandempires.weather.sim.SystemsSim;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The forecaster (phase 7b of {@code claude/weather-backbone-phase7.md}): the one way to ask for a forecast, for
 * commands now and instruments later ({@link #request}).
 *
 * <h2>How a request is answered</h2>
 * <ul>
 *   <li><b>Always after the delay</b> (Bright, 2026-10-09: 30 real seconds by default): the forecaster has that long
 *       to work it out. A forecast already made for the area this cache window is reused (the work is saved, not the
 *       wait).</li>
 *   <li><b>Regions:</b> everyone inside one region (512 blocks for the day forecast, 2048 for the longer one) shares a
 *       forecast, reused for an in-game hour (day) or six (longer); see {@link ForecastSettings}.</li>
 *   <li><b>Work:</b> on the server thread a snapshot of the weather around the region is taken
 *       ({@link ForecastSnapshot}, well under a millisecond); then one low-priority background thread runs it
 *       ({@link ForecastRun}). One forecast is worked out at a time; requests for a region already queued or running
 *       join it; at most {@link ForecastSettings#queue()} wait.</li>
 *   <li><b>Jumps</b> ({@code /eae weather step}, the night skip, debug spawns and clears) throw cached forecasts away
 *       ({@link #invalidate}); waiting requests are answered from a fresh run.</li>
 * </ul>
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class ForecastService {

    /** What to do with the answer. Called on the server thread. */
    public interface Callback {
        void ready(Forecast forecast);

        void failed(String why);
    }

    /** One region of one product. */
    record Key(Forecast.Product product, int rx, int rz) {

        static Key of(Forecast.Product product, double x, double z) {
            return new Key(product, Math.floorDiv((int) Math.floor(x), product.region),
                    Math.floorDiv((int) Math.floor(z), product.region));
        }

        double centreX() {
            return (rx + 0.5) * product.region;
        }

        double centreZ() {
            return (rz + 0.5) * product.region;
        }
    }

    private static final class Job {
        final Key key;
        final long window;
        final List<Pending> waiters = new ArrayList<>();
        int generation;
        /** Set on the worker; read on the server thread only once {@link #finished}. */
        volatile Forecast result;
        volatile boolean failed;
        volatile long nanos;
        boolean finished;
        boolean stale;

        Job(Key key, long window) {
            this.key = key;
            this.window = window;
        }
    }

    private static final class Pending {
        final Key key;
        final long due;
        final Callback callback;
        Job job;
        Forecast ready;
        boolean warned;

        Pending(Key key, long due, Callback callback) {
            this.key = key;
            this.due = due;
            this.callback = callback;
        }
    }

    private record Cached(Forecast forecast, long window, int generation) {
    }

    private static final Map<Key, Cached> CACHE = new HashMap<>();
    /** Jobs queued or running, by region. */
    private static final Map<Key, Job> JOBS = new HashMap<>();
    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final ConcurrentLinkedQueue<Job> DONE = new ConcurrentLinkedQueue<>();
    private static Job running;
    private static int generation;
    private static ForecastSettings settings;
    private static ExecutorService worker;
    private static int runsLogged;
    /** Game tests: the delay in seconds instead of the config's (negative: the config's). */
    private static volatile int testDelaySeconds = -1;

    /** Game tests only: answer after {@code seconds} instead of the configured delay (negative restores it). */
    public static void setDelayForTests(int seconds) {
        testDelaySeconds = seconds;
    }

    /**
     * Asks for a {@code product} forecast for the region around (x, z). The answer comes to {@code callback} on the
     * server thread after the delay. Returns false (and calls nothing) if the Overworld's weather isn't running or
     * the queue is full.
     */
    public static boolean request(ServerLevel level, double x, double z, Forecast.Product product, Callback callback) {
        ServerLevel overworld = level.getServer().overworld();
        WeatherSim sim = WeatherSim.of(overworld);
        if (sim == null) {
            return false;
        }
        ForecastSettings now = settings();
        Key key = Key.of(product, x, z);
        long window = Math.floorDiv(sim.time(), now.cacheTicks(product));
        int delay = testDelaySeconds >= 0 ? testDelaySeconds : now.delaySeconds();
        Pending pending = new Pending(key, System.currentTimeMillis() + 1000L * delay, callback);
        Cached cached = CACHE.get(key);
        if (cached != null && cached.window == window && cached.generation == generation) {
            pending.ready = cached.forecast;
        } else {
            Job job = JOBS.get(key);
            if (job == null || job.stale) {
                if (QUEUE.size() >= now.queue()) {
                    return false;
                }
                job = new Job(key, window);
                JOBS.put(key, job);
                QUEUE.add(job);
            }
            attach(pending, job);
        }
        PENDING.add(pending);
        return true;
    }

    /** Throws away forecasts made before a jump in the weather; waiting requests get fresh ones. */
    public static void invalidate(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) {
            return;
        }
        generation++;
        CACHE.clear();
        if (running != null) {
            running.stale = true;
        }
        WeatherSim sim = WeatherSim.of(level);
        for (Pending p : PENDING) {
            if (p.ready == null || sim == null) {
                continue;
            }
            p.ready = null;
            Job job = JOBS.get(p.key);
            if (job == null || job.stale) {
                job = new Job(p.key, Math.floorDiv(sim.time(), settings().cacheTicks(p.key.product)));
                JOBS.put(p.key, job);
                QUEUE.add(job);
            }
            attach(p, job);
        }
    }

    /** How many requests are waiting for an answer, and how many forecasts are queued (debug). */
    public static int[] load() {
        return new int[]{PENDING.size(), QUEUE.size() + (running != null ? 1 : 0)};
    }

    private static void attach(Pending p, Job job) {
        p.job = job;
        job.waiters.add(p);
    }

    private static ForecastSettings settings() {
        ForecastSettings now = WeatherConfig.forecast();
        if (!now.equals(settings)) {
            settings = now;
            CACHE.clear();
        }
        return now;
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty() && QUEUE.isEmpty() && running == null && DONE.isEmpty()) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        collect(level);
        start(level);
        deliver();
    }

    /** Takes in finished jobs. */
    private static void collect(ServerLevel level) {
        Job job;
        while ((job = DONE.poll()) != null) {
            job.finished = true;
            if (running == job) {
                running = null;
            }
            if (job.stale || job.generation != generation) {
                // A jump happened while it ran: work it out again for whoever waits.
                if (JOBS.get(job.key) == job) {
                    JOBS.remove(job.key);
                }
                WeatherSim sim = WeatherSim.of(level);
                if (!job.waiters.isEmpty() && sim != null) {
                    Job again = JOBS.get(job.key);
                    boolean fresh = again == null || again.stale;
                    if (fresh) {
                        again = new Job(job.key, Math.floorDiv(sim.time(), settings().cacheTicks(job.key.product)));
                    }
                    for (Pending p : job.waiters) {
                        attach(p, again);
                    }
                    if (fresh) {
                        JOBS.put(job.key, again);
                        QUEUE.addFirst(again);
                    }
                }
                continue;
            }
            if (JOBS.get(job.key) == job) {
                JOBS.remove(job.key);
            }
            if (!job.failed && job.result != null) {
                CACHE.put(job.key, new Cached(job.result, job.window, job.generation));
                if (runsLogged < 5) {
                    runsLogged++;
                    EnginesAndEmpiresMod.LOGGER.info("Weather: {} forecast for {}, {} worked out in {} ms",
                            job.key.product.id, Math.round(job.key.centreX()), Math.round(job.key.centreZ()),
                            job.nanos / 1_000_000);
                }
            }
        }
    }

    /** Starts the next job if the worker is free. */
    private static void start(ServerLevel level) {
        if (running != null || QUEUE.isEmpty()) {
            return;
        }
        Job job = QUEUE.poll();
        WeatherSim sim = WeatherSim.of(level);
        ForecastSnapshot snapshot;
        try {
            if (sim == null) {
                throw new IllegalStateException("no weather simulation");
            }
            snapshot = snapshot(level, sim, job.key, job.window, settings());
        } catch (RuntimeException e) {
            EnginesAndEmpiresMod.LOGGER.warn("Weather: couldn't take a forecast snapshot", e);
            job.failed = true;
            DONE.add(job);
            return;
        }
        job.generation = generation;
        running = job;
        worker().submit(() -> {
            long started = System.nanoTime();
            try {
                job.result = ForecastRun.run(snapshot);
                if (job.result == null) {
                    job.failed = true;
                }
            } catch (Throwable t) {
                EnginesAndEmpiresMod.LOGGER.warn("Weather: a forecast run failed", t);
                job.failed = true;
            }
            job.nanos = System.nanoTime() - started;
            DONE.add(job);
        });
    }

    /** Answers requests whose time has come. */
    private static void deliver() {
        long now = System.currentTimeMillis();
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (now < p.due) {
                continue;
            }
            Forecast f = p.ready;
            if (f == null && p.job != null && p.job.finished && !p.job.stale) {
                f = p.job.result;
                if (f == null) {
                    it.remove();
                    p.callback.failed("the forecaster couldn't work it out (see the server log)");
                    continue;
                }
            }
            if (f == null) {
                if (!p.warned && now > p.due + 10_000) {
                    p.warned = true;
                    EnginesAndEmpiresMod.LOGGER.warn("Weather: a {} forecast is taking longer than its delay",
                            p.key.product.id);
                }
                continue;
            }
            it.remove();
            p.callback.ready(f);
        }
    }

    /** Copies what the forecast for {@code key} needs from the world (server thread). */
    static ForecastSnapshot snapshot(ServerLevel level, WeatherSim sim, Key key, long window, ForecastSettings st) {
        Forecast.Product product = key.product;
        long salt = SimMath.hash(level.getSeed(), 0xF0CA57L, product.ordinal(), key.rx, key.rz, window);
        return snapshot(level, sim, key, ForecastSnapshot.domain(product), salt, level.getDayTime(), st);
    }

    /**
     * As {@link #snapshot(ServerLevel, WeatherSim, Key, long, ForecastSettings)} over field domain {@code domain}
     * ({west, east, north/south} of the region's centre), with re-roll salt {@code salt} and Minecraft day time
     * {@code dayTime} for the simulation's present (the scoring tool reads truth through small snapshots, and keeps
     * its own day time while {@code /eae weather step} runs the weather ahead of the clock).
     */
    static ForecastSnapshot snapshot(ServerLevel level, WeatherSim sim, Key key, double[] domain, long salt,
                                     long dayTime, ForecastSettings st) {
        Forecast.Product product = key.product;
        double cx = key.centreX();
        double cz = key.centreZ();
        SystemsSim systems = sim.forecastSystems(salt);
        AtmosphereField field = sim.field().copyDomain(cx - domain[0], cz - domain[2], cx + domain[1], cz + domain[2]);
        return new ForecastSnapshot(product, cx, cz, level.getSeed(), level.getSeaLevel(), systems, field, sim.time(),
                dayTime, SeasonSource.yearFraction(level), SeasonSource.yearTicks(level), st.stepTicks(product),
                st.days(), WeatherConfig.heightCoolingScale(), WeatherConfig.maxHeightCooling());
    }

    private static ExecutorService worker() {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "EAE Forecaster");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        return worker;
    }

    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        CACHE.clear();
        JOBS.clear();
        QUEUE.clear();
        PENDING.clear();
        DONE.clear();
        running = null;
        settings = null;
    }

    private ForecastService() {
    }
}
