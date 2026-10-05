package dev.brights0ng.enginesandempires.oregen.shape;

/**
 * A rotated set of axes for describing a deposit: two axes lying in its plane, and a normal.
 * Immutable and thread-safe.
 *
 * <p>The first in-plane axis ("strike", axis A) is horizontal whenever the plane is not itself
 * horizontal. The second ("dip", axis B) points across the plane, and N is the normal. A rotation about
 * the normal turns A and B within the plane.
 */
public final class Orientation {

    private final double ax;
    private final double ay;
    private final double az;
    private final double bx;
    private final double by;
    private final double bz;
    private final double nx;
    private final double ny;
    private final double nz;

    private Orientation(double ax, double ay, double az, double bx, double by, double bz,
                        double nx, double ny, double nz) {
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
    }

    /** Axes for a plane with the given normal (which need not be unit length), rotated in-plane by {@code rotation} radians. */
    public static Orientation fromNormal(double x, double y, double z, double rotation) {
        double length = StrictMath.sqrt(x * x + y * y + z * z);
        double nx = x / length;
        double ny = y / length;
        double nz = z / length;

        // Strike: horizontal and perpendicular to the normal.
        double sx = -nz;
        double sz = nx;
        double sLength = StrictMath.sqrt(sx * sx + sz * sz);
        if (sLength < 1.0e-6) {
            sx = 1.0; // the normal is vertical, so any horizontal direction will do
            sz = 0.0;
            sLength = 1.0;
        }
        sx /= sLength;
        sz /= sLength;

        // Dip axis: the normal crossed with strike (strike has no y component).
        double dx = ny * sz;
        double dy = nz * sx - nx * sz;
        double dz = -ny * sx;

        double cos = StrictMath.cos(rotation);
        double sin = StrictMath.sin(rotation);
        return new Orientation(
                sx * cos + dx * sin, dy * sin, sz * cos + dz * sin,
                -sx * sin + dx * cos, dy * cos, -sz * sin + dz * cos,
                nx, ny, nz);
    }

    /**
     * Axes for a plane whose normal leans {@code tilt} radians from vertical towards compass direction
     * {@code azimuth}. Tilt 0 is a flat, horizontal plane; tilt pi/2 is a vertical plane.
     */
    public static Orientation fromTilt(double tilt, double azimuth, double rotation) {
        return fromNormal(
                StrictMath.sin(tilt) * StrictMath.cos(azimuth),
                StrictMath.cos(tilt),
                StrictMath.sin(tilt) * StrictMath.sin(azimuth),
                rotation);
    }

    /** The coordinate of a point along axis A. */
    public double a(double x, double y, double z) {
        return x * ax + y * ay + z * az;
    }

    /** The coordinate of a point along axis B. */
    public double b(double x, double y, double z) {
        return x * bx + y * by + z * bz;
    }

    /** The coordinate of a point along the normal. */
    public double n(double x, double y, double z) {
        return x * nx + y * ny + z * nz;
    }

    /** The world x of the point with these coordinates along A, B and N. */
    public double worldX(double a, double b, double n) {
        return a * ax + b * bx + n * nx;
    }

    public double worldY(double a, double b, double n) {
        return a * ay + b * by + n * ny;
    }

    public double worldZ(double a, double b, double n) {
        return a * az + b * bz + n * nz;
    }

    /**
     * How far along a world axis (0 = x, 1 = y, 2 = z) an ellipsoid with these semi-axes along A, B and
     * N extends from its centre.
     */
    public double ellipsoidReach(int axis, double semiA, double semiB, double semiN) {
        return switch (axis) {
            case 0 -> hypot(semiA * ax, semiB * bx, semiN * nx);
            case 1 -> hypot(semiA * ay, semiB * by, semiN * ny);
            default -> hypot(semiA * az, semiB * bz, semiN * nz);
        };
    }

    /**
     * How far along a world axis a cylinder extends from its centre, when its axis is the normal, its
     * half-length is {@code halfLength} and its radius is {@code radius}.
     */
    public double cylinderReach(int axis, double halfLength, double radius) {
        double n = switch (axis) {
            case 0 -> nx;
            case 1 -> ny;
            default -> nz;
        };
        return halfLength * Math.abs(n) + radius * StrictMath.sqrt(Math.max(0.0, 1.0 - n * n));
    }

    private static double hypot(double x, double y, double z) {
        return StrictMath.sqrt(x * x + y * y + z * z);
    }
}
