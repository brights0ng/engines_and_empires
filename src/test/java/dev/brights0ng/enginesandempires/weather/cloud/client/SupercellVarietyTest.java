package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.StormVariety;

/** Supercells differ from each other the way real ones do, centred on the design's average storm. */
class SupercellVarietyTest {

    private static final int N = 600;

    private static UUID region(int i) {
        return new UUID(0xA11CEL + i * 0x9E3779B97F4A7C15L, i * 0xC2B2AE3D27D4EB4FL + 7);
    }

    private static Supercell.Look[] looks() {
        Supercell.Look[] out = new Supercell.Look[N];
        for (int i = 0; i < N; i++) {
            out[i] = Supercell.Look.of(region(i), 1);
        }
        return out;
    }

    private static double[] log(Supercell.Look[] looks, ToDoubleFunction<Supercell.Look> f) {
        return Arrays.stream(looks).mapToDouble(k -> Math.log(f.applyAsDouble(k))).toArray();
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static double sd(double[] v) {
        double m = Arrays.stream(v).average().orElse(0);
        return Math.sqrt(Arrays.stream(v).map(x -> (x - m) * (x - m)).average().orElse(0));
    }

    private static double corr(double[] a, double[] b) {
        double ma = Arrays.stream(a).average().orElse(0), mb = Arrays.stream(b).average().orElse(0);
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < a.length; i++) {
            sab += (a[i] - ma) * (b[i] - mb);
            saa += (a[i] - ma) * (a[i] - ma);
            sbb += (b[i] - mb) * (b[i] - mb);
        }
        return sab / Math.sqrt(saa * sbb);
    }

    @Test
    void oneStormAlwaysLooksTheSame() {
        Supercell.Look a = Supercell.Look.of(region(3), 1);
        Supercell.Look b = Supercell.Look.of(region(3), 1);
        assertEquals(a.updraftWidth, b.updraftWidth);
        assertEquals(a.ffLength, b.ffLength);
        assertEquals(a.flankAngle, b.flankAngle);
    }

    @Test
    void noVarietyIsTheDesignItself() {
        Supercell.Look k = Supercell.Look.of(region(5), 0);
        assertEquals(Supercell.Look.UPDRAFT_WIDTH, k.updraftWidth, 1e-12);
        assertEquals(Supercell.Look.FF_LENGTH, k.ffLength, 1e-12);
        assertEquals(25, k.flankAngle, 1e-12);
        assertEquals(1, k.darkness, 1e-12);
    }

    @Test
    void centredOnTheDesignAndNotablyVaried() {
        Supercell.Look[] looks = looks();
        Object[][] parts = {
                {"updraft width", (ToDoubleFunction<Supercell.Look>) k -> k.updraftWidth, Supercell.Look.UPDRAFT_WIDTH},
                {"lean", (ToDoubleFunction<Supercell.Look>) k -> k.lean, Supercell.Look.LEAN},
                {"anvil length", (ToDoubleFunction<Supercell.Look>) k -> k.anvilLength, Supercell.Look.ANVIL_LENGTH},
                {"overshoot", (ToDoubleFunction<Supercell.Look>) k -> k.overshoot, Supercell.Look.OVERSHOOT},
                {"backshear", (ToDoubleFunction<Supercell.Look>) k -> k.backshear, Supercell.Look.BACKSHEAR},
                {"forward flank", (ToDoubleFunction<Supercell.Look>) k -> k.ffLength, Supercell.Look.FF_LENGTH},
                {"shelf", (ToDoubleFunction<Supercell.Look>) k -> k.shelfReach, Supercell.Look.SHELF_REACH}};
        for (Object[] p : parts) {
            @SuppressWarnings("unchecked")
            double[] v = log(looks, (ToDoubleFunction<Supercell.Look>) p[1]);
            double med = Math.exp(median(v)) / (double) p[2];
            System.out.printf("%s: median %.2f x the design, typical spread x%.2f%n", p[0], med, Math.exp(sd(v)));
            assertEquals(1, med, 0.08, p[0] + " is centred on the design");
            assertTrue(sd(v) > 0.14, p[0] + " varies notably");
        }
    }

    @Test
    void partsMoveTogetherLikeRealStorms() {
        Supercell.Look[] looks = looks();
        double[] ffLength = log(looks, k -> k.ffLength);
        double[] ffWidth = log(looks, k -> k.ffWidth);
        double[] shelf = log(looks, k -> k.shelfReach);
        double[] striation = log(looks, k -> k.striation);
        double[] lean = log(looks, k -> k.lean);
        double[] anvil = log(looks, k -> k.anvilLength);
        double[] overshoot = log(looks, k -> k.overshoot);
        double[] backshear = log(looks, k -> k.backshear);
        assertTrue(corr(ffLength, ffWidth) > 0.6, "HP: long and wide forward flank together");
        assertTrue(corr(ffLength, shelf) > 0.6, "HP: big forward flank, big shelf");
        assertTrue(corr(ffLength, striation) < -0.5, "LP: little forward flank, sculpted striations");
        assertTrue(corr(lean, anvil) > 0.5, "shear: tilted tower, long anvil");
        assertTrue(corr(overshoot, backshear) > 0.5, "instability: tall overshoot, strong backshear");
        assertTrue(Math.abs(corr(lean, ffLength)) < 0.2, "shear and precipitation are independent");
    }

    @Test
    void lowPrecipitationStormsHaveHigherBases() {
        double[] p = new double[N];
        double[] base = new double[N];
        for (int i = 0; i < N; i++) {
            p[i] = StormVariety.of(region(i)).precipitation();
            base[i] = CloudScale.heights("supercell", region(i), 1).base();
        }
        assertTrue(corr(p, base) < -0.25, "HP low, LP high: " + corr(p, base));
    }

    @Test
    void everyVariedStormKeepsItsStructure() {
        for (int i = 0; i < 20; i++) {
            CloudField field = CloudField.of(SupercellTest.storm(region(i), 1, 0));
            Supercell s = field.storm;
            String who = "storm " + i + " " + s.look.variety;
            // Measured from the tower's base: the anvil reaches further downwind than upwind.
            assertTrue(s.leanTop + s.anvilDown > s.anvilUp - s.leanTop, who + ": the anvil streams downwind");
            // Nothing pokes above the anvil top but the overshoot.
            CloudField.Column top = field.newColumn();
            double[] bb = field.bounds();
            for (double x = bb[0]; x < bb[1]; x += 256) {
                for (double z = bb[4]; z < bb[5]; z += 256) {
                    // (The overshoot is centred over the updraft's top, give or take the warp.)
                    if (Math.hypot(x - s.ax, z - s.az) < s.overR * 1.3 + field.warpLength) {
                        continue;
                    }
                    top.time = Double.NaN;
                    field.column(x, z, 500, top);
                    assertTrue(field.density(top, x, s.ya + 0.06 * s.h, z, 500, false) < 0,
                            who + ": cloud above the anvil at " + x + " " + z);
                }
            }
            int last = Supercell.TOWER_COUNT - 1;
            assertTrue(s.towerX[last] < s.ux && s.towerZ[last] > s.uz, who + ": flanking line to the right-rear");
            assertTrue(s.ffX > s.ux && s.ffZ < s.uz, who + ": forward flank downwind and left");
            assertTrue(s.widthAt(0) > s.widthAt(0.55) && s.widthAt(1) > s.widthAt(0.55), who + ": tower shape");
            // Nothing below the base but the wall cloud.
            double[] b = field.bounds();
            double y = s.yb - 0.03 * s.h;
            CloudField.Column col = field.newColumn();
            for (double x = b[0]; x < b[1]; x += 128) {
                for (double z = b[4]; z < b[5]; z += 128) {
                    if (Math.hypot(x - s.wallX, z - s.wallZ) < s.wallR * 1.6) {
                        continue;
                    }
                    field.column(x, z, 500, col);
                    assertTrue(field.density(col, x, y, z, 500, false) < 0, who + ": cloud under the base at " + x
                            + " " + z);
                }
            }
        }
    }

    @Test
    void theShelfRunsBackIntoTheStorm() {
        for (int i = 0; i < 20; i++) {
            CloudField field = CloudField.of(SupercellTest.storm(region(i), 1, 0));
            Supercell s = field.storm;
            double[] c = new double[s.cacheSize()];
            // From just behind the shelf's leading edge back to the forward flank's centre, along its middle: every
            // step has cloud somewhere in the low part of the storm. (The template itself, before the warp bends it
            // and the churn roughens it.)
            double front = s.shelfReach - 0.1 * s.shelfDepth;
            for (double u = front; u > 0; u -= 48) {
                double x = s.ffX + u * s.dx, z = s.ffZ + u * s.dz;
                s.column(c, x, z, x, z, 500);
                boolean any = false;
                for (double y = s.yb; y < s.yb + 0.4 * s.h && !any; y += 16) {
                    any = s.body(c, y, 500) > 0;
                }
                assertTrue(any, "storm " + i + " " + s.look.variety + ": a gap behind the shelf, " + (int) u
                        + " blocks ahead of the forward flank's centre (shelf front " + (int) s.shelfReach + ")");
            }
        }
    }

    @Test
    void theAnvilTopBulgesALittle() {
        Supercell s = CloudField.of(SupercellTest.storm(region(2), 1, 0)).storm;
        double[] c = new double[s.cacheSize()];
        double most = 0;
        for (double u = 0; u < 0.7 * s.anvilDown; u += 64) {
            for (double w = -0.5 * s.anvilWidth; w <= 0.5 * s.anvilWidth; w += 64) {
                double x = s.ax + u * s.dx + w * s.rx, z = s.az + u * s.dz + w * s.rz;
                s.column(c, x, z, x, z, 500);
                most = Math.max(most, c[Supercell.ANVIL_TOP] - s.ya);
            }
        }
        assertTrue(most > 0.012 * s.h, "the top bulges: " + most);
        assertTrue(most < 0.045 * s.h, "but only a little: " + most);
    }
}
