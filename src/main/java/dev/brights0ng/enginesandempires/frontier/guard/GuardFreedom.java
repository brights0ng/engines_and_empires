package dev.brights0ng.enginesandempires.frontier.guard;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Whether a guard is free to move rather than penned: a small flood fill over walkable ground from where it stands. A spot
 * is walkable if something solid is under it and there is room for a body (two blocks) above; from a spot the guard can
 * walk to each of the four spots beside it: on the same level, one block up (a step), or one block down.
 *
 * <p>A guard counts as free if it can reach at least {@code needed} spots (64 by default: about an 8×8 yard). A golem in a
 * 3×3 pen fails; one walking a village square passes. The search stops as soon as it has found enough, and never looks at
 * more than {@code limit} spots.
 *
 * <p>Nothing here touches Minecraft: the world is asked through {@link Ground}.
 */
public final class GuardFreedom {

    /** The world, as far as walking goes. */
    @FunctionalInterface
    public interface Ground {
        /** Whether a body can stand with its feet at {@code (x, y, z)}. */
        boolean standable(int x, int y, int z);
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Same level first, then a step up, then a step down. */
    private static final int[] LEVELS = {0, 1, -1};

    /** How many spots can be walked to from {@code (x, y, z)}, counting it, up to {@code needed}. */
    public static int reachable(Ground ground, int x, int y, int z, int needed, int limit) {
        int startY = startLevel(ground, x, y, z);
        if (startY == Integer.MIN_VALUE) {
            return 0;
        }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        seen.add(pack(x, startY, z));
        queue.add(new int[] {x, startY, z});
        int reached = 0;
        while (!queue.isEmpty() && reached < needed && seen.size() <= limit) {
            int[] at = queue.poll();
            reached++;
            for (int[] side : SIDES) {
                int nx = at[0] + side[0];
                int nz = at[2] + side[1];
                for (int dy : LEVELS) {
                    int ny = at[1] + dy;
                    if (ground.standable(nx, ny, nz)) {
                        if (seen.add(pack(nx, ny, nz))) {
                            queue.add(new int[] {nx, ny, nz});
                        }
                        break; // only one level beside is walkable to
                    }
                }
            }
        }
        return reached;
    }

    public static boolean isFree(Ground ground, int x, int y, int z, int needed, int limit) {
        return reachable(ground, x, y, z, needed, limit) >= needed;
    }

    /** Where the guard's feet really are: its own spot, or the one above or below (standing on a slab, in a carpet). */
    private static int startLevel(Ground ground, int x, int y, int z) {
        for (int dy : LEVELS) {
            if (ground.standable(x, y + dy, z)) {
                return y + dy;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private GuardFreedom() {
    }
}
