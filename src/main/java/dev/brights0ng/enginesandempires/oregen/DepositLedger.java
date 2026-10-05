package dev.brights0ng.enginesandempires.oregen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Keeps track of what really happens to deposits as the world generates and is played in: how much ore
 * each chunk placed, and how much of it is still there.
 *
 * <p>A deposit spans several chunks and no chunk sees all of it, so each chunk reports how much of the
 * deposit's ore it actually placed. When every chunk that holds any of the deposit's ore has reported,
 * the deposit is complete and is classified, which is how "phantom deposits" (ones the terrain estimate said
 * were viable that nevertheless ended up with no ore) are measured:
 * <ul>
 *   <li>{@link State#PHANTOM}: it placed nothing. Anyone pinging it would have been sent to nothing.</li>
 *   <li>{@link State#THIN}: it placed less than {@link #THIN_RATIO} of its ore.</li>
 *   <li>{@link State#OK}: anything else.</li>
 * </ul>
 * Deposits the resolver rejected outright are recorded as {@link State#DROPPED}. Nothing is placed for
 * those, so they cannot be phantoms.
 *
 * <p>The ledger also remembers how much ore is left in each chunk, so a deposit players have mined out can
 * be told apart from one that has not. Ore only ever changes while its chunk is loaded, so the game refreshes a
 * chunk's count when it saves or unloads, and the last count stands until the chunk is next loaded. A deposit
 * with fewer than {@link #depletedBelow} ore blocks left is depleted, and once every one of its chunks is known
 * exactly, it is marked depleted for good.
 *
 * <p>Nothing here touches Minecraft. Entries can be snapshotted and restored, so the caller can save them
 * with the world. Thread-safe: worldgen reports from several threads at once.
 */
public final class DepositLedger {

    /** A completed deposit that placed less than this share of its ore is called thin. */
    public static final double THIN_RATIO = 0.25;

    /** A deposit with fewer ore blocks than this left is depleted: not worth a trip. */
    public static final int DEPLETED_BELOW = 8;

    /**
     * How few ore blocks a deposit of this size may have left before it counts as depleted: {@link #DEPLETED_BELOW},
     * or half the deposit if it started out smaller than twice that, so a tiny deposit is not depleted from the start.
     */
    public static int depletedBelow(int oreCount) {
        return Math.min(DEPLETED_BELOW, Math.max(1, oreCount / 2));
    }

    public enum State {
        /** Some of the chunks holding its ore have not generated yet. */
        IN_PROGRESS,
        /** Complete, and placed a reasonable amount of ore. */
        OK,
        /** Complete, but placed very little. */
        THIN,
        /** Complete, and placed no ore at all. */
        PHANTOM,
        /** Rejected by the resolver: no draw of it was viable. */
        DROPPED
    }

    /**
     * The chunks that hold any of a deposit's ore, and how many of its ore blocks each holds if all of them
     * are in rock.
     *
     * @param keys  the chunks, packed as by {@link #chunkKey}, sorted
     * @param cells how many ore blocks of the deposit's body lie in each of those chunks
     */
    public record ChunkProfile(long[] keys, int[] cells) {
    }

    /** Works out which chunks hold a body's ore, and how much each holds. */
    public static ChunkProfile profileOf(Deposit deposit, DepositBody body) {
        TreeMap<Long, Integer> counts = new TreeMap<>();
        body.forEach((dx, dy, dz, kind) -> {
            if (kind == DepositBody.ORE || kind == DepositBody.RICH) {
                counts.merge(chunkKey((deposit.x() + dx) >> 4, (deposit.z() + dz) >> 4), 1, Integer::sum);
            }
        });
        long[] keys = new long[counts.size()];
        int[] cells = new int[counts.size()];
        int i = 0;
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            keys[i] = entry.getKey();
            cells[i] = entry.getValue();
            i++;
        }
        return new ChunkProfile(keys, cells);
    }

    /**
     * A read-only picture of one deposit's entry.
     *
     * @param expectedOre    how many ore blocks the deposit would hold if all of it were in rock
     * @param solidFraction  the share of its ore the terrain estimate predicted to be in rock
     * @param expectedChunks the chunks (packed as by {@link #chunkKey}) that hold any of its ore, sorted
     * @param done           which of {@code expectedChunks} have generated and reported
     * @param placed         how much ore was placed in all
     * @param order          when it completed, so later ones sort later. 0 if it has not completed
     * @param cells          how many of the deposit's ore blocks lie in each of {@code expectedChunks}. Empty if not
     *                       yet known, as for entries saved before it was recorded
     * @param remaining      how much ore was last known to be left in each of {@code expectedChunks}, or -1 if not
     *                       known
     * @param depleted       true once the deposit is known to be used up, for good
     */
    public record EntryView(long seed, String oreId, int x, int z, int centerY, int attempt, int expectedOre,
                            float solidFraction, long[] expectedChunks, boolean[] done, int placed, State state,
                            long order, int[] cells, int[] remaining, boolean depleted) {

        public int chunksDone() {
            int n = 0;
            for (boolean d : done) {
                if (d) {
                    n++;
                }
            }
            return n;
        }

        /** The share of the deposit's ore that was actually placed. */
        public double actualFraction() {
            return expectedOre == 0 ? 0.0 : (double) placed / expectedOre;
        }
    }

    /**
     * A summary of everything tracked.
     *
     * @param depleted      how many deposits are marked as used up
     * @param byAttempt     how many viable deposits were accepted at each attempt (index 0 is first try)
     * @param meanPredicted the average share of ore the terrain estimate predicted, over completed deposits
     * @param meanActual    the average share of ore actually placed, over the same deposits
     */
    public record Stats(int tracked, int inProgress, int ok, int thin, int phantom, int dropped, int depleted,
                        int[] byAttempt, double meanPredicted, double meanActual) {

        /** How many deposits have completed. */
        public int completed() {
            return ok + thin + phantom;
        }
    }

    private static final class Entry {
        final long seed;
        final String oreId;
        final int x;
        final int z;
        final int centerY;
        final int attempt;
        final int expectedOre;
        final float solidFraction;
        final long[] expectedChunks;
        final boolean[] done;
        int placed;
        State state;
        long order;
        int[] cells;
        final int[] remaining;
        boolean depleted;

        Entry(long seed, String oreId, int x, int z, int centerY, int attempt, int expectedOre, float solidFraction,
              long[] expectedChunks, boolean[] done, int placed, State state, long order,
              int[] cells, int[] remaining, boolean depleted) {
            this.seed = seed;
            this.oreId = oreId;
            this.x = x;
            this.z = z;
            this.centerY = centerY;
            this.attempt = attempt;
            this.expectedOre = expectedOre;
            this.solidFraction = solidFraction;
            this.expectedChunks = expectedChunks;
            this.done = done;
            this.placed = placed;
            this.state = state;
            this.order = order;
            this.cells = cells;
            this.remaining = remaining;
            this.depleted = depleted;
        }

        EntryView view() {
            return new EntryView(seed, oreId, x, z, centerY, attempt, expectedOre, solidFraction,
                    expectedChunks.clone(), done.clone(), placed, state, order, cells.clone(), remaining.clone(), depleted);
        }
    }

    private final Map<Long, Entry> entries = new HashMap<>();
    private long nextOrder = 1;

    /** Packs chunk coordinates into one number, the same way Minecraft does. */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    /** Notes a deposit the resolver rejected. Does nothing if it is already known. */
    public synchronized void recordDropped(Deposit deposit, DepositResolver.Outcome outcome) {
        entries.putIfAbsent(deposit.seed(), new Entry(deposit.seed(), deposit.oreId(), deposit.x(), deposit.z(), 0,
                -1, 0, (float) outcome.solidFraction(), new long[0], new boolean[0], 0, State.DROPPED, 0,
                new int[0], new int[0], false));
    }

    /**
     * Reports that one chunk has placed some of a deposit's ore (perhaps none: report that too).
     *
     * @param chunk     the chunk, as packed by {@link #chunkKey}
     * @param orePlaced how many ore blocks the chunk really placed for this deposit. Until the chunk is next
     *                  refreshed this is also how much ore is left in it
     * @param profile   the chunks that hold any of this deposit's ore; only asked for the first time the
     *                  deposit is reported
     * @return the deposit's final entry if this report completed it, otherwise null. Reports for chunks
     *         that hold none of the deposit's ore, and repeat reports, are ignored
     */
    public synchronized EntryView recordChunk(Deposit deposit, DepositResolver.Outcome outcome, DepositBody body,
                                              long chunk, int orePlaced, Supplier<ChunkProfile> profile) {
        Entry entry = entries.get(deposit.seed());
        if (entry == null) {
            ChunkProfile sorted = sorted(profile.get());
            int n = sorted.keys().length;
            int[] unknown = new int[n];
            Arrays.fill(unknown, -1);
            entry = new Entry(deposit.seed(), deposit.oreId(), deposit.x(), deposit.z(), body.centerY(),
                    outcome.attempt(), body.oreCount(), (float) outcome.solidFraction(), sorted.keys(),
                    new boolean[n], 0, State.IN_PROGRESS, 0, sorted.cells(), unknown, false);
            entries.put(deposit.seed(), entry);
            if (n == 0) {
                complete(entry);
                return entry.view();
            }
        }
        if (entry.state != State.IN_PROGRESS) {
            return null;
        }
        int index = Arrays.binarySearch(entry.expectedChunks, chunk);
        if (index < 0 || entry.done[index]) {
            return null;
        }
        entry.done[index] = true;
        entry.placed += orePlaced;
        entry.remaining[index] = orePlaced;
        for (boolean d : entry.done) {
            if (!d) {
                return null;
            }
        }
        complete(entry);
        return entry.view();
    }

    private static ChunkProfile sorted(ChunkProfile profile) {
        int n = profile.keys().length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingLong(i -> profile.keys()[i]));
        long[] keys = new long[n];
        int[] cells = new int[n];
        for (int i = 0; i < n; i++) {
            keys[i] = profile.keys()[order[i]];
            cells[i] = profile.cells().length == n ? profile.cells()[order[i]] : 0;
        }
        return new ChunkProfile(keys, cells);
    }

    private void complete(Entry entry) {
        if (entry.placed == 0 && entry.expectedChunks.length > 0) {
            entry.state = State.PHANTOM;
        } else if (entry.placed < THIN_RATIO * entry.expectedOre) {
            entry.state = State.THIN;
        } else {
            entry.state = State.OK;
        }
        entry.order = nextOrder++;
    }

    /**
     * Records how much ore a deposit has left in one chunk, as counted when that chunk saved or unloaded. If that
     * leaves the whole deposit used up, and every one of its chunks is known, it is marked depleted for good.
     *
     * @return true if anything changed. Chunks that have not generated, and deposits that are not tracked, are ignored
     */
    public synchronized boolean updateRemaining(long seed, long chunk, int oreLeft) {
        Entry entry = entries.get(seed);
        if (entry == null) {
            return false;
        }
        int index = Arrays.binarySearch(entry.expectedChunks, chunk);
        if (index < 0 || !entry.done[index]) {
            return false;
        }
        boolean changed = entry.remaining[index] != oreLeft;
        entry.remaining[index] = oreLeft;
        if (!entry.depleted && isUsedUp(entry)) {
            entry.depleted = true;
            changed = true;
        }
        return changed;
    }

    private static boolean isUsedUp(Entry entry) {
        long total = 0;
        for (int i = 0; i < entry.expectedChunks.length; i++) {
            if (!entry.done[i] || entry.remaining[i] < 0) {
                return false; // some part is ungenerated or not known exactly, so it may yet hold ore
            }
            total += entry.remaining[i];
        }
        return total < depletedBelow(entry.expectedOre);
    }

    /** Marks a deposit as used up for good. Ore does not come back, so it is never counted again. */
    public synchronized void markDepleted(long seed) {
        Entry entry = entries.get(seed);
        if (entry != null) {
            entry.depleted = true;
        }
    }

    /** Fills in how many ore blocks lie in each chunk of an entry saved before that was recorded. */
    public synchronized void fillCells(long seed, int[] cells) {
        Entry entry = entries.get(seed);
        if (entry != null && entry.cells.length != entry.expectedChunks.length && cells.length == entry.expectedChunks.length) {
            entry.cells = cells.clone();
        }
    }

    /** The entry for a deposit, or null if nothing about it has been recorded. */
    public synchronized EntryView view(long seed) {
        Entry entry = entries.get(seed);
        return entry == null ? null : entry.view();
    }

    /**
     * Where a chunk is in a deposit's list of chunks holding ore, or -1 if the deposit is not tracked or none of
     * its ore is in that chunk. Cheap: nothing is copied.
     */
    public synchronized int chunkIndex(long seed, long chunk) {
        Entry entry = entries.get(seed);
        if (entry == null) {
            return -1;
        }
        int index = Arrays.binarySearch(entry.expectedChunks, chunk);
        return index < 0 ? -1 : index;
    }

    /** Totals over everything tracked. */
    public synchronized Stats stats() {
        int inProgress = 0;
        int ok = 0;
        int thin = 0;
        int phantom = 0;
        int dropped = 0;
        int depleted = 0;
        int[] byAttempt = new int[DepositResolver.MAX_ATTEMPTS];
        double predicted = 0.0;
        double actual = 0.0;
        int completed = 0;
        for (Entry entry : entries.values()) {
            switch (entry.state) {
                case IN_PROGRESS -> inProgress++;
                case OK -> ok++;
                case THIN -> thin++;
                case PHANTOM -> phantom++;
                case DROPPED -> dropped++;
            }
            if (entry.depleted) {
                depleted++;
            }
            if (entry.state != State.DROPPED && entry.attempt >= 0 && entry.attempt < byAttempt.length) {
                byAttempt[entry.attempt]++;
            }
            if (entry.state == State.OK || entry.state == State.THIN || entry.state == State.PHANTOM) {
                completed++;
                predicted += entry.solidFraction;
                actual += entry.expectedOre == 0 ? 0.0 : (double) entry.placed / entry.expectedOre;
            }
        }
        return new Stats(entries.size(), inProgress, ok, thin, phantom, dropped, depleted, byAttempt,
                completed == 0 ? 0.0 : predicted / completed, completed == 0 ? 0.0 : actual / completed);
    }

    /** The most recently completed phantom deposits, newest first. */
    public synchronized List<EntryView> phantoms(int limit) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.state == State.PHANTOM) {
                found.add(entry);
            }
        }
        found.sort(Comparator.comparingLong((Entry e) -> e.order).reversed());
        List<EntryView> views = new ArrayList<>();
        for (Entry entry : found.subList(0, Math.min(limit, found.size()))) {
            views.add(entry.view());
        }
        return views;
    }

    /** Forgets everything. */
    public synchronized void clear() {
        entries.clear();
        nextOrder = 1;
    }

    /** How many deposits are tracked. */
    public synchronized int size() {
        return entries.size();
    }

    /** A copy of every entry, for saving. */
    public synchronized List<EntryView> snapshot() {
        List<EntryView> views = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            views.add(entry.view());
        }
        return views;
    }

    /** Replaces everything tracked with saved entries. Entries saved before ore counts were kept are accepted, and treated as not yet known. */
    public synchronized void restore(List<EntryView> saved) {
        entries.clear();
        nextOrder = 1;
        for (EntryView view : saved) {
            int n = view.expectedChunks().length;
            int[] remaining = view.remaining().length == n ? view.remaining().clone() : new int[n];
            if (view.remaining().length != n) {
                Arrays.fill(remaining, -1);
            }
            entries.put(view.seed(), new Entry(view.seed(), view.oreId(), view.x(), view.z(), view.centerY(),
                    view.attempt(), view.expectedOre(), view.solidFraction(), view.expectedChunks().clone(),
                    view.done().clone(), view.placed(), view.state(), view.order(),
                    view.cells().clone(), remaining, view.depleted()));
            nextOrder = Math.max(nextOrder, view.order() + 1);
        }
    }
}
