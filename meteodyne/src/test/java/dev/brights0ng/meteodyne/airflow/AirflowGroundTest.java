// Written by Claude (GreedyFluids M7e: the smoke's p99 fix).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AirflowGroundTest {

    /** Runs queued work only when told to: the "background worker". */
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void drain() {
            while (!tasks.isEmpty()) {
                tasks.poll().run();
            }
        }
    }

    private static final List<Throwable> failures = new ArrayList<>();

    @Test
    void cornersArriveFromTheWorkerAndAreAskedForOnce() {
        Queue worker = new Queue();
        AtomicInteger calls = new AtomicInteger();
        AirflowGround g = new AirflowGround((x, z) -> {
            calls.incrementAndGet();
            return 64 + x / 16 + 10 * (z / 16);
        }, worker, 320, failures::add);
        assertNull(g.corners(0, 0, 2, 2), "nothing known before the worker runs");
        assertNull(g.corners(0, 0, 2, 2), "still pending");
        assertEquals(1, worker.tasks.size(), "the pending corners aren't asked for twice");
        worker.drain();
        assertArrayEquals(new int[] {64, 65, 74, 75}, g.corners(0, 0, 2, 2));
        assertEquals(4, calls.get());
        assertNotNull(g.corners(1, 0, 1, 2), "a neighbouring tile shares the corners");
        assertEquals(0, worker.tasks.size());
    }

    @Test
    void negativeCornersKeepTheirCoordinates() {
        Queue worker = new Queue();
        AirflowGround g = new AirflowGround((x, z) -> x + 1000 * z, worker, 320, failures::add);
        g.corners(-2, -3, 2, 2);
        worker.drain();
        assertArrayEquals(new int[] {-32 - 48000, -16 - 48000, -32 - 32000, -16 - 32000}, g.corners(-2, -3, 2, 2));
    }

    @Test
    void heightsInterpolateBetweenCorners() {
        int[] c = {60, 76, 92, 108}; // corners (0,0), (1,0), (0,1), (1,1): one block per block along x, two along z
        assertEquals(60, AirflowGround.at(c, 0, 0, 2, 0, 0), 1e-12);
        assertEquals(68, AirflowGround.at(c, 0, 0, 2, 8, 0), 1e-12);
        assertEquals(76, AirflowGround.at(c, 0, 0, 2, 0, 8), 1e-12);
        assertEquals(60 + 12 + 24, AirflowGround.at(c, 0, 0, 2, 12, 12), 1e-12);
        // negative blocks, corners from (-1, -1)
        assertEquals(60 + 8, AirflowGround.at(c, -1, -1, 2, -8, -16), 1e-12);
    }

    @Test
    void aTilesLatticeNeedsSixCornersAlongEachSide() {
        // the 64-block tile's lattice runs x0 .. x0 + 64; the last point sits on a corner and needs the one past it
        assertEquals(6, AirflowGround.span(128, 192));
        assertEquals(2, AirflowGround.corner(32));
        assertEquals(-1, AirflowGround.corner(-1));
        assertEquals(6, AirflowGround.span(-64, 0));
        // shifted (game-test) grids start mid-chunk
        assertEquals(6, AirflowGround.span(8, 72));
    }

    @Test
    void aFailingGeneratorFallsBackToTheTopOfTheWorldAndSaysSoOnce() {
        Queue worker = new Queue();
        List<Throwable> heard = new ArrayList<>();
        AirflowGround g = new AirflowGround((x, z) -> {
            throw new IllegalStateException("no noise");
        }, worker, 320, heard::add);
        g.corners(0, 0, 2, 1);
        worker.drain();
        assertArrayEquals(new int[] {320, 320}, g.corners(0, 0, 2, 1));
        assertEquals(1, heard.size());
    }

    @Test
    void aShutDownWorkerMeansTheCornersAreWorkedOutHere() {
        Executor closed = task -> {
            throw new RejectedExecutionException("shut down");
        };
        AirflowGround g = new AirflowGround((x, z) -> 70, closed, 320, failures::add);
        assertNull(g.corners(0, 0, 1, 1), "this call only asks");
        assertArrayEquals(new int[] {70}, g.corners(0, 0, 1, 1));
    }

    @Test
    void theCacheStartsAgainPastItsSize() {
        Queue worker = new Queue();
        AirflowGround g = new AirflowGround((x, z) -> 64, worker, 320, failures::add, 8);
        g.corners(0, 0, 2, 2);
        worker.drain();
        assertEquals(4, g.known());
        g.corners(10, 10, 3, 2);
        worker.drain();
        assertEquals(6, g.known(), "4 + 6 > 8: the old corners went");
        assertNull(g.corners(0, 0, 2, 2), "and are asked for again");
    }
}
