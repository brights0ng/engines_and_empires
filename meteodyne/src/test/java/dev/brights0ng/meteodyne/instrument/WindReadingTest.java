// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindReadingTest {
    private WindReading wind(float x, float z) { return new WindReading("minecraft:overworld", 100, x, z); }
    @Test void downwindCardinalsAndViewerRotationUseMinecraftYaw() {
        assertEquals(0, wind(0, 10).relativeAngle(0), 1e-9);
        assertEquals(90, wind(-10, 0).relativeAngle(0), 1e-9);
        assertEquals(-90, wind(10, 0).relativeAngle(0), 1e-9);
        assertEquals(-180, wind(0, -10).relativeAngle(0), 1e-9);
        assertEquals(0, wind(10, 0).relativeAngle(270), 1e-9);
        assertEquals(90, wind(10, 0).relativeAngle(180), 1e-9);
        assertEquals(2, WindReading.wrap(-358), 1e-9);
    }
    @Test void calmAndUnavailableAreDistinctAndReadingsExpire() {
        assertTrue(wind(0, 0).calm());
        assertTrue(wind(0, 0).usable("minecraft:overworld", 120));
        assertFalse(wind(0, 1).usable("minecraft:the_nether", 120));
        assertFalse(wind(0, 1).usable("minecraft:overworld", 161));
        assertFalse(wind(0, 1).usable("minecraft:overworld", 99));
        assertFalse(wind(Float.NaN, 0).usable("minecraft:overworld", 120));
        assertEquals(5, wind(3, 4).speed(), 1e-9);
    }
    @Test void cupRotationStopsInCalmAndIncreasesWithSpeedWithoutAliasing() {
        assertEquals(0, WindReading.rotorStep(0));
        assertTrue(WindReading.rotorStep(2) < WindReading.rotorStep(10));
        assertTrue(WindReading.rotorStep(100) < 90.0 / WindGaugeTextures.ROTOR_FRAMES);
    }
    @Test void speedRangeCannotGoNegativeAndPairedDragsKeepTheirSpan() {
        var controls = new SensorRangeControls(0, 300, 0, 1000);
        assertFalse(controls.move(true, -100, false));
        assertTrue(controls.move(false, 1200, true));
        assertEquals(700, controls.zero());
        assertEquals(1000, controls.full());
        assertEquals(0.7, controls.position(700), 1e-9);
        assertEquals(500, controls.valueAt(0.5));
        assertThrows(IllegalArgumentException.class, () -> new SensorRangeControls(-1, 100, 0, 1000));
    }
}
