package dev.brights0ng.meteodyne.cloud.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.meteodyne.climate.ClimateCurves;
import dev.brights0ng.meteodyne.cloud.CloudType;
import dev.brights0ng.meteodyne.field.Moisture;
import dev.brights0ng.meteodyne.sim.FrontGeometry;
import dev.brights0ng.meteodyne.sim.WeatherSystem;

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
        // Round 5: the rain shield reaches ~1,000 ahead, so altostratus is read at 1,300 (was 900).
        double[] distances = {3000, 2600, 1800, 1300, 200, -200};
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
        // 8b follow-up round 4: and its own rain band.
        assertTrue(at.cover(CloudType.NIMBOSTRATUS) > 0.3, "a rain band at the front: " + at.describe());
        assertTrue(CloudRain.precipitation(CloudType.NIMBOSTRATUS, at, false) > 0.3, "that rains");
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

    /** Weather phase 8a (2026-10-14): winter highs' outer sinking thinned most fronts' decks to nothing. */
    @Test
    void aFrontOverridesAHighsFringe() {
        WeatherSystem low = matureLow();
        double[] mid = FrontGeometry.of(low).stream().filter(f -> f.type() == FrontGeometry.Type.WARM).findFirst()
                .orElseThrow().points().get(3);
        // A high whose outer sinking reaches the warm front: about a third of its strength there.
        WeatherSystem high = new WeatherSystem(3, WeatherSystem.Kind.HIGH, mid[0], mid[1] - 7000, 0, 1, 50_000,
                120_000, 14, 7000, false);
        CloudDiagnostics.Need highOnly = CloudDiagnostics.diagnose(mid[0], mid[1] - 200, air(12, 0.8, 0, 0),
                CloudDiagnostics.Systems.of(List.of(high)), NOON, 0);
        CloudDiagnostics.Need both = CloudDiagnostics.diagnose(mid[0], mid[1] - 200, air(12, 0.8, 0, 0),
                CloudDiagnostics.Systems.of(List.of(low, high)), NOON, 0);
        CloudDiagnostics.Need frontOnly = CloudDiagnostics.diagnose(mid[0], mid[1] - 200, air(12, 0.8, 0, 0),
                CloudDiagnostics.Systems.of(List.of(low)), NOON, 0);
        System.out.printf("front under a high's fringe: sinking %.2f (high alone %.2f), ns %.2f (front alone %.2f)%n",
                both.subsidence(), highOnly.subsidence(), both.cover(CloudType.NIMBOSTRATUS),
                frontOnly.cover(CloudType.NIMBOSTRATUS));
        assertTrue(highOnly.subsidence() > 0.2 && highOnly.subsidence() < 0.6, "a fringe: " + highOnly.subsidence());
        assertTrue(both.subsidence() < 0.6 * highOnly.subsidence(), "the front's lift cancels most of it");
        assertTrue(both.cover(CloudType.NIMBOSTRATUS) > 0.6 * frontOnly.cover(CloudType.NIMBOSTRATUS),
                "the deck survives: " + both.describe());
    }

    /** Weather phase 8a (2026-10-14): winter lows passed by with nothing falling on their poleward side. */
    @Test
    void aMatureLowRainsOnItsPolewardSide() {
        WeatherSystem low = matureLow();
        CloudDiagnostics.Systems systems = CloudDiagnostics.Systems.of(List.of(low));
        double r = low.radius();
        CloudDiagnostics.Air moist = air(2, 0.75, 0, 0);
        // Northern-style low: poleward is -Z, west -X.
        double o = 0.55 * r / Math.sqrt(2);
        CloudDiagnostics.Need north = CloudDiagnostics.diagnose(-o, -o, moist, systems, NIGHT, -1);
        System.out.println("comma head:" + north.describe() + " from " + north.sourceOf(CloudType.NIMBOSTRATUS));
        assertTrue(north.cover(CloudType.NIMBOSTRATUS) > 0.4, north.describe());
        assertEquals("comma head", north.front());
        assertTrue(CloudRain.precipitation(CloudType.NIMBOSTRATUS, north, false) > 0.3, "and it rains (or snows)");
        // Mirrored low: its comma head is on the +Z side.
        WeatherSystem mirrored = new WeatherSystem(2, WeatherSystem.Kind.LOW, 0, 0, 1, -1, 35_000, 100_000, 26, 4500,
                false);
        CloudDiagnostics.Systems mirror = CloudDiagnostics.Systems.of(List.of(mirrored));
        assertTrue(CloudDiagnostics.diagnose(-o, o, moist, mirror, NIGHT, -1).cover(CloudType.NIMBOSTRATUS) > 0.4);
        // Dry air: no deck.
        assertTrue(CloudDiagnostics.diagnose(-o, -o, air(2, 0.3, 0, 0), systems, NIGHT, -1)
                .cover(CloudType.NIMBOSTRATUS) < 0.05);
    }

    @Test
    void theCommaHeadBelongsToGrownLowsOnTheirColdSide() {
        CloudDiagnostics.Systems.Centre mature = new CloudDiagnostics.Systems.Centre(true, 0, 0, 4500, 1, 1, 0.5);
        CloudDiagnostics.Systems.Centre wave = new CloudDiagnostics.Systems.Centre(true, 0, 0, 4500, 1, 1, 0.08);
        CloudDiagnostics.Systems.Centre dying = new CloudDiagnostics.Systems.Centre(true, 0, 0, 4500, 1, 1, 0.99);
        double o = 2500 / Math.sqrt(2);
        assertEquals(1, CloudDiagnostics.commaHead(mature, -o, -o), 1e-6, "full toward the poleward west");
        double north = CloudDiagnostics.commaHead(mature, 0, -2500);
        double west = CloudDiagnostics.commaHead(mature, -2500, 0);
        assertTrue(north > 0.6 && north < 0.9 && Math.abs(north - west) < 1e-9, "due poleward or west: " + north);
        assertTrue(CloudDiagnostics.commaHead(mature, o, -o) < 1e-6, "none toward the poleward east (warm front)");
        assertTrue(CloudDiagnostics.commaHead(mature, 0, 2500) < 1e-6, "none on the equatorward side");
        assertTrue(CloudDiagnostics.commaHead(mature, -70, -70) < 0.1, "not at the very centre (the core's own job)");
        assertTrue(CloudDiagnostics.commaHead(mature, -6000, -6000) < 1e-6, "not beyond the low");
        assertTrue(CloudDiagnostics.commaHead(wave, -o, -o) < 0.05, "a young wave has none yet");
        assertTrue(CloudDiagnostics.commaHead(dying, -o, -o) < 0.05, "a dying low has lost it");
    }

    /** Weather phase 8b (2026-10-14): spring sun on still-cold air is unstable; the autumn sun on warm air settles it. */
    @Test
    void theSpringSunUnsettlesTheAirAndTheAutumnSunCalmsIt() {
        CloudDiagnostics.Air mild = air(10, 0.7, 0, 0);
        // Same air, same land season factor; only the sun differs (spring: sun ahead; autumn: behind).
        CloudDiagnostics.Need spring = CloudDiagnostics.diagnose(0, 0, mild, CloudDiagnostics.Systems.NONE, NOON, 0, 0.7);
        CloudDiagnostics.Need autumn = CloudDiagnostics.diagnose(0, 0, mild, CloudDiagnostics.Systems.NONE, NOON, 0, -0.7);
        CloudDiagnostics.Need even = CloudDiagnostics.diagnose(0, 0, mild, CloudDiagnostics.Systems.NONE, NOON, 0);
        System.out.printf("instability: spring %.2f, even %.2f, autumn %.2f%n", spring.instability(),
                even.instability(), autumn.instability());
        assertTrue(spring.instability() > even.instability() + 0.1, "spring is less stable");
        assertTrue(autumn.instability() < even.instability() - 0.1, "autumn is more settled");
        // At night the lead does nothing (it works through the sun's heating).
        CloudDiagnostics.Need springNight = CloudDiagnostics.diagnose(0, 0, mild, CloudDiagnostics.Systems.NONE, NIGHT,
                0, 0.7);
        CloudDiagnostics.Need evenNight = CloudDiagnostics.diagnose(0, 0, mild, CloudDiagnostics.Systems.NONE, NIGHT, 0);
        assertEquals(evenNight.instability(), springNight.instability(), 0.15);
    }

    /** Weather phase 8b: cold air off warmer water brings snow showers onto the downwind shore. */
    @Test
    void coldAirOffWarmWaterSnowsOnTheShore() {
        // -8 C air over land in a cold winter afternoon, aloft at its normal offset, humid; 10 C of upwind warmth.
        CloudDiagnostics.Air inland = new CloudDiagnostics.Air(-8, -8 - 9.75, 0.8 * Moisture.capacity(-8), 0, -8, 0.6,
                0, 10, 0, 0, 8, 0, 12, 0, 0);
        CloudDiagnostics.Air shore = new CloudDiagnostics.Air(-8, -8 - 9.75, 0.8 * Moisture.capacity(-8), 0, -8, 0.6,
                0, 10, 0, 0, 8, 0, 12, 0, 0, 10);
        CloudDiagnostics.Need away = CloudDiagnostics.diagnose(0, 0, inland, CloudDiagnostics.Systems.NONE, NIGHT, -1);
        CloudDiagnostics.Need lee = CloudDiagnostics.diagnose(0, 0, shore, CloudDiagnostics.Systems.NONE, NIGHT, -1);
        System.out.println("shore:" + lee.describe() + " vs inland:" + away.describe());
        assertTrue(lee.instability() > away.instability() + 0.3, "heated from below");
        assertNotNull(lee.heapType(), "showers build on the shore: " + lee.describe());
        assertTrue(lee.heapType().ordinal() >= CloudType.CUMULUS_MEDIOCRIS.ordinal(), lee.describe());
    }

    /** Weather phase 8b (2026-10-14): very moist tropical air builds storms on its own; drier air doesn't. */
    @Test
    void veryMoistAirBuildsStormsOnItsOwn() {
        // 28 C, aloft at its normal offset, humid; tropical (45 mm) against temperate (22 mm) water at the same rh.
        CloudDiagnostics.Air tropical = new CloudDiagnostics.Air(28, 28 - 9.75, 45, 0, 28, 0.9, 0, 10, 0, 0, 2, 0, 4, 0, 0);
        CloudDiagnostics.Air temperate = new CloudDiagnostics.Air(28, 28 - 9.75, 22, 0, 28, 0.9, 0, 10, 0, 0, 2, 0, 4, 0, 0);
        CloudDiagnostics.Need wet = CloudDiagnostics.diagnose(0, 0, tropical, CloudDiagnostics.Systems.NONE, NOON, 0);
        CloudDiagnostics.Need dry = CloudDiagnostics.diagnose(0, 0, temperate, CloudDiagnostics.Systems.NONE, NOON, 0);
        System.out.println("tropical:" + wet.describe() + " | temperate:" + dry.describe());
        assertTrue(wet.instability() > dry.instability() + 0.3, "the moist air's own heat makes it unstable");
        assertNotNull(wet.heapType(), wet.describe());
        assertTrue(wet.heapType().ordinal() >= CloudType.CUMULUS_CONGESTUS.ordinal(), "towering: " + wet.describe());
    }

    /**
     * 8b follow-up (2026-10-14): wet-season tropical air organises its storms (more of the sky, a raining anvil deck);
     * hot dry-season air holding as much water at low humidity doesn't.
     */
    @Test
    void wetSeasonStormsOrganise() {
        assertTrue(CloudDiagnostics.organised(31, 0.78) > 0.9, "wet-season savanna air");
        assertEquals(0, CloudDiagnostics.organised(30, 0.5), 1e-9, "hot dry-season air");
        assertEquals(0, CloudDiagnostics.organised(18, 0.85), 1e-9, "humid temperate air");
        // 23 C at 78% (31 mm) with cold air aloft: storms.
        CloudDiagnostics.Air wetSeason = new CloudDiagnostics.Air(23, 23 - 9.75 - 3, 0.78 * Moisture.capacity(23), 0, 23,
                0.6, 0, 10, 0, 0, 2, 0, 4, 0, 0);
        CloudDiagnostics.Need n = CloudDiagnostics.diagnose(0, 0, wetSeason, CloudDiagnostics.Systems.NONE, AFTERNOON, 0);
        System.out.println("wet season:" + n.describe());
        assertNotNull(n.heapType(), n.describe());
        assertTrue(n.heapType().ordinal() >= CloudType.CUMULONIMBUS_CALVUS.ordinal(), n.describe());
        assertTrue(n.heapCover() > 0.47, "organised storms fill more of the sky: " + n.heapCover());
        assertTrue(n.cover(CloudType.NIMBOSTRATUS) > 0.05, "and leave a raining anvil: " + n.describe());
        assertEquals("storm anvil", n.sourceOf(CloudType.NIMBOSTRATUS));
    }

    /**
     * 8b follow-up round 4: upslope cloud forms in the air the wind lifts (moist air from upwind, cooled as it
     * climbs), not the peak's own dry air.
     */
    @Test
    void upslopeCloudFormsInTheLiftedAir() {
        double hc = -2.7;
        double peakQ = 0.57 * Moisture.capacity(10 + hc);
        double upwindQ = 0.8 * Moisture.capacity(13);
        // Wind 5 m/s up a slope of 0.16: orographic lift 0.4.
        CloudDiagnostics.Air lifted = new CloudDiagnostics.Air(10, 0.25, peakQ, 0, 10, 0.3, 0, 120, 0.16, 0, 5, 0, 10,
                0, hc, 0, upwindQ);
        CloudDiagnostics.Air ownOnly = new CloudDiagnostics.Air(10, 0.25, peakQ, 0, 10, 0.3, 0, 120, 0.16, 0, 5, 0, 10,
                0, hc, 0, Double.NaN);
        CloudDiagnostics.Need wet = CloudDiagnostics.diagnose(0, 0, lifted, CloudDiagnostics.Systems.NONE, NIGHT, 0);
        CloudDiagnostics.Need dry = CloudDiagnostics.diagnose(0, 0, ownOnly, CloudDiagnostics.Systems.NONE, NIGHT, 0);
        System.out.println("upslope, lifted:" + wet.describe() + " | own air:" + dry.describe());
        assertTrue(wet.cover(CloudType.NIMBOSTRATUS) > 0.3, "moist upwind air lifted rains on the slope: " + wet.describe());
        assertTrue(CloudRain.precipitation(CloudType.NIMBOSTRATUS, wet, false) > 0.5, "and it rains");
        assertTrue(dry.cover(CloudType.NIMBOSTRATUS) < 0.05, "the peak's own dry air alone doesn't: " + dry.describe());
    }

    /**
     * Round 5: the lifted air cools by the height it climbs, stretched by the world's mountain height (Tectonic's 320:
     * ~16 m a block). 60 blocks of climb in a Tectonic world is ~930 m, ~5.6 C: 72% air saturates.
     */
    @Test
    void liftedAirCoolsByTheHeightItClimbs() {
        assertEquals(4000.0 / 257, CloudDiagnostics.metresPerBlock(320, 63), 1e-9);
        assertTrue(CloudDiagnostics.metresPerBlock(256, 63) > CloudDiagnostics.metresPerBlock(320, 63),
                "lower worlds stretch more");
        double hc = -2.7;
        double q = 0.72 * Moisture.capacity(10 + hc);
        double climb = 60 * CloudDiagnostics.metresPerBlock(320, 63);
        CloudDiagnostics.Air climbing = new CloudDiagnostics.Air(10, 0.25, q, 0, 10, 0.3, 0, 120, 0.16, 0, 5, 0, 10,
                0, hc, 0, Double.NaN, climb);
        CloudDiagnostics.Air level = new CloudDiagnostics.Air(10, 0.25, q, 0, 10, 0.3, 0, 120, 0.16, 0, 5, 0, 10,
                0, hc, 0, Double.NaN, 0);
        CloudDiagnostics.Need up = CloudDiagnostics.diagnose(0, 0, climbing, CloudDiagnostics.Systems.NONE, NIGHT, 0);
        CloudDiagnostics.Need flat = CloudDiagnostics.diagnose(0, 0, level, CloudDiagnostics.Systems.NONE, NIGHT, 0);
        System.out.println("climbing:" + up.describe() + " | no climb:" + flat.describe());
        assertTrue(up.cover(CloudType.NIMBOSTRATUS) > 0.2, "rain on the climb: " + up.describe());
        assertTrue(flat.cover(CloudType.NIMBOSTRATUS) < 0.05, "none without it: " + flat.describe());
    }
}
