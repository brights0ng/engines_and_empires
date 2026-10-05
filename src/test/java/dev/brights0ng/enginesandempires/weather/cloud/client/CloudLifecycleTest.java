package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;

/** Birth and death as the renderer draws them (CloudField's erosion and the supercell's linger). */
class CloudLifecycleTest {

    static {
        CloudVoxelizer.seamOverlap = 0;
    }

    private static final UUID REGION = new UUID(0xC10DL, 0x11FEL);
    private static final UUID STORM = new UUID(0x5EC0L, 0xCE11L);

    @BeforeEach
    void averageStorms() {
        CloudTuning.variety = 0;
    }

    @AfterEach
    void restore() {
        CloudTuning.variety = 1;
    }

    // ---- shapes --------------------------------------------------------------------------------------------------

    private static CloudShape cumulus(int i, double x, double z, float growth, float decay) {
        CloudScale.Heights h = CloudScale.heights("cumulus_mediocris", REGION, growth);
        return new CloudShape(new UUID(1, i), REGION, "minecraft:overworld", x, 300, z, 0.1, 0, 0, 0,
                56, (float) h.base(), (float) h.top(), 0.8f, 0.85f, 0.5f, growth, decay, 1, 1, "cumulus_mediocris",
                0.4f, 0, 1, 0.3f, "CLOUDY", 0.18f, 0, 0, 900 + i, decay);
    }

    /** A three-cluster cumulus mediocris. */
    private static CloudFormation cumulus(float growth, float decay) {
        List<CloudShape> c = List.of(cumulus(1, 0, 0, growth, decay), cumulus(2, 60, 25, growth, decay),
                cumulus(3, -45, 40, growth, decay));
        return CloudFormation.of(REGION, c);
    }

    private static CloudFormation supercell(float growth, float decay, float anvilDecay) {
        List<CloudShape> lobes = new ArrayList<>();
        double[][] at = {{0, 0}, {320, 60}, {-280, 140}, {140, -330}, {-150, -290}, {420, -180}, {-420, -60}};
        for (int i = 0; i < at.length; i++) {
            CloudScale.Heights h = CloudScale.heights("supercell", STORM, growth);
            lobes.add(new CloudShape(new UUID(2, i), STORM, "minecraft:overworld", at[i][0], 350, at[i][1], 0.1, 0, 0,
                    0, 175, (float) h.base(), (float) h.top(), 0.8f, 0.9f, 0.5f, growth, decay, 1, 1, "supercell",
                    0.9f, 0.74f, 1, 0.5f, "THUNDER_CORE", 0.62f, 0.8f, 0.7f, 1234, anvilDecay));
        }
        return CloudFormation.of(STORM, lobes);
    }

    // ---- sampling ------------------------------------------------------------------------------------------------

    /** Points sampled inside the cloud on a grid, how many touch the outside, and their mean height in [base, top]. */
    private record Sample(int inside, int surface, double meanHeight) {
    }

    private static Sample sample(CloudField field, double step, double time) {
        double[] b = field.bounds();
        int nx = (int) Math.ceil((b[1] - b[0]) / step) + 1;
        int ny = (int) Math.ceil((b[3] - b[2]) / step) + 1;
        int nz = (int) Math.ceil((b[5] - b[4]) / step) + 1;
        boolean[] in = new boolean[nx * ny * nz];
        CloudField.Column col = field.newColumn();
        for (int k = 0; k < nz; k++) {
            for (int i = 0; i < nx; i++) {
                double x = b[0] + i * step, z = b[4] + k * step;
                field.column(x, z, time, col);
                for (int j = 0; j < ny; j++) {
                    double y = b[2] + j * step;
                    in[(k * ny + j) * nx + i] = field.density(col, x, y, z, time, false) > 0;
                }
            }
        }
        int inside = 0, surface = 0;
        double heights = 0;
        for (int k = 1; k < nz - 1; k++) {
            for (int j = 1; j < ny - 1; j++) {
                for (int i = 1; i < nx - 1; i++) {
                    int p = (k * ny + j) * nx + i;
                    if (!in[p]) {
                        continue;
                    }
                    inside++;
                    heights += ((b[2] + j * step) - field.baseY) / (field.topY - field.baseY);
                    if (!in[p - 1] || !in[p + 1] || !in[p - nx] || !in[p + nx] || !in[p - nx * ny] || !in[p + nx * ny]) {
                        surface++;
                    }
                }
            }
        }
        return new Sample(inside, surface, inside == 0 ? 0 : heights / inside);
    }

    // ---- tests ---------------------------------------------------------------------------------------------------

    /** Prints how much is left through birth, death and the linger, for tuning. */
    @Test
    void sweep() {
        double mature = sample(CloudField.of(cumulus(1, 0)), 6, 0).inside;
        StringBuilder sb = new StringBuilder("sweep cumulus birth:");
        for (float g : new float[]{0.05f, 0.15f, 0.3f, 0.5f, 0.7f, 0.9f}) {
            CloudField f = CloudField.of(cumulus(g, 0));
            sb.append(String.format(" g%.2f=%.2f", g, f == null ? 0 : sample(f, 6, 0).inside / mature));
        }
        sb.append(" | death:");
        for (float d : new float[]{0.1f, 0.3f, 0.5f, 0.7f, 0.85f, 0.95f}) {
            CloudField f = CloudField.of(cumulus(1, d));
            sb.append(String.format(" d%.2f=%.2f", d, f == null ? 0 : sample(f, 6, 0).inside / mature));
        }
        System.out.println(sb);
        double anvil = sample(CloudField.of(supercell(1, 1, 0)), 96, 0).inside;
        double whole = sample(CloudField.of(supercell(1, 0, 0)), 96, 0).inside;
        StringBuilder sa = new StringBuilder(String.format("sweep supercell: anvil alone %.2f of the storm; linger:",
                anvil / whole));
        for (float a : new float[]{0.1f, 0.3f, 0.5f, 0.7f, 0.85f, 0.95f}) {
            CloudField f = CloudField.of(supercell(1, 1, a));
            sa.append(String.format(" a%.2f=%.2f", a, f == null ? 0 : sample(f, 96, 0).inside / anvil));
        }
        sa.append(" | death:");
        for (float d : new float[]{0.2f, 0.5f, 0.8f}) {
            sa.append(String.format(" d%.2f=%.2f", d, sample(CloudField.of(supercell(1, d, 0)), 96, 0).inside / whole));
        }
        sa.append(" | birth:");
        for (float g : new float[]{0.02f, 0.05f, 0.15f, 0.3f, 0.5f, 0.7f, 0.9f}) {
            CloudField f = CloudField.of(supercell(g, 0, 0));
            sa.append(String.format(" g%.2f=%.2f", g, f == null ? 0 : sample(f, 96, 0).inside / whole));
        }
        System.out.println(sa);
    }

    /** A cloud starts from almost nothing, however big it will be (Bright, 2026-10-04). */
    @Test
    void bigCloudsStartSmall() {
        double whole = sample(CloudField.of(supercell(1, 0, 0)), 48, 0).inside;
        double first = sample(CloudField.of(supercell(0.03f, 0, 0)), 48, 0).inside;
        assertTrue(first < 0.02 * whole, "a newborn supercell is a few fragments: " + first + " of " + whole);
        double cumulus = sample(CloudField.of(cumulus(1, 0)), 6, 0).inside;
        double newborn = sample(CloudField.of(cumulus(0.03f, 0)), 6, 0).inside;
        assertTrue(newborn < 0.01 * cumulus, "a newborn cumulus too: " + newborn + " of " + cumulus);
    }

    /** Young clouds are white; their grey comes in as they thicken (shading by the cloud above). */
    @Test
    void formingCloudsDarkenAsTheyGrow() {
        CloudField young = CloudField.of(supercell(0.2f, 0, 0));
        CloudField grown = CloudField.of(supercell(1, 0, 0));
        Supercell s = grown.storm;
        double y = s.yb + 0.02 * s.h;
        double youngAbove = young.profile(s.ux, s.uz, 0, 16, young.newColumn()).depthAbove(y);
        double grownAbove = grown.profile(s.ux, s.uz, 0, 16, grown.newColumn()).depthAbove(y);
        double youngBase = CloudVoxelizer.brightness(youngAbove, young.water);
        double grownBase = CloudVoxelizer.brightness(grownAbove, grown.water);
        System.out.printf("supercell base brightness: forming %.2f (%.0f blocks above), grown %.2f (%.0f above)%n",
                youngBase, youngAbove, grownBase, grownAbove);
        assertTrue(youngBase > grownBase + 0.08, "a forming storm's base is lighter");
        assertTrue(grownBase < 0.45, "a grown storm's base is dark: " + grownBase);
    }

    /** Thin and dry clouds stay white; thick and wet ones go grey; every top is white. */
    @Test
    void shadingFollowsThicknessAndWater() {
        assertEquals(1, CloudVoxelizer.brightness(0, 1.5), 1e-9, "a top is white");
        double cumulusBase = CloudVoxelizer.brightness(1200 * 0.2, 0.4);
        double stormBase = CloudVoxelizer.brightness(9000 * 0.2, 1.2);
        double stratusBase = CloudVoxelizer.brightness(400 * 0.2, 0.25);
        System.out.printf("bases: stratus %.2f, cumulus %.2f, cumulonimbus %.2f%n", stratusBase, cumulusBase, stormBase);
        assertTrue(stratusBase > cumulusBase && cumulusBase > stormBase);
        assertTrue(cumulusBase > 0.45 && cumulusBase < 0.75, "a fair-weather cumulus base is a soft grey");
        assertTrue(stormBase < 0.4, "a storm's base is dark");
        assertTrue(stormBase >= CloudTuning.baseLight, "but never darker than the sky light allows");
    }

    @Test
    void aFormingCloudStartsAtItsBase() {
        Sample mature = sample(CloudField.of(cumulus(1, 0)), 6, 0);
        Sample young = sample(CloudField.of(cumulus(0.35f, 0)), 6, 0);
        System.out.printf("cumulus: mature %d points (mean height %.2f), forming %d (%.2f)%n", mature.inside,
                mature.meanHeight, young.inside, young.meanHeight);
        assertTrue(young.inside > 0, "something has formed");
        assertTrue(young.inside < 0.5 * mature.inside, "much less of it");
        assertTrue(young.meanHeight < mature.meanHeight, "and it sits low: the base comes first");
        Sample barely = sample(CloudField.of(cumulus(0.08f, 0)), 6, 0);
        assertTrue(barely.inside < 0.1 * mature.inside, "at first only fragments: " + barely.inside);
    }

    @Test
    void aDyingCloudErodesAndFrays() {
        Sample mature = sample(CloudField.of(cumulus(1, 0)), 6, 0);
        Sample dying = sample(CloudField.of(cumulus(1, 0.5f)), 6, 0);
        Sample late = sample(CloudField.of(cumulus(1, 0.7f)), 6, 0);
        Sample nearlyGone = sample(CloudField.of(cumulus(1, 0.95f)), 6, 0);
        System.out.printf("cumulus: mature %d points (%.2f on the surface), dying %d (%.2f), nearly gone %d%n",
                mature.inside, (double) mature.surface / mature.inside, dying.inside,
                (double) dying.surface / Math.max(1, dying.inside), nearlyGone.inside);
        assertTrue(dying.inside < 0.9 * mature.inside && dying.inside > 0.5 * mature.inside,
                "half way through dying, some is gone, most is left");
        assertTrue(late.inside < 0.65 * mature.inside, "late in dying, much is gone");
        assertTrue((double) dying.surface / dying.inside > (double) mature.surface / mature.inside,
                "what is left is ragged: more surface for its volume");
        assertTrue(nearlyGone.inside < 0.08 * mature.inside, "nearly gone at the end");
        assertNull(CloudField.of(cumulus(1, 1)), "and gone when its life is over");
    }

    @Test
    void formingAndDyingMeshesAreClosed() {
        for (float[] gd : new float[][]{{0.3f, 0}, {1, 0.6f}}) {
            List<float[]> v = new ArrayList<>();
            CloudVoxelizer.Result r = CloudVoxelizer.build(cumulus(gd[0], gd[1]), 4, 0, (x, y, z, c) -> v.add(new float[]{x, y, z}));
            assertTrue(r.quads() > 0, "growth " + gd[0] + " decay " + gd[1] + " has cloud");
            double sx = 0, sy = 0, sz = 0;
            for (int q = 0; q + 3 < v.size(); q += 4) {
                float[] a = v.get(q), b = v.get(q + 1), c = v.get(q + 2);
                double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
                double wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
                sx += uy * wz - uz * wy;
                sy += uz * wx - ux * wz;
                sz += ux * wy - uy * wx;
            }
            assertEquals(0, sx, 1e-2);
            assertEquals(0, sy, 1e-2);
            assertEquals(0, sz, 1e-2);
        }
    }

    @Test
    void aStormsAnvilOutlivesItsBody() {
        CloudField field = CloudField.of(supercell(1, 1, 0.3f));
        Supercell s = field.storm;
        CloudField.Column col = field.newColumn();
        // No body left: nothing in the updraft's column below the anvil.
        field.column(s.ux, s.uz, 0, col);
        for (double y = s.yb; y < s.yb + 0.5 * s.h; y += 16) {
            assertTrue(field.density(col, s.ux, y, s.uz, 0, false) <= 0, "no body at " + y);
        }
        // The anvil is still there downwind.
        double x = s.ax + 0.25 * s.anvilDown * s.dx, z = s.az + 0.25 * s.anvilDown * s.dz;
        field.column(x, z, 0, col);
        boolean anvil = false;
        for (double y = s.ya - 0.4 * s.h; y < s.ya + 0.05 * s.h && !anvil; y += 8) {
            anvil = field.density(col, x, y, z, 0, false) > 0;
        }
        assertTrue(anvil, "the orphan anvil remains");
        assertNull(CloudField.of(supercell(1, 1, 1)), "until it has thinned away");
    }

    @Test
    void anOrphanAnvilThins() {
        Sample early = sample(CloudField.of(supercell(1, 1, 0.1f)), 96, 0);
        Sample late = sample(CloudField.of(supercell(1, 1, 0.7f)), 96, 0);
        System.out.printf("orphan anvil: %d points early in the linger, %d late%n", early.inside, late.inside);
        assertTrue(early.inside > 0);
        assertTrue(late.inside < 0.6 * early.inside, "thinner late in the linger");
    }
}
