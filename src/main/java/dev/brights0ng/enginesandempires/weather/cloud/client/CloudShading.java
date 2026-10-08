package dev.brights0ng.enginesandempires.weather.cloud.client;

/**
 * The clouds' lighting as the shader works it out every frame (clouds.fsh; 2026-10-07 evening, Bright: no steps in
 * the light at dusk, dawn and night): a build stores per vertex the sky's light, how much of the sun's (or moon's)
 * light can reach it at most, how much gets through the cloud toward the light, and its surface direction; which side
 * is lit and the sun's contrast follow the live light. Pure Java, for the wisps (drawn on the CPU) and the tests'
 * renderer; keep in step with clouds.fsh.
 */
public final class CloudShading {

    /**
     * How far below the horizon the light (CloudLight's, its angle there exaggerated 5x) must be to count as wholly
     * from below (the afterglow): the switch to underlighting takes about 8 s of the afterglow's 19 (it was 0.05, 2 s).
     */
    static final double BELOW = 0.2;

    /**
     * {sky part, sun part} of a surface: {@code sky} its sky light, {@code sunScale} the most sunlight it can take,
     * {@code through} how much gets through the cloud toward the light (worked out for a light from above, see
     * {@link CloudLight#bakeAt}), (nx, ny, nz) its direction; the light toward (lx, ly, lz) at {@code strength}.
     */
    public static double[] parts(double sky, double sunScale, double through, double nx, double ny, double nz,
                                 double lx, double ly, double lz, double strength, double shadowSide) {
        // A light from below (the afterglow) reaches the underside with nothing in its way.
        double below = Math.max(0, Math.min(1, -ly / BELOW));
        double t = through + (1 - through) * below;
        double facing = nx * lx + ny * ly + nz * lz;
        double direct = Math.max(0, Math.min(1, t * (facing + CloudVoxelizer.WRAP) / (1 + CloudVoxelizer.WRAP)));
        // The sun's contrast acts on what faces sideways or up; a base facing down keeps the sky light alone, unless
        // the light is from below.
        double up = Math.max(-1, Math.min(1, ly / BELOW));
        double contrast = (1 - shadowSide) * Math.max(0, Math.min(1, 1 + ny * up));
        return new double[]{sky * (1 - contrast * strength), sunScale * contrast * direct * strength};
    }

    /** As {@link #parts} for a packed vertex colour ({@link CloudVoxelizer#pack}) and normal. */
    public static double[] parts(int argb, double nx, double ny, double nz, double lx, double ly, double lz,
                                 double strength, double shadowSide) {
        return parts(((argb >> 16) & 255) / 255.0, ((argb >> 8) & 255) / 255.0, (argb & 255) / 255.0, nx, ny, nz, lx,
                ly, lz, strength, shadowSide);
    }

    private CloudShading() {
    }
}
