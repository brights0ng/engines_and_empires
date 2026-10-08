package dev.brights0ng.enginesandempires.weather.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Heights at x0.2 and the type registry. */
class CloudScaleTest {

    @Test
    void cumulonimbusAreKilometresTallAtFifthScale() {
        for (int i = 0; i < 200; i++) {
            CloudScale.Heights h = CloudScale.heights(CloudType.CUMULONIMBUS_CAPILLATUS,
                    new UUID(i * 7919L, i * 104729L), 1);
            double base = h.base() - CloudScale.GROUND_Y;
            double top = h.top() - CloudScale.GROUND_Y;
            assertTrue(base >= 100 && base <= 300, "base " + base);
            assertTrue(top >= 2000 && top <= 3000, "top " + top);
            assertTrue(h.top() <= CloudScale.MAX_TOP_Y);
        }
    }

    @Test
    void oneCloudSharesItsHeights() {
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
    void layerCloudsDontGrowUpward() {
        CloudScale.Heights a = CloudScale.heights(CloudType.ALTOSTRATUS, 900, 200, 0.1);
        assertEquals(1100, a.top(), 1e-9);
    }

    @Test
    void decksStackLowMidHigh() {
        UUID id = UUID.randomUUID();
        double stratus = CloudScale.heights(CloudType.STRATUS, id, 1).base();
        double alto = CloudScale.heights(CloudType.ALTOSTRATUS, id, 1).base();
        double cirro = CloudScale.heights(CloudType.CIRROSTRATUS, id, 1).base();
        assertTrue(stratus < alto && alto < cirro);
        CloudScale.Heights cirrus = CloudScale.heights("cirrus", UUID.randomUUID(), 1);
        assertTrue(cirrus.base() - CloudScale.GROUND_Y >= 1200);
        assertTrue(cirrus.top() - cirrus.base() <= 300);
    }

    @Test
    void registryKnowsTheStandardNames() {
        assertEquals(CloudType.NIMBOSTRATUS, CloudType.of("engines_and_empires:nimbostratus"));
        assertEquals(CloudType.CIRROSTRATUS, CloudType.byName("CIRROSTRATUS"));
        assertNull(CloudType.of("supercell"), "no supercells (Bright, 2026-10-05)");
        assertNull(CloudType.of("vapor_cluster"));
        for (CloudType t : CloudType.values()) {
            assertEquals(t, CloudType.of(t.id));
            assertTrue(t.baseMax >= t.baseMin && t.topMax >= t.topMin, t.id);
        }
        assertEquals(CloudType.CUMULUS_CONGESTUS, CloudType.CUMULUS_MEDIOCRIS.grownInto());
        assertNotNull(CloudType.CUMULUS_CONGESTUS.grownInto());
        assertNull(CloudType.NIMBOSTRATUS.grownInto());
        // A cumulonimbus is about as wide as it is tall at x0.2.
        assertEquals(1200, CloudType.CUMULONIMBUS_CAPILLATUS.radiusBlocks(), 1);
        assertTrue(CloudType.CUMULUS_HUMILIS.radiusBlocks() < CloudType.CUMULONIMBUS_CALVUS.radiusBlocks());
    }

    /** No cloud base below y 100, whatever the type or air (Bright, 2026-10-08); thickness kept. */
    @Test
    void noCloudBaseBelowY100() {
        assertEquals(CloudScale.MIN_BASE_Y, CloudScale.baseY(0), 1e-9);
        assertEquals(CloudScale.MIN_BASE_Y, CloudScale.baseY(-500), 1e-9);
        assertEquals(CloudScale.GROUND_Y + 1000 * CloudScale.SCALE, CloudScale.baseY(1000), 1e-9);
        for (CloudType t : CloudType.values()) {
            assertTrue(CloudScale.baseY(t.baseMin) >= CloudScale.MIN_BASE_Y, t.id);
            for (int i = 0; i < 50; i++) {
                CloudScale.Heights h = CloudScale.heights(t, new UUID(i * 31L, i * 977L), 1);
                assertTrue(h.base() >= CloudScale.MIN_BASE_Y, t.id + " base " + h.base());
            }
        }
        // A base handed in low (an older save, a test) is lifted; its thickness stays.
        CloudScale.Heights h = CloudScale.heights(CloudType.NIMBOSTRATUS, 70, 60, 1);
        assertEquals(CloudScale.MIN_BASE_Y, h.base(), 1e-9);
        assertEquals(CloudScale.MIN_BASE_Y + 60, h.top(), 1e-9);
    }
}
