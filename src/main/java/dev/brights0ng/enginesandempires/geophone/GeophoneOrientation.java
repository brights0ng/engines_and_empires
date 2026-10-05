package dev.brights0ng.enginesandempires.geophone;

/**
 * The geometry of a geophone staked into a face of a block: which way the rod points, and what space it takes up.
 *
 * <p>A geophone is placed exactly where the player clicked, with its rod pointing straight out of the face that was
 * clicked: up from the top of a block, down from the underside, and sideways from a wall. Its model is drawn
 * standing upright, so the renderer tips it over to match, and the hitbox is built to match the way it points.
 * Both are worked out here, without Minecraft, so they can be tested: getting a sign wrong here would leave rods
 * pointing into the ground.
 *
 * <p>A face is described by its outward normal: a unit step along one axis, as {@code (0, 1, 0)} for a top face.
 */
public final class GeophoneOrientation {

    /**
     * How long the spike is, in blocks: eight pixels of the model. It is the part of the rod below the crossguard, and is
     * what is driven into the block. The crossguard stops it, so the spike is exactly how far a geophone slides in.
     */
    public static final double SPIKE_LENGTH = 8.0 / 16.0;

    /**
     * How far the geophone sticks out of the block once it is in, in blocks: twelve pixels of the model, from the
     * crossguard up to the tip of the sensor cap.
     */
    public static final double VISIBLE_LENGTH = 12.0 / 16.0;

    /** How wide the hitbox is around the rod, in blocks. Wider than the rod, so it is easy to aim at. */
    public static final double THICKNESS = 0.28;

    /**
     * A tilt of the upright model: turn about the x axis by {@code xDegrees}, or about the z axis by
     * {@code zDegrees}. At most one of them is not zero.
     */
    public record Tilt(double xDegrees, double zDegrees) {
    }

    /**
     * The tilt that turns an upright geophone, pointing along +y, to point along the given face normal.
     *
     * @throws IllegalArgumentException if the normal is not a unit step along one axis
     */
    public static Tilt tiltFor(int normalX, int normalY, int normalZ) {
        if (normalX == 0 && normalY == 1 && normalZ == 0) {
            return new Tilt(0, 0);
        }
        if (normalX == 0 && normalY == -1 && normalZ == 0) {
            return new Tilt(180, 0);
        }
        if (normalX == 0 && normalY == 0 && normalZ == -1) {
            return new Tilt(-90, 0);
        }
        if (normalX == 0 && normalY == 0 && normalZ == 1) {
            return new Tilt(90, 0);
        }
        if (normalX == 1 && normalY == 0 && normalZ == 0) {
            return new Tilt(0, -90);
        }
        if (normalX == -1 && normalY == 0 && normalZ == 0) {
            return new Tilt(0, 90);
        }
        throw new IllegalArgumentException("Not a face normal: (" + normalX + ", " + normalY + ", " + normalZ + ")");
    }

    /** Turns a point by a tilt: the same turn the renderer applies to the model. Right-handed, as in the game. */
    public static double[] rotate(Tilt tilt, double x, double y, double z) {
        double ax = Math.toRadians(tilt.xDegrees());
        double y1 = y * Math.cos(ax) - z * Math.sin(ax);
        double z1 = y * Math.sin(ax) + z * Math.cos(ax);
        double az = Math.toRadians(tilt.zDegrees());
        double x2 = x * Math.cos(az) - y1 * Math.sin(az);
        double y2 = x * Math.sin(az) + y1 * Math.cos(az);
        return new double[]{x2, y2, z1};
    }

    /**
     * The box a geophone occupies, as {@code {minX, minY, minZ, maxX, maxY, maxZ}}: the rod, from where it enters the
     * face out to its tip, and a little wider than the rod on the other two axes.
     *
     * @param x the point on the face where it was staked
     */
    public static double[] bounds(double x, double y, double z, int normalX, int normalY, int normalZ) {
        tiltFor(normalX, normalY, normalZ); // rejects anything that is not a face normal
        double[] centre = {x, y, z};
        int[] normal = {normalX, normalY, normalZ};
        double[] box = new double[6];
        for (int axis = 0; axis < 3; axis++) {
            double low;
            double high;
            if (normal[axis] == 0) {
                low = centre[axis] - THICKNESS / 2.0;
                high = centre[axis] + THICKNESS / 2.0;
            } else {
                double tip = centre[axis] + normal[axis] * VISIBLE_LENGTH;
                low = Math.min(centre[axis], tip);
                high = Math.max(centre[axis], tip);
            }
            box[axis] = low;
            box[axis + 3] = high;
        }
        return box;
    }

    private GeophoneOrientation() {
    }
}
