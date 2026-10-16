package dev.brights0ng.enginesandempires.weather.heat.air;

/**
 * Radiant heat (weather phase 8f-1): the warmth (or chill) felt straight from a source by line of sight, which the
 * wind does not carry off. Falls off with the square of distance; the world side works out how much of the line of
 * sight gets through (1 clear, 1/4 through glass and other light-passing blocks, 0 blocked, as for the sun in 8e-2).
 * Ice and glowstone act only this way (Bright, 2026-10-15). Pure.
 */
public final class Radiant {

    /**
     * The warmth, C (negative for cold), at {@code distance} blocks from a source of table power {@code tablePower}
     * radiating {@code share} of it, with {@code through} (0-1) of the line of sight clear.
     */
    public static double warmth(AirParams p, double tablePower, double share, double distance, double through) {
        double d = Math.max(p.radiantMin(), distance);
        return p.radiantK() * tablePower * share * through / (d * d);
    }

    /** How far, blocks, a source's radiant warmth stays above {@link AirParams#radiantFloor} (with a clear line). */
    public static double reach(AirParams p, double tablePower, double share) {
        double strength = Math.abs(p.radiantK() * tablePower * share);
        if (strength <= p.radiantFloor()) {
            return strength > 0 ? p.radiantMin() : 0;
        }
        return Math.sqrt(strength / p.radiantFloor());
    }

    /**
     * Many sources together (8f-3; Bright, 2026-10-15): the warm ones and the cold ones are each summed, then eased
     * toward a ceiling, {@code limit x tanh(sum / limit)}, so a lava lake's edge feels like its hottest source's air
     * limit at most, not hundreds of degrees; one weak source feels as it would alone.
     *
     * @param hotSum    sum of the warm sources' warmth, C (0 or more)
     * @param hotLimit  the ceiling for warmth, C (the hottest contributor's air limit)
     * @param coldSum   sum of the cold sources' chill, C (0 or less)
     * @param coldLimit the floor for chill, C, as a positive size
     */
    public static double combine(double hotSum, double hotLimit, double coldSum, double coldLimit) {
        return ease(Math.max(0, hotSum), hotLimit) - ease(Math.max(0, -coldSum), coldLimit);
    }

    private static double ease(double sum, double limit) {
        if (sum <= 0) {
            return 0;
        }
        if (!(limit > 0) || !Double.isFinite(limit)) {
            return sum;
        }
        return limit * Math.tanh(sum / limit);
    }

    private Radiant() {
    }
}
