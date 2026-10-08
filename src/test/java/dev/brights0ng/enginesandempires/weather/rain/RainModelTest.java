package dev.brights0ng.enginesandempires.weather.rain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.client.CloudShape;

/** The shared rain model: which clouds rain, where, how hard, the wind drift and lifecycles. */
class RainModelTest {

    private static final double GROUND = CloudScale.GROUND_Y + 1;

    /**
     * One dome of {@code type} at (x, z), radius {@code r} (blocks), moving at (vx, vz) blocks per tick, raining as
     * hard as its type can ({@code precipitation} 1).
     */
    private static CloudShape cloud(String type, UUID region, int id, double x, double z, float r, double vx, double vz,
                                    float growth, float decay, float anvilDecay, float precipitation) {
        CloudScale.Heights h = CloudScale.heights(type, region, growth);
        return new CloudShape(new UUID(region.getLeastSignificantBits(), id), region, "minecraft:overworld", x, z,
                vx, vz, 0, r, (float) h.base(), (float) h.top(), 0.8f, 0.9f, 0.5f, growth, decay, anvilDecay, type,
                0.6f, 0.5f, 0.4f, 0.38f, precipitation, 0.3f, 1000 + id);
    }

    private static CloudShape cloud(String type, UUID region, int id, double x, double z, float r, double vx, double vz,
                                    float growth, float decay, float anvilDecay) {
        return cloud(type, region, id, x, z, r, vx, vz, growth, decay, anvilDecay, 1);
    }

    private static CloudShape mature(String type, double x, double z, float r) {
        return cloud(type, UUID.nameUUIDFromBytes(type.getBytes()), 1, x, z, r, 0, 0, 1, 0, 0);
    }

    private static RainModel.Sample at(List<CloudShape> shapes, double x, double z, double windX, boolean snow) {
        return RainModel.sample(RainModel.build(shapes, 0, RainModel.RAW), x, GROUND, z, windX, 0, snow);
    }

    private static RainModel.Sample calm(List<CloudShape> shapes, double x, double z) {
        return at(shapes, x, z, 0, false);
    }

    @Test
    void fairWeatherCloudsDontRain() {
        for (String type : new String[]{"cumulus_humilis", "cumulus_mediocris", "cirrus", "cirrostratus"}) {
            RainModel.Sample s = calm(List.of(mature(type, 0, 0, 150)), 0, 0);
            assertFalse(s.raining(), type);
            if (type.startsWith("cumulus")) {
                assertTrue(s.cover() > 0.3, type + " still covers the sky: " + s.cover());
            }
        }
    }

    @Test
    void cloudsTheSimulationKeepsDryDontRain() {
        UUID region = UUID.nameUUIDFromBytes("dry".getBytes());
        RainModel.Sample s = calm(List.of(cloud("nimbostratus", region, 1, 0, 0, 3000, 0, 0, 1, 0, 0, 0)), 0, 0);
        assertFalse(s.raining(), "a nimbostratus the simulation has dry (phase 4a) gives no rain");
        assertTrue(s.cover() > 0.5, "but covers the sky");
    }

    @Test
    void strengthsGoFromDrizzleToHeavy() {
        double drizzle = calm(List.of(mature("stratus", 0, 0, 600)), 0, 0).strength();
        double shower = calm(List.of(mature("cumulus_congestus", 0, 0, 300)), 0, 0).strength();
        double steady = calm(List.of(mature("nimbostratus", 0, 0, 3000)), 0, 0).strength();
        RainModel.Sample storm = calm(List.of(mature("cumulonimbus_capillatus", 0, 0, 1200)), 0, 0);
        System.out.printf("strengths: stratus %.2f, congestus %.2f, nimbostratus %.2f, capillatus %.2f%n", drizzle,
                shower, steady, storm.strength());
        assertTrue(drizzle > RainModel.MIN && drizzle < 0.25, "stratus drizzles");
        assertTrue(shower > drizzle && shower < steady, "congestus showers are light");
        assertTrue(storm.strength() > 0.8, "a cumulonimbus pours");
        assertTrue(storm.thunder(), "with thunder");
        assertFalse(calm(List.of(mature("nimbostratus", 0, 0, 3000)), 0, 0).thunder());
    }

    @Test
    void convectiveRainFallsUnderTheCoreOnly() {
        double r = 800;
        List<CloudShape> cb = List.of(mature("cumulonimbus_calvus", 0, 0, (float) r));
        assertTrue(calm(cb, 0, 0).strength() > 0.6, "heavy in the middle");
        assertTrue(calm(cb, 0.35 * r, 0).strength() < calm(cb, 0, 0).strength(), "lighter off-centre");
        assertFalse(calm(cb, 0.8 * r, 0).raining(), "dry under the cloud's outer part");
        assertTrue(calm(cb, 0.8 * r, 0).cover() > 0.5, "though still under the cloud");
        // A nimbostratus rains under nearly all of itself.
        double rn = 3000;
        List<CloudShape> ns = List.of(mature("nimbostratus", 0, 0, (float) rn));
        assertTrue(calm(ns, 0.8 * rn, 0).raining(), "sheet rain reaches its edges");
    }

    @Test
    void rainDriftsDownwindOfTheCloud() {
        // A shower carried east by a 7 m/s wind (0.14 blocks per tick) with 10 m/s below it: the drops are blown east.
        UUID region = UUID.nameUUIDFromBytes("drift".getBytes());
        double vx = 7 * RainModel.ADVECTION / 20;
        List<CloudShape> cb = List.of(cloud("cumulonimbus_capillatus", region, 1, 0, 0, 1200, vx, 0, 1, 0, 0));
        double core = 1200 * 0.6;
        double base = cb.get(0).baseY();
        double drift = 3.0 / (RainModel.DRIZZLE_FALL + (RainModel.HEAVY_FALL - RainModel.DRIZZLE_FALL) * 0.95)
                * (base - GROUND);
        System.out.printf("drift: base %.0f, core radius %.0f, offset %.0f blocks%n", base, core, drift);
        assertTrue(at(cb, core + 0.5 * drift, 0, 10, false).raining(), "wet past the core's downwind edge");
        assertFalse(calm(cb, core + 0.5 * drift, 0).raining(), "which is dry without the shear");
        assertTrue(at(cb, -core + 0.5 * drift, 0, 10, false).strength() < calm(cb, -core + 0.5 * drift, 0).strength(),
                "and drier under the upwind side");
        // Without shear (wind = the cloud's own carrying wind) it falls straight down.
        assertEquals(calm(cb, 0, 0).strength(), at(cb, 0, 0, 7, false).strength(), 1e-6);
    }

    @Test
    void snowDriftsFurtherThanRain() {
        UUID region = UUID.nameUUIDFromBytes("snow".getBytes());
        List<CloudShape> ns = List.of(cloud("nimbostratus", region, 1, 0, 0, 3000, 0, 0, 1, 0, 0));
        // How far downwind the wet area's edge moves in a 3 m/s wind, for rain and for snow.
        double calm = 0, rain = 0, snow = 0;
        for (double x = 0; x < 8000; x += 5) {
            if (calm(ns, x, 0).raining()) {
                calm = x;
            }
            if (at(ns, x, 0, 3, false).raining()) {
                rain = x;
            }
            if (at(ns, x, 0, 3, true).raining()) {
                snow = x;
            }
        }
        System.out.printf("wet edge moved downwind: rain %.0f blocks, snow %.0f%n", rain - calm, snow - calm);
        assertTrue(rain - calm > 0, "rain is blown downwind");
        assertTrue(snow - calm > 4 * (rain - calm), "snow drifts several times further");
    }

    @Test
    void rainFollowsTheLifecycle() {
        UUID region = UUID.nameUUIDFromBytes("life".getBytes());
        double mature = calm(List.of(cloud("cumulonimbus_capillatus", region, 1, 0, 0, 1200, 0, 0, 1, 0, 0)), 0, 0)
                .strength();
        double young = calm(List.of(cloud("cumulonimbus_capillatus", region, 1, 0, 0, 1200, 0, 0, 0.5f, 0, 0)), 0, 0)
                .strength();
        double dying = calm(List.of(cloud("cumulonimbus_capillatus", region, 1, 0, 0, 1200, 0, 0, 1, 0.6f, 0)), 0, 0)
                .strength();
        RainModel.Sample orphan = calm(List.of(cloud("cumulonimbus_capillatus", region, 1, 0, 0, 1200, 0, 0, 1, 1, 0.3f)),
                0, 0);
        assertEquals(0, young, 1e-9, "no rain until well formed");
        assertTrue(dying < 0.5 * mature, "tapering as it dies: " + dying + " vs " + mature);
        assertFalse(orphan.raining(), "a lingering anvil is dry");
    }

    @Test
    void nothingFallsAboveTheCloud() {
        List<CloudShape> cb = List.of(mature("cumulonimbus_capillatus", 0, 0, 1200));
        double top = cb.get(0).topY();
        assertFalse(RainModel.sample(RainModel.build(cb, 0, RainModel.RAW), 0, top + 10, 0, 0, 0, false).raining());
        double base = cb.get(0).baseY();
        assertTrue(RainModel.sample(RainModel.build(cb, 0, RainModel.RAW), 0, base + 50, 0, 0, 0, false).raining(),
                "inside the cloud it is raining");
    }

    @Test
    void heightCoolingIsAtTheX02Scale() {
        assertEquals(-0.65 * 5, Temperature.heightCorrection(63, 163, 5, 30), 1e-9, "100 blocks up");
        assertEquals(-0.65, Temperature.heightCorrection(63, 163, 1, 30), 1e-9, "a block as a metre");
        assertEquals(-30, Temperature.heightCorrection(63, 2000, 5, 30), 1e-9, "capped");
        assertEquals(Temperature.MAX_WARMING, Temperature.heightCorrection(63, -2000, 5, 30), 1e-9);
        // A 250-block peak (Tectonic's mountains reach further) is about 8 C colder than the coast.
        assertTrue(Temperature.heightCorrection(63, 313, 5, 30) < -8);
    }

    @Test
    void clientTemperatureInterpolatesTheSyncedGrid() {
        float[] t = new float[25];
        for (int k = 0; k < 5; k++) {
            for (int i = 0; i < 5; i++) {
                t[k * 5 + i] = i * 2; // 2 C warmer every 48 blocks east
            }
        }
        t[12] = Float.NaN; // a missing value in the middle
        ClientWeather.State p = new ClientWeather.State(true, 0, 0, 0, 0, 0, 0, 63, 5, 30, 0, 0, 48, 5, t, null, 0, 0,
                -1);
        assertEquals(1, ClientWeather.grid(p, 24, 0), 1e-9, "half way between two points");
        assertEquals(8, ClientWeather.grid(p, 5000, 0), 1e-9, "clamped to the grid's edge");
        assertEquals(6, ClientWeather.grid(p, 120, 96), 1e-9, "a missing point is left out");
        ClientWeather.accept(p);
        try {
            assertEquals(1 - 3.25, ClientWeather.temperature(24, 163, 0), 1e-6, "with the height cooling");
            assertEquals(0, ClientWeather.windX(), 1e-9);
        } finally {
            ClientWeather.clear();
        }
    }
}
