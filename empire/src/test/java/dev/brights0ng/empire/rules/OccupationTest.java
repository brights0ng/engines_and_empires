// Written by Gemini.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class OccupationTest {

    @Test
    void lookupByIdAndRegionType() {
        assertEquals(Occupation.UNEMPLOYED, Occupation.byId("unemployed"));
        assertEquals(Occupation.FARMER, Occupation.byId("farmer"));
        assertEquals(Occupation.FARMER, Occupation.byId("FARMER"));
        assertNull(Occupation.byId(null));
        assertNull(Occupation.byId(""));
        assertNull(Occupation.byId("blacksmith"));

        assertEquals(Occupation.FARMER, Occupation.forRegionType(RegionType.FARM));
        assertNull(Occupation.forRegionType(RegionType.HOME));
        assertNull(Occupation.forRegionType(null));
    }

    @Test
    void farmerDefaultsAndRegionCapacity() {
        assertFalse(Occupation.UNEMPLOYED.isEmployed());
        assertTrue(Occupation.FARMER.isEmployed());
        assertEquals(RegionType.FARM, Occupation.FARMER.workplaceType());
        assertFalse(Occupation.FARMER.indoor(), "farmer work is outdoor (04:00-16:00)");
        assertEquals(CivilianSchedule.DEFAULT_WORK_ASK_TICKS, Occupation.FARMER.defaultDailyAskTicks());

        assertEquals(1, RegionRules.FARM_MAX_WORKERS);
        assertEquals(1, RegionRules.workerCapacity(RegionType.FARM));
        assertEquals(0, RegionRules.workerCapacity(RegionType.HOME));
        assertEquals(0, RegionRules.workerCapacity(null));
    }

    @Test
    void occupationLangKeysExistInEnUs() throws Exception {
        JsonObject lang;
        try (InputStream in = OccupationTest.class.getResourceAsStream("/assets/empire/lang/en_us.json")) {
            assertNotNull(in, "en_us.json on the classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (Occupation occ : Occupation.values()) {
            assertTrue(lang.has(occ.langKey()), "missing lang key " + occ.langKey());
        }
    }
}
