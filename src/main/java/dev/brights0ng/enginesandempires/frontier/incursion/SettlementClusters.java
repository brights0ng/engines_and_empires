package dev.brights0ng.enginesandempires.frontier.incursion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.brights0ng.enginesandempires.frontier.tier.SectionKey;

/**
 * Groups Settled sections into settlements: sections touching each other (on a face, an edge or a corner) belong to the
 * same one, so a village and the farm fields around it are one settlement, and two bases a few chunks apart are two.
 *
 * <p>Nothing here touches Minecraft (sections are {@link SectionKey} longs).
 */
public final class SettlementClusters {

    /** The connected groups among {@code sections}, biggest first. */
    public static List<Set<Long>> cluster(Collection<Long> sections) {
        Set<Long> left = new HashSet<>(sections);
        List<Set<Long>> clusters = new ArrayList<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        while (!left.isEmpty()) {
            long start = left.iterator().next();
            left.remove(start);
            Set<Long> cluster = new HashSet<>();
            cluster.add(start);
            queue.add(start);
            while (!queue.isEmpty()) {
                long at = queue.poll();
                int x = SectionKey.x(at);
                int y = SectionKey.y(at);
                int z = SectionKey.z(at);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long next = SectionKey.of(x + dx, y + dy, z + dz);
                            if (left.remove(next)) {
                                cluster.add(next);
                                queue.add(next);
                            }
                        }
                    }
                }
            }
            clusters.add(cluster);
        }
        clusters.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return clusters;
    }

    /** The chunk columns ({@code x, z} packed as {@link #column}) a settlement's sections stand in. */
    public static Set<Long> columns(Set<Long> sections) {
        Set<Long> columns = new HashSet<>();
        for (long section : sections) {
            columns.add(column(SectionKey.x(section), SectionKey.z(section)));
        }
        return columns;
    }

    /**
     * The columns on a settlement's edge: those with a side neighbour outside it. Incursions come up there, on the way in
     * from the wild.
     */
    public static List<Long> edge(Set<Long> columns) {
        List<Long> edge = new ArrayList<>();
        for (long c : columns) {
            int x = columnX(c);
            int z = columnZ(c);
            if (!columns.contains(column(x + 1, z)) || !columns.contains(column(x - 1, z))
                    || !columns.contains(column(x, z + 1)) || !columns.contains(column(x, z - 1))) {
                edge.add(c);
            }
        }
        return edge;
    }

    /** Packs a chunk column the way Minecraft's {@code ChunkPos.asLong} does. */
    public static long column(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    public static int columnX(long column) {
        return (int) column;
    }

    public static int columnZ(long column) {
        return (int) (column >>> 32);
    }

    private SettlementClusters() {
    }
}
