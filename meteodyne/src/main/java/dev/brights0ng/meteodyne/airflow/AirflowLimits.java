// Written by Claude (GreedyFluids M7e: the safety limits on opening tiles); edited by Claude (M8b b4: secondaries and
// the cap; M8b5b: a merged region counts as its tiles).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Limits on how many airflow tiles run (Bright, 2026-11-15), pure Java:
 * <ul>
 *   <li><b>Speed:</b> a player moving faster than the limit (5.5 blocks a second by default, just under running)
 *       over the last {@link #WINDOW_TICKS} opens no new tile; a tile they are already in keeps running at any
 *       speed. Speed is the path between the manager's position samples over the window, so a teleport counts as
 *       fast until it leaves the window, and a player with no earlier sample (just joined) as still.</li>
 *   <li><b>Cap:</b> at most a number of tiles, server-wide (the default) or per player. Over it, the tiles that have
 *       waited longest in their grace (no player in them) close early. A tile with a player in it is never closed by
 *       the cap. Per player, a tile belongs to the player last in it.</li>
 *   <li><b>Secondaries (M8b b4; Bright, 2026-11-17):</b> tiles kept running beside a player's
 *       ({@link AirflowNeighbourTiles}) count toward the cap; per player, a secondary belongs to the player whose tile
 *       keeps it. Over the cap, tiles in their grace close first, then secondaries; never a tile with a player in it.
 *       A secondary is opened only while the tiles kept running (players' and secondaries) are under the cap
 *       ({@link #roomFor}), so the cap never closes one it just let open.</li>
 *   <li><b>Merged regions (M8b5b):</b> a region that runs several tiles as one counts as that many tiles, and closes
 *       whole.</li>
 * </ul>
 */
public final class AirflowLimits {

    /** The speed window, ticks (2 s). */
    static final int WINDOW_TICKS = 40;

    /** How the tile cap counts. */
    public enum CapMode {
        /** At most the cap's number of tiles on the server. */
        SERVER,
        /** At most the cap's number of tiles per player, a tile belonging to the player last in it. */
        PER_PLAYER
    }

    /** Recent positions of each player, for their speed. */
    static final class Speeds {
        private final int window;
        private final Map<UUID, ArrayDeque<double[]>> samples = new HashMap<>();

        Speeds(int windowTicks) {
            this.window = windowTicks;
        }

        /**
         * Records {@code id}'s position at {@code tick} and returns their mean speed over the window, blocks a
         * second: the path between the samples in it over the time they span; 0 with no earlier sample in it.
         */
        double record(UUID id, long tick, double x, double y, double z) {
            ArrayDeque<double[]> q = samples.computeIfAbsent(id, k -> new ArrayDeque<>());
            q.addLast(new double[] {tick, x, y, z});
            while (q.peekFirst()[0] < tick - window) {
                q.pollFirst();
            }
            if (q.size() < 2) {
                return 0;
            }
            double path = 0;
            double[] last = null;
            for (double[] s : q) {
                if (last != null) {
                    path += Math.sqrt(sq(s[1] - last[1]) + sq(s[2] - last[2]) + sq(s[3] - last[3]));
                }
                last = s;
            }
            double span = tick - q.peekFirst()[0];
            return span <= 0 ? 0 : path / (span / 20.0);
        }

        /** Forgets everyone but {@code ids} (players who left, or left the level). */
        void keep(Set<UUID> ids) {
            samples.keySet().retainAll(ids);
        }

        /** Players with samples (tests). */
        int tracked() {
            return samples.size();
        }

        private static double sq(double v) {
            return v * v;
        }
    }

    /**
     * One tile, as the cap sees it: whether a player is in it, whether it runs as a secondary (and no player is in
     * it), the last time either kept it, and how many tiles it is (M8b5b: a merged region's tiles; 1 otherwise).
     */
    record Tile<T>(T ref, UUID owner, long lastOccupied, boolean occupied, boolean wanted, int tiles) {
        /** One tile. */
        Tile(T ref, UUID owner, long lastOccupied, boolean occupied, boolean wanted) {
            this(ref, owner, lastOccupied, occupied, wanted, 1);
        }

        /** A tile that is no secondary. */
        Tile(T ref, UUID owner, long lastOccupied, boolean occupied) {
            this(ref, owner, lastOccupied, occupied, false, 1);
        }

        /** Whether a player or a player's tile keeps it running (it is not in its grace). */
        boolean kept() {
            return occupied || wanted;
        }
    }

    /** Whether a player at {@code speed} blocks a second may open a tile under {@code limit} (0 or less: no limit). */
    static boolean mayOpen(double speed, double limit) {
        return limit <= 0 || speed <= limit;
    }

    /**
     * Whether a secondary for {@code owner} may open beside {@code tiles}: the tiles kept running (in the whole server,
     * or {@code owner}'s per player) are fewer than {@code cap}. Tiles in their grace don't count: the cap closes them.
     */
    static <T> boolean roomFor(List<Tile<T>> tiles, CapMode mode, int cap, UUID owner) {
        int kept = 0;
        for (Tile<T> t : tiles) {
            if (t.kept() && (mode == CapMode.SERVER || java.util.Objects.equals(t.owner(), owner))) {
                kept += t.tiles();
            }
        }
        return kept < cap;
    }

    /**
     * The tiles to close to bring {@code tiles} within {@code cap}: those in their grace first, longest waiting
     * first; then secondaries; never an occupied one.
     */
    static <T> List<T> overCap(List<Tile<T>> tiles, CapMode mode, int cap) {
        List<T> out = new ArrayList<>();
        if (mode == CapMode.PER_PLAYER) {
            Map<UUID, List<Tile<T>>> byOwner = new HashMap<>();
            for (Tile<T> t : tiles) {
                byOwner.computeIfAbsent(t.owner(), k -> new ArrayList<>()).add(t);
            }
            for (List<Tile<T>> own : byOwner.values()) {
                closeOldest(own, cap, out);
            }
        } else {
            closeOldest(tiles, cap, out);
        }
        return out;
    }

    private static <T> void closeOldest(List<Tile<T>> tiles, int cap, List<T> out) {
        int total = 0;
        for (Tile<T> t : tiles) {
            total += t.tiles();
        }
        int excess = total - Math.max(0, cap);
        if (excess <= 0) {
            return;
        }
        List<Tile<T>> waiting = new ArrayList<>();
        for (Tile<T> t : tiles) {
            if (!t.occupied()) {
                waiting.add(t);
            }
        }
        waiting.sort(Comparator.<Tile<T>, Boolean>comparing(Tile::wanted).thenComparingLong(Tile::lastOccupied));
        for (int i = 0; i < waiting.size() && excess > 0; i++) {
            out.add(waiting.get(i).ref());
            excess -= waiting.get(i).tiles();
        }
    }

    private AirflowLimits() {
    }
}
