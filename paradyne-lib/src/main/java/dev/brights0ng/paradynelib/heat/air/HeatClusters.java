package dev.brights0ng.paradynelib.heat.air;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups heat sources into regions (weather phase 8f-2). Pure.
 *
 * <p>Sources whose margins touch (within {@code 2 x margin} blocks of each other along every axis) share a region;
 * a region's box is its sources' bounding box grown by {@code margin} (kept inside the world's height). A box longer
 * than {@code maxSize} along an axis is cut in two at its middle, again and again, so regions never overlap; the cut
 * is a seam where each side sees the other as outside air (only for very large groups, such as a lava lake).
 */
public final class HeatClusters {

    /** A region to be: its box {minX, minY, minZ, maxX, maxY, maxZ} (inclusive) and the indices of its sources. */
    public record Cluster(int[] box, int[] members) {
    }

    /**
     * @param x, y, z the sources' block positions (same length)
     * @param minY    the lowest block y a box may reach
     * @param maxY    the highest block y a box may reach
     */
    public static List<Cluster> cluster(int[] x, int[] y, int[] z, int margin, int maxSize, int minY, int maxY) {
        int n = x.length;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        int reach = 2 * margin;
        int cell = Math.max(1, reach);
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int cx = Math.floorDiv(x[i], cell);
            int cy = Math.floorDiv(y[i], cell);
            int cz = Math.floorDiv(z[i], cell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> others = grid.get(key(cx + dx, cy + dy, cz + dz));
                        if (others == null) {
                            continue;
                        }
                        for (int j : others) {
                            if (Math.abs(x[i] - x[j]) <= reach && Math.abs(y[i] - y[j]) <= reach
                                    && Math.abs(z[i] - z[j]) <= reach) {
                                union(parent, i, j);
                            }
                        }
                    }
                }
            }
            grid.computeIfAbsent(key(cx, cy, cz), k -> new ArrayList<>()).add(i);
        }
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        List<Cluster> out = new ArrayList<>();
        for (List<Integer> g : groups.values()) {
            int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                    Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int i : g) {
                box[0] = Math.min(box[0], x[i]);
                box[1] = Math.min(box[1], y[i]);
                box[2] = Math.min(box[2], z[i]);
                box[3] = Math.max(box[3], x[i]);
                box[4] = Math.max(box[4], y[i]);
                box[5] = Math.max(box[5], z[i]);
            }
            box[0] -= margin;
            box[1] = Math.max(minY, box[1] - margin);
            box[2] -= margin;
            box[3] += margin;
            box[4] = Math.min(maxY, box[4] + margin);
            box[5] += margin;
            split(box, g.stream().mapToInt(Integer::intValue).toArray(), x, y, z, maxSize, out);
        }
        out.sort((a, b) -> {
            for (int k = 0; k < 6; k++) {
                int c = Integer.compare(a.box[k], b.box[k]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        return out;
    }

    private static void split(int[] box, int[] members, int[] x, int[] y, int[] z, int maxSize, List<Cluster> out) {
        if (members.length == 0) {
            return;
        }
        int axis = -1;
        int longest = maxSize;
        for (int a = 0; a < 3; a++) {
            int extent = box[a + 3] - box[a] + 1;
            if (extent > longest) {
                longest = extent;
                axis = a;
            }
        }
        if (axis < 0) {
            out.add(new Cluster(box, members));
            return;
        }
        int cut = box[axis] + (box[axis + 3] - box[axis]) / 2;
        int[] lowBox = box.clone();
        int[] highBox = box.clone();
        lowBox[axis + 3] = cut;
        highBox[axis] = cut + 1;
        int[] coord = axis == 0 ? x : axis == 1 ? y : z;
        int lows = 0;
        for (int m : members) {
            if (coord[m] <= cut) {
                lows++;
            }
        }
        int[] low = new int[lows];
        int[] high = new int[members.length - lows];
        int li = 0;
        int hi = 0;
        for (int m : members) {
            if (coord[m] <= cut) {
                low[li++] = m;
            } else {
                high[hi++] = m;
            }
        }
        split(lowBox, low, x, y, z, maxSize, out);
        split(highBox, high, x, y, z, maxSize, out);
    }

    private static long key(int cx, int cy, int cz) {
        return ((long) cx & 0x1FFFFF) | (((long) cy & 0x1FFFFF) << 21) | (((long) cz & 0x1FFFFF) << 42);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[ra] = rb;
        }
    }

    private HeatClusters() {
    }
}
