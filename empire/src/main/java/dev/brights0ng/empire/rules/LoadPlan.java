// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Which chunks an empire's civilians keep running (civilian chunk-loading plan §3.2; Bright, D2, D5, D8, D10, D11):
 * the staked chunks among the 3x3 around each civilian, while the empire may load. A chunk no longer wanted keeps its
 * ticket for {@link #KEEP_TICKS}, so civilians crossing a chunk border back and forth do not reload it.
 */
public final class LoadPlan {

    /** The server's setting ({@code EmpireConfig.civilianLoading}). */
    public enum Mode {
        /** Civilians load chunks, subject to the frozen and inactivity gates. */
        ALWAYS,
        /** Only while a member of the empire is online. */
        MEMBER_ONLINE,
        /** Never: civilians freeze with their chunks. */
        OFF
    }

    /** Ticks between recomputes (provisional, P4). */
    public static final int LOAD_TICKS = 20;
    /** Ticks an unwanted chunk keeps its ticket (provisional, P5: 30 s). */
    public static final int KEEP_TICKS = 600;
    /** Server time with no member online after which an empire stops loading (Bright, D11: 5 days at 20 TPS). */
    public static final long INACTIVE_TICKS = 5L * 24 * 60 * 60 * 20;

    /**
     * Whether an empire's civilians may load chunks: never with {@link Mode#OFF} or without members (frozen, D10); with
     * {@link Mode#MEMBER_ONLINE} only while one is online; with {@link Mode#ALWAYS} while one is online or was within
     * {@code inactiveLimit} ticks of server time (D11).
     */
    public static boolean mayLoad(Mode mode, boolean hasMembers, boolean memberOnline, long now, long lastMemberOnline,
                                  long inactiveLimit) {
        if (mode == Mode.OFF || !hasMembers) {
            return false;
        }
        if (memberOnline) {
            return true;
        }
        return mode == Mode.ALWAYS && now - lastMemberOnline <= inactiveLimit;
    }

    /** The staked chunks among the 3x3 around each civilian's chunk (D5: empire chunks only). */
    public static Set<Long> wanted(Collection<Long> civilianChunks, Set<Long> staked) {
        Set<Long> wanted = new HashSet<>();
        for (long chunk : civilianChunks) {
            int cx = ChunkKeys.getX(chunk);
            int cz = ChunkKeys.getZ(chunk);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long near = ChunkKeys.pack(cx + dx, cz + dz);
                    if (staked.contains(near)) {
                        wanted.add(near);
                    }
                }
            }
        }
        return wanted;
    }

    /** What to change after an {@link Tickets#update}: tickets to add and to remove. */
    public record Changes(Set<Long> add, Set<Long> remove) {

        public boolean isEmpty() {
            return add.isEmpty() && remove.isEmpty();
        }
    }

    /** One empire's held tickets, with when each became unwanted. Not saved: rebuilt from the records at start. */
    public static final class Tickets {

        private static final long WANTED = Long.MIN_VALUE;

        /** Chunk to the time it stopped being wanted, or {@link #WANTED}. */
        private final Map<Long, Long> held = new HashMap<>();

        /**
         * Brings the held tickets in line with {@code wanted} at {@code now}. When the empire may not load, or a held
         * chunk is no longer staked, its ticket goes at once; a chunk merely no longer wanted goes {@code keepTicks}
         * after it stopped being wanted. The returned changes are already applied to {@link #held}.
         */
        public Changes update(Set<Long> wanted, Set<Long> staked, boolean mayLoad, long now, long keepTicks) {
            Set<Long> add = new HashSet<>();
            Set<Long> remove = new HashSet<>();
            Iterator<Map.Entry<Long, Long>> it = held.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Long> entry = it.next();
                long chunk = entry.getKey();
                if (!mayLoad || !staked.contains(chunk)) {
                    remove.add(chunk);
                    it.remove();
                } else if (wanted.contains(chunk)) {
                    entry.setValue(WANTED);
                } else if (entry.getValue() == WANTED) {
                    entry.setValue(now);
                } else if (now - entry.getValue() >= keepTicks) {
                    remove.add(chunk);
                    it.remove();
                }
            }
            if (mayLoad) {
                for (long chunk : wanted) {
                    if (!held.containsKey(chunk)) {
                        held.put(chunk, WANTED);
                        add.add(chunk);
                    }
                }
            }
            return new Changes(add, remove);
        }

        /** Drops every ticket (the empire is gone); returns the chunks to release. */
        public Set<Long> clear() {
            Set<Long> all = new HashSet<>(held.keySet());
            held.clear();
            return all;
        }

        public Set<Long> held() {
            return Collections.unmodifiableSet(held.keySet());
        }
    }

    private LoadPlan() {
    }
}
