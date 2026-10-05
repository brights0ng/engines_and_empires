package dev.brights0ng.enginesandempires.oregen.shape;

import dev.brights0ng.enginesandempires.oregen.DepositRandom;
import dev.brights0ng.enginesandempires.oregen.Hashing;
import dev.brights0ng.enginesandempires.oregen.Noise3D;

/**
 * A vein: a steep, thin, wavy sheet with rich shoots plunging along it, like an orogenic gold lode. You
 * find it, then follow the shoots. A deposit may be one vein or a small swarm of two or three slightly
 * splayed ones.
 *
 * @param minDensity       leanest a vein can be: the smallest fraction of its footprint that is ore
 * @param maxDensity       richest a vein can be
 * @param minThickness     thinnest a vein can be, in blocks
 * @param maxThickness     thickest a vein can be, in blocks
 * @param maxElongation    how much longer a vein may be along strike than down its dip, as a ratio
 * @param maxPlates        most veins in one swarm
 * @param minDipDegrees    shallowest dip a vein may have, measured from horizontal (90 is vertical)
 * @param maxSplayDegrees  how far the veins of a swarm may diverge from each other
 */
public record VeinShape(double minDensity, double maxDensity, double minThickness, double maxThickness,
                        double maxElongation, int maxPlates, double minDipDegrees, double maxSplayDegrees)
        implements BodyShape {

    /** Gold: thin, lean, with rich shoots. */
    public static final VeinShape GOLD = new VeinShape(0.35, 0.55, 1.4, 2.8, 2.2, 3, 65.0, 12.0);

    /** Redstone: fatter and denser. */
    public static final VeinShape REDSTONE = new VeinShape(0.50, 0.75, 2.4, 4.0, 1.6, 2, 60.0, 12.0);

    private static final Plate.Style STYLE = new Plate.Style(1.4, 0.30, 0.35);
    private static final double SHOOT_FLOOR = 0.30; // how much ore the barren parts of a vein still get
    private static final double JITTER = 0.25;
    private static final double MIN_PLUNGE = StrictMath.toRadians(25.0);
    private static final double MAX_PLUNGE = StrictMath.toRadians(75.0);
    private static final double MAX_SHIFT = 0.35;      // sideways shift of a splay, as a fraction of the main vein's size
    private static final double MIN_SEPARATION = 1.4;  // gap between swarm veins, in vein thicknesses
    private static final double MAX_SEPARATION = 2.8;

    public VeinShape {
        if (!(minDensity > 0.0) || maxDensity < minDensity || maxDensity > 1.0) {
            throw new IllegalArgumentException("need 0 < minDensity <= maxDensity <= 1");
        }
        if (!(minThickness > 0.5) || maxThickness < minThickness) {
            throw new IllegalArgumentException("need 0.5 < minThickness <= maxThickness");
        }
        if (maxElongation < 1.0 || maxPlates < 1) {
            throw new IllegalArgumentException("need maxElongation >= 1 and maxPlates >= 1");
        }
        if (minDipDegrees < 30.0 || minDipDegrees > 90.0 || maxSplayDegrees < 0.0 || maxSplayDegrees > 30.0) {
            throw new IllegalArgumentException("need 30 <= minDipDegrees <= 90 and 0 <= maxSplayDegrees <= 30");
        }
    }

    @Override
    public String name() {
        return "vein";
    }

    @Override
    public ShapeField create(ShapeRequest request) {
        DepositRandom rng = request.rng();
        double density = rng.range(minDensity, maxDensity);
        double thickness = rng.range(minThickness, maxThickness);
        double roll = rng.nextDouble();
        int count = Math.min(maxPlates, roll < 0.45 ? 1 : roll < 0.80 ? 2 : 3);
        double elongation = rng.range(1.0, maxElongation);

        double[] weights = switch (count) {
            case 1 -> new double[]{1.0};
            case 2 -> new double[]{0.62, 0.38};
            default -> new double[]{0.5, 0.3, 0.2};
        };

        double rc = thickness / 2.0;
        double footprint = request.wantedOre() / density;
        double dip = rng.range(StrictMath.toRadians(minDipDegrees), StrictMath.PI / 2.0);
        double azimuth = rng.range(0.0, 2.0 * StrictMath.PI);
        double splay = StrictMath.toRadians(maxSplayDegrees);
        Orientation main = Orientation.fromTilt(dip, azimuth, 0.0);
        double plunge = rng.range(MIN_PLUNGE, MAX_PLUNGE);

        Plate[] plates = new Plate[count];
        Noise3D[] shoots = new Noise3D[count];
        double mainA = 0.0;
        double mainB = 0.0;
        for (int i = 0; i < count; i++) {
            double volume = footprint * weights[i];
            double rb = StrictMath.sqrt(3.0 * volume / (4.0 * StrictMath.PI * rc * elongation));
            double ra = elongation * rb;
            long noiseSeed = rng.nextLong();

            Orientation frame = main;
            double ox = 0.0;
            double oy = 0.0;
            double oz = 0.0;
            if (i == 0) {
                mainA = ra;
                mainB = rb;
            } else {
                double tilt = Math.max(StrictMath.toRadians(minDipDegrees), Math.min(StrictMath.PI / 2.0, dip + rng.range(-splay, splay)));
                frame = Orientation.fromTilt(tilt, azimuth + rng.range(-splay, splay), 0.0);
                double acrossOffset = (rng.nextDouble() < 0.5 ? -1.0 : 1.0) * rng.range(MIN_SEPARATION, MAX_SEPARATION) * thickness;
                double strikeOffset = rng.range(-MAX_SHIFT, MAX_SHIFT) * mainA;
                double dipOffset = rng.range(-MAX_SHIFT, MAX_SHIFT) * mainB;
                ox = main.worldX(strikeOffset, dipOffset, acrossOffset);
                oy = main.worldY(strikeOffset, dipOffset, acrossOffset);
                oz = main.worldZ(strikeOffset, dipOffset, acrossOffset);
            }
            plates[i] = new Plate(ox, oy, oz, frame, ra, rb, rc, STYLE, noiseSeed);
            shoots[i] = new Noise3D(noiseSeed ^ 0x44L);
        }
        return new Field(plates, shoots, plunge, Math.max(6.0, 0.8 * mainB), rng.nextLong());
    }

    @Override
    public int maxReach(int maxOre, int referenceOre) {
        double footprint = maxOre / minDensity;
        double thinnest = minThickness / 2.0;
        double longest = StrictMath.sqrt(3.0 * footprint * maxElongation / (4.0 * StrictMath.PI * thinnest));
        double slide = MAX_SHIFT * longest * 2.0 + MAX_SEPARATION * maxThickness;
        return (int) Math.ceil(longest * (1.0 + STYLE.edgeAmplitude()) + slide
                + maxThickness / 2.0 * (1.0 + STYLE.swellAmplitude()) + STYLE.waveAmplitude()) + 2;
    }

    private static final class Field implements ShapeField {

        private final Plate[] plates;
        private final Noise3D[] shoots;
        private final double cosPlunge;
        private final double sinPlunge;
        private final double shootLength;
        private final double shootWidth;
        private final long seed;
        private final int reachX;
        private final int reachY;
        private final int reachZ;

        Field(Plate[] plates, Noise3D[] shoots, double plunge, double shootLength, long seed) {
            this.plates = plates;
            this.shoots = shoots;
            this.cosPlunge = StrictMath.cos(plunge);
            this.sinPlunge = StrictMath.sin(plunge);
            this.shootLength = shootLength;
            this.shootWidth = shootLength / 2.2;
            this.seed = seed;
            this.reachX = reach(0);
            this.reachY = reach(1);
            this.reachZ = reach(2);
        }

        private int reach(int axis) {
            double most = 0.0;
            for (Plate plate : plates) {
                most = Math.max(most, plate.reach(axis));
            }
            return (int) Math.ceil(most) + 1;
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
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < plates.length; i++) {
                double envelope = plates[i].envelope(dx, dy, dz);
                if (envelope <= 0.0) {
                    continue;
                }
                double richness = StrictMath.sqrt(envelope) * (SHOOT_FLOOR + (1.0 - SHOOT_FLOOR) * shoot(i, dx, dy, dz));
                best = Math.max(best, richness);
            }
            if (best == Double.NEGATIVE_INFINITY) {
                out.outside();
                return;
            }
            double jitter = (Hashing.unit(Hashing.cell(seed, dx, dy, dz)) - 0.5) * JITTER;
            out.candidate(best + jitter);
        }

        /** 0 to 1: how much of a rich shoot this spot of plate i is in. Shoots are long ribbons plunging along the vein. */
        private double shoot(int i, double dx, double dy, double dz) {
            double a = plates[i].strike(dx, dy, dz);
            double b = plates[i].dip(dx, dy, dz);
            double along = a * cosPlunge + b * sinPlunge;
            double across = -a * sinPlunge + b * cosPlunge;
            double n = shoots[i].noise(along / shootLength, across / shootWidth, 0.5 + i);
            double t = Math.max(0.0, Math.min(1.0, (n + 0.15) / 0.75));
            return t * t * (3.0 - 2.0 * t);
        }

        @Override
        public double structure(int dx, int dy, int dz) {
            double best = 0.0;
            for (int i = 0; i < plates.length; i++) {
                if (plates[i].envelope(dx, dy, dz) > 0.0) {
                    best = Math.max(best, SHOOT_FLOOR + (1.0 - SHOOT_FLOOR) * shoot(i, dx, dy, dz));
                }
            }
            return best;
        }
    }
}
