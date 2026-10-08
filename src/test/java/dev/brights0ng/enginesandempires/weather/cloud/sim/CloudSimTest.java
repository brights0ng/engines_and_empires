package dev.brights0ng.enginesandempires.weather.cloud.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;
import dev.brights0ng.enginesandempires.weather.field.AtmosphereField;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/** The spawner on a made-up world: warm starts, drift, dissolving, heap budgets, and the sync's wire format. */
public class CloudSimTest {

    /** Uniform air everywhere; {@code air} can be swapped between passes. */
    public static class World implements CloudSim.Env {
        CloudDiagnostics.Air air;
        double[] wind = {2, 0, 10, 0};
        long dayTime;
        double season;

        public World(CloudDiagnostics.Air air, long dayTime, double season) {
            this.air = air;
            this.dayTime = dayTime;
            this.season = season;
        }

        @Override
        public CloudDiagnostics.Air air(double x, double z) {
            return air;
        }

        @Override
        public double[] wind(double x, double z) {
            return wind;
        }

        @Override
        public CloudDiagnostics.Systems systems() {
            return CloudDiagnostics.Systems.NONE;
        }

        @Override
        public long dayTime() {
            return dayTime;
        }

        @Override
        public double season() {
            return season;
        }
    }

    /** Saturated, stable night air: a stratus deck everywhere. */
    static World foggyNight() {
        return new World(CloudDiagnosticsTest.air(10, 1.0, -2, 0), CloudDiagnosticsTest.NIGHT, 0);
    }

    static final List<CloudSim.Anchor> PLAYER = List.of(new CloudSim.Anchor(0, 0));

    @Test
    void aNewAreaGetsItsSkyAtOnce() {
        CloudSim sim = new CloudSim();
        World world = foggyNight();
        CloudSim.Report r = sim.pass(1000, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(1));
        System.out.println("warm start: " + r);
        assertTrue(r.spawnedLayer() > 20, "a deck across the player's range: " + r);
        long stratus = sim.clouds().stream().filter(c -> c.type == CloudType.STRATUS).count();
        assertTrue(stratus > 20);
        // Already formed, not forming in front of the player.
        for (SimCloud c : sim.clouds()) {
            assertEquals(1, c.phase(1000).growth(), 1e-9, c.type.id);
        }
        // A second pass adds little: the slots are full.
        CloudSim.Report again = sim.pass(1040, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(2));
        assertTrue(again.spawnedLayer() < r.spawnedLayer() / 4, "slots stay filled: " + again);
    }

    @Test
    void cloudsDriftWithTheAir() {
        CloudSim sim = new CloudSim();
        World world = foggyNight();
        sim.pass(0, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(3));
        SimCloud c = sim.clouds().getFirst();
        // Low cloud: 35% surface wind + 65% aloft = 7.2 m/s east, carried at 0.4 blocks a second per m/s.
        double expected = (0.35 * 2 + 0.65 * 10) * AtmosphereField.ADVECTION / 20;
        assertEquals(expected, c.vx, 1e-9);
        assertEquals(0, c.vz, 1e-9);
        double x0 = c.xAt(0);
        assertEquals(x0 + expected * 1200, c.xAt(1200), 1e-6, "about 170 blocks a minute");
    }

    /** Debug: natural clouds can be paused, leaving debug clouds alone, which last for studying (Bright, 2026-10-06). */
    @Test
    void naturalCloudsCanBePaused() {
        CloudSim sim = new CloudSim();
        World world = foggyNight();
        sim.pass(0, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(1));
        SimCloud sample = sim.spawnAt(CloudType.CUMULUS_HUMILIS, 0, 0, 0, world, new SplittableRandom(2), 1.0);
        int before = sim.clouds().size();
        assertEquals(before - 1, sim.setNatural(false), "every natural cloud removed");
        assertEquals(List.of(sample), sim.clouds());
        for (long t = 40; t <= 4000; t += 40) {
            CloudSim.Report r = sim.pass(t, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(t));
            assertEquals(0, r.spawnedLayer() + r.spawnedHeap(), "nothing new while paused");
        }
        assertEquals(1, sim.clouds().size(), "the sample is still there");
        assertEquals(0, sample.phase(4000).decay(), 1e-9, "and not fading");
        sim.setNatural(true);
        CloudSim.Report r = sim.pass(4040, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(9));
        assertTrue(r.spawnedLayer() > 20, "the sky fills again at once: " + r);
    }

    /** Bright, 2026-10-07: no fair-weather congestus (or storms); common where a weather system drives them. */
    @Test
    void fairWeatherCumulusTopOutAtMediocris() {
        SplittableRandom rng = new SplittableRandom(4);
        int n = 100_000, sun = 0, front = 0, humilis = 0;
        for (int i = 0; i < n; i++) {
            CloudType a = CloudSim.pickHeap(CloudType.CUMULUS_CONGESTUS, CloudDiagnostics.SUN, rng);
            CloudType b = CloudSim.pickHeap(CloudType.CUMULONIMBUS_CAPILLATUS, CloudDiagnostics.SUN, rng);
            if (a.ordinal() >= CloudType.CUMULUS_CONGESTUS.ordinal() || b.ordinal() >= CloudType.CUMULUS_CONGESTUS.ordinal()) {
                sun++;
            }
            if (a == CloudType.CUMULUS_HUMILIS) {
                humilis++;
            }
            if (CloudSim.pickHeap(CloudType.CUMULUS_CONGESTUS, "cold front line", rng) == CloudType.CUMULUS_CONGESTUS) {
                front++;
            }
        }
        System.out.printf("congestus where the air could build them: %.1f%% on a sunny afternoon (humilis %.0f%%), "
                + "%.0f%% on a cold front%n", 100.0 * sun / n, 100.0 * humilis / n, 100.0 * front / n);
        assertEquals(0, sun);
        assertTrue(humilis > 0.6 * n, "mostly small ones");
        assertEquals(0.6, (double) front / n, 0.01);
    }

    @Test
    void layerCloudsDissolveWhenTheirConditionsGo() {
        CloudSim sim = new CloudSim();
        World world = foggyNight();
        sim.pass(0, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(4));
        int before = sim.clouds().size();
        world.air = CloudDiagnosticsTest.air(10, 0.3, 0, 0);
        CloudSim.Report r = sim.pass(40, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(5));
        assertEquals(before, r.dissolved(), "every cloud starts dissolving");
        long fade = sim.clouds().getFirst().span().death();
        SimCloud c = sim.clouds().getFirst();
        assertTrue(c.phase(40 + fade / 2).decay() > 0.3, "half way through fading");
        sim.pass(40 + fade + 40, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(6));
        assertTrue(sim.clouds().isEmpty(), "and gone after their fade");
    }

    @Test
    void layerCloudsLastWhileTheirConditionsHold() {
        CloudSim sim = new CloudSim();
        World world = foggyNight();
        sim.pass(0, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(7));
        UUID id = sim.clouds().getFirst().id;
        // Half an hour later the same cloud is still there (if it hasn't drifted out of range).
        for (long t = 40; t <= 6000; t += 40) {
            sim.pass(t, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(t));
        }
        SimCloud c = sim.clouds().stream().filter(s -> s.id.equals(id)).findFirst().orElse(null);
        if (c != null) {
            assertFalse(c.dissolving);
            assertTrue(c.end > 6000);
        }
        assertTrue(sim.clouds().size() > 20, "the deck keeps itself filled as it drifts");
    }

    @Test
    void sunnyAfternoonsFillWithCumulusWithinBudget() {
        CloudSim sim = new CloudSim();
        World world = new World(CloudDiagnosticsTest.air(18, 0.55, 0, 0), CloudDiagnosticsTest.AFTERNOON, 1);
        CloudSim.Report r = sim.pass(0, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(8));
        System.out.println("sunny afternoon: " + r);
        assertTrue(r.spawnedHeap() > 30, "a field of cumulus: " + r);
        assertTrue(sim.clouds().stream().allMatch(c -> c.type.heap()), "and no layer cloud");
        assertTrue(sim.clouds().size() <= CloudSim.Settings.DEFAULT.maxPerPlayer());
        for (int i = 1; i < 20; i++) {
            sim.pass(i * 40L, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(100 + i));
        }
        assertTrue(sim.clouds().size() <= CloudSim.Settings.DEFAULT.maxPerPlayer());
        // Evening: no new ones; the old ones live out their lifespans.
        world.dayTime = CloudDiagnosticsTest.NIGHT;
        CloudSim.Report evening = sim.pass(840, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(9));
        assertEquals(0, evening.spawnedHeap());
    }

    @Test
    void shapesFollowTheCloudsLife() {
        CloudSim sim = new CloudSim();
        World world = new World(CloudDiagnosticsTest.air(18, 0.55, 0, 0), CloudDiagnosticsTest.AFTERNOON, 1);
        SimCloud c = sim.spawnAt(CloudType.CUMULUS_CONGESTUS, 100, 200, 5000, world, new SplittableRandom(10));
        List<CloudShape> shapes = c.shapes("minecraft:overworld", 5000);
        assertFalse(shapes.isEmpty());
        CloudShape s = shapes.getFirst();
        assertEquals(c.id, s.regionId());
        assertEquals("cumulus_congestus", s.typeId());
        assertEquals(1, s.growth(), 1e-6, "spawned mature");
        assertTrue(s.topY() > s.baseY() + 100, "a towering cumulus is hundreds of blocks tall");
        assertTrue(c.shapes("minecraft:overworld", c.end + 1).isEmpty(), "nothing once over");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSyncRoundTrips() throws java.io.IOException {
        SimCloud c = new SimCloud(UUID.randomUUID(), CloudType.ALTOSTRATUS, 12.5, -40, 0.125, -0.0625, 100, 50, 9000, 700,
                180, 0.8f, 0, 0, List.of(new SimCloud.Dome(1, 2, 300, 77, 1), new SimCloud.Dome(-50, 20, 250, 78, 1)),
                3, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CloudWire.write(new DataOutputStream(bytes), true, List.of(c), List.of(new UUID(1, 2)));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        Object[] back = CloudWire.read(in);
        assertTrue((Boolean) back[0]);
        assertEquals(List.of(new UUID(1, 2)), back[2]);
        List<SimCloud> upserts = (List<SimCloud>) back[1];
        SimCloud r = upserts.getFirst();
        assertEquals(c.id, r.id);
        assertEquals(CloudType.ALTOSTRATUS, r.type);
        assertEquals(c.end, r.end);
        assertEquals(c.domes, r.domes);
        assertEquals(c.version, r.version);
        assertTrue(r.dissolving);
        assertEquals(0, in.available());
        // The client builds the same shapes as the server.
        assertEquals(c.shapes("minecraft:overworld", 200), r.shapes("minecraft:overworld", 200));
        CloudSyncClient.accept(true, upserts, List.of());
        try {
            assertEquals(1, CloudSyncClient.count());
            assertNotNull(CloudSyncClient.shapes(200));
        } finally {
            CloudSyncClient.clear();
        }
    }

    /**
     * 2026-10-06 flight test: at a deepening low's core, stratocumulus outranked nimbostratus in the low deck, the mid
     * deck was skipped for a nimbostratus that never came, and a cumulus field filled the sky.
     */
    @Test
    void aLowsCoreGetsItsRainDeck() {
        dev.brights0ng.enginesandempires.weather.sim.WeatherSystem low = CloudDiagnosticsTest.matureLow();
        dev.brights0ng.enginesandempires.weather.sim.WeatherSystem high =
                new dev.brights0ng.enginesandempires.weather.sim.WeatherSystem(2,
                        dev.brights0ng.enginesandempires.weather.sim.WeatherSystem.Kind.HIGH, 5000, 0, 0, 1, 20_000,
                        100_000, 8, 5200, false);
        CloudDiagnostics.Systems systems = CloudDiagnostics.Systems.of(List.of(low, high));
        World world = new World(CloudDiagnosticsTest.air(4, 0.65, -8, 0), CloudDiagnosticsTest.NOON, 0) {
            @Override
            public CloudDiagnostics.Systems systems() {
                return systems;
            }
        };
        CloudSim sim = new CloudSim();
        for (long t = 0; t <= 400; t += 40) {
            sim.pass(t, PLAYER, world, CloudSim.Settings.DEFAULT, new SplittableRandom(t + 11));
        }
        long ns = 0, sc = 0, heap = 0;
        for (SimCloud c : sim.clouds()) {
            if (c.dissolving || Math.hypot(c.xAt(400), c.zAt(400)) > 1500) {
                continue;
            }
            if (c.type == CloudType.NIMBOSTRATUS) {
                ns++;
            } else if (c.type == CloudType.STRATOCUMULUS) {
                sc++;
            } else if (c.type.heap()) {
                heap++;
            }
        }
        System.out.printf("low core: %d nimbostratus, %d stratocumulus, %d heap within 1.5 km (%s)%n", ns, sc, heap,
                sim.need(0, 0, world).describe());
        assertTrue(ns >= 1, "a rain deck over the core");
        assertEquals(0, sc, "the rain deck owns the low deck");
    }
}
