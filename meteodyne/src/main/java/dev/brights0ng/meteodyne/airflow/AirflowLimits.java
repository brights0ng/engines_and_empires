// Written by Claude (GreedyFluids M7e: the safety limits on opening tiles).
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

    /** One tile, as the cap sees it. */
    record Tile<T>(T ref, UUID owner, long lastOccupied, boolean occupied) {
    }

    /** Whether a player at {@code speed} blocks a second may open a tile under {@code limit} (0 or less: no limit). */
    static boolean mayOpen(double speed, double limit) {
        return limit <= 0 || speed <= limit;
    }

    /** The tiles to close to bring {@code tiles} within {@code cap}, oldest in grace first; never an occupied one. */
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
        int excess = tiles.size() - Math.max(0, cap);
        if (excess <= 0) {
            return;
        }
        List<Tile<T>> waiting = new ArrayList<>();
        for (Tile<T> t : tiles) {
            if (!t.occupied()) {
                waiting.add(t);
            }
        }
        waiting.sort(Comparator.comparingLong(Tile::lastOccupied));
        for (int i = 0; i < Math.min(excess, waiting.size()); i++) {
            out.add(waiting.get(i).ref());
        }
    }

    private AirflowLimits() {
    }
}
