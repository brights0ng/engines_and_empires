// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Rolls a god's next demand. For each tier with q &gt; 0 at the empire's dominion, one of the god's options for that tier is
 * picked: uniformly among the eligible ones (q·m &ge; 0.5), or, when none is, the one with the highest multiplier at a
 * count of 1. The count is max(1, ceil(q·m)). The roll is deterministic: it is seeded from the empire's id, how many
 * sacrifices it has completed, and the tier.
 */
public final class DemandRoller {

    /** The least q·m an option needs to be picked at random. */
    public static final double ELIGIBLE = 0.5;

    /**
     * @param tiers the god's options, tiers 1-5 in order (a missing or empty tier is skipped)
     */
    public static List<DemandLine> roll(long empireIdMsb, long empireIdLsb, int sacrificeCount, double dominion,
            List<List<DemandOption>> tiers) {
        List<DemandLine> lines = new ArrayList<>();
        for (int tier = 1; tier <= SacrificeCurve.TIERS && tier <= tiers.size(); tier++) {
            double q = SacrificeCurve.q(tier, dominion);
            List<DemandOption> options = tiers.get(tier - 1);
            if (q <= 0 || options == null || options.isEmpty()) {
                continue;
            }
            SplittableRandom random = new SplittableRandom(seed(empireIdMsb, empireIdLsb, sacrificeCount, tier));
            lines.add(pick(random, q, options));
        }
        return lines;
    }

    /** Whether an option of multiplier {@code m} may be picked at random at quantity {@code q}. */
    public static boolean eligible(double q, double m) {
        return q * m >= ELIGIBLE;
    }

    /** One tier's line: see the class notes. */
    static DemandLine pick(SplittableRandom random, double q, List<DemandOption> options) {
        List<DemandOption> eligible = new ArrayList<>();
        for (DemandOption option : options) {
            if (eligible(q, option.multiplier())) {
                eligible.add(option);
            }
        }
        if (eligible.isEmpty()) {
            DemandOption best = options.get(0);
            for (DemandOption option : options) {
                if (option.multiplier() > best.multiplier()) {
                    best = option;
                }
            }
            return best.line(1);
        }
        DemandOption chosen = eligible.get(random.nextInt(eligible.size()));
        return chosen.line(Math.max(1, (int) Math.ceil(q * chosen.multiplier())));
    }

    /** Mixes the inputs into one seed (SplitMix64's finaliser over a combination of them). */
    static long seed(long msb, long lsb, int sacrificeCount, int tier) {
        long h = msb;
        h = mix(h ^ Long.rotateLeft(lsb, 23));
        h = mix(h ^ (sacrificeCount * 0x9E3779B97F4A7C15L));
        h = mix(h ^ (tier * 0xC2B2AE3D27D4EB4FL));
        return h;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private DemandRoller() {
    }
}
