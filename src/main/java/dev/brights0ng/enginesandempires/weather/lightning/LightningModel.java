package dev.brights0ng.enginesandempires.weather.lightning;

import java.util.List;
import java.util.SplittableRandom;

/**
 * The lightning model's maths (weather phase 6b; Bright, 2026-10-08), pure Java.
 *
 * <ul>
 *   <li><b>How often:</b> a storm flashes at random (a Poisson process) at {@link Settings#perMinute} flashes a minute
 *       times its strength (the cloud's {@code lightning} value, 0-1). One a minute from a full-strength storm.</li>
 *   <li><b>Where in the storm:</b> anywhere in it, more often near its centre: a tower (dome) is picked by its area,
 *       weighted toward the storm's middle, and the point within it is denser toward the tower's own middle. The
 *       height is in the middle half of the cloud.</li>
 *   <li><b>Which kind:</b> {@link Settings#groundShare} (25%) strike the ground; the rest stay in the cloud, where they
 *       can still hit a ship or flying thing inside it.</li>
 *   <li><b>The tallest point:</b> a ground strike attaches to the tallest thing near the spot under the flash, as real
 *       lightning does: the highest column in reach, less a small penalty for each block away.</li>
 * </ul>
 */
public final class LightningModel {

    /** Height lost per block of distance when comparing columns for the tallest point (prefers nearer, equal-height). */
    public static final double DISTANCE_PENALTY = 0.25;

    /**
     * The server's lightning settings.
     *
     * @param enabled      whether storms make lightning at all
     * @param perMinute    flashes a minute from a full-strength storm
     * @param groundShare  the share of flashes that strike the ground
     * @param attachRadius how far a ground strike looks for the tallest thing, blocks
     * @param skyReach     how far an in-cloud flash reaches to a ship or flying thing, blocks
     * @param range        storms flash within this many blocks of a player, who hear of flashes this far
     */
    public record Settings(boolean enabled, double perMinute, double groundShare, double attachRadius, double skyReach,
                           double range) {

        public static final Settings DEFAULT = new Settings(true, 1.0, 0.25, 16, 24, 3072);
    }

    /** What a flash does: stays in the cloud, hits something in the sky inside it, or strikes the ground. */
    public enum Kind {
        CLOUD, SKY, GROUND
    }

    /** A tower of the storm, in world coordinates. */
    public record Dome(double x, double z, double radius) {
    }

    /** Where a flash is, and the radius of the tower it lights up. */
    public record Point(double x, double y, double z, double radius) {
    }

    /** A column to strike: its top's height (terrain, or a ship's top over it). */
    public record Column(double x, double z, double height) {
    }

    /** The chance a storm of {@code strength} flashes at least once in {@code ticks}. */
    public static double chance(double strength, double perMinute, double ticks) {
        double rate = Math.max(0, strength) * Math.max(0, perMinute) / 1200.0;
        return 1 - Math.exp(-rate * ticks);
    }

    /** Ground or cloud, from a uniform random {@code u}. */
    public static Kind kind(double u, double groundShare) {
        return u < groundShare ? Kind.GROUND : Kind.CLOUD;
    }

    /**
     * Where in a storm a flash happens, or null if it has no towers.
     *
     * @param cx    the storm's centre, x
     * @param cz    the storm's centre, z
     * @param reach how far the storm reaches from its centre
     */
    public static Point where(List<Dome> domes, double cx, double cz, double reach, double base, double top,
                              SplittableRandom rng) {
        if (domes.isEmpty()) {
            return null;
        }
        double[] weights = new double[domes.size()];
        double total = 0;
        double scale = Math.max(1, reach);
        for (int i = 0; i < domes.size(); i++) {
            Dome d = domes.get(i);
            double off = Math.hypot(d.x() - cx, d.z() - cz) / scale;
            weights[i] = d.radius() * d.radius() * Math.exp(-2 * off * off);
            total += weights[i];
        }
        double pick = rng.nextDouble() * total;
        Dome chosen = domes.get(domes.size() - 1);
        for (int i = 0; i < domes.size(); i++) {
            pick -= weights[i];
            if (pick <= 0) {
                chosen = domes.get(i);
                break;
            }
        }
        // A uniform radius (not its square root) crowds points toward the middle; kept inside the visible tower.
        double r = 0.85 * chosen.radius() * rng.nextDouble();
        double a = rng.nextDouble() * Math.PI * 2;
        double y = base + (top - base) * (0.25 + 0.5 * rng.nextDouble());
        return new Point(chosen.x() + r * Math.cos(a), y, chosen.z() + r * Math.sin(a), chosen.radius());
    }

    /** The column a strike near (x, z) attaches to, or null if there are none. */
    public static Column tallest(List<Column> columns, double x, double z) {
        Column best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Column c : columns) {
            double score = c.height() - DISTANCE_PENALTY * Math.hypot(c.x() - x, c.z() - z);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private LightningModel() {
    }
}
