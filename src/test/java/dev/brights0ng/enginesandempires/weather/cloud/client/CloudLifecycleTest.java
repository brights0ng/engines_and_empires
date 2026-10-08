package dev.brights0ng.enginesandempires.weather.cloud.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.CloudType;

/** Birth and death as the renderer draws them (CloudField's erosion and a storm's lingering anvil). */
class CloudLifecycleTest {

    static {
        CloudVoxelizer.seamOverlap = 0;
    }

    private static final UUID REGION = new UUID(0xC10DL, 0x11FEL);
    private static final UUID STORM = new UUID(0x5EC0L, 0xCE11L);

    // ---- shapes --------------------------------------------------------------------------------------------------

    private static CloudShape cumulus(int i, double x, double z, float growth, float decay) {
        CloudScale.Heights h = CloudScale.heights(CloudType.CUMULUS_MEDIOCRIS, REGION, growth);
        return new CloudShape(new UUID(1, i), REGION, "minecraft:overworld", x, z, 0.1, 0, 0,
                140, (float) h.base(), (float) h.top(), 0.8f, 0.85f, 0.5f, growth, decay, decay, "cumulus_mediocris",
                0.4f, 0, 0.3f, 0.18f, 0, 0, 900 + i);
    }

    /** A three-dome cumulus mediocris. */
    private static CloudFormation cumulus(float growth, float decay) {
        List<CloudShape> c = List.of(cumulus(1, 0, 0, growth, decay), cumulus(2, 150, 62, growth, decay),
                cumulus(3, -112, 100, growth, decay));
        return CloudFormation.of(REGION, c);
    }

    /** A cumulonimbus capillatus: a main tower and two flanking ones, with an anvil. */
    private static CloudFormation storm(float growth, float decay, float anvilDecay) {
        List<CloudShape> domes = new ArrayList<>();
        double[][] at = {{0, 0, 520}, {-460, 260, 340}, {-420, -300, 320}};
        for (int i = 0; i < at.length; i++) {
            CloudScale.Heights h = CloudScale.heights(CloudType.CUMULONIMBUS_CAPILLATUS, STORM, growth);
            domes.add(new CloudShape(new UUID(2, i), STORM, "minecraft:overworld", at[i][0], at[i][1], 0.1, 0, 0,
                    (float) at[i][2], (float) h.base(), (float) h.top(), 0.95f, 0.95f, 0.3f, growth, decay, anvilDecay,
                    "cumulonimbus_capillatus", i == 0 ? 0.9f : 0.6f, 0.8f, 0.7f, 0.6f, 0, 0, 1234 + i));
        }
        return CloudFormation.of(STORM, domes);
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
        double anvil = sample(CloudField.of(storm(1, 1, 0)), 48, 0).inside;
        double whole = sample(CloudField.of(storm(1, 0, 0)), 48, 0).inside;
        StringBuilder sa = new StringBuilder(String.format("sweep cumulonimbus: anvil alone %.2f of the storm; linger:",
                anvil / whole));
        for (float a : new float[]{0.1f, 0.3f, 0.5f, 0.7f, 0.85f, 0.95f}) {
            CloudField f = CloudField.of(storm(1, 1, a));
            sa.append(String.format(" a%.2f=%.2f", a, f == null ? 0 : sample(f, 48, 0).inside / anvil));
        }
        System.out.println(sa);
    }

    /** A cloud starts from almost nothing, however big it will be (Bright, 2026-10-04). */
    @Test
    void bigCloudsStartSmall() {
        double whole = sample(CloudField.of(storm(1, 0, 0)), 32, 0).inside;
        CloudField newborn = CloudField.of(storm(0.03f, 0, 0));
        double first = newborn == null ? 0 : sample(newborn, 32, 0).inside;
        assertTrue(first < 0.02 * whole, "a newborn storm is a few fragments: " + first + " of " + whole);
        double cumulus = sample(CloudField.of(cumulus(1, 0)), 6, 0).inside;
        CloudField young = CloudField.of(cumulus(0.03f, 0));
        double youngCount = young == null ? 0 : sample(young, 6, 0).inside;
        assertTrue(youngCount < 0.01 * cumulus, "a newborn cumulus too: " + youngCount + " of " + cumulus);
    }

    /** Young clouds are white; their grey comes in as they thicken (shading by the cloud above). */
    @Test
    void formingCloudsDarkenAsTheyGrow() {
        CloudField young = CloudField.of(storm(0.3f, 0, 0));
        CloudField grown = CloudField.of(storm(1, 0, 0));
        double y = grown.baseY + 0.02 * (grown.topY - grown.baseY);
        double youngAbove = young.profile(0, 0, 0, 16, young.newColumn()).depthAbove(y);
        double grownAbove = grown.profile(0, 0, 0, 16, grown.newColumn()).depthAbove(y);
        double youngBase = CloudVoxelizer.brightness(youngAbove, young.water);
        double grownBase = CloudVoxelizer.brightness(grownAbove, grown.water);
        System.out.printf("cumulonimbus base brightness: forming %.2f (%.0f blocks above), grown %.2f (%.0f above)%n",
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
        CloudField barelyField = CloudField.of(cumulus(0.08f, 0));
        int barely = barelyField == null ? 0 : sample(barelyField, 6, 0).inside;
        assertTrue(barely < 0.1 * mature.inside, "at first only fragments: " + barely);
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
        CloudField field = CloudField.of(storm(1, 1, 0.3f));
        CloudField.Anvil a = field.anvil;
        CloudField.Column col = field.newColumn();
        // No body left: nothing in the main tower's lower half.
        field.column(0, 0, 0, col);
        double mid = field.baseY + 0.4 * (a.top - field.baseY);
        for (double y = field.baseY; y < mid; y += 16) {
            assertTrue(field.density(col, 0, y, 0, 0, false) <= 0, "no body at " + y);
        }
        // The anvil is still there downwind.
        double x = a.ux + 0.35 * a.downwind * field.driftX, z = a.uz + 0.35 * a.downwind * field.driftZ;
        field.column(x, z, 0, col);
        boolean anvil = false;
        for (double y = a.top - a.thick * 1.5; y < a.top + 8 && !anvil; y += 4) {
            anvil = field.density(col, x, y, z, 0, false) > 0;
        }
        assertTrue(anvil, "the orphan anvil remains");
        assertNull(CloudField.of(storm(1, 1, 1)), "until it has thinned away");
    }

    @Test
    void anOrphanAnvilThins() {
        Sample early = sample(CloudField.of(storm(1, 1, 0.1f)), 48, 0);
        Sample late = sample(CloudField.of(storm(1, 1, 0.7f)), 48, 0);
        System.out.printf("orphan anvil: %d points early in the linger, %d late%n", early.inside, late.inside);
        assertTrue(early.inside > 0);
        assertTrue(late.inside < 0.75 * early.inside, "thinner late in the linger");
    }
}
