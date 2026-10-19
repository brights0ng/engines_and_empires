// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

/** Horizontal wind travelling toward (x,z), in m/s; bearings use Minecraft yaw (zero south). */
public record WindReading(String dimension, long time, float x, float z) {
    public static final double CALM = 0.05;
    public double speed() { return Math.hypot(x, z); }
    public boolean usable(String currentDimension, long now) {
        return Float.isFinite(x) && Float.isFinite(z)
                && new InstrumentReading(dimension, time, 0).usable(currentDimension, now);
    }
    public boolean calm() { return speed() < CALM; }
    public double yaw() { return Math.toDegrees(Math.atan2(-x, z)); }
    public static double wrap(double degrees) { return ((degrees + 180) % 360 + 360) % 360 - 180; }
    /** Arrow angle on a dial whose top faces the given Minecraft yaw; positive clockwise. */
    public double relativeAngle(double facingYaw) { return wrap(yaw() - facingYaw); }
    /** Bounded rotor speed prevents high winds aliasing into an apparently stationary rotor. */
    public static double rotorStep(double speed) { return speed < CALM ? 0 : Math.min(9, speed * 0.6); }
}
