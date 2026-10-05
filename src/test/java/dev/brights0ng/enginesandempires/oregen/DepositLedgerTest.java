package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class DepositLedgerTest {

    private static final DepositBody BODY = BodySamples.of(OreTypes.IRON).get(0);
    private static final int ORE = BODY.oreCount();
    private static final DepositResolver.Outcome FIRST_TRY = new DepositResolver.Outcome(0, 0.8, false);

    private static final long A = DepositLedger.chunkKey(0, 0);
    private static final long B = DepositLedger.chunkKey(1, 0);
    private static final long C = DepositLedger.chunkKey(0, 1);

    private static Deposit deposit(long seed) {
        return new Deposit("iron", (int) seed * 100, (int) seed * 50, seed);
    }

    private static DepositLedger.EntryView report(DepositLedger ledger, Deposit deposit, DepositResolver.Outcome outcome,
                                                  long chunk, int placed, long... expected) {
        return ledger.recordChunk(deposit, outcome, BODY, chunk, placed, () -> profile(expected));
    }

    /** Each chunk holds one ore block: these tests are about which chunks there are, not how much ore is in them. */
    private static DepositLedger.ChunkProfile profile(long... chunks) {
        int[] cells = new int[chunks.length];
        java.util.Arrays.fill(cells, 1);
        return new DepositLedger.ChunkProfile(chunks, cells);
    }

    @Test
    void aDepositCompletesOnlyWhenEveryChunkHoldingItsOreHasReported() {
        DepositLedger ledger = new DepositLedger();
        Deposit d = deposit(1);
        assertNull(report(ledger, d, FIRST_TRY, A, ORE / 2, A, B, C));
        assertNull(report(ledger, d, FIRST_TRY, B, 0, A, B, C));
        assertEquals(1, ledger.stats().inProgress());
        DepositLedger.EntryView done = report(ledger, d, FIRST_TRY, C, ORE / 4, A, B, C);
        assertNotNull(done);
        assertEquals(DepositLedger.State.OK, done.state());
        assertEquals(ORE / 2 + ORE / 4, done.placed());
        assertEquals(3, done.chunksDone());
        assertEquals(1, ledger.stats().ok());
        assertEquals(0, ledger.stats().inProgress());
    }

    @Test
    void aDepositThatPlacedNothingIsAPhantom() {
        DepositLedger ledger = new DepositLedger();
        report(ledger, deposit(1), FIRST_TRY, A, 0, A, B);
        DepositLedger.EntryView done = report(ledger, deposit(1), FIRST_TRY, B, 0, A, B);
        assertNotNull(done);
        assertEquals(DepositLedger.State.PHANTOM, done.state());
        assertEquals(0, done.placed());
        assertEquals(1, ledger.stats().phantom());
        assertEquals(1, ledger.phantoms(10).size());
        assertEquals(deposit(1).x(), ledger.phantoms(10).get(0).x());
    }

    @Test
    void aDepositThatPlacedVeryLittleIsThin() {
        DepositLedger ledger = new DepositLedger();
        int little = (int) (ORE * DepositLedger.THIN_RATIO) - 1;
        DepositLedger.EntryView done = report(ledger, deposit(1), FIRST_TRY, A, little, A);
        assertNotNull(done);
        assertEquals(DepositLedger.State.THIN, done.state());
        int enough = (int) Math.ceil(ORE * DepositLedger.THIN_RATIO);
        assertEquals(DepositLedger.State.OK, report(ledger, deposit(2), FIRST_TRY, A, enough, A).state());
    }

    @Test
    void reportsForOtherChunksAndRepeatReportsAreIgnored() {
        DepositLedger ledger = new DepositLedger();
        Deposit d = deposit(1);
        long elsewhere = DepositLedger.chunkKey(50, 50);
        assertNull(report(ledger, d, FIRST_TRY, A, 10, A, B));
        assertNull(report(ledger, d, FIRST_TRY, elsewhere, 999, A, B)); // holds none of its ore
        assertNull(report(ledger, d, FIRST_TRY, A, 500, A, B));         // A already reported
        DepositLedger.EntryView done = report(ledger, d, FIRST_TRY, B, 20, A, B);
        assertNotNull(done);
        assertEquals(30, done.placed());
        // Once complete, further reports change nothing.
        assertNull(report(ledger, d, FIRST_TRY, A, 1000, A, B));
        assertEquals(30, ledger.snapshot().get(0).placed());
    }

    @Test
    void droppedDepositsAreRecordedOnceAndNeverBecomePhantoms() {
        DepositLedger ledger = new DepositLedger();
        DepositResolver.Outcome dropped = new DepositResolver.Outcome(-1, 0.05, true);
        ledger.recordDropped(deposit(1), dropped);
        ledger.recordDropped(deposit(1), dropped);
        DepositLedger.Stats stats = ledger.stats();
        assertEquals(1, stats.tracked());
        assertEquals(1, stats.dropped());
        assertEquals(0, stats.phantom());
        assertNull(report(ledger, deposit(1), FIRST_TRY, A, 0, A));
        assertEquals(DepositLedger.State.DROPPED, ledger.snapshot().get(0).state());
    }

    @Test
    void statsSummariseEverythingTracked() {
        DepositLedger ledger = new DepositLedger();
        report(ledger, deposit(1), new DepositResolver.Outcome(0, 0.9, false), A, ORE, A);                 // ok, first try
        report(ledger, deposit(2), new DepositResolver.Outcome(0, 0.7, false), A, ORE / 2, A);             // ok, first try
        report(ledger, deposit(3), new DepositResolver.Outcome(2, 0.5, false), A, 1, A);                   // thin, third try
        report(ledger, deposit(4), new DepositResolver.Outcome(1, 0.4, false), A, 0, A);                   // phantom, second try
        report(ledger, deposit(5), new DepositResolver.Outcome(0, 0.6, false), A, 5, A, B);                // in progress
        ledger.recordDropped(deposit(6), new DepositResolver.Outcome(-1, 0.1, true));

        DepositLedger.Stats stats = ledger.stats();
        assertEquals(6, stats.tracked());
        assertEquals(2, stats.ok());
        assertEquals(1, stats.thin());
        assertEquals(1, stats.phantom());
        assertEquals(1, stats.inProgress());
        assertEquals(1, stats.dropped());
        assertEquals(4, stats.completed());
        assertEquals(3, stats.byAttempt()[0]);   // deposits 1, 2 and the one still in progress
        assertEquals(1, stats.byAttempt()[1]);
        assertEquals(1, stats.byAttempt()[2]);
        assertEquals((0.9 + 0.7 + 0.5 + 0.4) / 4, stats.meanPredicted(), 1.0e-6);
        double actual = (1.0 + (ORE / 2) / (double) ORE + 1.0 / ORE + 0.0) / 4;
        assertEquals(actual, stats.meanActual(), 1.0e-6);
    }

    @Test
    void phantomsAreListedNewestFirstAndLimited() {
        DepositLedger ledger = new DepositLedger();
        for (int i = 1; i <= 5; i++) {
            report(ledger, deposit(i), FIRST_TRY, A, 0, A);
        }
        List<DepositLedger.EntryView> latest = ledger.phantoms(3);
        assertEquals(3, latest.size());
        assertEquals(deposit(5).x(), latest.get(0).x());
        assertEquals(deposit(4).x(), latest.get(1).x());
        assertEquals(deposit(3).x(), latest.get(2).x());
    }

    /** The ledger is saved with the world, so what comes back must behave exactly like what went in. */
    @Test
    void savedEntriesRestoreAndPartiallyReportedDepositsCanStillComplete() {
        DepositLedger original = new DepositLedger();
        report(original, deposit(1), FIRST_TRY, A, ORE, A);                        // complete, ok
        report(original, deposit(2), FIRST_TRY, A, 0, A);                          // complete, phantom
        report(original, deposit(3), FIRST_TRY, A, 40, A, B, C);                   // waiting for B and C
        original.recordDropped(deposit(4), new DepositResolver.Outcome(-1, 0.0, true));

        DepositLedger restored = new DepositLedger();
        restored.restore(original.snapshot());
        assertEquals(original.stats().tracked(), restored.stats().tracked());
        assertEquals(original.stats().ok(), restored.stats().ok());
        assertEquals(original.stats().phantom(), restored.stats().phantom());
        assertEquals(original.stats().inProgress(), restored.stats().inProgress());
        assertEquals(original.stats().dropped(), restored.stats().dropped());

        assertNull(report(restored, deposit(3), FIRST_TRY, B, 10, A, B, C));
        DepositLedger.EntryView done = report(restored, deposit(3), FIRST_TRY, C, 25, A, B, C);
        assertNotNull(done);
        assertEquals(75, done.placed());

        // A phantom completed after restoring is newer than any completed before it.
        report(restored, deposit(9), FIRST_TRY, A, 0, A);
        assertEquals(deposit(9).x(), restored.phantoms(1).get(0).x());
    }

    @Test
    void clearForgetsEverything() {
        DepositLedger ledger = new DepositLedger();
        report(ledger, deposit(1), FIRST_TRY, A, 0, A);
        ledger.recordDropped(deposit(2), new DepositResolver.Outcome(-1, 0.0, true));
        assertEquals(2, ledger.size());
        ledger.clear();
        assertEquals(0, ledger.size());
        assertEquals(0, ledger.stats().tracked());
    }

    @Test
    void chunkKeysPackTheWayMinecraftDoes() {
        assertEquals(1L | (2L << 32), DepositLedger.chunkKey(1, 2));
        assertEquals(-1L, DepositLedger.chunkKey(-1, -1));
        assertEquals(0xFFFFFFFFL, DepositLedger.chunkKey(-1, 0));
        assertTrue(DepositLedger.chunkKey(3, 4) != DepositLedger.chunkKey(4, 3));
    }

    /** Worldgen reports from several threads at once. However they interleave, a deposit completes exactly once. */
    @Test
    void concurrentReportsFromManyThreadsCompleteEachDepositExactlyOnce() throws Exception {
        int chunks = 64;
        long[] expected = new long[chunks];
        for (int i = 0; i < chunks; i++) {
            expected[i] = DepositLedger.chunkKey(i % 8, i / 8);
        }
        DepositLedger ledger = new DepositLedger();
        Deposit d = deposit(7);
        AtomicInteger completions = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < chunks; i++) {
                long chunk = expected[i];
                jobs.add(() -> {
                    if (ledger.recordChunk(d, FIRST_TRY, BODY, chunk, 3, () -> profile(expected)) != null) {
                        completions.incrementAndGet();
                    }
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(jobs)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, completions.get());
        assertEquals(chunks * 3, ledger.snapshot().get(0).placed());
    }
}
