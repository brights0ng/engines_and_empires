package dev.brights0ng.enginesandempires.weather.rain;

/**
 * What the renderer remembers about one streak's rain, so rain already falling keeps falling (Bright,
 * 2026-10-04: when the wet area moves, only new rain should change).
 *
 * <ul>
 *   <li><b>Starting:</b> when a column the renderer has been watching starts getting rain, the rain arrives from above:
 *       its lower end (the <i>head</i>) falls from the top of the drawn band at the fall speed until it reaches the
 *       ground.</li>
 *   <li><b>Stopping:</b> its upper end (the <i>tail</i>) falls the same way and the last drops reach the ground before it
 *       is gone.</li>
 *   <li><b>First sight</b> (walking into a shower, joining a world): a column seen for the first time while it is raining
 *       is drawn already raining all the way down, so rain doesn't keep arriving ahead of you as you walk.</li>
 *   <li><b>Strength</b> eases toward its target over about a second.</li>
 * </ul>
 * Heights are world y; fall speeds are {@link RainModel}'s (blocks = metres per second). Pure Java, tested.
 */
public final class RainColumn {

    /** Strength eases this much of the way to its target per tick. */
    static final double STRENGTH_EASE = 0.05;

    public double target;
    public double strength;
    public boolean snow;
    public boolean active;
    /** Where the streak meets the ground: height (world y) and spot (world x, z). */
    public double ground;
    public double x;
    public double z;
    /** The rain's lower end (world y), and last tick's, for interpolation. */
    public double head;
    public double prevHead;
    /** Its upper end: infinite while rain keeps coming. */
    public double tail = Double.POSITIVE_INFINITY;
    public double prevTail = Double.POSITIVE_INFINITY;
    public long sampledTick = Long.MIN_VALUE;
    public long seenTick;

    /** A column seen for the first time: drawn as it is, no front. */
    public static RainColumn first(double strength, boolean snow, double ground, long tick) {
        RainColumn c = new RainColumn();
        c.target = strength;
        c.strength = strength;
        c.snow = snow;
        c.ground = ground;
        c.active = strength > RainModel.MIN;
        c.head = ground;
        c.prevHead = ground;
        c.sampledTick = tick;
        c.seenTick = tick;
        return c;
    }

    /** Blocks per tick a drop falls: drizzle 4 m/s up to heavy rain 9 m/s, snow 1.2 m/s. */
    public static double fallPerTick(boolean snow, double strength) {
        double mps = snow ? RainModel.SNOW_FALL
                : RainModel.DRIZZLE_FALL + (RainModel.HEAVY_FALL - RainModel.DRIZZLE_FALL) * Math.min(1, strength);
        return mps / 20;
    }

    /** One tick, with the drawn band's top at {@code top} (world y). */
    public void step(double top) {
        prevHead = head;
        prevTail = tail;
        boolean raining = target > RainModel.MIN;
        double v = fallPerTick(snow, Math.max(strength, target));
        if (raining) {
            if (!active) {
                // Rain starts at the top and falls.
                active = true;
                head = top;
                prevHead = top;
                tail = Double.POSITIVE_INFINITY;
                prevTail = tail;
                strength = target;
            } else if (tail != Double.POSITIVE_INFINITY) {
                // It started again while the last of it was still falling: rain keeps coming from above.
                tail = Double.POSITIVE_INFINITY;
                prevTail = tail;
            }
            head = Math.max(ground, Math.min(head, top) - v);
            strength += (target - strength) * STRENGTH_EASE;
        } else if (active) {
            head = Math.max(ground, head - v);
            tail = Math.min(tail, top) - v;
            if (tail <= head + 0.01) {
                active = false;
            }
        }
    }

    /** The lower end at partial tick {@code pt}. */
    public double headAt(float pt) {
        return prevHead + (head - prevHead) * pt;
    }

    /** The upper end at partial tick {@code pt} (infinite while rain keeps coming). */
    public double tailAt(float pt) {
        if (Double.isInfinite(tail)) {
            return tail;
        }
        double from = Double.isInfinite(prevTail) ? tail : prevTail;
        return from + (tail - from) * pt;
    }

    /** Whether rain reaches the ground here now. */
    public boolean landing() {
        return active && head <= ground + 0.5 && !snow;
    }
}
