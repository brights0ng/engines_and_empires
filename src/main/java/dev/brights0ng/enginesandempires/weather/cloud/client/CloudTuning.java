package dev.brights0ng.enginesandempires.weather.cloud.client;

/**
 * The cloud renderer's tunable numbers, as plain fields so the shape code (pure Java, also run in tests) can read
 * them without the config system. {@link CloudConfig} copies the client config into these on load and whenever the
 * file changes; tests see the defaults.
 *
 * <p>Shape values are multipliers (1 = as designed). They only change how clouds look on this client; rain and
 * lightning will follow the shared heights and footprints ({@code CloudScale}), not these.
 */
public final class CloudTuning {

    // ---- LOD and rebuilding
    /** Section side, blocks (256, 512 or 1024). */
    public static volatile int sectionSize = 512;
    /** Distances (blocks, 3D) where the voxel size doubles: 2x past the first, 4x past the second, and so on. */
    public static volatile double[] lodDistances = {384, 1024, 2048, 4096};
    /** The largest voxel size, blocks (a power of two, 8 to 128). */
    public static volatile int maxVoxel = 64;
    /** Background CPU for rebuilding storms, in cores. */
    public static volatile double rebuildBudget = 0.5;

    // ---- noise
    /** Size of the churning features, times the cloud's own scale. */
    public static volatile double noiseScale = 1.0;
    /** Strength of the churning features. */
    public static volatile double noiseStrength = 1.0;
    /** How smoothly a cumulus's bubbles blend into each other (1 = the first bubble model; higher fills creases). */
    public static volatile double bubbleSmoothing = 1.6;

    // ---- shading (see CloudVoxelizer#brightness)
    /** Multiplier on every cloud type's water content: higher makes thick clouds darker. */
    public static volatile double waterContent = 1.0;
    /** The darkest a cloud's underside gets (0-1): light from the sky around it reaches even a storm's base. */
    public static volatile double baseLight = 0.22;
    /** How quickly clouds darken with depth: lower keeps more of the grey for the very thickest clouds. */
    public static volatile double shadeContrast = 0.35;
    /** Cumulus: how bright the side away from the sun is, as a share of the sunlit side. */
    public static volatile double shadowSide = 0.65;
    /** Cumulus: how strongly creases between bubbles darken. */
    public static volatile double creases = 0.35;
    /** Cumulus: how strongly thin edges glow when you look toward the sun (the silver lining). */
    public static volatile double silverLining = 0.9;
    /** How strongly clouds shade the clouds below them (0 = not at all, 1 = realistic). */
    public static volatile double cloudShadows = 1.0;

    // ---- wisps (CloudWisps)
    public static volatile boolean wisps = true;
    /** How far away (blocks, to the cloud's edge) cumulus get wisps. */
    public static volatile double wispDistance = 300;
    /** Multiplier on how many wisps a cloud has. */
    public static volatile double wispDensity = 1.0;
    /** Multiplier on the wisps' opacity. */
    public static volatile double wispOpacity = 1.0;

    // ---- veils (CloudVeilRenderer: cirrostratus and cirrus)
    /** Multiplier on the see-through high clouds' opacity (0 hides them). */
    public static volatile double veilOpacity = 1.0;

    /** Bumped whenever any value changes, so every storm is rebuilt with the new ones. */
    public static volatile int version;

    /** The voxel size for this many blocks from the camera, given the configured size near it. */
    static int voxelSizeAt(double distance, int nearVoxel) {
        int size = nearVoxel;
        for (double d : lodDistances) {
            if (distance >= d) {
                size *= 2;
            }
        }
        return Math.min(size, Math.max(nearVoxel, maxVoxel));
    }

    private CloudTuning() {
    }
}
