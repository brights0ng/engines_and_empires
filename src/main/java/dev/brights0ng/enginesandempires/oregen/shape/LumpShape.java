package dev.brights0ng.enginesandempires.oregen.shape;

import dev.brights0ng.enginesandempires.oregen.DepositRandom;
import dev.brights0ng.enginesandempires.oregen.Hashing;
import dev.brights0ng.enginesandempires.oregen.Noise3D;

/**
 * A stratified lump: a compact body of layered ore, like a small piece of banded iron formation.
 *
 * <p>Its footprint is a warped, slightly flattened ellipsoid. Inside it, alternating ore-rich and
 * ore-poor layers run across a gently wavy set of parallel beds, tilted a little from horizontal, so
 * the ore comes out in bands.
 *
 * @param minDensity     leanest a lump can be: the smallest fraction of its footprint that is ore
 * @param maxDensity     richest a lump can be
 * @param maxFlatten     how flat a lump may be along its bedding, as a ratio of width to thickness
 * @param maxElongation  how much a lump may be stretched along one horizontal direction, as a ratio
 * @param maxTiltDegrees how far its layers may tilt from horizontal
 * @param minPeriod      thinnest a rich-plus-poor layer pair can be, in blocks
 * @param maxPeriod      thickest a rich-plus-poor layer pair can be, in blocks
 */
public record LumpShape(double minDensity, double maxDensity, double maxFlatten, double maxElongation,
                        double maxTiltDegrees, double minPeriod, double maxPeriod) implements BodyShape {

    /** Iron and zinc. */
    public static final LumpShape DEFAULT = new LumpShape(0.25, 0.55, 1.8, 1.5, 20.0, 3.0, 6.0);

    /** How far noise may push the footprint's edge, as a fraction of its longest semi-axis. */
    private static final double WARP_FRACTION = 0.30;
    private static final double BED_WARP = 1.2;        // blocks of wobble in the layers
    private static final double BED_WAVELENGTH = 9.0;  // blocks
    private static final double LAYER_FLOOR = 0.12;    // how much ore the poor layers still get
    private static final double JITTER = 0.35;         // random scatter added to each block's score

    public LumpShape {
        if (!(minDensity > 0.0) || maxDensity < minDensity || maxDensity > 1.0) {
            throw new IllegalArgumentException("need 0 < minDensity <= maxDensity <= 1");
        }
        if (maxFlatten < 1.0 || maxElongation < 1.0) {
            throw new IllegalArgumentException("maxFlatten and maxElongation must be at least 1");
        }
        if (maxTiltDegrees < 0.0 || maxTiltDegrees > 60.0) {
            throw new IllegalArgumentException("maxTiltDegrees must be between 0 and 60");
        }
        if (!(minPeriod > 1.0) || maxPeriod < minPeriod) {
            throw new IllegalArgumentException("need 1 < minPeriod <= maxPeriod");
        }
    }

    @Override
    public String name() {
        return "lump";
    }

    @Override
    public ShapeField create(ShapeRequest request) {
        DepositRandom rng = request.rng();
        double density = rng.range(minDensity, maxDensity);
        double flatten = rng.range(1.0, maxFlatten);
        double elongation = rng.range(1.0, maxElongation);

        double footprint = request.wantedOre() / density;
        double r = StrictMath.cbrt(3.0 * footprint * flatten / (4.0 * StrictMath.PI));
        double semiA = r * StrictMath.sqrt(elongation);
        double semiB = r / StrictMath.sqrt(elongation);
        double semiC = r / flatten;

        double tilt = rng.range(0.0, StrictMath.toRadians(maxTiltDegrees));
        double azimuth = rng.range(0.0, 2.0 * StrictMath.PI);
        double rotation = rng.range(0.0, 2.0 * StrictMath.PI);
        Orientation frame = Orientation.fromTilt(tilt, azimuth, rotation);

        double period = rng.range(minPeriod, maxPeriod);
        double phase = rng.range(0.0, 1.0);
        long noiseSeed = rng.nextLong();
        return new Field(frame, semiA, semiB, semiC, period, phase, noiseSeed);
    }

    @Override
    public int maxReach(int maxOre, int referenceOre) {
        double r = StrictMath.cbrt(3.0 * (maxOre / minDensity) * maxFlatten / (4.0 * StrictMath.PI));
        double longestSemiAxis = r * StrictMath.sqrt(maxElongation);
        return (int) Math.ceil(longestSemiAxis * (1.0 + WARP_FRACTION)) + 1;
    }

    private static final class Field implements ShapeField {

        private final Orientation frame;
        private final double semiA;
        private final double semiB;
        private final double semiC;
        private final double period;
        private final double phase;
        private final long seed;
        private final double warpWavelength;
        private final double warpAmplitude;
        private final Noise3D warpX;
        private final Noise3D warpY;
        private final Noise3D warpZ;
        private final Noise3D bedNoise;
        private final int reachX;
        private final int reachY;
        private final int reachZ;

        Field(Orientation frame, double semiA, double semiB, double semiC,
              double period, double phase, long noiseSeed) {
            this.frame = frame;
            this.semiA = semiA;
            this.semiB = semiB;
            this.semiC = semiC;
            this.period = period;
            this.phase = phase;
            this.seed = noiseSeed;
            double meanRadius = StrictMath.cbrt(semiA * semiB * semiC);
            this.warpWavelength = Math.max(4.0, 0.9 * meanRadius);
            this.warpAmplitude = WARP_FRACTION * semiA;
            this.warpX = new Noise3D(noiseSeed ^ 0x1L);
            this.warpY = new Noise3D(noiseSeed ^ 0x2L);
            this.warpZ = new Noise3D(noiseSeed ^ 0x3L);
            this.bedNoise = new Noise3D(noiseSeed ^ 0x4L);
            this.reachX = reach(0);
            this.reachY = reach(1);
            this.reachZ = reach(2);
        }

        private int reach(int axis) {
            return (int) Math.ceil(frame.ellipsoidReach(axis, semiA, semiB, semiC) + warpAmplitude) + 1;
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
            double envelope = envelope(dx, dy, dz);
            if (envelope <= 0.0) {
                out.outside();
                return;
            }
            double richness = StrictMath.sqrt(envelope) * structure(dx, dy, dz);
            double jitter = (Hashing.unit(Hashing.cell(seed, dx, dy, dz)) - 0.5) * JITTER;
            out.candidate(richness + jitter);
        }

        /** 1 at the centre, falling to 0 at the (noise-warped) edge of the footprint; 0 or less outside. */
        private double envelope(double dx, double dy, double dz) {
            // Warping moves a point by at most sqrt(3) * warpAmplitude, so anything further out than that is outside.
            double slack = 1.7321 * warpAmplitude;
            if (Math.abs(frame.a(dx, dy, dz)) > semiA + slack
                    || Math.abs(frame.b(dx, dy, dz)) > semiB + slack
                    || Math.abs(frame.n(dx, dy, dz)) > semiC + slack) {
                return -1.0;
            }
            double wx = warpX.noise(dx / warpWavelength, dy / warpWavelength, dz / warpWavelength);
            double wy = warpY.noise(dx / warpWavelength, dy / warpWavelength, dz / warpWavelength);
            double wz = warpZ.noise(dx / warpWavelength, dy / warpWavelength, dz / warpWavelength);
            double qx = dx + warpAmplitude * wx;
            double qy = dy + warpAmplitude * wy;
            double qz = dz + warpAmplitude * wz;
            double a = frame.a(qx, qy, qz) / semiA;
            double b = frame.b(qx, qy, qz) / semiB;
            double c = frame.n(qx, qy, qz) / semiC;
            return 1.0 - (a * a + b * b + c * c);
        }

        /** Alternating rich and poor layers across the bedding normal, wobbling gently. */
        @Override
        public double structure(int dx, int dy, int dz) {
            double across = frame.n(dx, dy, dz)
                    + BED_WARP * bedNoise.noise(dx / BED_WAVELENGTH, dy / BED_WAVELENGTH, dz / BED_WAVELENGTH);
            double band = 0.5 + 0.5 * StrictMath.cos(2.0 * StrictMath.PI * (across / period + phase));
            double smooth = band * band * (3.0 - 2.0 * band);
            return LAYER_FLOOR + (1.0 - LAYER_FLOOR) * smooth;
        }
    }
}
