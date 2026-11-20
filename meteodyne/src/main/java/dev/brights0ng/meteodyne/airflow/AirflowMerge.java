// Written by Claude (GreedyFluids M8b5b: tiles merged into one region).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.List;

/**
 * Which tiles run as one region (M8b5b, M1; Bright, 2026-11-17 and 11-18, decisions 20 and 23), pure Java. A merged
 * region is one simulation over a box of tiles, so air crosses the borders inside it.
 * <ul>
 *   <li><b>Candidates</b> (from the region manager, around a player's tile): {@link Mode#LINKED}, the tiles the
 *       enclosed air the player is in reaches across borders (B3's links, {@link AirflowNeighbourTiles});
 *       {@link Mode#ADJACENT}, also every running tile joined to the player's face to face inside the 3×3×3 block.</li>
 *   <li><b>Boxes only</b> (decision 23), at most {@link #MAX_TILES} tiles, holding the player's tile, inside the block:
 *       lines of 2 or 3 tiles and 2×2 squares. The box chosen is the largest whose tiles are all candidates, then the
 *       one with the strongest links; the box already running wins a tie, so a region doesn't swap one box for an
 *       equal one.</li>
 *   <li><b>Settle</b> (manager): a box merges once chosen for {@code airflowMergeSettleSeconds} (5 s) with every tile
 *       running, and a merged region splits once it hasn't been chosen for as long.</li>
 * </ul>
 */
public final class AirflowMerge {

    /** What {@code [heat] airflowMergeTiles} merges. */
    public enum Mode {
        /** Nothing: every tile runs alone. */
        OFF,
        /** Tiles linked by the enclosed air a player is in. */
        LINKED,
        /** Those, and any running tiles joined to a player's tile face to face. */
        ADJACENT
    }

    /** The most tiles a merged region holds (decision 20). */
    public static final int MAX_TILES = 4;

    /** A box of tiles: its low corner, tiles from the player's, and its size in tiles along each axis. */
    record Box(int dx, int dy, int dz, int sx, int sy, int sz) {
        int tiles() {
            return sx * sy * sz;
        }

        boolean holds(int x, int y, int z) {
            return x >= dx && y >= dy && z >= dz && x < dx + sx && y < dy + sy && z < dz + sz;
        }
    }

    /** The candidates around the player's tile, by tile offset. */
    interface Tiles {
        /** Whether the tile at (dx, dy, dz) from the player's may be in the box. */
        boolean in(int dx, int dy, int dz);

        /** How strongly it is linked (B3's cell pairs; 0 for none). */
        int link(int dx, int dy, int dz);
    }

    /** Every box holding (0, 0, 0) inside the 3×3×3 block, of 2 to {@link #MAX_TILES} tiles, largest first. */
    static final List<Box> SHAPES = shapes();

    /**
     * The box the player's tile should run in: the largest of {@link #SHAPES} whose tiles are all in, then the most
     * strongly linked; {@code current} (the box running now, or null) if it is still all in and no smaller than that.
     * Null when no box of 2 or more tiles is.
     */
    static Box choose(Tiles tiles, Box current) {
        Box best = null;
        int bestLink = -1;
        for (Box b : SHAPES) {
            if (best != null && b.tiles() < best.tiles()) {
                break; // the shapes are largest first
            }
            if (!allIn(tiles, b)) {
                continue;
            }
            int link = link(tiles, b);
            if (best == null || link > bestLink) {
                best = b;
                bestLink = link;
            }
        }
        if (current != null && current.tiles() >= 2 && current.tiles() <= MAX_TILES && current.holds(0, 0, 0)
                && allIn(tiles, current) && (best == null || current.tiles() >= best.tiles())) {
            return current;
        }
        return best;
    }

    private static boolean allIn(Tiles tiles, Box b) {
        for (int y = b.dy(); y < b.dy() + b.sy(); y++) {
            for (int z = b.dz(); z < b.dz() + b.sz(); z++) {
                for (int x = b.dx(); x < b.dx() + b.sx(); x++) {
                    if (!tiles.in(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int link(Tiles tiles, Box b) {
        int sum = 0;
        for (int y = b.dy(); y < b.dy() + b.sy(); y++) {
            for (int z = b.dz(); z < b.dz() + b.sz(); z++) {
                for (int x = b.dx(); x < b.dx() + b.sx(); x++) {
                    sum += tiles.link(x, y, z);
                }
            }
        }
        return sum;
    }

    private static List<Box> shapes() {
        List<Box> out = new ArrayList<>();
        // 2×2 squares (4 tiles), in each plane
        for (int plane = 0; plane < 3; plane++) {
            for (int a = -1; a <= 0; a++) {
                for (int b = -1; b <= 0; b++) {
                    out.add(switch (plane) {
                        case 0 -> new Box(a, b, 0, 2, 2, 1); // x–y
                        case 1 -> new Box(a, 0, b, 2, 1, 2); // x–z
                        default -> new Box(0, a, b, 1, 2, 2); // y–z
                    });
                }
            }
        }
        // lines of 3, centred on the player's tile
        out.add(new Box(-1, 0, 0, 3, 1, 1));
        out.add(new Box(0, -1, 0, 1, 3, 1));
        out.add(new Box(0, 0, -1, 1, 1, 3));
        // lines of 2
        for (int a = -1; a <= 0; a++) {
            out.add(new Box(a, 0, 0, 2, 1, 1));
            out.add(new Box(0, a, 0, 1, 2, 1));
            out.add(new Box(0, 0, a, 1, 1, 2));
        }
        return List.copyOf(out);
    }

    private AirflowMerge() {
    }
}
