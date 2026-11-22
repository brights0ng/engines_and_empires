// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure 6-neighbour connected-component partitioner and labeller for rooms within a region.
 * <p>
 * Separator blocks (doors, trapdoors, fence gates — chosen per region type) belong to no room. Remaining blocks
 * partition into 6-connected rooms that never touch each other directly. When indoor/outdoor labelling is enabled,
 * any room that has sky access or touches a non-separator whitelisted block with sky access is labelled
 * {@link RoomLabel#OUTDOOR}; all other rooms are labelled {@link RoomLabel#INDOOR}.
 */
public final class RegionRooms {

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    @FunctionalInterface
    public interface BlockPredicate {
        boolean test(int x, int y, int z);
    }

    public record Room(int index, Set<Long> blocks, Set<RoomLabel> labels) {

        public boolean hasLabel(RoomLabel label) {
            return labels.contains(label);
        }

        public boolean isOutdoor() {
            return labels.contains(RoomLabel.OUTDOOR);
        }

        public boolean isIndoor() {
            return labels.contains(RoomLabel.INDOOR);
        }

        public int size() {
            return blocks.size();
        }
    }

    public record Partition(List<Room> rooms, Set<Long> separators) {

        public static final Partition EMPTY = new Partition(List.of(), Set.of());

        /** Total blocks across all non-outdoor rooms (excludes outdoor rooms and separator blocks). */
        public int effectiveVolume() {
            int total = 0;
            for (Room room : rooms) {
                if (!room.isOutdoor()) {
                    total += room.size();
                }
            }
            return total;
        }

        public int indoorBlockCount() {
            int total = 0;
            for (Room room : rooms) {
                if (room.isIndoor()) {
                    total += room.size();
                }
            }
            return total;
        }

        public int outdoorBlockCount() {
            int total = 0;
            for (Room room : rooms) {
                if (room.isOutdoor()) {
                    total += room.size();
                }
            }
            return total;
        }

        public int indoorRoomCount() {
            int count = 0;
            for (Room room : rooms) {
                if (room.isIndoor()) {
                    count++;
                }
            }
            return count;
        }

        public int outdoorRoomCount() {
            int count = 0;
            for (Room room : rooms) {
                if (room.isOutdoor()) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Partitions {@code regionBlocks} into 6-connected rooms separated by blocks matching {@code isSeparator}.
     * If {@code labelIndoorOutdoor} is true, labels each room {@link RoomLabel#OUTDOOR} if any block in the room
     * itself or any 6-neighbour outside {@code regionBlocks} satisfies {@code isSkyExposedWhitelisted}, and
     * {@link RoomLabel#INDOOR} otherwise.
     */
    public static Partition partition(Set<Long> regionBlocks,
                                      BlockPredicate isSeparator,
                                      boolean labelIndoorOutdoor,
                                      BlockPredicate isSkyExposedWhitelisted) {
        if (regionBlocks.isEmpty()) {
            return Partition.EMPTY;
        }

        Set<Long> separators = new HashSet<>();
        for (long key : regionBlocks) {
            if (isSeparator.test(BlockKeys.getX(key), BlockKeys.getY(key), BlockKeys.getZ(key))) {
                separators.add(key);
            }
        }

        int maxRoomSize = regionBlocks.size() - separators.size();
        if (maxRoomSize <= 0) {
            return new Partition(List.of(), Collections.unmodifiableSet(separators));
        }

        Set<Long> visited = new HashSet<>(Math.max(16, (int) (maxRoomSize / 0.75f) + 1));
        long[] queue = new long[maxRoomSize];
        List<Room> rooms = new ArrayList<>();

        for (long seed : regionBlocks) {
            if (separators.contains(seed) || !visited.add(seed)) {
                continue;
            }
            Set<Long> roomBlocks = new HashSet<>();
            int head = 0;
            int tail = 0;
            roomBlocks.add(seed);
            queue[tail++] = seed;

            boolean outdoor = false;
            while (head < tail) {
                long current = queue[head++];
                int cx = BlockKeys.getX(current);
                int cy = BlockKeys.getY(current);
                int cz = BlockKeys.getZ(current);

                if (labelIndoorOutdoor && !outdoor && isSkyExposedWhitelisted != null
                        && isSkyExposedWhitelisted.test(cx, cy, cz)) {
                    outdoor = true;
                }

                for (int i = 0; i < 6; i++) {
                    int nx = cx + DX[i];
                    int ny = cy + DY[i];
                    int nz = cz + DZ[i];
                    long neighborKey = BlockKeys.pack(nx, ny, nz);
                    if (regionBlocks.contains(neighborKey)) {
                        if (!separators.contains(neighborKey) && visited.add(neighborKey)) {
                            roomBlocks.add(neighborKey);
                            queue[tail++] = neighborKey;
                        }
                    } else if (labelIndoorOutdoor && !outdoor && isSkyExposedWhitelisted != null
                            && isSkyExposedWhitelisted.test(nx, ny, nz)) {
                        outdoor = true;
                    }
                }
            }

            Set<RoomLabel> labels;
            if (labelIndoorOutdoor) {
                labels = Collections.unmodifiableSet(EnumSet.of(outdoor ? RoomLabel.OUTDOOR : RoomLabel.INDOOR));
            } else {
                labels = Set.of();
            }
            rooms.add(new Room(rooms.size(), Collections.unmodifiableSet(roomBlocks), labels));
        }

        return new Partition(Collections.unmodifiableList(rooms), Collections.unmodifiableSet(separators));
    }

    private RegionRooms() {
    }
}
