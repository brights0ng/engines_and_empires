// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure 6-neighbour 3D flood fill over packed {@link BlockKeys} coordinates with a block cap and early hazard abort.
 */
public final class RegionFloodFill {

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    public enum Cell {
        INCLUDE,
        BOUNDARY,
        HAZARD,
        EXPOSED_TO_SKY
    }

    @FunctionalInterface
    public interface Classifier {
        Cell classify(int x, int y, int z);
    }

    @FunctionalInterface
    public interface StepClassifier {
        Cell classify(int fromX, int fromY, int fromZ, int x, int y, int z);
    }

    public record Result(Set<Long> blocks, RegionInvalidReason failure) {

        public boolean succeeded() {
            return failure == RegionInvalidReason.NONE;
        }

        public static Result ok(Set<Long> blocks) {
            return new Result(Collections.unmodifiableSet(blocks), RegionInvalidReason.NONE);
        }

        public static Result failed(RegionInvalidReason reason) {
            return new Result(Set.of(), reason);
        }
    }

    /**
     * Flood fills 6-connected {@link Cell#INCLUDE} cells starting at {@code (startX, startY, startZ)}.
     * Aborts immediately with {@link RegionInvalidReason#HAZARD} if the start cell or any 6-neighbour of an included
     * cell classifies as {@link Cell#HAZARD}, with {@link RegionInvalidReason#TOO_LARGE} if the included block count
     * would exceed {@code maxBlocks}, or with {@link RegionInvalidReason#TOO_SMALL} if the start cell is a boundary.
     */
    public static Result fill(int startX, int startY, int startZ, int maxBlocks, Classifier classifier) {
        return fill(startX, startY, startZ, maxBlocks, (fx, fy, fz, x, y, z) -> classifier.classify(x, y, z));
    }

    /**
     * Flood fills 6-connected {@link Cell#INCLUDE} cells starting at {@code (startX, startY, startZ)}, passing the
     * parent cell coordinates to {@code classifier} (for the start cell itself, {@code from == start}).
     * Aborts immediately with {@link RegionInvalidReason#HAZARD} on {@link Cell#HAZARD},
     * {@link RegionInvalidReason#EXPOSED_TO_SKY} on {@link Cell#EXPOSED_TO_SKY},
     * {@link RegionInvalidReason#TOO_LARGE} if the included block count would exceed {@code maxBlocks},
     * or {@link RegionInvalidReason#TOO_SMALL} if the start cell is a boundary.
     */
    public static Result fill(int startX, int startY, int startZ, int maxBlocks, StepClassifier classifier) {
        if (maxBlocks <= 0) {
            return Result.failed(RegionInvalidReason.TOO_LARGE);
        }
        Cell startCell = classifier.classify(startX, startY, startZ, startX, startY, startZ);
        if (startCell == Cell.HAZARD) {
            return Result.failed(RegionInvalidReason.HAZARD);
        }
        if (startCell == Cell.EXPOSED_TO_SKY) {
            return Result.failed(RegionInvalidReason.EXPOSED_TO_SKY);
        }
        if (startCell != Cell.INCLUDE) {
            return Result.failed(RegionInvalidReason.TOO_SMALL);
        }

        Set<Long> filled = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        long[] queue = new long[maxBlocks];
        int head = 0;
        int tail = 0;

        long startKey = BlockKeys.pack(startX, startY, startZ);
        filled.add(startKey);
        visited.add(startKey);
        queue[tail++] = startKey;

        while (head < tail) {
            long current = queue[head++];
            int cx = BlockKeys.getX(current);
            int cy = BlockKeys.getY(current);
            int cz = BlockKeys.getZ(current);

            for (int i = 0; i < 6; i++) {
                int nx = cx + DX[i];
                int ny = cy + DY[i];
                int nz = cz + DZ[i];
                long neighborKey = BlockKeys.pack(nx, ny, nz);
                if (!visited.add(neighborKey)) {
                    continue;
                }
                Cell cell = classifier.classify(cx, cy, cz, nx, ny, nz);
                if (cell == Cell.HAZARD) {
                    return Result.failed(RegionInvalidReason.HAZARD);
                }
                if (cell == Cell.EXPOSED_TO_SKY) {
                    return Result.failed(RegionInvalidReason.EXPOSED_TO_SKY);
                }
                if (cell == Cell.INCLUDE) {
                    if (filled.size() >= maxBlocks) {
                        return Result.failed(RegionInvalidReason.TOO_LARGE);
                    }
                    filled.add(neighborKey);
                    queue[tail++] = neighborKey;
                }
            }
        }

        return Result.ok(filled);
    }

    private RegionFloodFill() {
    }
}
