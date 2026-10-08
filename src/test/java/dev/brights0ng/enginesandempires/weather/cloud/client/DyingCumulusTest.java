package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudType;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;

/**
 * Dying heap clouds (Bright, 2026-10-07: they popped out of existence; now they fray and break up over their whole
 * death): the volume left through a death, and film strips in build/cloud-pictures/death.
 */
class DyingCumulusTest {

    static CloudShape shape(CloudType t, double size, double thickness, double decay) {
        return shape(t, size, thickness, 1, decay);
    }

    /** As the server would send it at {@code growth}: its top grown so far (CloudScale.heights). */
    static CloudShape shape(CloudType t, double size, double thickness, double growth, double decay) {
        CloudType.Look look = t.look;
        float r = (float) (t.radiusBlocks() * size);
        float thick = SimCloud.heapThickness(thickness, size);
        float grown = (float) dev.brights0ng.enginesandempires.weather.cloud.CloudScale.heights(t, 200, thick, growth)
                .top();
        return new CloudShape(UUID.nameUUIDFromBytes(new byte[]{1}), UUID.nameUUIDFromBytes(new byte[]{2}),
                "minecraft:overworld", 0, 0, 0, 0, 0, r, 200, grown, look.density(), look.coverage(),
                look.edgeSoftness(), (float) growth, (float) decay, (float) decay, t.id, look.tower(), look.anvil(),
                look.baseDarkness(), look.stormDarkness(), 0, 0, 3);
    }

    static CloudField field(CloudShape s) {
        return CloudField.of(CloudFormation.of(s.regionId(), List.of(s)));
    }

    /** The cloud's volume (blocks^3, sampled every {@code step} blocks) and its bubble count. */
    static double[] volume(CloudField f, double step) {
        if (f == null) {
            return new double[]{0, 0};
        }
        CloudField.Column c = f.newColumn();
        double[] range = new double[2];
        double reach = 0;
        int bubbles = 0;
        for (CloudField.Member m : f.members) {
            reach = Math.max(reach, Math.hypot(m.cx, m.cz) + m.r * 1.6);
            bubbles += m.cu == null ? 0 : m.cu.n;
        }
        long n = 0;
        for (double x = -reach; x <= reach; x += step) {
            for (double z = -reach; z <= reach; z += step) {
                f.column(x, z, 0, c);
                if (!f.columnRange(c, range)) {
                    continue;
                }
                for (double y = Math.floor(range[0]); y <= range[1]; y += step) {
                    if (f.density(c, x, y, z, 0, false) > 0) {
                        n++;
                    }
                }
            }
        }
        return new double[]{n * step * step * step, bubbles};
    }

    @Test
    void dyingCumulusFadeAllThroughTheirDeath() {
        Object[][] cases = {{CloudType.CUMULUS_HUMILIS, 110.0}, {CloudType.CUMULUS_MEDIOCRIS, 280.0}};
        for (Object[] k : cases) {
            CloudType t = (CloudType) k[0];
            double[] full = volume(field(shape(t, 1.0, (Double) k[1], 0)), 4);
            double prev = 1;
            StringBuilder sb = new StringBuilder(t.id + ":");
            for (int i = 1; i <= 20; i++) {
                double d = i / 20.0;
                double[] v = volume(field(shape(t, 1.0, (Double) k[1], d)), 4);
                double left = v[0] / full[0];
                sb.append(String.format(" %.2f=%.0f%%", d, 100 * left));
                if (v[1] > 0) {
                    assertEquals(full[1], v[1], "the bubble layout holds through the death (no reshaping)");
                }
                assertTrue(prev - left < 0.2, "no sudden loss at " + d + ": " + sb);
                if (d == 0.25) {
                    assertTrue(left < 0.95, "visibly going a quarter of the way in: " + sb);
                }
                if (d == 0.5) {
                    assertTrue(left > 0.4 && left < 0.85, "about half gone halfway: " + sb);
                }
                if (d == 0.9) {
                    assertTrue(left < 0.05, "all but gone near the end: " + sb);
                }
                prev = left;
            }
            System.out.println(sb);
        }
    }

    @Test
    void formingCumulusBuildUpWithoutReshaping() {
        Object[][] cases = {{CloudType.CUMULUS_HUMILIS, 110.0}, {CloudType.CUMULUS_MEDIOCRIS, 280.0}};
        for (Object[] k : cases) {
            CloudType t = (CloudType) k[0];
            double[] full = volume(field(shape(t, 1.0, (Double) k[1], 1, 0)), 4);
            double prev = 0;
            StringBuilder sb = new StringBuilder(t.id + " forming:");
            for (int i = 1; i <= 20; i++) {
                double g = i / 20.0;
                double[] v = volume(field(shape(t, 1.0, (Double) k[1], g, 0)), 4);
                double has = v[0] / full[0];
                sb.append(String.format(" %.2f=%.0f%%", g, 100 * has));
                if (v[1] > 0) {
                    assertEquals(full[1], v[1], "the bubble layout holds through the birth (no reshaping)");
                }
                assertTrue(has - prev < 0.2, "no sudden growth at " + g + ": " + sb);
                assertTrue(has >= prev - 0.05, "it builds up: " + sb);
                prev = has;
            }
            assertEquals(1, prev, 0.02, "fully formed at the end: " + sb);
            System.out.println(sb);
        }
    }

    @Test
    void filmStrips() throws Exception {
        // One scale for every frame, the frames laid out across the view.
        File dir = new File("build/cloud-pictures/death");
        Object[][] strips = {{CloudType.CUMULUS_HUMILIS, 110.0, 1.0, 4}, {CloudType.CUMULUS_MEDIOCRIS, 280.0, 1.0, 4},
                {CloudType.CUMULUS_CONGESTUS, 900.0, 0.5, 8}};
        double[] steps = {0, 0.15, 0.3, 0.45, 0.6, 0.7, 0.8, 0.9};
        double yaw = Math.toRadians(205);
        for (Object[] k : strips) {
            CloudType t = (CloudType) k[0];
            CloudSnapshot snap = new CloudSnapshot();
            double spacing = t.radiusBlocks() * (Double) k[2] * 3.2;
            for (int i = 0; i < steps.length; i++) {
                CloudField f = field(shape(t, (Double) k[2], (Double) k[1], steps[i]));
                if (f == null) {
                    continue;
                }
                double off = i * spacing;
                float ox = (float) (off * Math.cos(yaw)), oz = (float) (-off * Math.sin(yaw));
                CloudVoxelizer.build(f, (Integer) k[3], 0, snap.sink(ox, oz));
            }
            snap.write(new File(dir, t.id + "_strip.png"), 1600, 205, -12);
            CloudSnapshot born = new CloudSnapshot();
            double[] growth = {0.1, 0.25, 0.4, 0.55, 0.7, 0.85, 1.0};
            for (int i = 0; i < growth.length; i++) {
                CloudField f = field(shape(t, (Double) k[2], (Double) k[1], growth[i], 0));
                if (f == null) {
                    continue;
                }
                double off = i * spacing;
                float ox = (float) (off * Math.cos(yaw)), oz = (float) (-off * Math.sin(yaw));
                CloudVoxelizer.build(f, (Integer) k[3], 0, born.sink(ox, oz));
            }
            born.write(new File(dir, t.id + "_birth_strip.png"), 1600, 205, -12);
        }
    }
}
