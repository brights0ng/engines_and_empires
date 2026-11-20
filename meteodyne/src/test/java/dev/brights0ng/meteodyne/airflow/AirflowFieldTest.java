// Written by Claude (GreedyFluids M7c c2; the shut-door case M7f).
package dev.brights0ng.meteodyne.airflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.brights0ng.greedyfluids.grid.CellGrid3D;
import dev.brights0ng.greedyfluids.grid.Material;
import org.junit.jupiter.api.Test;

class AirflowFieldTest {

    @Test
    void aLinearLatticeInterpolatesExactlyAtCellCentres() {
        int nx = 16, ny = 8, nz = 8;
        int lx = AirflowField.points(nx), ly = AirflowField.points(ny), lz = AirflowField.points(nz);
        float[] lattice = new float[lx * ly * lz];
        for (int j = 0; j < ly; j++) {
            for (int k = 0; k < lz; k++) {
                for (int i = 0; i < lx; i++) {
                    // 10 C, cooling 0.5 C per block up, warming 0.25 per block east
                    lattice[(j * lz + k) * lx + i] = 10 - 0.5f * 4 * j + 0.25f * 4 * i;
                }
            }
        }
        float[] cells = AirflowField.cells(lattice, nx, ny, nz);
        assertEquals(10 - 0.5 * 0.5 + 0.25 * 0.5, cells[0], 1e-5);
        assertEquals(10 - 0.5 * 7.5 + 0.25 * 15.5, cells[(7 * nz + 7) * nx + 15], 1e-5);
        assertThrows(IllegalArgumentException.class, () -> AirflowField.cells(new float[3], nx, ny, nz));
    }

    @Test
    void skyLightFillsOpenColumnsAndFadesThroughADoorway() {
        int n = 12, h = 6;
        CellGrid3D grid = new CellGrid3D(n, h, n);
        grid.fillSolid(0, 0, 0, n - 1, 0, n - 1, Material.STONE); // ground
        grid.fillSolid(2, 1, 2, 8, 4, 8, Material.STONE); // a house's shell
        grid.fillAir(3, 1, 3, 7, 3, 7); // its room
        grid.setAir(5, 1, 2); // a doorway in its north wall
        grid.setAir(5, 2, 2);
        int[] top = new int[n * n];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                boolean roofed = x >= 2 && x <= 8 && z >= 2 && z <= 8;
                top[z * n + x] = roofed ? 5 : 1; // the first open height: above the roof, or above the ground
            }
        }
        byte[] sky = AirflowField.sky(grid, top);
        assertEquals(15, sky[grid.index(0, 1, 0)], "open ground");
        assertEquals(0, sky[grid.index(0, 0, 0)], "solid");
        assertEquals(14, sky[grid.index(5, 1, 2)], "the doorway, one step in");
        assertEquals(13, sky[grid.index(5, 1, 3)], "just inside");
        assertEquals(9, sky[grid.index(5, 1, 7)], "the back wall: five steps further");
        float[] follow = AirflowField.follow(sky);
        assertEquals(1, follow[grid.index(0, 1, 0)], 0);
        assertEquals((13 / 15f) * (13 / 15f), follow[grid.index(5, 1, 3)], 1e-6);
    }

    @Test
    void aShutDoorStopsTheSkyAndAnOpenOnePassesIt() {
        int n = 12, h = 6;
        CellGrid3D grid = new CellGrid3D(n, h, n);
        grid.fillSolid(0, 0, 0, n - 1, 0, n - 1, Material.STONE);
        grid.fillSolid(2, 1, 2, 8, 4, 8, Material.STONE);
        grid.fillAir(3, 1, 3, 7, 3, 7);
        grid.setDoor(5, 1, 2, Material.DOOR, false); // a shut door in the north wall
        grid.setDoor(5, 2, 2, Material.DOOR, false);
        int[] top = new int[n * n];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                top[z * n + x] = x >= 2 && x <= 8 && z >= 2 && z <= 8 ? 5 : 1;
            }
        }
        byte[] sky = AirflowField.sky(grid, top);
        assertEquals(0, sky[grid.index(5, 1, 2)], "the shut door");
        assertEquals(0, sky[grid.index(5, 1, 3)], "the room behind it");
        assertEquals(15, sky[grid.index(5, 1, 1)], "outside");
        grid.setDoorOpen(5, 1, 2, true);
        grid.setDoorOpen(5, 2, 2, true);
        sky = AirflowField.sky(grid, top);
        assertEquals(14, sky[grid.index(5, 1, 2)], "the open door, as a doorway");
        assertEquals(9, sky[grid.index(5, 1, 7)], "the back wall");
    }

    @Test
    void columnTopsOutsideTheGridAreFine() {
        CellGrid3D grid = new CellGrid3D(4, 4, 4);
        int[] top = new int[16];
        java.util.Arrays.fill(top, -10); // the tile is all above the heightmap
        byte[] sky = AirflowField.sky(grid, top);
        for (byte b : sky) {
            assertEquals(15, b);
        }
        java.util.Arrays.fill(top, 99); // all under an overhang above the tile
        sky = AirflowField.sky(grid, top);
        for (byte b : sky) {
            assertEquals(0, b);
        }
    }
}
