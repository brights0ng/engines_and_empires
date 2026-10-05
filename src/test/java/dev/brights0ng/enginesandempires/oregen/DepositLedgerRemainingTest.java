package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The ledger's memory of how much ore is left in each chunk of each deposit. */
class DepositLedgerRemainingTest {

    private static final DepositBody BODY = BodySamples.of(OreTypes.IRON).get(0);
    private static final int ORE = BODY.oreCount();
    private static final DepositResolver.Outcome FIRST_TRY = new DepositResolver.Outcome(0, 0.8, false);

    private static final long A = DepositLedger.chunkKey(0, 0);
    private static final long B = DepositLedger.chunkKey(1, 0);
    private static final long C = DepositLedger.chunkKey(0, 1);

    private static Deposit deposit(long seed) {
        return new Deposit("iron", (int) seed * 100, (int) seed * 50, seed);
    }

    private static DepositLedger.ChunkProfile profile(long... chunks) {
        int[] cells = new int[chunks.length];
        Arrays.fill(cells, 1);
        return new DepositLedger.ChunkProfile(chunks, cells);
    }

    private static void report(DepositLedger ledger, Deposit deposit, long chunk, int placed, long... expected) {
        ledger.recordChunk(deposit, FIRST_TRY, BODY, chunk, placed, () -> profile(expected));
    }

    private static int remainingIn(DepositLedger ledger, Deposit deposit, long chunk) {
        DepositLedger.EntryView view = ledger.view(deposit.seed());
        return view.remaining()[Arrays.binarySearch(view.expectedChunks(), chunk)];
    }

    @Test
    void eachChunkStartsWithWhatItPlacedAsWhatIsLeftInIt() {
        DepositLedger ledger = new DepositLedger();
        Deposit d = deposit(1);
        report(ledger, d, A, 40, A, B, C);
        report(ledger, d, C, 25, A, B, C);
        assertEquals(40, remainingIn(ledger, d, A));
        assertEquals(-1, remainingIn(ledger, d, B), "a chunk that has not generated has no count");
        assertEquals(25, remainingIn(ledger, d, C));
    }

    @Test
    void refreshingAChunkUpdatesItsCountAndIgnoresChunksThatHaveNotGenerated() {
        DepositLedger ledger = new DepositLedger();
        Deposit d = deposit(1);
        report(ledger, d, A, 50, A, B);
        assertTrue(ledger.updateRemaining(d.seed(), A, 30));
        assertFalse(ledger.updateRemaining(d.seed(), A, 30), "the same count again changes nothing");
        assertFalse(ledger.updateRemaining(d.seed(), B, 10), "B has not generated");
        assertFalse(ledger.updateRemaining(d.seed(), DepositLedger.chunkKey(9, 9), 10), "no ore of this deposit is there");
        assertFalse(ledger.updateRemaining(deposit(2).seed(), A, 10), "an untracked deposit");
        assertEquals(30, remainingIn(ledger, d, A));
    }

    @Test
    void aDepositIsMarkedDepletedOnlyOnceEveryChunkIsGeneratedAndKnown() {
        DepositLedger ledger = new DepositLedger();
        Deposit d = deposit(1);
        assertEquals(DepositLedger.DEPLETED_BELOW, DepositLedger.depletedBelow(ORE), "a full-sized deposit uses the standard limit");

        report(ledger, d, A, 50, A, B, C);
        report(ledger, d, B, 60, A, B, C);
        // C has not generated, so it may hold plenty of ore: the deposit cannot be used up yet.
        ledger.updateRemaining(d.seed(), A, 0);
        ledger.updateRemaining(d.seed(), B, 0);
        assertFalse(ledger.view(d.seed()).depleted());

        report(ledger, d, C, 3, A, B, C); // now C generates, with 3 ore in it
        ledger.updateRemaining(d.seed(), C, 3);
        assertTrue(ledger.view(d.seed()).depleted(), "0 + 0 + 3 ore left is under the limit");
        assertEquals(1, ledger.stats().depleted());
    }

    @Test
    void aDepositWithExactlyTheLimitLeftIsNotDepleted() {
        DepositLedger ledger = new DepositLedger();
        Deposit d = deposit(1);
        report(ledger, d, A, 50, A, B);
        report(ledger, d, B, 50, A, B);
        ledger.updateRemaining(d.seed(), A, DepositLedger.DEPLETED_BELOW);
        ledger.updateRemaining(d.seed(), B, 0);
        assertFalse(ledger.view(d.seed()).depleted(), "exactly 8 left is still worth mining");
        ledger.updateRemaining(d.seed(), A, DepositLedger.DEPLETED_BELOW - 1);
        assertTrue(ledger.view(d.seed()).depleted(), "7 left is not");
    }

    @Test
    void aCountThatIsNotKnownKeepsADepositFromBeingMarkedDepleted() {
        DepositLedger ledger = new DepositLedger();
        Deposit d = deposit(1);
        DepositLedger.EntryView legacy = new DepositLedger.EntryView(d.seed(), "iron", d.x(), d.z(), 0, 0, ORE, 0.8f,
                new long[]{A, B}, new boolean[]{true, true}, 100, DepositLedger.State.OK, 1L, new int[0], new int[0], false);
        ledger.restore(List.of(legacy));
        ledger.updateRemaining(d.seed(), A, 0);
        assertFalse(ledger.view(d.seed()).depleted(), "B's count is unknown, so it may hold ore");
        ledger.updateRemaining(d.seed(), B, 0);
        assertTrue(ledger.view(d.seed()).depleted());
    }

    @Test
    void tinyDepositsAreNotDepletedFromTheStart() {
        assertEquals(1, DepositLedger.depletedBelow(1));
        assertEquals(1, DepositLedger.depletedBelow(2));
        assertEquals(5, DepositLedger.depletedBelow(10));
        assertEquals(8, DepositLedger.depletedBelow(16));
        assertEquals(8, DepositLedger.depletedBelow(100));
        assertEquals(8, DepositLedger.depletedBelow(4000));
    }

    @Test
    void chunkIndexFindsTrackedChunksWithoutCopyingAnything() {
        DepositLedger ledger = new DepositLedger();
        Deposit d = deposit(1);
        report(ledger, d, A, 1, A, B, C);
        assertTrue(ledger.chunkIndex(d.seed(), A) >= 0);
        assertTrue(ledger.chunkIndex(d.seed(), C) >= 0);
        assertEquals(-1, ledger.chunkIndex(d.seed(), DepositLedger.chunkKey(7, 7)));
        assertEquals(-1, ledger.chunkIndex(deposit(2).seed(), A));
    }

    @Test
    void profileOfCountsTheOreBlocksInEachChunkAndTheirTotalIsTheBodysOre() {
        Deposit d = new OreMap(BodySamples.SEED, OreTypes.IRON.layer()).depositsNear(0, 0, 2000).get(0);
        DepositBody body = DepositBody.generate(d, OreTypes.IRON);
        DepositLedger.ChunkProfile profile = DepositLedger.profileOf(d, body);
        int total = 0;
        for (int i = 0; i < profile.keys().length; i++) {
            total += profile.cells()[i];
            assertTrue(i == 0 || profile.keys()[i] > profile.keys()[i - 1], "keys are sorted");
        }
        assertEquals(body.oreCount(), total);
    }

    @Test
    void savedEntriesKeepTheirOreCountsAndDepletion() {
        DepositLedger original = new DepositLedger();
        report(original, deposit(1), A, 50, A, B);
        report(original, deposit(1), B, 60, A, B);
        original.updateRemaining(deposit(1).seed(), A, 12);
        report(original, deposit(2), A, 5, A);
        original.updateRemaining(deposit(2).seed(), A, 0);

        DepositLedger restored = new DepositLedger();
        restored.restore(original.snapshot());
        for (long seed : new long[]{deposit(1).seed(), deposit(2).seed()}) {
            DepositLedger.EntryView before = original.view(seed);
            DepositLedger.EntryView after = restored.view(seed);
            assertEquals(Arrays.toString(before.remaining()), Arrays.toString(after.remaining()));
            assertEquals(Arrays.toString(before.cells()), Arrays.toString(after.cells()));
            assertEquals(before.depleted(), after.depleted());
        }
        assertTrue(restored.view(deposit(2).seed()).depleted());
        assertFalse(restored.view(deposit(1).seed()).depleted());
    }

    /** Ledgers saved before ore counts existed must still load, with the counts treated as unknown. */
    @Test
    void entriesSavedBeforeOreCountsWereKeptStillRestore() {
        DepositLedger.EntryView old = new DepositLedger.EntryView(5L, "iron", 10, 20, 30, 0, ORE, 0.8f,
                new long[]{A, B}, new boolean[]{true, true}, 100, DepositLedger.State.OK, 1L, new int[0], new int[0], false);
        DepositLedger ledger = new DepositLedger();
        ledger.restore(List.of(old));
        DepositLedger.EntryView view = ledger.view(5L);
        assertEquals(2, view.remaining().length);
        assertEquals(-1, view.remaining()[0]);
        assertEquals(-1, view.remaining()[1]);
        assertEquals(0, view.cells().length, "cell counts are unknown until worked out from the body");
        ledger.fillCells(5L, new int[]{30, 70});
        assertEquals(2, ledger.view(5L).cells().length);
        ledger.fillCells(5L, new int[]{1, 1});
        assertEquals(30, ledger.view(5L).cells()[0], "counts that are already known are not overwritten");
    }
}
