// Written by Gemini; surroundingStaked by Claude.
package dev.brights0ng.empire.rules;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

public final class ClaimGraph {

    /**
     * Checks if the given chunk is cardinally adjacent (N, S, E, W) to any chunk in the staked set.
     */
    public static boolean isAdjacent(Set<Long> staked, long chunk) {
        if (staked == null || staked.isEmpty()) {
            return false;
        }
        int x = ChunkKeys.getX(chunk);
        int z = ChunkKeys.getZ(chunk);
        return staked.contains(ChunkKeys.pack(x + 1, z))
                || staked.contains(ChunkKeys.pack(x - 1, z))
                || staked.contains(ChunkKeys.pack(x, z + 1))
                || staked.contains(ChunkKeys.pack(x, z - 1));
    }

    /** Whether {@code chunk} and all 8 chunks around it are in the staked set. */
    public static boolean surroundingStaked(Set<Long> staked, long chunk) {
        if (staked == null) {
            return false;
        }
        int x = ChunkKeys.getX(chunk);
        int z = ChunkKeys.getZ(chunk);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!staked.contains(ChunkKeys.pack(x + dx, z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Checks if removing {@code chunk} would split the staked chunks into disconnected components.
     * A bridge chunk is one whose removal causes fewer than |set| - 1 remaining chunks to be reachable
     * from an arbitrary remaining chunk via 4-neighbour adjacency.
     */
    public static boolean isBridge(Set<Long> staked, long chunk) {
        if (staked == null || !staked.contains(chunk)) {
            return false;
        }
        int targetSize = staked.size() - 1;
        if (targetSize <= 1) {
            return false;
        }

        // Find a starting chunk in staked that is not the chunk to remove
        long start = 0L;
        boolean foundStart = false;
        for (long c : staked) {
            if (c != chunk) {
                start = c;
                foundStart = true;
                break;
            }
        }
        if (!foundStart) {
            return false;
        }

        // Flood fill 4-neighbours
        Set<Long> visited = new HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);

        while (!queue.isEmpty()) {
            long current = queue.remove();
            int cx = ChunkKeys.getX(current);
            int cz = ChunkKeys.getZ(current);

            checkNeighbor(staked, chunk, visited, queue, ChunkKeys.pack(cx + 1, cz));
            checkNeighbor(staked, chunk, visited, queue, ChunkKeys.pack(cx - 1, cz));
            checkNeighbor(staked, chunk, visited, queue, ChunkKeys.pack(cx, cz + 1));
            checkNeighbor(staked, chunk, visited, queue, ChunkKeys.pack(cx, cz - 1));
        }

        return visited.size() < targetSize;
    }

    private static void checkNeighbor(Set<Long> staked, long removedChunk, Set<Long> visited, Queue<Long> queue, long neighbor) {
        if (neighbor != removedChunk && staked.contains(neighbor) && visited.add(neighbor)) {
            queue.add(neighbor);
        }
    }

    private ClaimGraph() {
    }
}
