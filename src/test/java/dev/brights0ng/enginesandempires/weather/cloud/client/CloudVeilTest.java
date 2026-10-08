package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;

/** The high deck's see-through clouds (2026-10-07 evening): cirrostratus strips and veil, cirrus fibres. */
class CloudVeilTest {

    static SimCloud high(CloudType t, double x, double z, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        double thick = CloudScale.thickness(t, t.baseMin, 0.5);
        return new SimCloud(new UUID(seed, seed * 31), t, x, z, 0.3, 0, 0, -1_000_000, 1_000_000,
                (float) CloudScale.baseY(t.baseMin + 200), (float) thick, t.look.coverage(), 0, 0,
                SimCloud.domesFor(t, 0.6 * 1536, rng), 0, false);
    }

    static List<CloudShape> shapes(List<SimCloud> clouds) {
        List<CloudShape> out = new ArrayList<>();
        for (SimCloud c : clouds) {
            out.addAll(c.shapes("minecraft:overworld", 0));
        }
        return out;
    }

    @Test
    void highCloudsAreVeilsNotVoxels() {
        List<CloudShape> s = shapes(List.of(high(CloudType.CIRROSTRATUS, 0, 0, 3)));
        assertNull(CloudField.of(CloudFormation.of(s.getFirst().regionId(), s)), "no voxel field for a veil cloud");
        CloudVeils.Grid g = CloudVeils.of(s, 0, 0, 0, 3000, 64);
        assertTrue(g.any());
        float middle = g.strips()[g.index(32, 32)];
        float far = g.strips()[g.index(0, 0)];
        System.out.printf("cirrostratus cover: %.2f in the middle, %.2f at the grid's corner; height %.0f%n", middle,
                far, g.height()[g.index(32, 32)]);
        assertTrue(middle > 0.5 && far < 0.05);
        assertTrue(g.height()[g.index(32, 32)] > 1000, "up in the high deck");
        CloudVeils.Grid none = CloudVeils.of(shapes(List.of(LayerSheetTest.layer(CloudType.STRATUS, 0, 0, 4, 0.1))), 0,
                0, 0, 3000, 64);
        assertFalse(none.any(), "low clouds aren't veils");
    }

    /** Cirrostratus: mostly strips with the sky between; cirrus: sparse wisps, thinner than cirrostratus. */
    @Test
    void stripsShowTheSkyBetweenAndCirrusIsWispiest() {
        int n = 0, gaps = 0;
        double sumCs = 0, sumCi = 0;
        for (int i = 0; i < 200; i++) {
            for (int j = 0; j < 200; j++) {
                double x = i * 30 + 0.5, z = j * 30 + 0.5;
                double cs = CloudVeilPattern.alpha(x, z, 1, 0, 1, 0);
                double ci = CloudVeilPattern.alpha(x, z, 1, 0, 0, 1);
                sumCs += cs;
                sumCi += ci;
                if (cs < 0.05) {
                    gaps++;
                }
                n++;
            }
        }
        System.out.printf("cirrostratus: mean opacity %.3f, sky showing (under 5%%) on %.0f%%; cirrus mean %.3f%n",
                sumCs / n, 100.0 * gaps / n, sumCi / n);
        assertTrue(gaps > 0.25 * n, "sky between the strips");
        assertTrue(sumCi < sumCs, "cirrus is wispier than cirrostratus");
    }

    @Test
    void pictures() throws Exception {
        File dir = new File("build/cloud-pictures/veils");
        dir.mkdirs();
        String[] names = {"cirrostratus", "cirrus"};
        for (int k = 0; k < 2; k++) {
            // From above, 6,000 blocks square, the wind along x (left to right).
            int w = 600;
            BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    double a = CloudVeilPattern.alpha(x * 10.0, y * 10.0, 1, 0, k == 0 ? 1 : 0, k == 1 ? 1 : 0);
                    img.setRGB(x, y, blend(110, 165, 240, a));
                }
            }
            ImageIO.write(img, "png", new File(dir, names[k] + "_from_above.png"));
            // From the ground, looking up 35 degrees across the wind toward the deck 1,500 blocks up.
            int width = 900, height = 500;
            BufferedImage sky = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            double pitch = Math.toRadians(35), fov = Math.toRadians(90);
            double focal = (width / 2.0) / Math.tan(fov / 2);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double sx = x - width / 2.0, sy = height / 2.0 - y;
                    // Camera looking along +z, tilted up by pitch.
                    double dy = sy * Math.cos(pitch) + focal * Math.sin(pitch);
                    double dz = -sy * Math.sin(pitch) + focal * Math.cos(pitch);
                    double dx = sx;
                    int r = (int) (110 + 50.0 * y / height), g = (int) (165 + 40.0 * y / height);
                    double a = 0;
                    if (dy > 0) {
                        double t = 1500 / dy;
                        double px = dx * t, pz = dz * t;
                        double dist = Math.hypot(px, pz);
                        a = CloudVeilPattern.alpha(px + 50_000, pz + 50_000, 1, 0, k == 0 ? 1 : 0, k == 1 ? 1 : 0)
                                * (1 - smoothstep(1400, 3072, dist));
                    }
                    sky.setRGB(x, y, blend(r, g, 255, a));
                }
            }
            ImageIO.write(sky, "png", new File(dir, names[k] + "_from_below.png"));
        }
    }

    private static int blend(int r, int g, int b, double a) {
        a = Math.max(0, Math.min(1, a));
        int rr = (int) Math.round(r + (250 - r) * a), gg = (int) Math.round(g + (250 - g) * a);
        int bb = (int) Math.round(b + (252 - b) * a);
        return (rr << 16) | (gg << 8) | bb;
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
