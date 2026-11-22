// Written by Claude (GreedyFluids M7b b3); edited by Claude (b4: busy time and region counters; M7d d2: saves; M7f:
// per-region busy time, held steps and edit-batch time for the profile; M8b: heat across the tile's borders).
// Edited by Claude: a region whose values run away (non-finite or implausible) fails as Diverged, marked on its slot
// (2026-11-21).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

import dev.brights0ng.greedyfluids.region.AirFrame;

/**
 * The air thread (M7b b3): one daemon thread stepping every GreedyFluids region.
 * <ul>
 *   <li><b>Time.</b> A region's simulated time maps to game ticks from the tick it was built:
 *       {@code (tick - start) × 0.05 s}, less any backlog dropped. The server gives the tick ({@link #tick}).</li>
 *   <li><b>Scheduling.</b> Each turn steps the region furthest behind, if it is at least one step behind; with none,
 *       the thread parks until the next tick. Building a region is one turn.</li>
 *   <li><b>Lag cap.</b> A region more than {@link #LAG_CAP_SECONDS} behind drops its backlog (counted).</li>
 *   <li><b>Events.</b> Each region's queue ({@link Slot#post}) is drained just before its next step and handed to the
 *       model in order.</li>
 *   <li><b>Frames.</b> After every step the region's {@link AirFrame} and the tick it stands for go to the slot's
 *       volatile {@link Slot#frame}.</li>
 *   <li><b>Failure.</b> A region that throws, or whose values run away ({@link Model#problem}: non-finite, or out of
 *       {@link AirflowPlausibility}'s band) after a step or at its build, is {@link Status#FAILED} and dropped; a
 *       runaway is a {@link Diverged}, marked on the slot ({@link Slot#diverged}). Its owner rebuilds it with
 *       {@link #replace}, after {@link #backoffTicks} (the manager rebuilds a first runaway at once, without its
 *       saves).</li>
 *   <li><b>Saves (M7d d2).</b> A running region's state is packed ({@link Model#save}) after the step that follows
 *       {@link Slot#requestSave}, when it is retired, and when the thread stops; the saves wait in
 *       {@link #pollSave} for the owner, who writes them on its own thread.</li>
 * </ul>
 * Pure Java (GreedyFluids, no Minecraft), so the unit tests drive it with fake models through {@link #runOnce}.
 */
public final class AirflowRuntime {

    /** Seconds of game time per tick. */
    public static final double TICK_SECONDS = 0.05;
    /** A region further behind than this drops its backlog, s. */
    public static final double LAG_CAP_SECONDS = 10;
    /** How long the thread parks with nothing to do, at most, ns (a tick or a post wakes it sooner). */
    static final long IDLE_NANOS = 50_000_000L;

    /** A region as the air thread sees it. Everything but construction runs on the air thread. */
    public interface Model {
        /** Applies queued events, in order, at a step boundary. */
        void apply(List<Object> events);

        void step();

        /** Simulated time since the model was built, s. */
        double time();

        /** One step, s. */
        double dt();

        /** Whether the values are still finite. */
        boolean healthy();

        /**
         * What has run away in the region, as a short description, or null when nothing has (2026-11-21): by default
         * non-finite values ({@link #healthy}); the real region adds temperatures out of {@link AirflowPlausibility}'s
         * band.
         */
        default String problem() {
            return healthy() ? null : "non-finite values";
        }

        /** A frame of the current state (may be null for test models). */
        AirFrame publish();

        int boxes();

        /** What the region has taken in so far (zeros for test models). */
        default Counters counters() {
            return Counters.NONE;
        }

        /** The region's state packed for saving, as of game tick {@code tick} (null: nothing to save). */
        default AirflowSaved.Tile save(long tick) {
            return null;
        }
    }

    /**
     * A region's running totals: edits applied, door toggles, events skipped as desyncs, and the watts set now; for the
     * profile (M7f), the steps the velocity hold kept heat-only, and the edit batches applied with their wall time, ns.
     * {@code exchangedJ} is the heat out through the tile's borders to its running neighbours so far, J (M8b; negative:
     * in from them).
     */
    public record Counters(long edits, long toggles, long desyncs, double watts, long held, long editBatches,
            long editNanos, double exchangedJ) {
        public static final Counters NONE = new Counters(0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Builds a model, on the air thread. */
    @FunctionalInterface
    public interface Factory {
        Model build();
    }

    public enum Status { PENDING, RUNNING, FAILED, RETIRED }

    /** A region's values ran away ({@link Model#problem}), as opposed to an error in its code or inputs (2026-11-21). */
    public static final class Diverged extends IllegalStateException {
        Diverged(String message) {
            super(message);
        }
    }

    /** A published frame and the game tick it stands for. */
    public record Frame(AirFrame frame, long tick) {
    }

    /** A region's packed state, the game tick it stands for, and the slot it came from. */
    public record Save(Slot slot, AirflowSaved.Tile tile, long tick) {
    }

    /** One region's place on the air thread: its queue, latest frame and counters. */
    public static final class Slot {
        private final long id;
        private final Factory factory;
        private final ConcurrentLinkedQueue<Object> events = new ConcurrentLinkedQueue<>();
        private volatile Status status = Status.PENDING;
        private volatile Frame frame;
        private volatile String failure;
        private volatile int failures;
        private volatile boolean diverged;
        private volatile double lag, stepMs;
        private volatile int boxes;
        private volatile long steps, drops;
        private volatile long busyNanos;
        private volatile Counters counters = Counters.NONE;
        private volatile boolean saveWanted;
        // Air thread only
        private Model model;
        private long startTick;
        private double dropped;

        private Slot(long id, Factory factory, int failures) {
            this.id = id;
            this.factory = factory;
            this.failures = failures;
        }

        /** Queues an event for the region's next step (dropped once the region failed or retired). */
        public void post(Object event) {
            Status s = status;
            if (s == Status.PENDING || s == Status.RUNNING) {
                events.add(event);
            }
        }

        /** Asks for the region's state to be saved after its next step. */
        public void requestSave() {
            saveWanted = true;
        }

        public long id() {
            return id;
        }

        public Status status() {
            return status;
        }

        /** The latest frame, or null before the region is built. */
        public Frame frame() {
            return frame;
        }

        /** Why the region failed, or null. */
        public String failure() {
            return failure;
        }

        /** Failures of this region and the ones it replaced. */
        public int failures() {
            return failures;
        }

        /** Whether this slot failed because its values ran away ({@link Diverged}), not on an error. */
        public boolean diverged() {
            return diverged;
        }

        /** How far behind game time the region was at its last turn, s. */
        public double lag() {
            return lag;
        }

        /** Mean wall time of an event batch and step, ms (exponential, weight 0.1). */
        public double stepMs() {
            return stepMs;
        }

        public int boxes() {
            return boxes;
        }

        public long steps() {
            return steps;
        }

        /** Wall time the air thread has spent on this region's turns (events, step, frame and saves), ns. */
        public long busyNanos() {
            return busyNanos;
        }

        /** Backlogs dropped by the lag cap. */
        public long drops() {
            return drops;
        }

        /** Events waiting for the next step. */
        public int queued() {
            return events.size();
        }

        /** The region's counters after its last step. */
        public Counters counters() {
            return counters;
        }
    }

    private final ConcurrentLinkedQueue<Slot> builds = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Slot> retires = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Save> saves = new ConcurrentLinkedQueue<>();
    private final List<Slot> running = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private static final AtomicLong RUNTIMES = new AtomicLong();
    private final String threadName = "Meteodyne airflow #" + RUNTIMES.incrementAndGet();
    private final BiConsumer<Slot, Throwable> onFailure;
    private volatile long tick;
    private volatile boolean stopping;
    private volatile Thread thread;
    private volatile long busyNanos;

    /** {@code onFailure} hears each failure, on the air thread (slot null for an error of the runtime's own). */
    public AirflowRuntime(BiConsumer<Slot, Throwable> onFailure) {
        this.onFailure = onFailure;
    }

    /** Queues a new region; it is built on the air thread. */
    public Slot create(Factory factory) {
        return enqueue(new Slot(ids.incrementAndGet(), factory, 0));
    }

    /** Retires {@code old} and queues its rebuild, which keeps its failure count (for the backoff). */
    public Slot replace(Slot old, Factory factory) {
        retire(old);
        return enqueue(new Slot(ids.incrementAndGet(), factory, old.failures));
    }

    /** Retires a region: its model is dropped at the air thread's next turn. */
    public void retire(Slot slot) {
        retires.add(slot);
        wake();
    }

    /** The current game tick (server thread, every tick). */
    public void tick(long gameTick) {
        tick = gameTick;
        wake();
    }

    /** How long to wait before rebuilding a region that failed {@code failures} times: 5 s, doubling to 60 s, ticks. */
    public static long backoffTicks(int failures) {
        return Math.min(1200L, 100L << Math.max(0, Math.min(failures - 1, 4)));
    }

    /** Starts the air thread. */
    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        stopping = false;
        Thread t = new Thread(this::loop, threadName);
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Stops the air thread and waits for it (at most 5 s). Returns whether it stopped. */
    public synchronized boolean stop() {
        Thread t = thread;
        if (t == null) {
            return true;
        }
        stopping = true;
        LockSupport.unpark(t);
        try {
            t.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
        return !t.isAlive();
    }

    /** Whether the air thread is running. */
    public boolean alive() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    /** Wall time the air thread has spent working (building, stepping, retiring), ns. */
    public long busyNanos() {
        return busyNanos;
    }

    /** The air thread's name ("Meteodyne airflow #n", one number per runtime). */
    public String threadName() {
        return threadName;
    }

    /** The oldest save not yet taken, or null. */
    public Save pollSave() {
        return saves.poll();
    }

    private Slot enqueue(Slot slot) {
        builds.add(slot);
        wake();
        return slot;
    }

    private void wake() {
        Thread t = thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    private void loop() {
        while (!stopping) {
            boolean worked;
            long start = System.nanoTime();
            try {
                worked = runOnce();
            } catch (Throwable e) {
                // runOnce catches each region's own exceptions; this is anything else, kept from killing the thread
                worked = false;
                onFailure.accept(null, e);
            }
            if (worked) {
                busyNanos += System.nanoTime() - start;
            }
            if (!worked && !stopping) {
                LockSupport.parkNanos(this, IDLE_NANOS);
            }
        }
        for (Slot s : running) {
            save(s);
            s.model = null;
            s.status = Status.RETIRED;
        }
        running.clear();
    }

    /**
     * One turn: retirements, then one build, else one step of the region furthest behind. Returns whether anything
     * was done. The air thread's loop; tests call it directly instead of starting the thread.
     */
    boolean runOnce() {
        boolean worked = false;
        for (Slot s; (s = retires.poll()) != null; ) {
            running.remove(s);
            if (s.model != null && s.status == Status.RUNNING) {
                save(s);
            }
            s.model = null;
            s.events.clear();
            if (s.status != Status.FAILED) {
                s.status = Status.RETIRED;
            }
            worked = true;
        }
        for (Slot b; (b = builds.poll()) != null; ) {
            if (b.status == Status.PENDING) {
                build(b);
                return true;
            }
        }
        long now = tick;
        Slot best = null;
        double bestLag = 0;
        for (Slot s : running) {
            double lag = (now - s.startTick) * TICK_SECONDS - s.dropped - s.model.time();
            if (lag > LAG_CAP_SECONDS) {
                s.dropped += lag;
                s.drops++;
                lag = 0;
            }
            s.lag = lag;
            if (lag >= s.model.dt() && lag > bestLag) {
                best = s;
                bestLag = lag;
            }
        }
        if (best == null) {
            return worked;
        }
        step(best);
        return true;
    }

    private void build(Slot s) {
        try {
            Model m = s.factory.build();
            String problem = m.problem();
            if (problem != null) {
                throw new Diverged(problem + " at the build");
            }
            s.model = m;
            s.startTick = tick;
            s.boxes = m.boxes();
            s.counters = m.counters();
            s.frame = new Frame(m.publish(), s.startTick);
            s.status = Status.RUNNING;
            running.add(s);
        } catch (RuntimeException | StackOverflowError e) {
            fail(s, e);
        }
    }

    private void step(Slot s) {
        try {
            long start = System.nanoTime();
            List<Object> events = new ArrayList<>();
            for (Object e; (e = s.events.poll()) != null; ) {
                events.add(e);
            }
            if (!events.isEmpty()) {
                s.model.apply(events);
            }
            s.model.step();
            String problem = s.model.problem();
            if (problem != null) {
                throw new Diverged(problem + " after a step");
            }
            double ms = (System.nanoTime() - start) / 1e6;
            s.stepMs = s.steps == 0 ? ms : 0.9 * s.stepMs + 0.1 * ms;
            s.steps++;
            s.boxes = s.model.boxes();
            s.counters = s.model.counters();
            s.frame = new Frame(s.model.publish(),
                    s.startTick + Math.round((s.model.time() + s.dropped) / TICK_SECONDS));
            if (s.saveWanted) {
                s.saveWanted = false;
                save(s);
            }
            s.busyNanos += System.nanoTime() - start; // one writer: the air thread
        } catch (RuntimeException | StackOverflowError e) {
            fail(s, e);
        }
    }

    /** Packs a running region's state into the save queue; a failure to pack is reported, not fatal to the region. */
    private void save(Slot s) {
        try {
            long at = s.frame == null ? s.startTick : s.frame.tick();
            AirflowSaved.Tile tile = s.model.save(at);
            if (tile != null) {
                saves.add(new Save(s, tile, at));
            }
        } catch (RuntimeException e) {
            onFailure.accept(s, e);
        }
    }

    private void fail(Slot s, Throwable e) {
        running.remove(s);
        s.model = null;
        s.events.clear();
        s.failures++;
        s.diverged = e instanceof Diverged;
        s.failure = e.toString();
        s.status = Status.FAILED;
        onFailure.accept(s, e);
    }
}
