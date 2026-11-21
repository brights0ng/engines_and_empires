// Written by Claude (GreedyFluids M7b b3); edited by Claude (M7d d2: saves).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.meteodyne.airflow.AirflowRuntime.Slot;
import dev.brights0ng.meteodyne.airflow.AirflowRuntime.Status;
import org.junit.jupiter.api.Test;

/** GreedyFluids M7b b3: the air thread's scheduling, lag cap, frames, event drain and failures, with fake regions. */
class AirflowRuntimeTest {

    /** A region that only counts: 0.5 s steps, a log of what happened, and a step on which it throws or goes bad. */
    static class Fake implements AirflowRuntime.Model {
        final String name;
        final List<String> log;
        int steps;
        int throwAt = -1, badAt = -1;
        final List<List<Object>> applied = new ArrayList<>();

        Fake(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void apply(List<Object> events) {
            applied.add(List.copyOf(events));
            log.add(name + " apply " + events.size());
        }

        @Override
        public void step() {
            steps++;
            if (steps == throwAt) {
                throw new IllegalStateException("boom");
            }
            log.add(name + " step");
        }

        @Override
        public double time() {
            return steps * 0.5;
        }

        @Override
        public double dt() {
            return 0.5;
        }

        @Override
        public boolean healthy() {
            return steps != badAt;
        }

        @Override
        public AirFrame publish() {
            return null;
        }

        @Override
        public int boxes() {
            return 7;
        }
    }

    /** A fake that can be saved: its tile is empty, and it counts its saves. */
    static final class Saving extends Fake {
        int saves;

        Saving(String name, List<String> log) {
            super(name, log);
        }

        @Override
        public AirflowSaved.Tile save(long tick) {
            saves++;
            return new AirflowSaved.Tile(0, 0, 0, 1, 1, 1, new AirflowSaved.Section[] {
                    new AirflowSaved.Section(tick, new int[0], new float[0])});
        }
    }

    private final List<String> log = new ArrayList<>();
    private final List<Slot> failed = new ArrayList<>();
    private final AirflowRuntime runtime = new AirflowRuntime((s, e) -> failed.add(s));

    private void drain() {
        for (int i = 0; i < 1000 && runtime.runOnce(); i++) {
            // until nothing is left to do
        }
    }

    @Test
    void stepsTheRegionFurthestBehindFirst() {
        Fake a = new Fake("a", log), b = new Fake("b", log);
        runtime.tick(0);
        Slot sb = runtime.create(() -> b);
        assertTrue(runtime.runOnce(), "a build is a turn");
        runtime.tick(10);
        Slot sa = runtime.create(() -> a);
        runtime.runOnce();
        assertEquals(Status.RUNNING, sa.status());
        assertEquals(Status.RUNNING, sb.status());
        runtime.tick(30); // b is 1.5 s behind, a 1.0 s
        runtime.runOnce();
        assertEquals(List.of("b step"), log, "the region furthest behind goes first");
        drain();
        assertEquals(3, b.steps, "b catches up to 1.5 s");
        assertEquals(2, a.steps, "a catches up to 1.0 s");
        assertFalse(runtime.runOnce(), "nothing left once both are within a step");
    }

    @Test
    void framesFollowEveryStepWithTheTickTheyStandFor() {
        Fake a = new Fake("a", log);
        runtime.tick(100);
        Slot s = runtime.create(() -> a);
        runtime.runOnce();
        assertEquals(100, s.frame().tick(), "the built region's first frame is its start tick");
        assertEquals(7, s.boxes());
        runtime.tick(110);
        runtime.runOnce();
        assertEquals(110, s.frame().tick(), "one 0.5 s step is 10 ticks");
        runtime.tick(140);
        runtime.runOnce();
        assertEquals(120, s.frame().tick(), "a frame after every step, not only when caught up");
        drain();
        assertEquals(140, s.frame().tick());
        assertEquals(4, s.steps());
    }

    @Test
    void theLagCapDropsTheBacklog() {
        Fake a = new Fake("a", log);
        runtime.tick(0);
        Slot s = runtime.create(() -> a);
        runtime.runOnce();
        runtime.tick(400); // 20 s behind
        assertFalse(runtime.runOnce(), "past the cap the backlog is dropped, not stepped");
        assertEquals(1, s.drops());
        assertEquals(0, a.steps);
        assertEquals(0, s.lag(), 1e-12);
        runtime.tick(410);
        assertTrue(runtime.runOnce());
        assertEquals(1, a.steps, "only the time since the drop is stepped");
        assertEquals(410, s.frame().tick(), "frames still stand for game ticks after a drop");
        runtime.tick(410 + 199); // 9.95 s behind: under the cap
        drain();
        assertEquals(1, s.drops());
        assertEquals(1 + 19, a.steps);
    }

    @Test
    void eventsDrainInOrderJustBeforeTheNextStep() {
        Fake a = new Fake("a", log);
        runtime.tick(0);
        Slot s = runtime.create(() -> a);
        s.post("one");
        runtime.runOnce(); // the build does not apply anything
        s.post("two");
        s.post("three");
        assertEquals(3, s.queued());
        assertFalse(runtime.runOnce(), "a caught-up region keeps its events for its next step");
        assertTrue(a.applied.isEmpty());
        runtime.tick(10);
        runtime.runOnce();
        assertEquals(List.of(List.of("one", "two", "three")), a.applied);
        assertEquals(List.of("a apply 3", "a step"), log, "applied once, before the step");
        assertEquals(0, s.queued());
        runtime.tick(20);
        runtime.runOnce();
        assertEquals(1, a.applied.size(), "no events, no apply");
    }

    @Test
    void aThrowingRegionFailsAndIsRebuiltWithItsCount() {
        Fake bad = new Fake("bad", log);
        bad.throwAt = 2;
        runtime.tick(0);
        Slot s = runtime.create(() -> bad);
        runtime.runOnce();
        runtime.tick(40);
        drain();
        assertEquals(Status.FAILED, s.status());
        assertEquals(List.of(s), failed);
        assertEquals(1, s.failures());
        assertTrue(s.failure().contains("boom"));
        assertEquals(10, s.frame().tick(), "the last good frame stays");
        s.post("ignored");
        assertEquals(0, s.queued(), "a failed region takes no events");

        Fake good = new Fake("good", log);
        Slot again = runtime.replace(s, () -> good);
        assertEquals(1, again.failures(), "a rebuild keeps the count for its backoff");
        runtime.runOnce();
        assertEquals(Status.RUNNING, again.status());
        assertEquals(Status.FAILED, s.status(), "retiring a failed slot keeps its failure");
        runtime.tick(60);
        drain();
        assertEquals(2, good.steps);
    }

    @Test
    void nonFiniteValuesAndBuildErrorsAreFailures() {
        Fake bad = new Fake("bad", log);
        bad.badAt = 1;
        runtime.tick(0);
        Slot s = runtime.create(() -> bad);
        Slot broken = runtime.create(() -> {
            throw new IllegalArgumentException("no air in the window");
        });
        runtime.runOnce();
        runtime.runOnce();
        assertEquals(Status.FAILED, broken.status());
        assertNull(broken.frame());
        runtime.tick(10);
        drain();
        assertEquals(Status.FAILED, s.status());
        assertTrue(s.failure().contains("non-finite"));
        assertEquals(2, failed.size());
    }

    @Test
    void aRegionRetiredBeforeItsBuildIsNeverBuilt() {
        AtomicInteger built = new AtomicInteger();
        Slot s = runtime.create(() -> {
            built.incrementAndGet();
            return new Fake("x", log);
        });
        runtime.retire(s);
        drain();
        assertEquals(0, built.get());
        assertEquals(Status.RETIRED, s.status());
    }

    @Test
    void retiringDropsTheRegion() {
        Fake a = new Fake("a", log);
        runtime.tick(0);
        Slot s = runtime.create(() -> a);
        runtime.runOnce();
        runtime.retire(s);
        runtime.tick(100);
        drain();
        assertEquals(Status.RETIRED, s.status());
        assertEquals(0, a.steps);
    }

    @Test
    void backoffDoublesFromFiveSecondsToAMinute() {
        assertEquals(100, AirflowRuntime.backoffTicks(1));
        assertEquals(200, AirflowRuntime.backoffTicks(2));
        assertEquals(400, AirflowRuntime.backoffTicks(3));
        assertEquals(800, AirflowRuntime.backoffTicks(4));
        assertEquals(1200, AirflowRuntime.backoffTicks(5));
        assertEquals(1200, AirflowRuntime.backoffTicks(40));
    }

    @Test
    void aRequestedSaveComesAfterTheNextStepAtItsFramesTick() {
        Saving a = new Saving("a", log);
        runtime.tick(0);
        Slot s = runtime.create(() -> a);
        runtime.runOnce();
        s.requestSave();
        assertNull(runtime.pollSave(), "nothing before a step");
        runtime.tick(10);
        runtime.runOnce();
        AirflowRuntime.Save save = runtime.pollSave();
        assertSame(s, save.slot());
        assertEquals(10, save.tick(), "one 0.5 s step stands for tick 10");
        assertEquals(10, save.tile().sections()[0].tick());
        assertNull(runtime.pollSave(), "one request, one save");
        runtime.tick(20);
        runtime.runOnce();
        assertNull(runtime.pollSave(), "no save unasked");
    }

    @Test
    void retiringOrStoppingSavesARunningRegion() throws InterruptedException {
        Saving a = new Saving("a", log), b = new Saving("b", log);
        runtime.tick(0);
        Slot sa = runtime.create(() -> a);
        runtime.runOnce();
        runtime.retire(sa);
        drain();
        assertSame(sa, runtime.pollSave().slot(), "retiring saves");
        Slot failed = runtime.create(() -> {
            throw new IllegalStateException("never built");
        });
        drain();
        runtime.retire(failed);
        drain();
        assertNull(runtime.pollSave(), "a failed region has nothing to save");
        runtime.start();
        Slot sb = runtime.create(() -> b);
        long until = System.nanoTime() + 5_000_000_000L;
        while (sb.status() != Status.RUNNING && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertTrue(runtime.stop());
        assertSame(sb, runtime.pollSave().slot(), "stopping saves what was running");
        assertEquals(1, b.saves);
    }

    @Test
    void theThreadStepsAndStops() throws InterruptedException {
        Fake a = new Fake("a", new ArrayList<>());
        runtime.start();
        runtime.tick(0);
        Slot s = runtime.create(() -> a);
        long until = System.nanoTime() + 5_000_000_000L;
        while (s.status() != Status.RUNNING && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        runtime.tick(20);
        while (s.steps() < 2 && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertEquals(2, s.steps(), "the thread catches the region up");
        assertTrue(runtime.alive());
        assertTrue(runtime.stop(), "stop joins the thread");
        assertFalse(runtime.alive());
        assertSame(Status.RETIRED, s.status(), "a stopped runtime drops its regions");
    }
}
