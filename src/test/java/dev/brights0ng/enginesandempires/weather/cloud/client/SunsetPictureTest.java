package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;

/**
 * Sunrise and sunset colours (2026-10-07, Bright: realistic, with a short afterglow): checks of {@link CloudColours}
 * and {@link CloudLight}, and film strips through a sunset in build/cloud-pictures/sunset.
 */
class SunsetPictureTest {

    /** Times of day (0 = noon) where the sun is at these heights on its way down. */
    static double timeAt(double h) {
        return Math.acos(h) / (Math.PI * 2);
    }

    @Test
    void middayIsWarmInTheSunCoolInTheShadeAndNightIsDim() {
        double[] sun = CloudColours.sun(1), sky = CloudColours.sky(1);
        assertTrue(sun[0] > sun[2] && sky[2] > sky[0], "warm sunlight, cool skylight");
        // A sunlit side (the sky's light less the sun's contrast, plus the sun's) is about white, a touch yellow.
        double c = 1 - CloudTuning.shadowSide;
        for (int k = 0; k < 3; k++) {
            double lit = sky[k] * (1 - c) + sun[k] * c;
            assertEquals(0.97, lit, 0.05, "channel " + k);
        }
        double[] night = CloudColours.sky(-1);
        assertTrue(night[0] < 0.15 && night[2] > night[0], "night sky light is dim and blue");
        double[] low = CloudColours.sun(0.03);
        assertTrue(low[0] > low[1] && low[1] > low[2], "a low sun is warm: " + low[0] + " " + low[1] + " " + low[2]);
    }

    @Test
    void theLightNeverJumps() {
        CloudLight prev = CloudLight.at(0);
        for (int i = 1; i <= 2000; i++) {
            CloudLight l = CloudLight.at(i / 2000.0);
            double turn = (l.x() - prev.x()) * (l.x() - prev.x()) + (l.y() - prev.y()) * (l.y() - prev.y());
            // (Steps of 12 ticks.) Where the light swaps from the sun to the moon its strength is zero, so the swap
            // can't be seen.
            assertTrue(Math.abs(l.strength() - prev.strength()) < 0.05 || turn > 1, "at " + i / 2000.0);
            if (turn > 1) {
                assertTrue(l.strength() < 0.01 && prev.strength() < 0.01, "the swap happens in the dark");
            }
            prev = l;
        }
        CloudLight setting = CloudLight.at(timeAt(-0.05));
        assertTrue(setting.y() < 0 && setting.strength() > 0.2, "the set sun still lights the clouds from below");
    }

    @Test
    void sunsetStrips() throws Exception {
        File dir = new File("build/cloud-pictures/sunset");
        double[] heights = {0.6, 0.17, 0.06, 0.0, -0.05, -0.09, -0.4};
        for (int view = 0; view < 2; view++) {
            CloudSnapshot snap = new CloudSnapshot();
            CloudShape c = CumulusSnapshotTest.sample(CloudType.CUMULUS_MEDIOCRIS, 1.0, 280, 3, 0);
            double spacing = c.radius() * 3.2;
            // Looking east (the sunlit side of a setting sun) and west (into it).
            double yaw = Math.toRadians(view == 0 ? 90 : 270);
            for (int i = 0; i < heights.length; i++) {
                double t = timeAt(heights[i]);
                CloudLight light = CloudLight.at(t);
                // Built as the game does: the shadowing through the cloud for the light from above, the lit side live.
                CloudLight baked = CloudLight.bakeAt(t);
                CloudField f = CloudField.of(CloudFormation.of(c.regionId(), List.of(c)))
                        .withLight(baked.x(), baked.y(), baked.z(), baked.strength());
                double off = i * spacing;
                float ox = (float) (off * Math.cos(yaw)), oz = (float) (-off * Math.sin(yaw));
                CloudVoxelizer.build(f, 4, 0, snap.sink(CloudColours.sky(heights[i]), CloudColours.sun(heights[i]),
                        light, ox, oz));
            }
            snap.write(new File(dir, view == 0 ? "sunset_lit_side.png" : "sunset_into_sun.png"), 1800,
                    view == 0 ? 90 : 270, -8);
        }
    }

    /**
     * 2026-10-07 evening (Bright: no steps in the clouds' look through the night): a cloud built once, lit live from
     * the afternoon through dusk, the afterglow, the night and dawn, changes by no more than a little between frames
     * (three ticks apart), whichever way its surfaces face.
     */
    @Test
    void theLitSideTurnsSmoothlyThroughTheNight() {
        double worst = 0;
        for (int a = 0; a < 12; a++) {
            for (int b = -2; b <= 2; b++) {
                double el = b * Math.PI / 5, az = a * Math.PI / 6;
                double nx = Math.cos(el) * Math.cos(az), ny = Math.sin(el), nz = Math.cos(el) * Math.sin(az);
                for (double through : new double[]{1, 0.3}) {
                    double prev = Double.NaN;
                    for (int i = 0; i <= 8000; i++) {
                        double t = 0.15 + i / 8000.0 * 0.75;
                        CloudLight l = CloudLight.at(t);
                        double h = CloudColours.sunHeight(t);
                        double[] p = CloudShading.parts(0.9, 0.9, through, nx, ny, nz, l.x(), l.y(), l.z(),
                                l.strength(), CloudTuning.shadowSide);
                        double[] sky = CloudColours.sky(h), sun = CloudColours.sun(h);
                        double v = p[0] * (sky[0] + sky[1] + sky[2]) / 3 + p[1] * (sun[0] + sun[1] + sun[2]) / 3;
                        if (!Double.isNaN(prev)) {
                            worst = Math.max(worst, Math.abs(v - prev));
                        }
                        prev = v;
                    }
                }
            }
        }
        System.out.printf("largest change between frames through the night: %.4f%n", worst);
        assertTrue(worst < 0.008, "no steps: " + worst);
    }
}
