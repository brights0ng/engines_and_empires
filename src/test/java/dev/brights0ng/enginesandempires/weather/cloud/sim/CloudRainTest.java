package dev.brights0ng.enginesandempires.weather.cloud.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import dev.brights0ng.enginesandempires.weather.field.FieldTile;
import dev.brights0ng.enginesandempires.weather.rain.RainModel;

/** Phase 4b: which clouds rain and how hard, virga, the moisture drain, and storm outflow. */
class CloudRainTest {

    static SimCloud cloud(CloudType t, long id) {
        SimCloud c = new SimCloud(new UUID(id, id * 31 + 7), t, 0, 0, 0.1, 0, 0, -100_000, 100_000, 300, 600,
                t.look.coverage(), 0, 0, SimCloud.domesFor(t, 900, new SplittableRandom(id)), 0, false);
        return c;
    }

    static CloudDiagnostics.Need need(double rh, double lclMetres, CloudType t, double cover) {
        double[] layer = new double[CloudType.values().length];
        layer[t.ordinal()] = cover;
        double[] hint = new double[CloudType.values().length];
        return new CloudDiagnostics.Need(layer, hint, null, 0, lclMetres, rh, 0, 0, Double.NaN, 0, "none", 0, 0, 0);
    }

    @Test
    void stormsAlwaysRainAndDrizzleNeedsSaturatedAir() {
        assertTrue(CloudRain.precipitation(cloud(CloudType.CUMULONIMBUS_CAPILLATUS, 1),
                need(0.4, 1500, CloudType.STRATUS, 0)) >= 0.6, "a storm rains even in drier air");
        assertEquals(0, CloudRain.precipitation(cloud(CloudType.STRATUS, 2), need(0.8, 500, CloudType.STRATUS, 1)),
                1e-9, "stratus doesn't drizzle at 80%");
        assertTrue(CloudRain.precipitation(cloud(CloudType.STRATUS, 2), need(0.97, 50, CloudType.STRATUS, 1)) > 0.5,
                "but does in saturated air");
        assertEquals(0, CloudRain.precipitation(cloud(CloudType.CUMULUS_MEDIOCRIS, 3),
                need(0.9, 300, CloudType.STRATUS, 0)), 1e-9, "fair-weather cumulus never rain");
        assertEquals(0, CloudRain.precipitation(cloud(CloudType.ALTOSTRATUS, 4),
                need(0.9, 300, CloudType.ALTOSTRATUS, 0.3)), 1e-9, "a thin altostratus deck is dry");
        assertTrue(CloudRain.precipitation(cloud(CloudType.NIMBOSTRATUS, 5),
                need(0.8, 500, CloudType.NIMBOSTRATUS, 0.9)) > 0.8, "a thick nimbostratus pours steadily");
    }

    @Test
    void aboutSixInTenCongestusShower() {
        int showers = 0;
        for (int i = 0; i < 1000; i++) {
            if (CloudRain.showers(cloud(CloudType.CUMULUS_CONGESTUS, i))) {
                showers++;
            }
        }
        assertTrue(showers > 520 && showers < 680, "showers " + showers);
    }

    @Test
    void highBasesInDryAirMakeVirga() {
        double base = CloudScale.baseY(2000);
        float dry = CloudRain.rainBottom(base, 0.5, 0.3);
        assertFalse(Float.isInfinite(dry), "in dry air the rain stops above the ground");
        assertTrue(dry > CloudScale.GROUND_Y + 100, "well above it: " + dry);
        assertTrue(Float.isInfinite(CloudRain.rainBottom(base, 0.5, 0.85)), "moist air: it lands");
        assertTrue(Float.isInfinite(CloudRain.rainBottom(CloudScale.baseY(600), 0.9, 0.6)),
                "a heavy shower from a low base lands");
        assertTrue(CloudRain.rainBottom(base, 0.2, 0.4) > CloudRain.rainBottom(base, 0.9, 0.4),
                "heavier rain falls further before it is gone");
    }

    @Test
    void virgaKeepsTheGroundDryButNotAShipUnderTheCloud() {
        UUID region = new UUID(9, 9);
        float base = 500;
        CloudShape s = new CloudShape(new UUID(1, 1), region, "minecraft:overworld", 0, 0, 0, 0, 0, 1200, base,
                base + 400, 0.9f, 0.95f, 0.3f, 1, 0, 0, "cumulonimbus_calvus", 0.8f, 0.3f, 0.6f, 0.5f, 1, 0.5f, 7,
                300f);
        List<RainModel.Cloud> clouds = RainModel.build(List.of(s), 0, RainModel.RAW);
        assertFalse(RainModel.sample(clouds, 0, 70, 0, 0, 0, false).raining(), "dry on the ground");
        assertTrue(RainModel.sample(clouds, 0, 400, 0, 0, 0, false).raining(), "wet at 400, above the rain's bottom");
        assertTrue(RainModel.sample(clouds, 0, 70, 0, 0, 0, false).cover() > 0.5, "under the cloud all the same");
    }

    @Test
    void rainRatesAreRealOnes() {
        assertTrue(CloudRain.rateMmPerHour(0.15) < 0.5, "drizzle");
        double steady = CloudRain.rateMmPerHour(0.55);
        assertTrue(steady > 2 && steady < 6, "steady rain " + steady);
        assertTrue(CloudRain.rateMmPerHour(0.95) > 15, "a storm's core");
    }

    @Test
    void rainDrainsTheAirUnderIt() {
        AtmosphereField field = new AtmosphereField();
        FieldTile tile = new FieldTile(0, 0);
        java.util.Arrays.fill(tile.q, 25);
        field.put(tile);
        CloudSimTest.World world = new CloudSimTest.World(CloudDiagnosticsTest.air(15, 0.9, 0, 0), 9000, 0) {
            @Override
            public double drain(double x, double z, double mm) {
                return field.drain(x, z, mm);
            }
        };
        CloudSim sim = new CloudSim();
        SimCloud c = sim.spawnAt(CloudType.NIMBOSTRATUS, 4096, 4096, 0, world, new SplittableRandom(3));
        List<CloudSim.Anchor> player = List.of(new CloudSim.Anchor(4096, 4096));
        CloudSim.Report first = sim.pass(0, player, world, CloudSim.Settings.DEFAULT, new SplittableRandom(4));
        CloudSim.Report r = null;
        for (long t = 40; t <= 1000; t += 40) {
            r = sim.pass(t, player, world, CloudSim.Settings.DEFAULT, new SplittableRandom(t));
        }
        double before = 25 * 256;
        double after = 0;
        double fallen = 0;
        for (int i = 0; i < 256; i++) {
            after += tile.q[i];
            fallen += tile.r[i];
        }
        System.out.printf("an hour of nimbostratus: %.1f mm taken (summed over cells), last pass %s%n", before - after, r);
        assertTrue(c.precipitation > 0.5, "it rains: " + c.precipitation);
        assertTrue(before - after > 1, "the air under it is drier");
        assertEquals(before - after, fallen, 1e-3, "and what left the air is recorded as fallen rain");
        assertEquals(0, first.drainedMm(), 1e-9, "the first pass has no time to rain over");
    }

    @Test
    void stormsBlowAGustFrontOutward() {
        SimCloud storm = cloud(CloudType.CUMULONIMBUS_CAPILLATUS, 11);
        storm.precipitation = 1;
        storm.birth = -1_000_000;
        storm.end = 1_000_000;
        // Make it mature now.
        storm.birth = -storm.span().birth() - 100;
        storm.end = storm.birth + storm.span().total();
        GustFronts g = GustFronts.of(List.of(storm), 0);
        assertEquals(1, g.size());
        SimCloud.Dome main = storm.domes.getFirst();
        double core = main.radius() * CloudType.CUMULONIMBUS_CAPILLATUS.rain.core();
        double front = core + GustFronts.FRONT_PAST;
        double[] ahead = g.at(main.dx() + front, main.dz(), 0);
        double[] behind = g.at(main.dx() - front, main.dz(), 0);
        double[] far = g.at(main.dx() + front + 1500, main.dz(), 0);
        System.out.printf("gust front: ahead %.1f m/s, behind %.1f, 1500 blocks past %.2f%n", ahead[0], -behind[0], far[0]);
        assertTrue(ahead[0] > 15 && ahead[0] < 35, "a realistic gust front ahead: " + ahead[0]);
        assertTrue(behind[0] < 0 && -behind[0] < ahead[0], "outward behind too, but weaker");
        assertTrue(Math.abs(far[0]) < 0.5, "gone a few hundred blocks out");
        storm.precipitation = 0;
        assertEquals(0, GustFronts.of(List.of(storm), 0).size(), "a dry cloud has no outflow");
    }
}
