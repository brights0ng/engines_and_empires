package dev.brights0ng.enginesandempires.weather.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CloudScaleTest {

    @Test
    void supercellsAreKilometresTallAtFifthScale() {
        for (int i = 0; i < 200; i++) {
            CloudScale.Heights h = CloudScale.heights("supercell", new UUID(i * 7919L, i * 104729L), 1);
            double base = h.base() - CloudScale.GROUND_Y;
            double top = h.top() - CloudScale.GROUND_Y;
            assertTrue(base >= 100 && base <= 400, "base " + base);
            assertTrue(top >= 3000 && top <= 4200, "top " + top);
            assertTrue(h.top() <= CloudScale.MAX_TOP_Y);
        }
    }

    @Test
    void oneFormationSharesItsHeights() {
        UUID region = UUID.randomUUID();
        assertEquals(CloudScale.heights("cumulonimbus_calvus", region, 1),
                CloudScale.heights("cumulonimbus_calvus", region, 1));
    }

    @Test
    void cumulusTopsRiseAsTheyGrow() {
        UUID region = UUID.randomUUID();
        CloudScale.Heights young = CloudScale.heights("cumulus_congestus", region, 0);
        CloudScale.Heights grown = CloudScale.heights("cumulus_congestus", region, 1);
        assertEquals(young.base(), grown.base(), 1e-9);
        assertTrue(grown.top() > young.top() * 1.2);
    }

    @Test
    void cirrusIsHighAndThin() {
        CloudScale.Heights h = CloudScale.heights("projectatmosphere:cirrus", UUID.randomUUID(), 1);
        assertTrue(h.base() - CloudScale.GROUND_Y >= 1200);
        assertTrue(h.top() - h.base() <= 300);
    }

    @Test
    void widthsAreStretchedToRealOnes() {
        assertEquals(1, CloudScale.horizontal("supercell"), 1e-9, "the supercell template sizes itself");
        assertEquals(1, CloudScale.horizontal("something_new"), 1e-9, "unknown types are left as PA sends them");
        // A cumulonimbus is about as wide as it is tall: PA's 116-block lobe becomes about 1,200 blocks.
        assertEquals(1200, 116 * CloudScale.horizontal("cumulonimbus_capillatus"), 1);
        assertTrue(CloudScale.horizontal("cumulus_humilis") < CloudScale.horizontal("cumulonimbus_calvus"));
    }
}
