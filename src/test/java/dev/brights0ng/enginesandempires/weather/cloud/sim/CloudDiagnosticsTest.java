package dev.brights0ng.enginesandempires.weather.cloud.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.climate.ClimateCurves;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.field.Moisture;
import dev.brights0ng.enginesandempires.weather.sim.FrontGeometry;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;

/** Where the diagnostics put cloud: the warm-front sequence, cold fronts, sunny afternoons, highs, slopes. */
public class CloudDiagnosticsTest {

    static final long NOON = 6000;
    static final long AFTERNOON = 9000; // 15:00
    static final long NIGHT = 20000;    // 02:00

    /** Air at {@code t} C (aloft at the normal offset plus {@code aloftOffset}) with relative humidity {@code rh}. */
    public static CloudDiagnostics.Air air(double t, double rh, double aloftOffset, int surface) {
        return new CloudDiagnostics.Air(t, t - 9.75 + aloftOffset, rh * Moisture.capacity(t), 0, t, 0.5, surface, 20, 0,
                0, 3, 0, 12, 0, -0.65);
    }

    static WeatherSystem matureLow() {
        return new WeatherSystem(1, WeatherSystem.Kind.LOW, 0, 0, 0, 1, 35_000, 100_000, 26, 4500, false);
    }

    @Test
    void aWarmFrontsCloudLowersAndThickensAsItNears() {
        WeatherSystem low = matureLow();
        CloudDiagnostics.Systems systems = CloudDiagnostics.Systems.of(List.of(low));
        FrontGeometry.Front warm = FrontGeometry.of(low).stream().filter(f -> f.type() == FrontGeometry.Type.WARM)
                .findFirst().orElseThrow();
        double[] mid = warm.points().get(3);
        CloudDiagnostics.Air moist = air(12, 0.8, 0, 0);
        StringBuilder sb = new StringBuilder("ahead of the warm front:");
        CloudType[] expected = new CloudType[6];
        double[] distances = {3000, 2600, 1800, 900, 200, -200};
        for (int i = 0; i < distances.length; i++) {
            // North is ahead (the cold side) for a northern-style low.
            CloudDiagnostics.Need n = CloudDiagnostics.diagnose(mid[0], mid[1] - distances[i], moist, systems, NOON, 0);
            // The layer type covering the most sky.
            CloudType most = null;
            for (CloudType t : CloudType.values()) {
                if (t.layer() && n.cover(t) > 0.15 && (most == null || n.cover(t) > n.cover(most))) {
                    most = t;
                }
            }
            expected[i] = most;
            sb.append(String.format(" %.0f:%s(%.0f ahead)", distances[i], n.describe(), n.warmAhead()));
        }
        System.out.println(sb);
        assertEquals(CloudType.CIRRUS, expected[0], "cirrus far ahead");
        assertEquals(CloudType.CIRROSTRATUS, expected[2], "then cirrostratus");
        assertEquals(CloudType.ALTOSTRATUS, expected[3], "then altostratus");
        assertEquals(CloudType.NIMBOSTRATUS, expected[4], "nimbostratus at the front");
        assertEquals(CloudType.NIMBOSTRATUS, expected[5], "and just behind it");
    }

    @Test
    void dryAirAheadOfAWarmFrontGetsNoNimbostratus() {
        WeatherSystem low = matureLow();
        CloudDiagnostics.Systems systems = CloudDiagnostics.Systems.of(List.of(low));
        double[] mid = FrontGeometry.of(low).stream().filter(f -> f.type() == FrontGeometry.Type.WARM).findFirst()
                .orElseThrow().points().get(3);
        CloudDiagnostics.Need n = CloudDiagnostics.diagnose(mid[0], mid[1] - 200, air(12, 0.3, 0, 0), systems, NOON, 0);
        assertTrue(n.cover(CloudType.NIMBOSTRATUS) < 0.1, n.describe());
    }

    @Test
    void aColdFrontBringsALineOfHeapCloud() {
        WeatherSystem low = matureLow();
        CloudDiagnostics.Systems systems = CloudDiagnostics.Systems.of(List.of(low));
        FrontGeometry.Front cold = FrontGeometry.of(low).stream().filter(f -> f.type() == FrontGeometry.Type.COLD)
                .findFirst().orElseThrow();
        double[] mid = cold.points().get(3);
        // Warm, humid air at night (no sun): only the front's lift builds cloud.
        CloudDiagnostics.Air warmSector = air(18, 0.75, 0, 0);
        CloudDiagnostics.Need at = CloudDiagnostics.diagnose(mid[0] + 100, mid[1], warmSector, systems, NIGHT, 0);
        CloudDiagnostics.Need away = CloudDiagnostics.diagnose(mid[0] + 3000, mid[1], warmSector,
                CloudDiagnostics.Systems.NONE, NIGHT, 0);
        System.out.println("cold front:" + at.describe() + " / away:" + away.describe());
        assertNotNull(at.heapType(), "a line of heap cloud at the front");
        assertTrue(at.heapType().ordinal() >= CloudType.CUMULUS_CONGESTUS.ordinal(), "towering: " + at.heapType());
        assertTrue(at.heapCover() > 0.3);
        assertTrue(away.heapType() == null || away.heapCover() < at.heapCover(), "not away from it");
    }

    @Test
    void sunnyAfternoonsGrowCumulusAndNightsDont() {
        CloudDiagnostics.Air plains = air(18, 0.55, 0, 0);
        CloudDiagnostics.Need summer = CloudDiagnostics.diagnose(0, 0, plains, CloudDiagnostics.Systems.NONE, AFTERNOON, 1);
        CloudDiagnostics.Need night = CloudDiagnostics.diagnose(0, 0, plains, CloudDiagnostics.Systems.NONE, NIGHT, 1);
        CloudDiagnostics.Need winter = CloudDiagnostics.diagnose(0, 0, plains, CloudDiagnostics.Systems.NONE, AFTERNOON, -1);
        System.out.printf("summer afternoon:%s (I %.2f, base %.0f m) / night:%s / winter afternoon:%s (I %.2f)%n",
                summer.describe(), summer.instability(), summer.lclMetres(), night.describe(), winter.describe(),
                winter.instability());
        assertNotNull(summer.heapType(), "cumulus on a summer afternoon");
        assertTrue(summer.heapCover() > 0.15);
        assertNull(night.heapType(), "none at night");
        assertTrue(winter.instability() < summer.instability(), "weaker winter sun");
        assertTrue(summer.lclMetres() > 500 && summer.lclMetres() < 2500, "a fair-weather cloud base");
    }

    @Test
    void waterDoesntHeatByDayButWarmsColdAirFromBelow() {
        CloudDiagnostics.Air sea = air(15, 0.7, 0, 2);
        assertNull(CloudDiagnostics.diagnose(0, 0, sea, CloudDiagnostics.Systems.NONE, AFTERNOON, 1).heapType(),
                "no afternoon cumulus over a mild sea");
        // A cold outbreak over a warm sea: the air 6 C colder than the water, colder still aloft.
        CloudDiagnostics.Air outbreak = new CloudDiagnostics.Air(4, 4 - 9.75 - 6, 0.75 * Moisture.capacity(4), 0, 10,
                0.5, 2, 0, 0, 0, 8, 0, 15, 0, 0);
        CloudDiagnostics.Need n = CloudDiagnostics.diagnose(0, 0, outbreak, CloudDiagnostics.Systems.NONE, NIGHT, -1);
        assertNotNull(n.heapType(), "ocean-effect showers day or night: " + n.describe());
    }

    @Test
    void highsClearTheSky() {
        WeatherSystem high = new WeatherSystem(2, WeatherSystem.Kind.HIGH, 0, 0, 0, 1, 50_000, 120_000, 14, 7000, false);
        CloudDiagnostics.Air humid = air(14, 0.85, 0, 0);
        CloudDiagnostics.Need under = CloudDiagnostics.diagnose(0, 0, humid, CloudDiagnostics.Systems.of(List.of(high)),
                NIGHT, 0);
        CloudDiagnostics.Need open = CloudDiagnostics.diagnose(0, 0, humid, CloudDiagnostics.Systems.NONE, NIGHT, 0);
        assertTrue(under.subsidence() > 0.8);
        double lowUnder = Math.max(under.cover(CloudType.STRATUS), under.cover(CloudType.STRATOCUMULUS));
        double lowOpen = Math.max(open.cover(CloudType.STRATUS), open.cover(CloudType.STRATOCUMULUS));
        assertTrue(lowOpen > 0.3 && lowUnder < 0.5 * lowOpen, under.describe() + " vs " + open.describe());
    }

    @Test
    void anOvercastSkyKeepsTheAfternoonFromBoilingUp() {
        WeatherSystem low = matureLow();
        CloudDiagnostics.Systems systems = CloudDiagnostics.Systems.of(List.of(low));
        double[] mid = FrontGeometry.of(low).stream().filter(f -> f.type() == FrontGeometry.Type.WARM).findFirst()
                .orElseThrow().points().get(3);
        CloudDiagnostics.Air moist = air(12, 0.8, 0, 0);
        CloudDiagnostics.Need under = CloudDiagnostics.diagnose(mid[0], mid[1] - 200, moist, systems, AFTERNOON, 0);
        CloudDiagnostics.Need open = CloudDiagnostics.diagnose(mid[0], mid[1] - 200, moist,
                CloudDiagnostics.Systems.NONE, AFTERNOON, 0);
        assertTrue(under.instability() < 0.6 * open.instability(), under.describe() + " vs " + open.describe());
    }

    @Test
    void windBlowingUpASlopeCapsItInCloud() {
        // 8 m/s straight up a 30% slope, in moist air.
        CloudDiagnostics.Air slope = new CloudDiagnostics.Air(8, 8 - 9.75, 0.85 * Moisture.capacity(8 - 3), 0, 8, 0.6,
                1, 150, 0.3, 0, 8, 0, 12, 0, -3);
        CloudDiagnostics.Need n = CloudDiagnostics.diagnose(0, 0, slope, CloudDiagnostics.Systems.NONE, NIGHT, 0);
        System.out.println("upslope:" + n.describe());
        assertTrue(n.orographic() > 0.9);
        assertTrue(n.layerIn(CloudType.Deck.LOW, 0.3) != null, n.describe());
        // The lee side (wind blowing down the slope) stays clear of the cap.
        CloudDiagnostics.Air lee = new CloudDiagnostics.Air(8, 8 - 9.75, 0.6 * Moisture.capacity(8 - 3), 0, 8, 0.6,
                1, 150, -0.3, 0, 8, 0, 12, 0, -3);
        assertEquals(0, CloudDiagnostics.diagnose(0, 0, lee, CloudDiagnostics.Systems.NONE, NIGHT, 0).orographic(), 1e-9);
    }

    @Test
    void stormsNeedInstabilityAndMoisture() {
        assertNull(CloudDiagnostics.heapType(0.05, 0.8));
        assertEquals(CloudType.CUMULUS_HUMILIS, CloudDiagnostics.heapType(0.2, 0.6));
        assertEquals(CloudType.CUMULONIMBUS_CAPILLATUS, CloudDiagnostics.heapType(1.3, 0.8));
        assertEquals(CloudType.CUMULUS_CONGESTUS, CloudDiagnostics.heapType(1.3, 0.4), "too dry for a storm");
        assertEquals(CloudType.CUMULUS_CONGESTUS, CloudDiagnostics.heapType(1.3, 0.9, 5),
                "cold air holds too little water for a storm, however humid");
    }

    /** 2026-10-06: condensation in cooled air over flat ground makes stratus, not a raining nimbostratus deck. */
    @Test
    void coolingOverFlatGroundGivesStratusNotNimbostratus() {
        // Cold, nearly saturated air with 2.5 mm wrung out over flat ice, light wind.
        CloudDiagnostics.Air flat = new CloudDiagnostics.Air(-11, -17, 0.9 * Moisture.capacity(-11), 2.5, -11, 0.5, 3,
                0, 0, 0, 1.5, 4, 8, 0, 0);
        CloudDiagnostics.Need n = CloudDiagnostics.diagnose(0, 0, flat, CloudDiagnostics.Systems.NONE, NIGHT, -0.7);
        System.out.println("cooled flat ground:" + n.describe() + " stratus from " + n.sourceOf(CloudType.STRATUS));
        assertTrue(n.cover(CloudType.NIMBOSTRATUS) < 0.05, n.describe());
        assertTrue(n.cover(CloudType.STRATUS) > 0.2, n.describe());
        assertTrue(n.sourceOf(CloudType.STRATUS).equals("cooled moist air")
                || n.sourceOf(CloudType.STRATUS).equals("stable humid air"), n.sourceOf(CloudType.STRATUS));
        // The same water wrung out where the wind climbs a slope is mountain rain.
        CloudDiagnostics.Air slope = new CloudDiagnostics.Air(-11, -17, 0.9 * Moisture.capacity(-11 - 3), 2.5, -11,
                0.5, 0, 150, 0.3, 0, 8, 0, 12, 0, -3);
        CloudDiagnostics.Need m = CloudDiagnostics.diagnose(0, 0, slope, CloudDiagnostics.Systems.NONE, NIGHT, -0.7);
        assertTrue(m.cover(CloudType.NIMBOSTRATUS) > 0.5, m.describe());
        assertEquals("upslope", m.sourceOf(CloudType.NIMBOSTRATUS));
    }

    @Test
    void aHighSmothersRainingDecks() {
        WeatherSystem low = matureLow();
        double[] mid = FrontGeometry.of(low).stream().filter(f -> f.type() == FrontGeometry.Type.WARM).findFirst()
                .orElseThrow().points().get(3);
        WeatherSystem high = new WeatherSystem(3, WeatherSystem.Kind.HIGH, mid[0], mid[1] - 200, 0, 1, 50_000, 120_000,
                14, 7000, false);
        CloudDiagnostics.Need n = CloudDiagnostics.diagnose(mid[0], mid[1] - 200, air(12, 0.8, 0, 0),
                CloudDiagnostics.Systems.of(List.of(low, high)), NOON, 0);
        // A little less than the high's full sinking: the low's convergence reaches out to its warm front.
        assertTrue(n.subsidence() > 0.75, "sinking " + n.subsidence());
        assertTrue(n.cover(CloudType.NIMBOSTRATUS) < 0.05, n.describe());
    }

    /** 2026-10-06 flight test: cumulus kept forming after dusk, credited to the afternoon sun. */
    @Test
    void cumulusFollowTheSunNotTheWarmEvening() {
        assertEquals(0.0, ClimateCurves.solar(14000), "20:00");
        assertEquals(0.0, ClimateCurves.solar(1000), "7:00");
        assertTrue(ClimateCurves.solar(7000) > 0.95, "13:00");
        assertTrue(ClimateCurves.diurnal(14000) > 0, "the air is still warm at 20:00");
        CloudDiagnostics.Air plains = air(18, 0.55, 0, 0);
        CloudDiagnostics.Need evening = CloudDiagnostics.diagnose(0, 0, plains, CloudDiagnostics.Systems.NONE, 14000, 1);
        assertNull(evening.heapType(), "no new cumulus at 20:00: " + evening.describe());
        assertTrue(evening.heating() <= 0);
    }

    /** 2026-10-06 flight test: cumulus grew into mediocris under a strong high. */
    @Test
    void aHighsLidKeepsCumulusSmall() {
        CloudDiagnostics.Air hot = air(26, 0.7, -6, 0);
        CloudDiagnostics.Need free = CloudDiagnostics.diagnose(0, 0, hot, CloudDiagnostics.Systems.NONE, AFTERNOON, 1);
        assertNotNull(free.heapType());
        assertTrue(free.heapType().ordinal() > CloudType.CUMULUS_HUMILIS.ordinal(), free.describe());
        WeatherSystem high = new WeatherSystem(3, WeatherSystem.Kind.HIGH, 0, 0, 0, 1, 50_000, 120_000, 14, 7000,
                false);
        CloudDiagnostics.Need lid = CloudDiagnostics.diagnose(0, 0, hot, CloudDiagnostics.Systems.of(List.of(high)),
                AFTERNOON, 1);
        assertTrue(lid.subsidence() > CloudDiagnostics.LID_SUBSIDENCE);
        assertTrue(lid.heapType() == null || lid.heapType() == CloudType.CUMULUS_HUMILIS, lid.describe());
    }

    /** 2026-10-06 flight test: a building high 5 km off made the air sink at a deepening low's core. */
    @Test
    void aLowsCoreCancelsAHighsSinking() {
        WeatherSystem low = matureLow();
        WeatherSystem high = new WeatherSystem(2, WeatherSystem.Kind.HIGH, 5000, 0, 0, 1, 20_000, 100_000, 8, 5200,
                false);
        CloudDiagnostics.Systems both = CloudDiagnostics.Systems.of(List.of(low, high));
        CloudDiagnostics.Need core = CloudDiagnostics.diagnose(0, 0, air(4, 0.65, -8, 0), both, NOON, 0);
        CloudDiagnostics.Need highOnly = CloudDiagnostics.diagnose(0, 0, air(4, 0.65, -8, 0),
                CloudDiagnostics.Systems.of(List.of(high)), NOON, 0);
        System.out.printf("low core: sinking %.2f (high alone %.2f), %s, heap cover %.2f%n", core.subsidence(),
                highOnly.subsidence(), core.describe(), core.heapCover());
        assertTrue(highOnly.subsidence() > 0.1, "the high reaches here on its own");
        assertTrue(core.subsidence() < 0.5 * highOnly.subsidence(), "the low's rising air cancels most of it");
    }

    /** 2026-10-06 flight test: under cold air aloft, land cumulus fields kept going until past midnight. */
    @Test
    void theNightsInversionCalmsTheLand() {
        assertEquals(1.0, ClimateCurves.landBuoyancy(6000), 1e-9, "noon");
        assertEquals(ClimateCurves.NIGHT_BUOYANCY, ClimateCurves.landBuoyancy(18000), 1e-9, "midnight");
        assertTrue(ClimateCurves.landBuoyancy(13000) > 0.8, "19:00, just after the sun stops heating");
        CloudDiagnostics.Air cold = air(4, 0.8, -8, 0);
        CloudDiagnostics.Need evening = CloudDiagnostics.diagnose(0, 0, cold, CloudDiagnostics.Systems.NONE, 12500, 0);
        CloudDiagnostics.Need midnight = CloudDiagnostics.diagnose(0, 0, cold, CloudDiagnostics.Systems.NONE, 18000, 0);
        System.out.printf("cold air aloft over land: 18:30 I %.2f (%s), 00:00 I %.2f (%s)%n", evening.instability(),
                evening.describe(), midnight.instability(), midnight.describe());
        assertTrue(midnight.instability() < 0.5 * evening.instability());
    }
}
