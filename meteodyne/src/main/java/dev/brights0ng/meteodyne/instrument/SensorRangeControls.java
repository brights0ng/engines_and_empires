// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

/** The range editor's bounded drag/scroll behavior, independent of client classes. Values are tenths of the instrument unit. */
public final class SensorRangeControls {
    public static final int MIN = -1000, MAX = 2000;
    private int zero, full;
    private final int minimum, maximum;

    public SensorRangeControls(int zero, int full) {
        this(zero, full, MIN, MAX);
    }

    public SensorRangeControls(int zero, int full, int minimum, int maximum) {
        this.minimum = minimum;
        this.maximum = maximum;
        if (minimum >= maximum || zero < minimum || zero > maximum || full < minimum || full > maximum || zero == full) { throw new IllegalArgumentException("Invalid sensor calibration"); }
        this.zero = zero;
        this.full = full;
    }

    public int zero() { return zero; }
    public int full() { return full; }
    public int endpoint(boolean zeroHandle) { return zeroHandle ? zero : full; }

    public static boolean valid(int zero, int full) {
        return zero >= MIN && zero <= MAX && full >= MIN && full <= MAX && zero != full;
    }

    public static double fraction(int value) { return (value - (double) MIN) / (MAX - MIN); }

    public static int atFraction(double fraction) {
        return (int) Math.round(MIN + Math.clamp(fraction, 0, 1) * (MAX - MIN));
    }

    public double position(int value) { return (value - (double) minimum) / (maximum - minimum); }
    public int valueAt(double fraction) { return (int) Math.round(minimum + Math.clamp(fraction, 0, 1) * (maximum - minimum)); }

    /** Ctrl moves the whole range, retaining its signed span and clamping both ends together at the limits. */
    public boolean move(boolean zeroHandle, int requested, boolean together) {
        int target = Math.clamp(requested, minimum, maximum);
        if (together) {
            int change = target - endpoint(zeroHandle);
            change = Math.clamp(change, minimum - Math.min(zero, full), maximum - Math.max(zero, full));
            if (change == 0) { return false; }
            zero += change;
            full += change;
        } else {
            if (target == endpoint(zeroHandle) || target == endpoint(!zeroHandle)) { return false; }
            if (zeroHandle) { zero = target; } else { full = target; }
        }
        return true;
    }
}
