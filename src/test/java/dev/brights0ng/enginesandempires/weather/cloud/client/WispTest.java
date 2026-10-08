package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;

/** Wisps (2026-10-07, Bright: soft haze on the edges, shreds under the base, drifting off and fading). */
class WispTest {

    static CloudField field(CloudType t, double size, double thickness, double growth, double decay) {
        CloudShape c = DyingCumulusTest.shape(t, size, thickness, growth, decay);
        return CloudField.of(CloudFormation.of(c.regionId(), List.of(c)));
    }

    @Test
    void hazeSitsOnTheEdgesAndShredsHangUnderTheBase() {
        CloudField f = field(CloudType.CUMULUS_MEDIOCRIS, 1.0, 280, 1, 0);
        CloudWisps w = new CloudWisps(1);
        w.tick(f, 0, 0, 10_000);
        int target = CloudWisps.target(f);
        assertEquals(target, w.wisps.size(), "fills up at once the first time");
        int shreds = 0;
        CloudField.Column c = f.newColumn();
        for (CloudWisps.Wisp x : w.wisps) {
            if (x.kind == CloudWisps.SHRED) {
                shreds++;
                assertTrue(x.y < f.baseY, "shreds hang below the base");
            } else {
                assertTrue(x.y > f.baseY, "haze is above the base");
            }
            assertTrue(x.alpha(0) >= 0 && x.alpha(0) <= 1);
            assertTrue(x.life >= CloudWisps.LIFE_MIN && x.life <= CloudWisps.LIFE_MAX);
        }
        System.out.printf("mediocris: %d wisps, %d shreds%n", w.wisps.size(), shreds);
        assertTrue(shreds > 0 && shreds < w.wisps.size() / 2);
        // They come and go: after a lifetime, all are new ones.
        for (int t = 1; t <= 700; t++) {
            w.tick(f, 0, t, 10_000);
        }
        for (CloudWisps.Wisp x : w.wisps) {
            assertTrue(x.born > 0, "replaced");
        }
    }

    @Test
    void formingAndDyingCloudsHaveMore() {
        int mature = CloudWisps.target(field(CloudType.CUMULUS_HUMILIS, 1.0, 110, 1, 0));
        int forming = CloudWisps.target(field(CloudType.CUMULUS_HUMILIS, 1.0, 110, 0.3, 0));
        int dying = CloudWisps.target(field(CloudType.CUMULUS_HUMILIS, 1.0, 110, 1, 0.7));
        System.out.printf("humilis wisps: mature %d, forming %d, dying %d%n", mature, forming, dying);
        assertTrue(forming > mature && dying > mature);
    }

    @Test
    void theWispsFadeInAndOut() {
        CloudWisps.Wisp w = new CloudWisps.Wisp();
        w.kind = CloudWisps.HAZE;
        w.born = 0;
        w.life = 400;
        assertEquals(0, w.alpha(0), 1e-9);
        assertTrue(w.alpha(50) < w.alpha(150));
        assertEquals(CloudWisps.HAZE_ALPHA, w.alpha(160), 1e-9);
        assertTrue(w.alpha(380) < w.alpha(250));
        assertEquals(0, w.alpha(400), 1e-9);
    }

    @Test
    void pictures() throws Exception {
        File dir = new File("build/cloud-pictures/wisps");
        Object[][] cases = {{CloudType.CUMULUS_HUMILIS, 110.0, 1.0, 0.0}, {CloudType.CUMULUS_MEDIOCRIS, 280.0, 1.0, 0.0},
                {CloudType.CUMULUS_MEDIOCRIS, 280.0, 1.0, 0.6}, {CloudType.CUMULUS_MEDIOCRIS, 280.0, 0.4, 0.0}};
        String[] names = {"humilis", "mediocris", "mediocris_dying", "mediocris_forming"};
        for (int k = 0; k < cases.length; k++) {
            Object[] q = cases[k];
            CloudField f = field((CloudType) q[0], 1.0, (Double) q[1], (Double) q[2], (Double) q[3]);
            for (boolean withWisps : new boolean[]{false, true}) {
                CloudSnapshot snap = new CloudSnapshot();
                CloudVoxelizer.build(f, 4, 0, snap.sink());
                if (withWisps) {
                    CloudWisps w = new CloudWisps(5);
                    w.tick(f, 0, 0, 10_000);
                    for (CloudWisps.Wisp x : w.wisps) {
                        double[] parts = x.parts(f.lightX, f.lightY, f.lightZ, 1);
                        int rgb = grey(parts[0] + parts[1]);
                        snap.sprite(x.x, x.y, x.z, x.size, x.stretch, x.turn, x.kind == CloudWisps.SHRED, rgb,
                                x.alpha(0));
                    }
                }
                snap.write(new File(dir, names[k] + (withWisps ? "_wisps" : "_plain") + ".png"), 480, 205, -12);
            }
        }
    }

    private static int grey(double v) {
        int g = (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
        return (g << 16) | (g << 8) | g;
    }
}
