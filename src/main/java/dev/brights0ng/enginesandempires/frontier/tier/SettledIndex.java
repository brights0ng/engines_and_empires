package dev.brights0ng.enginesandempires.frontier.tier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The sections that count as Settled land, grouped into regions of 8×8×8 sections, so "is there Settled land within 8 of
 * here" only has to look in a handful of regions rather than at every section around.
 *
 * <p>{@link #version()} goes up whenever the set changes, so anything worked out from it can tell when to work it out again.
 *
 * <p>Nothing here touches Minecraft (not even the fastutil collections it ships, so the unit tests can run it). There are
 * only ever a few thousand Settled sections, so boxing does not matter.
 */
public final class SettledIndex {

    private static final int REGION_SHIFT = 3;

    private final Map<Long, Set<Long>> regions = new HashMap<>();
    private int size;
    private long version;

    /** Adds or removes a section. Returns whether anything changed. */
    public boolean set(long section, boolean settled) {
        long region = regionOf(section);
        if (settled) {
            Set<Long> set = regions.computeIfAbsent(region, r -> new HashSet<>());
            if (set.add(section)) {
                size++;
                version++;
                return true;
            }
            return false;
        }
        Set<Long> set = regions.get(region);
        if (set != null && set.remove(section)) {
            if (set.isEmpty()) {
                regions.remove(region);
            }
            size--;
            version++;
            return true;
        }
        return false;
    }

    public boolean contains(long section) {
        Set<Long> set = regions.get(regionOf(section));
        return set != null && set.contains(section);
    }

    /** Whether any section in the set is within {@code radius} of {@code section} (as a cube, and counting itself). */
    public boolean anyWithin(long section, int radius) {
        if (size == 0) {
            return false;
        }
        int x = SectionKey.x(section);
        int y = SectionKey.y(section);
        int z = SectionKey.z(section);
        for (int rx = (x - radius) >> REGION_SHIFT; rx <= (x + radius) >> REGION_SHIFT; rx++) {
            for (int ry = (y - radius) >> REGION_SHIFT; ry <= (y + radius) >> REGION_SHIFT; ry++) {
                for (int rz = (z - radius) >> REGION_SHIFT; rz <= (z + radius) >> REGION_SHIFT; rz++) {
                    Set<Long> set = regions.get(SectionKey.of(rx, ry, rz));
                    if (set == null) {
                        continue;
                    }
                    for (long other : set) {
                        if (SectionKey.distance(section, other) <= radius) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public int size() {
        return size;
    }

    /** Every section in the set, in no particular order (a copy). */
    public java.util.List<Long> all() {
        java.util.List<Long> all = new java.util.ArrayList<>(size);
        for (Set<Long> set : regions.values()) {
            all.addAll(set);
        }
        return all;
    }

    public long version() {
        return version;
    }

    public void clear() {
        regions.clear();
        size = 0;
        version++;
    }

    private static long regionOf(long section) {
        return SectionKey.of(SectionKey.x(section) >> REGION_SHIFT, SectionKey.y(section) >> REGION_SHIFT,
                SectionKey.z(section) >> REGION_SHIFT);
    }
}
