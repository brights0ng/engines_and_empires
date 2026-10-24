// Written by Gemini; the divine area and unstaking without a main bonfire by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmpireStateTest {

    private EmpireState state;
    private final long center = ChunkKeys.pack(0, 0);

    @BeforeEach
    void setUp() {
        state = new EmpireState();
        state.found(center);
    }

    @Test
    void foundingSetsDominionTenStakesNineChunksAndLeavesOneAvailable() {
        assertEquals(EmpireState.FOUNDING_DOMINION, state.dominion());
        assertEquals(9, state.stakedCount());
        assertEquals(1, state.available(0));
        assertEquals(center, state.foundingChunk());

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                assertTrue(state.staked().contains(ChunkKeys.pack(dx, dz)));
            }
        }
    }

    @Test
    void availableNeverNegative() {
        // Artificially reduce dominion below staked count
        state.setDominion(5);
        assertEquals(0, state.available(0));
    }

    @Test
    void allStakeResultBranches() {
        long inEmpire = ChunkKeys.pack(0, 0);
        long adjacent1 = ChunkKeys.pack(2, 0);
        long adjacent2 = ChunkKeys.pack(2, 1);
        long farAway = ChunkKeys.pack(10, 10);

        // 1. ALREADY_OURS
        assertEquals(StakeResult.ALREADY_OURS, state.stake(inEmpire, 0, true, false));

        // 2. OWNED_BY_OTHER
        assertEquals(StakeResult.OWNED_BY_OTHER, state.stake(adjacent1, 0, true, true));

        // 3. NOT_ADJACENT
        assertEquals(StakeResult.NOT_ADJACENT, state.stake(farAway, 0, true, false));

        // 4. NOT_SETTLED
        assertEquals(StakeResult.NOT_SETTLED, state.stake(adjacent1, 0, false, false));

        // 5. OK (uses 1 available point)
        assertEquals(1, state.available(0));
        assertEquals(StakeResult.OK, state.stake(adjacent1, 0, true, false));
        assertTrue(state.staked().contains(adjacent1));
        assertEquals(10, state.stakedCount());
        assertEquals(0, state.available(0));

        // 6. NO_DOMINION (no available points left)
        assertEquals(StakeResult.NO_DOMINION, state.stake(adjacent2, 0, true, false));
    }

    @Test
    void allUnstakeResultBranches() {
        long notOurs = ChunkKeys.pack(10, 10);
        long core = center; // (0,0)

        // 1. NOT_OURS
        assertEquals(UnstakeResult.NOT_OURS, state.unstake(notOurs, 0, core));

        // 2. CORE_CHUNK
        assertEquals(UnstakeResult.CORE_CHUNK, state.unstake(core, 0, core));

        // Setup a bridge chunk by creating a line from (2,0): (2,0) staked, then (3,0) staked
        state.addDominion(5);
        long c20 = ChunkKeys.pack(2, 0);
        long c30 = ChunkKeys.pack(3, 0);
        assertEquals(StakeResult.OK, state.stake(c20, 0, true, false));
        assertEquals(StakeResult.OK, state.stake(c30, 0, true, false));

        // 3. BRIDGE_CHUNK: c20 connects c30 to the rest of the empire
        assertEquals(UnstakeResult.BRIDGE_CHUNK, state.unstake(c20, 0, core));

        // 4. OK: c30 is a leaf / non-bridge outside the divine area
        int stakedBefore = state.stakedCount();
        assertEquals(UnstakeResult.OK, state.unstake(c30, 0, core));
        assertFalse(state.staked().contains(c30));
        assertEquals(stakedBefore - 1, state.stakedCount());
    }

    @Test
    void divineAreaIsTheCoreAndItsEightNeighbours() {
        state.addDominion(20);
        // Stake the ring at distance 2 (edges first, then corners, so each is adjacent) so nothing tested is a bridge.
        for (int pass = 0; pass < 2; pass++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    boolean corner = Math.abs(x) == 2 && Math.abs(z) == 2;
                    if (Math.max(Math.abs(x), Math.abs(z)) == 2 && corner == (pass == 1)) {
                        assertEquals(StakeResult.OK, state.stake(ChunkKeys.pack(x, z), 0, true, false), "stake " + x + "," + z);
                    }
                }
            }
        }
        assertEquals(UnstakeResult.CORE_CHUNK, state.unstake(center, 0, center));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    assertEquals(UnstakeResult.DIVINE_CHUNK, state.unstake(ChunkKeys.pack(dx, dz), 0, center));
                }
            }
        }
        assertEquals(UnstakeResult.OK, state.unstake(ChunkKeys.pack(2, 2), 0, center));
        assertEquals(UnstakeResult.OK, state.unstake(ChunkKeys.pack(-2, 0), 0, center));
    }

    @Test
    void dormantPointsReleasedExactlyAtDayBoundary() {
        long outside = ChunkKeys.pack(2, 0);
        assertEquals(StakeResult.OK, state.stake(outside, 0, true, false));
        int availableBefore = state.available(0); // 0

        // Unstake on day 0
        assertEquals(UnstakeResult.OK, state.unstake(outside, 0, center));
        // On day 0, freed point is dormant (due on day 1)
        assertEquals(availableBefore, state.available(0));
        assertEquals(1, state.dormant().size());
        assertEquals(1, state.dormant().get(0).availableOnDay());

        // On day 1 (the next dawn), point is released
        assertEquals(availableBefore + 1, state.available(1));
        assertTrue(state.dormant().isEmpty());
    }

    @Test
    void addDominionAndForcibleDestake() {
        assertEquals(10, state.dominion());
        state.addDominion(EmpireState.DOMINION_PER_SACRIFICE);
        assertEquals(15, state.dominion());
        assertEquals(6, state.available(0));

        long corner = ChunkKeys.pack(1, 1);
        assertTrue(state.staked().contains(corner));
        state.forciblyDestake(corner);
        assertFalse(state.staked().contains(corner));
        assertEquals(14, state.dominion());
        assertEquals(6, state.available(0)); // D dropped from 15 to 14, staked dropped from 9 to 8, available remains 6
    }

    @Test
    void translationKeysMatchConvention() {
        for (StakeResult result : StakeResult.values()) {
            assertTrue(result.translationKey().startsWith("message.empire.stake."));
        }
        for (UnstakeResult result : UnstakeResult.values()) {
            assertTrue(result.translationKey().startsWith("message.empire.unstake."));
        }
    }

    @Test
    void withoutAMainBonfireOnlyTheCoreChunkCannotBeUnstaked() {
        long side = ChunkKeys.pack(1, 0);
        assertEquals(UnstakeResult.DIVINE_CHUNK, state.unstake(side, 0, center, true));
        assertEquals(UnstakeResult.CORE_CHUNK, state.unstake(center, 0, center, false));
        assertEquals(UnstakeResult.OK, state.unstake(side, 0, center, false));
        assertFalse(state.staked().contains(side));
    }
}
