package dev.brights0ng.meteodyne.sky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.meteodyne.cloud.client.CloudShape;

/** The sun's ray through the clouds (weather phase 8e-2): {@code StormShade.opticalDepthAlong}. */
class StormShadeRayTest {

    private static CloudShape cloud(double x, double z, float radius, float base, float top, float density,
                                    float coverage, float anvil, float storm) {
        UUID id = UUID.randomUUID();
        return new CloudShape(id, id, "minecraft:overworld", x, z, 0, 0, 0, radius, base, top, density, coverage, 0.3f,
                1f, 0f, 0f, "test", 0.5f, anvil, 0.5f, storm, 0f, 0f, 1);
    }

    private static StormShade.Index index(CloudShape... clouds) {
        return new StormShade.Index(List.of(clouds), 0);
    }

    @Test
    void aVerticalRayGivesTheColumnsDepth() {
        StormShade.Index storm = index(cloud(0, 0, 800, 200, 2600, 0.95f, 0.95f, 0.8f, 0.6f));
        double cover = StormShade.sample(storm, 0, 0).cover();
        double ray = StormShade.opticalDepthAlong(storm, 0, 100, 0, 0, 1, 0);
        assertEquals(cover, 1 - Math.exp(-ray), 0.03, "the same model, the same answer straight up");
    }

    @Test
    void aSlantedRayThroughAWideLayerIsLongerByTheSlant() {
        StormShade.Index layer = index(cloud(0, 0, 9000, 300, 600, 0.5f, 1f, 0f, 0f));
        double up = StormShade.opticalDepthAlong(layer, 0, 100, 0, 0, 1, 0);
        double s = Math.sqrt(0.5);
        double slant = StormShade.opticalDepthAlong(layer, 0, 100, 0, s, s, 0);
        assertTrue(up > 0, "the layer has depth");
        assertEquals(Math.sqrt(2), slant / up, 0.1, "45 degrees: the path through the layer is 1.41 times longer");
    }

    @Test
    void aRayPassesCloudsNotOnItsPath() {
        // A small cloud 300 blocks east at 200-400 up: the 45 degree ray from the origin crosses 200-400 at x 100-300
        StormShade.Index onPath = index(cloud(250, 0, 150, 200, 400, 0.9f, 1f, 0f, 0f));
        StormShade.Index offPath = index(cloud(-250, 0, 150, 200, 400, 0.9f, 1f, 0f, 0f));
        double s = Math.sqrt(0.5);
        assertTrue(StormShade.opticalDepthAlong(onPath, 0, 0, 0, s, s, 0) > 0.2, "through it");
        assertEquals(0, StormShade.opticalDepthAlong(offPath, 0, 0, 0, s, s, 0), 1e-9, "away from the sun's side: clear");
    }

    @Test
    void aVerySlantedRayAndNoCloudsAreClear() {
        StormShade.Index layer = index(cloud(0, 0, 9000, 300, 600, 0.5f, 1f, 0f, 0f));
        assertEquals(0, StormShade.opticalDepthAlong(layer, 0, 100, 0, 1, 0, 0), "sideways");
        assertEquals(0, StormShade.opticalDepthAlong(layer, 0, 100, 0, 0, -1, 0), "down");
        assertEquals(0, StormShade.opticalDepthAlong(index(), 0, 100, 0, 0, 1, 0), "no clouds");
    }

    @Test
    void aPersonAboveTheCloudIsInTheSun() {
        StormShade.Index layer = index(cloud(0, 0, 9000, 300, 600, 0.5f, 1f, 0f, 0f));
        assertEquals(0, StormShade.opticalDepthAlong(layer, 0, 700, 0, 0, 1, 0), 1e-9, "a ship above the deck");
        double inside = StormShade.opticalDepthAlong(layer, 0, 450, 0, 0, 1, 0);
        double below = StormShade.opticalDepthAlong(layer, 0, 100, 0, 0, 1, 0);
        assertEquals(0.5, inside / below, 0.1, "half way up the cloud: half the depth");
    }

    @Test
    void thickerCloudCutsMoreSun() {
        StormShade.Index thin = index(cloud(0, 0, 9000, 6000, 6300, 0.25f, 0.6f, 0f, 0f));
        StormShade.Index thick = index(cloud(0, 0, 9000, 300, 3300, 0.85f, 0.98f, 0f, 0f));
        double thinShare = Math.exp(-StormShade.opticalDepthAlong(thin, 0, 100, 0, 0, 1, 0));
        double thickShare = Math.exp(-StormShade.opticalDepthAlong(thick, 0, 100, 0, 0, 1, 0));
        assertTrue(thinShare > 0.4, "thin high cloud lets plenty through: " + thinShare);
        assertTrue(thickShare < 0.05, "a thick deck almost none: " + thickShare);
    }
}
