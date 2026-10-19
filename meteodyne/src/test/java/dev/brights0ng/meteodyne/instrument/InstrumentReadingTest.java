// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class InstrumentReadingTest {
    @Test
    void aReadingIsOnlyUsableInItsWorldAndWhileFresh() {
        InstrumentReading reading = new InstrumentReading("minecraft:overworld", 100, -12.5F);
        assertTrue(reading.usable("minecraft:overworld", 100));
        assertTrue(reading.usable("minecraft:overworld", 160));
        assertFalse(reading.usable("minecraft:overworld", 161));
        assertFalse(reading.usable("minecraft:overworld", 99));
        assertFalse(reading.usable("minecraft:the_nether", 101));
        assertFalse(new InstrumentReading("minecraft:overworld", 100, Float.NaN).usable("minecraft:overworld", 100));
    }

}
