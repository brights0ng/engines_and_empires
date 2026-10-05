package dev.brights0ng.enginesandempires.oregen.shape;

import dev.brights0ng.enginesandempires.oregen.DepositRandom;
import dev.brights0ng.enginesandempires.oregen.Hashing;
import dev.brights0ng.enginesandempires.oregen.Noise3D;

/**
 * A disseminated body: a large upright cloud of sparse, scattered ore, richest in a zone inside it.
 * Copper uses it as a wide, low-grade body with a rich ring, like a porphyry. Diamond uses it as a tall,
 * narrow pipe that widens upwards, like a kimberlite.
 *
 * @param minDensity      leanest a body can be: the smallest fraction of its footprint that is ore
 * @param maxDensity      richest a body can be. Both are small, because the ore is sparse
 * @param minAspect       shortest a body can be, as a ratio of its height to its width
 * @param maxAspect       tallest a body can be, as a ratio of its height to its width
 * @param taper           how much wider the top is than the middle, and the bottom narrower, from 0
 *                        (straight-sided) to 0.6
 * @param ringWeight      how much the richest zone is a ring around a leaner core (1) rather than the core
 *                        itself (0)
 * @param maxTiltDegrees  how far the body's axis may lean from vertical
 */
public record DisseminatedShape(double minDensity, double maxDensity, double minAspect, double maxAspect,
                                double taper, double ringWeight, double maxTiltDegrees) implements BodyShape {

    /** Copper: wide, roughly as tall as it is wide, with a rich ring. */
    public static final DisseminatedShape COPPER = new DisseminatedShape(0.04, 0.09, 0.7, 1.3, 0.15, 0.7, 8.0);

    /** Diamond: a tall, narrow pipe, wider at the top, richest in the core. */
    public static final DisseminatedShape DIAMOND = new DisseminatedShape(0.06, 0.12, 3.5, 6.0, 0.45, 0.0, 12.0);

    /** How far noise may push the body's edge, as a fraction of its base radius. */
    private static final double WARP_FRACTION = 0.25;
    private static final double ZONE_WEIGHT = 0.5;  // how much the rich zone shapes ore placement
    private static final double SCATTER = 0.8;      // how much random scatter shapes it

    /** Volume of the footprint per (pi * r0^3 * aspect), for a straight-sided body with rounded ends. */
    private static final double BASE_VOLUME = 1.6;
    private static final double TAPER_VOLUME = 0.381;

    public DisseminatedShape {
        if (!(minDensity > 0.0) || maxDensity < minDensity || maxDensity > 0.5) {
            throw new IllegalArgumentException("need 0 < minDensity <= maxDensity <= 0.5");
        }
        if (!(minAspect > 0.0) || maxAspect < minAspect) {
            throw new IllegalArgumentException("need 0 < minAspect <= maxAspect");
        }
        if (taper < 0.0 || taper > 0.6 || ringWeight < 0.0 || ringWeight > 1.0) {
            throw new IllegalArgumentException("need 0 <= taper <= 0.6 and 0 <= ringWeight <= 1");
        }
        if (maxTiltDegrees < 0.0 || maxTiltDegrees > 45.0) {
            throw new IllegalArgumentException("maxTiltDegrees must be between 0 and 45");
        }
    }

    @Override
    public String name() {
        return "disseminated";
    }

    @Override
    public ShapeField create(ShapeRequest request) {
        DepositRandom rng = request.rng();
        double density = rng.range(minDensity, maxDensity);
        double aspect = rng.range(minAspect, maxAspect);

        double r0 = baseRadius(request.wantedOre() / density, aspect);
        double halfHeight = aspect * r0;

        double tilt = rng.range(0.0, StrictMath.toRadians(maxTiltDegrees));
        double azimuth = rng.range(0.0, 2.0 * StrictMath.PI);
        double rotation = rng.range(0.0, 2.0 * StrictMath.PI);
        Orientation frame = Orientation.fromTilt(tilt, azimuth, rotation);
        long noiseSeed = rng.nextLong();
        return new Field(frame, r0, halfHeight, taper, ringWeight, noiseSeed);
    }

    private double baseRadius(double footprint, double aspect) {
        return StrictMath.cbrt(footprint / (StrictMath.PI * aspect * (BASE_VOLUME + TAPER_VOLUME * taper * taper)));
    }

    @Override
    public int maxReach(int maxOre, int referenceOre) {
        double footprint = maxOre / minDensity;
        double tilt = StrictMath.sin(StrictMath.toRadians(maxTiltDegrees));
        double most = 0.0;
        for (double aspect : new double[]{minAspect, maxAspect}) {
            double r0 = baseRadius(footprint, aspect);
            double top = r0 * (1.0 + taper);
            most = Math.max(most, aspect * r0 * tilt + top + WARP_FRACTION * r0);
        }
        return (int) Math.ceil(most) + 2;
    }

    private static final class Field implements ShapeField {

        private final Orientation frame;
        private final double r0;
        private final double halfHeight;
        private final double taper;
        private final double ringWeight;
        private final long seed;
        private final double warpAmplitude;
        private final double warpWavelength;
        private final Noise3D warpX;
        private final Noise3D warpY;
        private final Noise3D warpZ;
        private final int reachX;
        private final int reachY;
        private final int reachZ;

        Field(Orientation frame, double r0, double halfHeight, double taper, double ringWeight, long noiseSeed) {
            this.frame = frame;
            this.r0 = r0;
            this.halfHeight = halfHeight;
            this.taper = taper;
            this.ringWeight = ringWeight;
            this.seed = noiseSeed;
            this.warpAmplitude = WARP_FRACTION * r0;
            this.warpWavelength = Math.max(4.0, 1.2 * r0);
            this.warpX = new Noise3D(noiseSeed ^ 0x1L);
            this.warpY = new Noise3D(noiseSeed ^ 0x2L);
            this.warpZ = new Noise3D(noiseSeed ^ 0x3L);
            this.reachX = reach(0);
            this.reachY = reach(1);
            this.reachZ = reach(2);
        }

        private int reach(int axis) {
            return (int) Math.ceil(frame.cylinderReach(axis, halfHeight, r0 * (1.0 + taper)) + warpAmplitude) + 1;
        }

        @Override
        public int reachX() {
            return reachX;
        }

        @Override
        public int reachY() {
            return reachY;
        }

        @Override
        public int reachZ() {
            return reachZ;
        }

        @Override
        public void sample(int dx, int dy, int dz, CellSample out) {
            double zone = zone(dx, dy, dz);
            if (Double.isNaN(zone)) {
                out.outside();
                return;
            }
            double scatter = (Hashing.unit(Hashing.cell(seed, dx, dy, dz)) - 0.5) * SCATTER;
            out.candidate(ZONE_WEIGHT * zone + scatter);
        }

        @Override
        public double structure(int dx, int dy, int dz) {
            double zone = zone(dx, dy, dz);
            return Double.isNaN(zone) ? 0.0 : zone;
        }

        /** How rich this spot's zone is, from 0 to 1, or NaN if the spot is outside the body. */
        private double zone(double dx, double dy, double dz) {
            // Warping moves a point by at most sqrt(3) * warpAmplitude, so anything further out than that is outside.
            double slack = 1.7321 * warpAmplitude;
            double height0 = frame.n(dx, dy, dz);
            if (Math.abs(height0) > halfHeight + slack) {
                return Double.NaN;
            }
            double radial0 = StrictMath.sqrt(Math.max(0.0, dx * dx + dy * dy + dz * dz - height0 * height0));
            if (radial0 > r0 * (1.0 + taper) + slack) {
                return Double.NaN;
            }

            double wx = warpX.noise(dx / warpWavelength, dy / warpWavelength, dz / warpWavelength);
            double wy = warpY.noise(dx / warpWavelength, dy / warpWavelength, dz / warpWavelength);
            double wz = warpZ.noise(dx / warpWavelength, dy / warpWavelength, dz / warpWavelength);
            double qx = dx + warpAmplitude * wx;
            double qy = dy + warpAmplitude * wy;
            double qz = dz + warpAmplitude * wz;

            double u = frame.n(qx, qy, qz) / halfHeight;
            if (Math.abs(u) >= 1.0) {
                return Double.NaN;
            }
            double a = frame.a(qx, qy, qz);
            double b = frame.b(qx, qy, qz);
            double radius = r0 * (1.0 + taper * u) * StrictMath.sqrt(1.0 - u * u * u * u); // rounded ends
            double rho = StrictMath.sqrt(a * a + b * b) / radius;
            if (rho >= 1.0) {
                return Double.NaN;
            }
            double core = 1.0 - rho * rho;
            double ring = StrictMath.exp(-((rho - 0.55) / 0.28) * ((rho - 0.55) / 0.28));
            return (1.0 - ringWeight) * core + ringWeight * ring;
        }
    }
}
