package dev.brights0ng.enginesandempires.oregen;

/**
 * Decides, before anything is generated, whether a deposit will really have ore, and if its first draw
 * would not, finds one that will.
 *
 * <p>The ore map says where deposits are, but not what the ground there is like. A deposit over a vast
 * open cavern, or in the Nether's open spaces, would place no ore at all, and the geophone would still
 * ping it. Worldgen cannot notice this after the fact, because it works one chunk at a time in any order
 * and no chunk sees the whole deposit. So the question is asked up front, of the terrain noise, by
 * {@link TerrainProbe}, and the answer is a pure function of the seed: worldgen, commands and the
 * geophone all get the same one, and a ping never moves.
 *
 * <p>How it works:
 * <ol>
 *   <li>Build the deposit's ordinary body (attempt 0) and test a sample of its ore blocks against the
 *       terrain. If enough of them are in solid rock, the deposit stands as it is.</li>
 *   <li>If not, draw the deposit again at the <em>same map position</em>: a fresh height, size and shape.
 *       Height is the free dimension, since rock is nearly always somewhere in a column even where a
 *       cavern fills part of it, and keeping x and z fixed preserves the map's spacing guarantees. Up to
 *       {@link #MAX_ATTEMPTS} draws are tried.</li>
 *   <li>If none works, the deposit does not exist. It is left off the map rather than moved.</li>
 * </ol>
 */
public final class DepositResolver {

    /** How many draws of a deposit are tried before giving up on it. */
    public static final int MAX_ATTEMPTS = 8;
    /** How many of a body's ore blocks are tested against the terrain. */
    public static final int SAMPLES = 32;
    /** The smallest share of tested ore blocks that must be in solid rock. */
    public static final double MIN_SOLID_FRACTION = 0.25;
    /** The fewest ore blocks a deposit is expected to place (its ore count times the solid share), or half its ore if it is tiny. */
    public static final double MIN_EXPECTED_ORE = 8.0;

    /** Builds the body of a given attempt at one deposit. */
    @FunctionalInterface
    public interface BodySource {
        DepositBody body(int attempt);
    }

    /**
     * What became of a deposit.
     *
     * @param attempt       the draw that was accepted, or -1 if the deposit was dropped
     * @param solidFraction the share of the accepted body's tested ore blocks that were in solid rock, or,
     *                      for a dropped deposit, the best share any draw managed
     * @param dropped       true if no draw was viable and the deposit does not exist
     */
    public record Outcome(int attempt, double solidFraction, boolean dropped) {

        /** True if the deposit exists. */
        public boolean viable() {
            return !dropped;
        }
    }

    /** An outcome with the body it accepted, or null for a dropped deposit. */
    public record Result(Outcome outcome, DepositBody body) {
    }

    /** Works out what becomes of a deposit. Deterministic for a given terrain. */
    public static Result resolve(Deposit deposit, BodySource bodies, TerrainProbe probe) {
        double best = 0.0;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            DepositBody body = bodies.body(attempt);
            double fraction = solidFraction(deposit, body, probe);
            best = Math.max(best, fraction);
            if (isViable(body, fraction)) {
                return new Result(new Outcome(attempt, fraction, false), body);
            }
        }
        return new Result(new Outcome(-1, best, true), null);
    }

    /** Whether a body whose tested ore blocks were this solid is worth keeping. */
    public static boolean isViable(DepositBody body, double solidFraction) {
        double needed = Math.min(MIN_EXPECTED_ORE, body.oreCount() / 2.0);
        return solidFraction >= MIN_SOLID_FRACTION && solidFraction * body.oreCount() >= needed;
    }

    /**
     * The share of the body's ore blocks that are in solid rock, estimated from an evenly spread sample of
     * {@link #SAMPLES} of them.
     */
    public static double solidFraction(Deposit deposit, DepositBody body, TerrainProbe probe) {
        int[] x = new int[body.oreCount()];
        int[] y = new int[body.oreCount()];
        int[] z = new int[body.oreCount()];
        int[] count = {0};
        body.forEach((dx, dy, dz, kind) -> {
            if ((kind == DepositBody.ORE || kind == DepositBody.RICH) && count[0] < x.length) {
                x[count[0]] = deposit.x() + dx;
                y[count[0]] = body.centerY() + dy;
                z[count[0]] = deposit.z() + dz;
                count[0]++;
            }
        });
        int ore = count[0];
        if (ore == 0) {
            return 0.0;
        }
        int stride = Math.max(1, ore / SAMPLES);
        int tested = 0;
        int solid = 0;
        for (int i = stride / 2; i < ore && tested < SAMPLES; i += stride) {
            tested++;
            if (probe.isSolid(x[i], y[i], z[i])) {
                solid++;
            }
        }
        return tested == 0 ? 0.0 : solid / (double) tested;
    }

    private DepositResolver() {
    }
}
