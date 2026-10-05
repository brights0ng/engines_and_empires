package dev.brights0ng.enginesandempires.geophone;

/**
 * How a vibration travels from where it is made, to a deposit, and back out to a listener.
 *
 * <p>Every vibration moves at the same speed, 64 blocks a second (3.2 a tick), whatever it passes through. That is
 * about 1% of the speed of sound in the Earth's crust: slow enough that the delay between geophones lighting up
 * can be seen with the eye. A deposit receives the vibration when it arrives, and sends out one of its own, so
 * what a geophone hears is the vibration's path from the source, to the deposit, to the geophone.
 *
 * <p>A deposit is not a point, it can be a hundred blocks across, so each of its ore blocks reflects the
 * vibration on its own, and the geophone hears the earliest of those first and the latest last. The gap between
 * them is how long the pulse lasts, and it grows with the size of the deposit.
 *
 * <p>The range of the vibration limits both legs: an ore block only receives it if it is within range of the
 * source, and a geophone only hears the ore block's echo if it is within that same range of the ore block.
 *
 * <p>Nothing here touches Minecraft. All maths, no state, safe from any thread.
 */
public final class WaveModel {

    /** How fast every vibration travels. */
    public static final double BLOCKS_PER_SECOND = 64.0;

    /** How far a vibration travels in one game tick. */
    public static final double BLOCKS_PER_TICK = BLOCKS_PER_SECOND / 20.0;

    /** The ore blocks of a deposit, as the coordinates of their centres. */
    public record OreCells(double[] x, double[] y, double[] z) {

        public OreCells {
            if (x.length != y.length || y.length != z.length) {
                throw new IllegalArgumentException("x, y and z must be the same length");
            }
        }

        public int size() {
            return x.length;
        }
    }

    /**
     * When a deposit's echo reaches a listener.
     *
     * @param earliestTicks ticks after the vibration was made when the first of the echo arrives
     * @param latestTicks   ticks after it when the last of the echo arrives
     */
    public record Arrival(double earliestTicks, double latestTicks) {

        /** How long the pulse lasts: the gap between the first of the echo and the last. */
        public double spreadTicks() {
            return latestTicks - earliestTicks;
        }
    }

    /**
     * When the echo of a deposit reaches a listener, for a vibration made at the source.
     *
     * @param range how far the vibration reaches from the source, and how far an echo reaches from an ore block
     * @return the arrival, or null if no ore block is within range of both the source and the listener
     */
    public static Arrival arrival(OreCells cells, double sourceX, double sourceY, double sourceZ,
                                  double listenerX, double listenerY, double listenerZ, double range) {
        double rangeSquared = range * range;
        double earliest = Double.POSITIVE_INFINITY;
        double latest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < cells.size(); i++) {
            double toSource = squared(cells.x()[i] - sourceX, cells.y()[i] - sourceY, cells.z()[i] - sourceZ);
            if (toSource > rangeSquared) {
                continue;
            }
            double toListener = squared(cells.x()[i] - listenerX, cells.y()[i] - listenerY, cells.z()[i] - listenerZ);
            if (toListener > rangeSquared) {
                continue;
            }
            double ticks = (Math.sqrt(toSource) + Math.sqrt(toListener)) / BLOCKS_PER_TICK;
            earliest = Math.min(earliest, ticks);
            latest = Math.max(latest, ticks);
        }
        return earliest == Double.POSITIVE_INFINITY ? null : new Arrival(earliest, latest);
    }

    private static double squared(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
    }

    private WaveModel() {
    }
}
