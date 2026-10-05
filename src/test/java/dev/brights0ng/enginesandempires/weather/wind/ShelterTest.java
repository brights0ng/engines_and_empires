package dev.brights0ng.enginesandempires.weather.wind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShelterTest {

    private static final double ROOF = WindParams.DEFAULTS.roofWeight();

    private static double[] cover(double top, double north, double south, double west, double east) {
        return new double[]{top, north, south, west, east};
    }

    @Test
    void openAirIsFullyExposed() {
        assertEquals(1, Shelter.exposure(cover(0, 0, 0, 0, 0), 1, 0, ROOF), 1e-9);
    }

    @Test
    void allFiveSidesCoveredIsImmune() {
        assertEquals(0, Shelter.exposure(cover(1, 1, 1, 1, 1), 0.3, -0.7, ROOF), 1e-9);
    }

    @Test
    void anUpwindWallShelters() {
        // Wind travelling toward +X (east) comes from the west: the west face is upwind.
        assertEquals(0, Shelter.exposure(cover(0, 0, 0, 1, 0), 1, 0, ROOF), 1e-9);
    }

    @Test
    void aDownwindWallDoesNothing() {
        assertEquals(1, Shelter.exposure(cover(0, 0, 0, 0, 1), 1, 0, ROOF), 1e-9);
    }

    @Test
    void aWallAtAnAngleSheltersPartly() {
        // Wind toward the south-east: north and west faces share the weight equally.
        assertEquals(0.5, Shelter.exposure(cover(0, 0, 0, 1, 0), 1, 1, ROOF), 1e-9);
    }

    @Test
    void aRoofAloneCutsByTheRoofWeight() {
        assertEquals(1 - ROOF, Shelter.exposure(cover(1, 0, 0, 0, 0), 0, 1, ROOF), 1e-9);
    }

    @Test
    void moreCoverMeansLessPush() {
        double some = Shelter.exposure(cover(0.2, 0.3, 0, 0, 0), 0, 1, ROOF);
        double more = Shelter.exposure(cover(0.6, 0.8, 0, 0, 0), 0, 1, ROOF);
        assertTrue(more < some);
    }
}
