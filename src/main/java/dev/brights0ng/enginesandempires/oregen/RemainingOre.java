package dev.brights0ng.enginesandempires.oregen;

import java.util.Arrays;

/**
 * Works out how much ore a deposit has left: whether it has been mined out.
 *
 * <p>A deposit spans several chunks and each part has a different best source of truth:
 * <ul>
 *   <li><b>A chunk that is loaded:</b> count its ore blocks in the world, right now. This is exact, and it is
 *       the only source that sees mining done since the chunk loaded.</li>
 *   <li><b>A chunk that has been generated but is not loaded:</b> nothing can be read, but nothing can change
 *       either, so the ledger's last count stands. It is refreshed every time the chunk saves or unloads, so
 *       it is never out of date, except after a crash.</li>
 *   <li><b>A chunk that has not been generated, or whose count was never recorded:</b> an estimate, from how many of
 *       the deposit's ore blocks lie in it and how much of the deposit the terrain estimate expects to be in rock.
 *       It has not been mined, so it counts as intact.</li>
 * </ul>
 *
 * <p>The world is only asked about loaded chunks, and only for the deposit's ore blocks, so a deposit that
 * is nowhere near a player costs nothing to check. Reading the world is only safe on the main thread, so
 * this must be too. Nothing here touches Minecraft: the world is a small interface.
 */
public final class RemainingOre {

    /** What the world can be asked about. Only ever asked about ore in chunks it says are loaded. */
    public interface World {
        /** Whether this chunk is loaded, so that its blocks can be read. Must not load it. */
        boolean isLoaded(int chunkX, int chunkZ);

        /** Whether the block at this position is an ore block of this ore. */
        boolean isOre(String oreId, int x, int y, int z);
    }

    /**
     * How a deposit stands.
     *
     * @param remaining       how many ore blocks are left, but not counting further than the cap it was asked with
     * @param exact           true if every part of the count was read or recorded, not estimated
     * @param depleted        true if fewer ore blocks are left than {@link DepositLedger#depletedBelow} allows
     * @param depletedForGood true if it is depleted and every part of it is known exactly, so it will never be worth
     *                        counting again
     */
    public record Assessment(int remaining, boolean exact, boolean depleted, boolean depletedForGood) {
    }

    /**
     * Assesses a deposit that exists.
     *
     * @param cap stop counting once this many ore blocks are found; pass {@link Integer#MAX_VALUE} for the exact count
     */
    public static Assessment assess(ResolvedDeposit resolved, DepositLedger ledger, World world, int cap) {
        Deposit deposit = resolved.deposit();
        DepositBody body = resolved.body();
        double fraction = resolved.outcome().solidFraction();
        DepositLedger.EntryView entry = ledger.view(deposit.seed());

        if (entry != null && entry.depleted()) {
            return new Assessment(0, true, true, true);
        }
        if (entry == null || entry.expectedChunks().length == 0) {
            // Nothing of it has generated yet, so none of it can have been mined.
            int nominal = (int) Math.min(cap, Math.round(body.oreCount() * fraction));
            return new Assessment(nominal, false, false, false);
        }

        long[] keys = entry.expectedChunks();
        int n = keys.length;
        int[] cells = entry.cells();
        if (cells.length != n) {
            // Saved before cell counts were kept: work them out from the body, and keep them.
            cells = DepositLedger.profileOf(deposit, body).cells();
            ledger.fillCells(deposit.seed(), cells);
            if (cells.length != n) {
                cells = new int[n]; // the body no longer matches what was saved; fall back on nothing at all
            }
        }

        boolean[] live = new boolean[n];
        boolean anyLive = false;
        boolean exact = true;
        boolean allDone = true;
        long known = 0;
        for (int i = 0; i < n; i++) {
            if (!entry.done()[i]) {
                allDone = false;
            }
            int chunkX = (int) keys[i];
            int chunkZ = (int) (keys[i] >> 32);
            if (world.isLoaded(chunkX, chunkZ)) {
                live[i] = true;
                anyLive = true;
            } else if (entry.remaining()[i] >= 0) {
                known += entry.remaining()[i];
            } else {
                known += Math.round(cells[i] * fraction);
                exact = false;
            }
        }

        long total = known;
        if (anyLive && known < cap) {
            long[] found = {0};
            String oreId = deposit.oreId();
            long limit = (long) cap - known;
            body.forEach((dx, dy, dz, kind) -> {
                if (found[0] >= limit || (kind != DepositBody.ORE && kind != DepositBody.RICH)) {
                    return;
                }
                int x = deposit.x() + dx;
                int z = deposit.z() + dz;
                int index = Arrays.binarySearch(keys, DepositLedger.chunkKey(x >> 4, z >> 4));
                if (index >= 0 && live[index] && world.isOre(oreId, x, body.centerY() + dy, z)) {
                    found[0]++;
                }
            });
            total += found[0];
        }

        int remaining = (int) Math.min(total, cap);
        boolean depleted = remaining < DepositLedger.depletedBelow(body.oreCount());
        return new Assessment(remaining, exact, depleted, depleted && exact && allDone);
    }

    private RemainingOre() {
    }
}
