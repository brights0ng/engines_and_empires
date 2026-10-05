package dev.brights0ng.enginesandempires.oregen.shape;

import dev.brights0ng.enginesandempires.oregen.DepositRandom;
import dev.brights0ng.enginesandempires.oregen.Hashing;

/**
 * A seam: a thin, wide, bedded sheet that you follow rather than mine out, like a coal seam. Its
 * thickness pinches and swells, it undulates, and it may be one seam or two or three stacked with rock
 * between them.
 *
 * @param minDensity        leanest a seam can be: the smallest fraction of its slab that is ore
 * @param maxDensity        richest a seam can be
 * @param minPairThickness  thinnest a seam-plus-rock pair can be, in blocks
 * @param maxPairThickness  thickest a seam-plus-rock pair can be, in blocks
 * @param maxSeams          most seams that may be stacked in one deposit
 * @param maxElongation     how much longer a seam may be along one direction than the other, as a ratio
 * @param maxTiltDegrees    how far it may tilt from horizontal
 */
public record SeamShape(double minDensity, double maxDensity, double minPairThickness, double maxPairThickness,
                        int maxSeams, double maxElongation, double maxTiltDegrees) implements BodyShape {

    /** Coal: thick, dense, often stacked. */
    public static final SeamShape COAL = new SeamShape(0.45, 0.75, 3.5, 6.0, 3, 1.8, 22.0);

    /** Lapis: thinner and leaner, more like a flat lens. */
    public static final SeamShape LAPIS = new SeamShape(0.30, 0.50, 2.5, 4.0, 2, 2.0, 25.0);

    private static final Plate.Style STYLE = new Plate.Style(1.6, 0.25, 0.40);
    private static final double LAYER_FLOOR = 0.08;
    private static final double JITTER = 0.25;

    public SeamShape {
        if (!(minDensity > 0.0) || maxDensity < minDensity || maxDensity > 1.0) {
            throw new IllegalArgumentException("need 0 < minDensity <= maxDensity <= 1");
        }
        if (!(minPairThickness > 1.0) || maxPairThickness < minPairThickness) {
            throw new IllegalArgumentException("need 1 < minPairThickness <= maxPairThickness");
        }
        if (maxSeams < 1 || maxElongation < 1.0) {
            throw new IllegalArgumentException("need maxSeams >= 1 and maxElongation >= 1");
        }
        if (maxTiltDegrees < 0.0 || maxTiltDegrees > 60.0) {
            throw new IllegalArgumentException("maxTiltDegrees must be between 0 and 60");
        }
    }

    @Override
    public String name() {
        return "seam";
    }

    @Override
    public ShapeField create(ShapeRequest request) {
        DepositRandom rng = request.rng();
        double density = rng.range(minDensity, maxDensity);
        double pair = rng.range(minPairThickness, maxPairThickness);
        double roll = rng.nextDouble();
        int seams = Math.min(maxSeams, roll < 0.5 ? 1 : roll < 0.85 ? 2 : 3);
        double elongation = rng.range(1.0, maxElongation);

        double rc = pair * seams / 2.0;
        double footprint = request.wantedOre() / density;
        double ra = StrictMath.sqrt(3.0 * footprint * elongation / (4.0 * StrictMath.PI * rc));
        double rb = ra / elongation;

        double tilt = rng.range(0.0, StrictMath.toRadians(maxTiltDegrees));
        double azimuth = rng.range(0.0, 2.0 * StrictMath.PI);
        double rotation = rng.range(0.0, 2.0 * StrictMath.PI);
        double phase = rng.range(0.0, 1.0);
        long noiseSeed = rng.nextLong();

        Plate plate = new Plate(0.0, 0.0, 0.0, Orientation.fromTilt(tilt, azimuth, rotation), ra, rb, rc, STYLE, noiseSeed);
        return new Field(plate, pair, phase, noiseSeed);
    }

    @Override
    public int maxReach(int maxOre, int referenceOre) {
        double footprint = maxOre / minDensity;
        double thinnest = minPairThickness / 2.0;
        double longest = StrictMath.sqrt(3.0 * footprint * maxElongation / (4.0 * StrictMath.PI * thinnest));
        double thickest = maxPairThickness * maxSeams / 2.0;
        return (int) Math.ceil(longest * (1.0 + STYLE.edgeAmplitude())
                + thickest * (1.0 + STYLE.swellAmplitude()) + STYLE.waveAmplitude()) + 1;
    }

    private static final class Field implements ShapeField {

        private final Plate plate;
        private final double period;
        private final double phase;
        private final long seed;
        private final int reachX;
        private final int reachY;
        private final int reachZ;

        Field(Plate plate, double period, double phase, long seed) {
            this.plate = plate;
            this.period = period;
            this.phase = phase;
            this.seed = seed;
            this.reachX = (int) Math.ceil(plate.reach(0)) + 1;
            this.reachY = (int) Math.ceil(plate.reach(1)) + 1;
            this.reachZ = (int) Math.ceil(plate.reach(2)) + 1;
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
            double envelope = plate.envelope(dx, dy, dz);
            if (envelope <= 0.0) {
                out.outside();
                return;
            }
            double richness = StrictMath.sqrt(envelope) * structure(dx, dy, dz);
            double jitter = (Hashing.unit(Hashing.cell(seed, dx, dy, dz)) - 0.5) * JITTER;
            out.candidate(richness + jitter);
        }

        /** Alternating seams and rock across the slab, following its undulation. */
        @Override
        public double structure(int dx, int dy, int dz) {
            double across = plate.acrossFromMidsurface(dx, dy, dz);
            double band = 0.5 + 0.5 * StrictMath.cos(2.0 * StrictMath.PI * (across / period + phase));
            double smooth = band * band * (3.0 - 2.0 * band);
            return LAYER_FLOOR + (1.0 - LAYER_FLOOR) * smooth;
        }
    }
}
