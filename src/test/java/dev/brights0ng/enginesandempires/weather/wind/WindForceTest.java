package dev.brights0ng.enginesandempires.weather.wind;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WindForceTest {

    private static final double K = WindParams.DEFAULTS.pushCoefficient();

    @Test
    void pushIsQuadraticInClosingSpeed() {
        double at10 = WindForce.push(10, 0, 100, 1, 1, K);
        double at20 = WindForce.push(20, 0, 100, 1, 1, K);
        assertEquals(4 * at10, at20, 1e-9, "twice the wind, four times the push");
        assertEquals(K * 100 * 100, at10, 1e-9);
    }

    @Test
    void lightWindsBarelyRegister() {
        double light = WindForce.push(5, 0, 100, 1, 1, K);
        double storm = WindForce.push(25, 0, 100, 1, 1, K);
        assertEquals(1.0 / 25, light / storm, 1e-9);
    }

    @Test
    void windNeverBrakes() {
        assertEquals(0, WindForce.push(10, 10, 100, 1, 1, K), "moving with the wind");
        assertEquals(0, WindForce.push(10, 15, 100, 1, 1, K), "outrunning the wind");
        assertEquals(0, WindForce.push(0, -20, 100, 1, 1, K), "calm air adds no drag");
    }

    @Test
    void headingIntoTheWindAddsNoDragOfItsOwn() {
        assertEquals(WindForce.push(10, 0, 50, 1, 1, K), WindForce.push(10, -25, 50, 1, 1, K), 1e-9);
    }

    @Test
    void movingWithTheWindEasesThePush() {
        assertEquals(WindForce.push(6, 0, 50, 1, 1, K), WindForce.push(10, 4, 50, 1, 1, K), 1e-9);
    }

    @Test
    void scalesWithAreaPressureAndExposure() {
        double base = WindForce.push(12, 0, 40, 1, 1, K);
        assertEquals(2 * base, WindForce.push(12, 0, 80, 1, 1, K), 1e-9);
        assertEquals(0.5 * base, WindForce.push(12, 0, 40, 0.5, 1, K), 1e-9);
        assertEquals(0.25 * base, WindForce.push(12, 0, 40, 1, 0.25, K), 1e-9);
        assertEquals(0, WindForce.push(12, 0, 40, 1, 0, K), "fully sheltered");
    }

    @Test
    void oneStepNeverClosesMoreThanHalfTheGap() {
        assertEquals(3, WindForce.limitImpulse(3, 10, 20), 1e-9, "well under the cap");
        assertEquals(0.5 * 0.25 * 20, WindForce.limitImpulse(50, 0.25, 20), 1e-9, "a light block in a storm");
        assertEquals(0, WindForce.limitImpulse(5, 1, 0));
    }
}
