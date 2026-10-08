package dev.brights0ng.enginesandempires.weather.ships;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShipRulesTest {

    @Test
    void tiltIsMeasuredFromStraightUp() {
        assertEquals(0, ShipRules.tiltDegrees(0, 1, 0), 1e-9);
        assertEquals(90, ShipRules.tiltDegrees(1, 0, 0), 1e-9);
        assertEquals(180, ShipRules.tiltDegrees(0, -2, 0), 1e-9);
    }

    @Test
    void decksCollectUpTo45Degrees() {
        double r = Math.toRadians(40);
        assertTrue(ShipRules.collects(Math.sin(r), Math.cos(r), 0));
        r = Math.toRadians(50);
        assertFalse(ShipRules.collects(0, Math.cos(r), Math.sin(r)));
        assertFalse(ShipRules.collects(0, -1, 0), "upside down");
    }

    @Test
    void snowIsStrippedAbove20MetresASecond() {
        assertFalse(ShipRules.strips(20));
        assertTrue(ShipRules.strips(20.5));
    }
}
