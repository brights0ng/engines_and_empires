package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Checks how a deposit's remaining ore is worked out, against a made-up world in which chunks can be loaded or
 * not, and ore blocks can be mined out.
 */
class RemainingOreTest {

    private static final long SEED = 20260920L;
    private static final DepositResolver.Outcome FIRST_TRY = new DepositResolver.Outcome(0, 1.0, false);

    /** An iron deposit spanning several chunks, with a ledger, and a world in which its ore can be mined. */
    private static final class Fixture {
        final Deposit deposit;
        final DepositBody body;
        final ResolvedDeposit resolved;
        final DepositLedger ledger = new DepositLedger();
        final DepositLedger.ChunkProfile profile;
        final Set<String> mined = new HashSet<>();
        final Set<Long> loaded = new HashSet<>();
        final List<int[]> oreCells = new ArrayList<>();
        int oreLookups;

        Fixture(boolean generated) {
            Deposit found = null;
            DepositBody foundBody = null;
            for (Deposit candidate : new OreMap(SEED, OreTypes.IRON.layer()).depositsNear(0, 0, 4000)) {
                DepositBody candidateBody = DepositBody.generate(candidate, OreTypes.IRON);
                if (DepositLedger.profileOf(candidate, candidateBody).keys().length >= 3) {
                    found = candidate;
                    foundBody = candidateBody;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalStateException("no iron deposit spans three chunks");
            }
            deposit = found;
            body = foundBody;
            resolved = new ResolvedDeposit(deposit, FIRST_TRY, body);
            profile = DepositLedger.profileOf(deposit, body);
            DepositBody b = body;
            Deposit d = deposit;
            body.forEach((dx, dy, dz, kind) -> {
                if (kind == DepositBody.ORE || kind == DepositBody.RICH) {
                    oreCells.add(new int[]{d.x() + dx, b.centerY() + dy, d.z() + dz});
                }
            });
            if (generated) {
                for (int i = 0; i < profile.keys().length; i++) {
                    ledger.recordChunk(deposit, FIRST_TRY, body, profile.keys()[i], profile.cells()[i], () -> profile);
                }
            }
        }

        long chunkOf(int[] cell) {
            return DepositLedger.chunkKey(cell[0] >> 4, cell[2] >> 4);
        }

        void load(long chunk) {
            loaded.add(chunk);
        }

        void loadAll() {
            for (long key : profile.keys()) {
                loaded.add(key);
            }
        }

        void mineWhere(java.util.function.Predicate<int[]> which) {
            for (int[] cell : oreCells) {
                if (which.test(cell)) {
                    mined.add(cell[0] + "," + cell[1] + "," + cell[2]);
                }
            }
        }

        void mineChunk(long chunk) {
            mineWhere(cell -> chunkOf(cell) == chunk);
        }

        /** How much ore the deposit has in a chunk, as it will be counted when that chunk saves. */
        int standingIn(long chunk) {
            int n = 0;
            for (int[] cell : oreCells) {
                if (chunkOf(cell) == chunk && !mined.contains(cell[0] + "," + cell[1] + "," + cell[2])) {
                    n++;
                }
            }
            return n;
        }

        RemainingOre.World world() {
            return new RemainingOre.World() {
                @Override
                public boolean isLoaded(int chunkX, int chunkZ) {
                    return loaded.contains(DepositLedger.chunkKey(chunkX, chunkZ));
                }

                @Override
                public boolean isOre(String oreId, int x, int y, int z) {
                    oreLookups++;
                    return !mined.contains(x + "," + y + "," + z);
                }
            };
        }

        RemainingOre.Assessment assess(int cap) {
            return RemainingOre.assess(resolved, ledger, world(), cap);
        }

        RemainingOre.Assessment assess() {
            return assess(Integer.MAX_VALUE);
        }
    }

    private static final RemainingOre.World NO_WORLD = new RemainingOre.World() {
        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            fail("the world must not be asked about chunks here");
            return false;
        }

        @Override
        public boolean isOre(String oreId, int x, int y, int z) {
            fail("the world must not be asked about blocks here");
            return false;
        }
    };

    @Test
    void aDepositNothingOfWhichHasGeneratedIsIntactAndTheWorldIsNotAsked() {
        Fixture f = new Fixture(false);
        RemainingOre.Assessment assessment = RemainingOre.assess(f.resolved, f.ledger, NO_WORLD, Integer.MAX_VALUE);
        assertEquals(f.body.oreCount(), assessment.remaining());
        assertFalse(assessment.exact(), "it is an estimate: nothing has been placed yet");
        assertFalse(assessment.depleted());
        assertFalse(assessment.depletedForGood());
    }

    @Test
    void anUngeneratedDepositsEstimateFollowsWhatTheTerrainCheckPredicted() {
        Fixture f = new Fixture(false);
        ResolvedDeposit half = new ResolvedDeposit(f.deposit, new DepositResolver.Outcome(0, 0.5, false), f.body);
        assertEquals(Math.round(f.body.oreCount() * 0.5), RemainingOre.assess(half, f.ledger, NO_WORLD, Integer.MAX_VALUE).remaining());
    }

    @Test
    void chunksThatAreNotLoadedUseTheLedgersCountAndTheWorldIsNotAsked() {
        Fixture f = new Fixture(true);
        RemainingOre.Assessment assessment = RemainingOre.assess(f.resolved, f.ledger, new RemainingOre.World() {
            @Override
            public boolean isLoaded(int chunkX, int chunkZ) {
                return false;
            }

            @Override
            public boolean isOre(String oreId, int x, int y, int z) {
                fail("no block of an unloaded chunk may be read");
                return false;
            }
        }, Integer.MAX_VALUE);
        assertEquals(f.body.oreCount(), assessment.remaining());
        assertTrue(assessment.exact());
        assertFalse(assessment.depleted());
    }

    @Test
    void loadedChunksAreCountedLiveAndOnlyForTheDepositsOreBlocks() {
        Fixture f = new Fixture(true);
        f.loadAll();
        RemainingOre.Assessment assessment = f.assess();
        assertEquals(f.body.oreCount(), assessment.remaining());
        assertTrue(assessment.exact());
        assertEquals(f.body.oreCount(), f.oreLookups, "exactly one lookup per ore block, and none for host rock");
    }

    @Test
    void miningInALoadedChunkIsSeenAtOnce() {
        Fixture f = new Fixture(true);
        f.loadAll();
        long first = f.profile.keys()[0];
        int inFirst = f.profile.cells()[0];
        f.mineChunk(first);
        assertEquals(f.body.oreCount() - inFirst, f.assess().remaining());
    }

    /** The whole reason for the ledger: ore mined out of a chunk that has since unloaded. */
    @Test
    void miningInAChunkThatHasSinceUnloadedIsSeenOnceItsCountWasRefreshed() {
        Fixture f = new Fixture(true);
        long first = f.profile.keys()[0];
        int inFirst = f.profile.cells()[0];
        f.mineChunk(first);
        assertEquals(f.body.oreCount(), f.assess().remaining(), "until the chunk saves, the ledger still has the old count");
        f.ledger.updateRemaining(f.deposit.seed(), first, f.standingIn(first)); // the chunk saves as it unloads
        assertEquals(f.body.oreCount() - inFirst, f.assess().remaining());
    }

    @Test
    void loadedAndUnloadedPartsAreCombined() {
        Fixture f = new Fixture(true);
        long a = f.profile.keys()[0];
        long b = f.profile.keys()[1];
        f.mineChunk(a);
        f.ledger.updateRemaining(f.deposit.seed(), a, 0); // a saved and unloaded, mined out
        f.load(b);
        f.mineWhere(cell -> f.chunkOf(cell) == b && cell[1] % 2 == 0); // half of b is mined while it is loaded
        int expected = f.body.oreCount() - f.profile.cells()[0] - (f.profile.cells()[1] - f.standingIn(b));
        assertEquals(expected, f.assess().remaining());
    }

    @Test
    void aDepositMinedOutEntirelyIsDepletedForGood() {
        Fixture f = new Fixture(true);
        f.loadAll();
        f.mineWhere(cell -> true);
        RemainingOre.Assessment assessment = f.assess();
        assertEquals(0, assessment.remaining());
        assertTrue(assessment.depleted());
        assertTrue(assessment.depletedForGood(), "every chunk is generated and was read exactly");

        // Once it is marked, nothing more is ever asked of the world.
        f.ledger.markDepleted(f.deposit.seed());
        RemainingOre.Assessment again = RemainingOre.assess(f.resolved, f.ledger, NO_WORLD, Integer.MAX_VALUE);
        assertEquals(0, again.remaining());
        assertTrue(again.depletedForGood());
    }

    /** Depleted is fewer than 8 left, so 8 is fine and 7 is not. */
    @Test
    void eightOreBlocksLeftIsWorthMiningAndSevenIsNot() {
        Fixture f = new Fixture(true);
        f.loadAll();
        int limit = DepositLedger.DEPLETED_BELOW;
        for (int keep : new int[]{limit, limit - 1}) {
            f.mined.clear();
            int[] index = {0};
            f.mineWhere(cell -> index[0]++ >= keep);
            RemainingOre.Assessment assessment = f.assess();
            assertEquals(keep, assessment.remaining());
            assertEquals(keep < limit, assessment.depleted(), keep + " ore left");
        }
    }

    @Test
    void aDepositWithUngeneratedPartsIsNeverDepletedForGood() {
        Fixture f = new Fixture(false);
        long first = f.profile.keys()[0];
        f.ledger.recordChunk(f.deposit, FIRST_TRY, f.body, first, f.profile.cells()[0], () -> f.profile);
        f.load(first); // only the first chunk has generated, so it is the only one that can be loaded
        f.mineWhere(cell -> true);
        RemainingOre.Assessment assessment = f.assess();
        assertFalse(assessment.exact(), "the other chunks are estimated, since they have not generated");
        assertFalse(assessment.depletedForGood(), "the chunks that have not generated may yet hold ore");
    }

    @Test
    void countingStopsAtTheCap() {
        Fixture f = new Fixture(true);
        f.loadAll();
        RemainingOre.Assessment assessment = f.assess(10);
        assertEquals(10, assessment.remaining());
        assertEquals(10, f.oreLookups, "it should stop reading blocks once it has found enough");
        assertFalse(assessment.depleted());
    }

    @Test
    void entriesSavedBeforeCountsWereKeptAreCompletedFromTheBodyAndTreatedAsEstimates() {
        Fixture f = new Fixture(true);
        DepositLedger.EntryView modern = f.ledger.view(f.deposit.seed());
        DepositLedger.EntryView old = new DepositLedger.EntryView(modern.seed(), modern.oreId(), modern.x(), modern.z(),
                modern.centerY(), modern.attempt(), modern.expectedOre(), modern.solidFraction(), modern.expectedChunks(),
                modern.done(), modern.placed(), modern.state(), modern.order(), new int[0], new int[0], false);
        DepositLedger legacy = new DepositLedger();
        legacy.restore(List.of(old));

        RemainingOre.Assessment assessment = RemainingOre.assess(f.resolved, legacy, NO_WORLD_UNLOADED, Integer.MAX_VALUE);
        assertFalse(assessment.exact(), "the count of each chunk was never recorded");
        assertEquals(f.body.oreCount(), assessment.remaining(), "estimated from how much ore each chunk holds");
        assertEquals(f.profile.keys().length, legacy.view(f.deposit.seed()).cells().length, "cell counts were filled in");
        assertFalse(assessment.depletedForGood());
    }

    /** A world with nothing loaded, for tests that only need the ledger. */
    private static final RemainingOre.World NO_WORLD_UNLOADED = new RemainingOre.World() {
        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public boolean isOre(String oreId, int x, int y, int z) {
            return false;
        }
    };
}
