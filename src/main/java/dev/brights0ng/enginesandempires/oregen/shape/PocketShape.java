package dev.brights0ng.enginesandempires.oregen.shape;

import dev.brights0ng.enginesandempires.oregen.DepositRandom;
import dev.brights0ng.enginesandempires.oregen.Hashing;

/**
 * A pocket cluster: several small, dense pockets of ore strung along a fracture. You hunt for them.
 * Solid pockets suit emerald. Hollow ones, ringed with a shell of ore around an empty cavity, suit
 * crystals and quartz.
 *
 * @param minPockets  fewest pockets in a typical-sized deposit
 * @param maxPockets  most pockets in a typical-sized deposit. Bigger deposits get proportionally more
 * @param minDensity  leanest a pocket's ore shell can be: the smallest fraction of it that is ore
 * @param maxDensity  richest a pocket's ore shell can be
 * @param minHollow   smallest cavity, as a fraction of a pocket's radius (0 means solid)
 * @param maxHollow   largest cavity, as a fraction of a pocket's radius
 */
public record PocketShape(int minPockets, int maxPockets, double minDensity, double maxDensity,
                          double minHollow, double maxHollow) implements BodyShape {

    /** Emerald: a few small, solid pockets. */
    public static final PocketShape EMERALD = new PocketShape(2, 5, 0.75, 0.95, 0.0, 0.0);

    /** Crystal: a geode or two, hollow with a lining of crystal. */
    public static final PocketShape CRYSTAL = new PocketShape(1, 3, 0.75, 0.95, 0.50, 0.65);

    /** Quartz: a few hollow, crystal-lined cavities. */
    public static final PocketShape QUARTZ = new PocketShape(2, 5, 0.75, 0.95, 0.40, 0.55);

    private static final int MAX_POCKETS = 24;
    private static final double MIN_WEIGHT = 0.65;     // size of the smallest pocket relative to the average
    private static final double MAX_WEIGHT = 1.35;
    private static final double MIN_ASPECT = 0.80;     // how squashed or stretched a pocket may be along an axis
    private static final double MAX_ASPECT = 1.25;
    private static final double SPACING = 2.1;         // distance between neighbouring pockets, in pocket radii
    private static final double CHAIN_JITTER = 0.3;    // how far a pocket may stray along the chain, in spacings
    private static final double SIDEWAYS = 0.8;        // how far a pocket may stray sideways, in pocket radii (one sigma)
    private static final double SIDEWAYS_LIMIT = 2.0;  // ... but never beyond this many sigma
    private static final double HOST_RING = 0.35;      // thickness of the host-rock shell around a pocket, in pocket radii
    private static final double JITTER = 0.30;
    private static final double SLACK = 1.10;         // build a little more shell than needed, so blocks lost to rounding and overlap do not leave the pocket short

    public PocketShape {
        if (minPockets < 1 || maxPockets < minPockets || maxPockets > MAX_POCKETS) {
            throw new IllegalArgumentException("need 1 <= minPockets <= maxPockets <= " + MAX_POCKETS);
        }
        if (!(minDensity > 0.0) || maxDensity < minDensity || maxDensity > 1.0) {
            throw new IllegalArgumentException("need 0 < minDensity <= maxDensity <= 1");
        }
        if (minHollow < 0.0 || maxHollow < minHollow || maxHollow > 0.8) {
            throw new IllegalArgumentException("need 0 <= minHollow <= maxHollow <= 0.8");
        }
    }

    @Override
    public String name() {
        return "pockets";
    }

    @Override
    public ShapeField create(ShapeRequest request) {
        DepositRandom rng = request.rng();
        double density = rng.range(minDensity, maxDensity);
        double hollow = rng.range(minHollow, maxHollow);

        double scale = StrictMath.cbrt(request.wantedOre() / (double) request.referenceOre());
        int count = (int) Math.floor(rng.range(minPockets, maxPockets + 1.0) * scale + 0.5);
        count = Math.max(1, Math.min(MAX_POCKETS, count));

        double[] weight = new double[count];
        double[][] aspect = new double[count][3];
        double volumeWeight = 0.0;
        for (int i = 0; i < count; i++) {
            weight[i] = rng.range(MIN_WEIGHT, MAX_WEIGHT);
            volumeWeight += weight[i] * weight[i] * weight[i];
            double x = rng.range(MIN_ASPECT, MAX_ASPECT);
            double y = rng.range(MIN_ASPECT, MAX_ASPECT);
            double z = rng.range(MIN_ASPECT, MAX_ASPECT);
            double normalise = StrictMath.cbrt(x * y * z); // keep each pocket's volume unchanged by its squash
            aspect[i][0] = x / normalise;
            aspect[i][1] = y / normalise;
            aspect[i][2] = z / normalise;
        }

        double shellShare = 1.0 - hollow * hollow * hollow;
        double footprint = request.wantedOre() / density;
        double radius = Math.max(1.0, StrictMath.cbrt(footprint / (4.0 / 3.0 * StrictMath.PI * shellShare * volumeWeight)));

        // Pockets are strung along a fracture running in a random direction, with some sideways scatter.
        double up = rng.range(-1.0, 1.0);
        double around = rng.range(0.0, 2.0 * StrictMath.PI);
        double flat = StrictMath.sqrt(1.0 - up * up);
        Orientation chain = Orientation.fromNormal(flat * StrictMath.cos(around), up, flat * StrictMath.sin(around), rng.range(0.0, 2.0 * StrictMath.PI));

        double[] cx = new double[count];
        double[] cy = new double[count];
        double[] cz = new double[count];
        double[][] radii = new double[count][3];
        double spacing = SPACING * radius;
        for (int i = 0; i < count; i++) {
            double along = (i - (count - 1) / 2.0 + rng.range(-CHAIN_JITTER, CHAIN_JITTER)) * spacing;
            double sideA = sideways(rng) * radius;
            double sideB = sideways(rng) * radius;
            cx[i] = chain.worldX(sideA, sideB, along);
            cy[i] = chain.worldY(sideA, sideB, along);
            cz[i] = chain.worldZ(sideA, sideB, along);
            for (int axis = 0; axis < 3; axis++) {
                radii[i][axis] = radius * weight[i] * aspect[i][axis];
            }
        }
        refine(radii, hollow, footprint * SLACK);
        return new Field(cx, cy, cz, radii, hollow, rng.nextLong());
    }

    /**
     * Small pockets hold noticeably fewer whole blocks than their volume suggests, so count the blocks
     * their shells really contain and grow or shrink every pocket until the total is about right.
     */
    private static void refine(double[][] radii, double hollow, double target) {
        for (int pass = 0; pass < 4; pass++) {
            double blocks = 0.0;
            for (double[] r : radii) {
                blocks += shellBlocks(r, hollow);
            }
            if (blocks < 1.0) {
                return;
            }
            double factor = StrictMath.cbrt(target / blocks);
            if (Math.abs(factor - 1.0) < 0.01) {
                return;
            }
            for (double[] r : radii) {
                for (int axis = 0; axis < 3; axis++) {
                    r[axis] = Math.max(0.8, r[axis] * factor);
                }
            }
        }
    }

    /** How many whole blocks lie in the ore shell of a pocket centred on a block, with these radii. */
    private static int shellBlocks(double[] r, double hollow) {
        int blocks = 0;
        int ex = (int) Math.ceil(r[0]);
        int ey = (int) Math.ceil(r[1]);
        int ez = (int) Math.ceil(r[2]);
        for (int x = -ex; x <= ex; x++) {
            for (int y = -ey; y <= ey; y++) {
                for (int z = -ez; z <= ez; z++) {
                    double rho = StrictMath.sqrt((x / r[0]) * (x / r[0]) + (y / r[1]) * (y / r[1]) + (z / r[2]) * (z / r[2]));
                    if (rho >= hollow && rho <= 1.0) {
                        blocks++;
                    }
                }
            }
        }
        return blocks;
    }

    private static double sideways(DepositRandom rng) {
        return Math.max(-SIDEWAYS_LIMIT, Math.min(SIDEWAYS_LIMIT, rng.nextGaussian())) * SIDEWAYS;
    }

    @Override
    public int maxReach(int maxOre, int referenceOre) {
        double footprint = maxOre / minDensity;
        int mostPockets = Math.min(MAX_POCKETS,
                (int) Math.floor((maxPockets + 1.0) * StrictMath.cbrt(maxOre / (double) referenceOre) + 0.5));
        double shellShare = 1.0 - maxHollow * maxHollow * maxHollow;
        double smallestVolumeWeight = MIN_WEIGHT * MIN_WEIGHT * MIN_WEIGHT;
        double most = 0.0;
        for (int count = 1; count <= mostPockets; count++) {
            double radius = Math.max(1.0, StrictMath.cbrt(footprint
                    / (4.0 / 3.0 * StrictMath.PI * shellShare * count * smallestVolumeWeight)));
            double alongChain = ((count - 1) / 2.0 + CHAIN_JITTER) * SPACING * radius;
            double sideways = Math.sqrt(2.0) * SIDEWAYS_LIMIT * SIDEWAYS * radius;
            double pocket = radius * MAX_WEIGHT * MAX_ASPECT * (1.0 + HOST_RING);
            most = Math.max(most, alongChain + sideways + pocket);
        }
        return (int) Math.ceil(most) + 2;
    }

    private static final class Field implements ShapeField {

        private final double[] cx;
        private final double[] cy;
        private final double[] cz;
        private final double[][] radii;
        private final double hollow;
        private final long seed;
        private final int reachX;
        private final int reachY;
        private final int reachZ;

        Field(double[] cx, double[] cy, double[] cz, double[][] radii, double hollow, long seed) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.radii = radii;
            this.hollow = hollow;
            this.seed = seed;
            this.reachX = reach(cx, 0);
            this.reachY = reach(cy, 1);
            this.reachZ = reach(cz, 2);
        }

        private int reach(double[] centre, int axis) {
            double most = 0.0;
            for (int i = 0; i < centre.length; i++) {
                most = Math.max(most, Math.abs(centre[i]) + radii[i][axis] * (1.0 + HOST_RING));
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
            boolean inRing = false;
            for (int i = 0; i < cx.length; i++) {
                double ox = dx - cx[i];
                double oy = dy - cy[i];
                double oz = dz - cz[i];
                double[] r = radii[i];
                double limit = 1.0 + HOST_RING;
                if (Math.abs(ox) > r[0] * limit || Math.abs(oy) > r[1] * limit || Math.abs(oz) > r[2] * limit) {
                    continue;
                }
                double rho = StrictMath.sqrt((ox / r[0]) * (ox / r[0]) + (oy / r[1]) * (oy / r[1]) + (oz / r[2]) * (oz / r[2]));
                if (rho < hollow) {
                    out.cavity();
                    return;
                }
                if (rho <= 1.0) {
                    double t = (rho - hollow) / (1.0 - hollow);
                    best = Math.max(best, 1.0 - t * t);
                } else if (rho <= limit) {
                    inRing = true;
                }
            }
            if (best != Double.NEGATIVE_INFINITY) {
                out.candidate(best + (Hashing.unit(Hashing.cell(seed, dx, dy, dz)) - 0.5) * JITTER);
            } else if (inRing) {
                out.host();
            } else {
                out.outside();
            }
        }
    }
}
