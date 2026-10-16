package dev.brights0ng.enginesandempires.weather.heat.air;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RadiantTest {

    private static final AirParams P = AirParams.DEFAULT;

    @Test
    void aCampfireWarmsSixDegreesAtOneBlockFallingWithTheSquare() {
        assertEquals(6, Radiant.warmth(P, 1, 0.5, 1, 1), 1e-9);
        assertEquals(1.5, Radiant.warmth(P, 1, 0.5, 2, 1), 1e-9);
        assertEquals(6, Radiant.warmth(P, 1, 0.5, 0.3, 1), 1e-9, "no closer than the minimum distance");
    }

    @Test
    void glassLetsAQuarterThroughAndWallsNone() {
        assertEquals(1.5, Radiant.warmth(P, 1, 0.5, 1, 0.25), 1e-9);
        assertEquals(0, Radiant.warmth(P, 1, 0.5, 1, 0), 1e-9);
    }

    @Test
    void coldRadiatesToo() {
        assertEquals(-12 * 0.8 / 4, Radiant.warmth(P, -0.8, 1, 2, 1), 1e-9, "blue ice, radiant only, 2 blocks");
    }

    @Test
    void reachIsWhereItFadesBelowTheFloor() {
        double reach = Radiant.reach(P, 1, 0.5);
        assertEquals(P.radiantFloor(), Radiant.warmth(P, 1, 0.5, reach, 1), 1e-9);
        assertEquals(Radiant.reach(P, 0.8, 1), Radiant.reach(P, -0.8, 1), 1e-9, "cold reaches as far");
        assertEquals(0, Radiant.reach(P, 1, 0), 1e-9, "nothing radiated, no reach");
    }

    @Test
    void gridPowerLeavesOutTheRadiantShare() {
        assertEquals(75, P.gridPower(1, 0.5), 1e-9);
        assertEquals(0, P.gridPower(0.15, 1), 1e-9, "glowstone: radiant only");
    }

    @Test
    void manySourcesEaseTowardTheLimit() {
        assertEquals(6, Radiant.combine(6, 45, 0, 20), 0.05, "one campfire feels about as it would alone");
        double lake = Radiant.combine(400, 60, 0, 20);
        org.junit.jupiter.api.Assertions.assertTrue(lake > 59 && lake <= 60, "a lava lake's edge: toward +60, " + lake);
        assertEquals(-20 * Math.tanh(0.5), Radiant.combine(0, 45, -10, 20), 1e-9, "cold eases toward -20");
        assertEquals(Radiant.combine(10, 45, 0, 20) + Radiant.combine(0, 45, -5, 20),
                Radiant.combine(10, 45, -5, 20), 1e-9, "warm and cold are eased apart, then added");
        assertEquals(0, Radiant.combine(0, 45, 0, 20), 1e-9);
    }
}
