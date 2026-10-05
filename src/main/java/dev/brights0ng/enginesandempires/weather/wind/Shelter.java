package dev.brights0ng.enginesandempires.weather.wind;

/**
 * How exposed a physics object is, from how much of its top and sides is covered (Bright, 2026-10-01).
 *
 * <p>A wall shelters more the more squarely it faces into the wind: each side's coverage is weighted by how far its
 * outward face points upwind, and a side facing downwind doesn't count at all. A roof gives a smaller, separate cut
 * ({@link WindParams#roofWeight()}), since sideways wind blows under an open roof. Full cover on the upwind sides makes
 * the object immune; so does full cover on all five.
 */
public final class Shelter {

    /** Faces, in the order coverage arrays use. */
    public static final int TOP = 0;
    public static final int NORTH = 1;
    public static final int SOUTH = 2;
    public static final int WEST = 3;
    public static final int EAST = 4;
    public static final int FACES = 5;

    /**
     * @param coverage   per face (see the constants), the share of its probes that met a block, 0 to 1
     * @param windX      the wind's direction of travel, x (any length)
     * @param windZ      the wind's direction of travel, z
     * @param roofWeight {@link WindParams#roofWeight()}
     * @return 0 (sheltered) to 1 (open air)
     */
    public static double exposure(double[] coverage, double windX, double windZ, double roofWeight) {
        // A face is upwind when it points against the wind's travel: the north face (outward -Z) for wind going +Z.
        double north = Math.max(0, windZ);
        double south = Math.max(0, -windZ);
        double west = Math.max(0, windX);
        double east = Math.max(0, -windX);
        double weights = north + south + west + east;
        double sides;
        if (weights < 1e-12) {
            sides = (coverage[NORTH] + coverage[SOUTH] + coverage[WEST] + coverage[EAST]) / 4;
        } else {
            sides = (north * coverage[NORTH] + south * coverage[SOUTH] + west * coverage[WEST] + east * coverage[EAST])
                    / weights;
        }
        double roof = Math.min(1, Math.max(0, roofWeight)) * coverage[TOP];
        return clamp01((1 - clamp01(sides)) * (1 - roof));
    }

    private static double clamp01(double value) {
        return Math.min(1, Math.max(0, value));
    }

    private Shelter() {
    }
}
