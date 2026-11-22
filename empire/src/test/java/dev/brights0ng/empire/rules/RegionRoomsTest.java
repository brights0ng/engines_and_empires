// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RegionRoomsTest {

    private static Set<Long> box(int x0, int y0, int z0, int x1, int y1, int z1) {
        Set<Long> out = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    out.add(BlockKeys.pack(x, y, z));
                }
            }
        }
        return out;
    }

    @Test
    void singleRoomWithNoSeparatorsIsOneIndoorRoom() {
        Set<Long> blocks = box(0, 0, 0, 2, 1, 2); // 18 blocks
        RegionRooms.Partition p = RegionRooms.partition(blocks, (x, y, z) -> false, true, (x, y, z) -> false);

        assertEquals(1, p.rooms().size());
        assertTrue(p.separators().isEmpty());
        assertEquals(18, p.effectiveVolume());
        assertEquals(18, p.indoorBlockCount());
        assertEquals(0, p.outdoorBlockCount());
        assertTrue(p.rooms().get(0).isIndoor());
        assertFalse(p.rooms().get(0).isOutdoor());
    }

    @Test
    void doorSeparatesTwoRoomsAndBelongsToNeither() {
        // Left room: x=0..1, y=0..1, z=0..1 (8 blocks)
        // Door:      x=2, y=0..1, z=0 (2 blocks)
        // Right room: x=3..4, y=0..1, z=0..1 (8 blocks)
        Set<Long> blocks = new HashSet<>();
        blocks.addAll(box(0, 0, 0, 1, 1, 1));
        blocks.add(BlockKeys.pack(2, 0, 0));
        blocks.add(BlockKeys.pack(2, 1, 0));
        blocks.addAll(box(3, 0, 0, 4, 1, 1));

        RegionRooms.Partition p = RegionRooms.partition(
                blocks,
                (x, y, z) -> x == 2 && z == 0,
                true,
                (x, y, z) -> false);

        assertEquals(2, p.rooms().size());
        assertEquals(2, p.separators().size());
        assertTrue(p.separators().contains(BlockKeys.pack(2, 0, 0)));
        assertTrue(p.separators().contains(BlockKeys.pack(2, 1, 0)));
        assertEquals(16, p.effectiveVolume());
        for (RegionRooms.Room room : p.rooms()) {
            assertEquals(8, room.size());
            assertTrue(room.isIndoor());
            assertFalse(room.blocks().contains(BlockKeys.pack(2, 0, 0)));
            assertFalse(room.blocks().contains(BlockKeys.pack(2, 1, 0)));
        }
    }

    @Test
    void partialDoorStillLeavesSingleRoomWhenTouchingAroundIt() {
        // Full 3x2x2 block, with only (1,0..1,0) being a door — rooms connect through z=1.
        Set<Long> blocks = box(0, 0, 0, 2, 1, 1);
        RegionRooms.Partition p = RegionRooms.partition(
                blocks,
                (x, y, z) -> x == 1 && z == 0,
                true,
                (x, y, z) -> false);

        assertEquals(1, p.rooms().size());
        assertEquals(2, p.separators().size());
        assertEquals(10, p.effectiveVolume());
    }

    @Test
    void porchTouchingSkyIsOutdoorWhileIndoorBehindDoorStaysIndoor() {
        // Indoor room: x=0..1, y=0..1, z=0..1 (8 blocks)
        // Exterior door: x=2, y=0..1, z=0 (2 blocks)
        // Covered porch: x=3..4, y=0..1, z=0..1 (8 blocks); sky-exposed whitelisted air at x=5
        Set<Long> blocks = new HashSet<>();
        blocks.addAll(box(0, 0, 0, 1, 1, 1));
        blocks.add(BlockKeys.pack(2, 0, 0));
        blocks.add(BlockKeys.pack(2, 1, 0));
        blocks.addAll(box(3, 0, 0, 4, 1, 1));

        RegionRooms.Partition p = RegionRooms.partition(
                blocks,
                (x, y, z) -> x == 2 && z == 0,
                true,
                (x, y, z) -> x >= 5);

        assertEquals(2, p.rooms().size());
        assertEquals(1, p.indoorRoomCount());
        assertEquals(1, p.outdoorRoomCount());
        assertEquals(8, p.effectiveVolume());
        assertEquals(8, p.indoorBlockCount());
        assertEquals(8, p.outdoorBlockCount());

        RegionRooms.Room indoor = p.rooms().stream().filter(RegionRooms.Room::isIndoor).findFirst().orElseThrow();
        RegionRooms.Room outdoor = p.rooms().stream().filter(RegionRooms.Room::isOutdoor).findFirst().orElseThrow();
        assertTrue(indoor.blocks().contains(BlockKeys.pack(0, 0, 0)));
        assertTrue(outdoor.blocks().contains(BlockKeys.pack(4, 0, 0)));
    }

    @Test
    void roofHoleTouchingSkyMarksRoomOutdoor() {
        // 3x2x3 room minus the sky-exposed column at (1, 0..1, 1).
        Set<Long> blocks = box(0, 0, 0, 2, 1, 2);
        blocks.remove(BlockKeys.pack(1, 0, 1));
        blocks.remove(BlockKeys.pack(1, 1, 1));

        RegionRooms.Partition p = RegionRooms.partition(
                blocks,
                (x, y, z) -> false,
                true,
                (x, y, z) -> x == 1 && z == 1);

        assertEquals(1, p.rooms().size());
        assertTrue(p.rooms().get(0).isOutdoor());
        assertEquals(0, p.effectiveVolume());
    }

    @Test
    void diagonalSkyExposureDoesNotLeakOutdoor() {
        // 2x1x2 room at x=0..1, z=0..1; sky-exposed block only at diagonal (2, 0, 2).
        Set<Long> blocks = box(0, 0, 0, 1, 0, 1);
        RegionRooms.Partition p = RegionRooms.partition(
                blocks,
                (x, y, z) -> false,
                true,
                (x, y, z) -> x == 2 && z == 2);

        assertEquals(1, p.rooms().size());
        assertTrue(p.rooms().get(0).isIndoor());
    }

    @Test
    void disablingIndoorOutdoorLeavesLabelsEmptyAndCountsAllRooms() {
        Set<Long> blocks = new HashSet<>();
        blocks.addAll(box(0, 0, 0, 1, 0, 1));
        blocks.add(BlockKeys.pack(2, 0, 0));
        blocks.addAll(box(3, 0, 0, 4, 0, 1));

        RegionRooms.Partition p = RegionRooms.partition(
                blocks,
                (x, y, z) -> x == 2 && z == 0,
                false,
                (x, y, z) -> true);

        assertEquals(2, p.rooms().size());
        assertEquals(1, p.separators().size());
        assertEquals(8, p.effectiveVolume());
        for (RegionRooms.Room room : p.rooms()) {
            assertTrue(room.labels().isEmpty());
            assertFalse(room.isIndoor());
            assertFalse(room.isOutdoor());
        }
    }
}
