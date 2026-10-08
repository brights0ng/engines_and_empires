package dev.brights0ng.enginesandempires.weather.ships;

/** The numbers for weather on ships (phase 5d; Bright, 2026-10-08). Pure Java. */
public final class ShipRules {

    /** Steeper than this (degrees from level) and a deck collects nothing; what is there stays put. */
    public static final double MAX_TILT_DEGREES = 45;
    /** Faster than this (m/s) the airflow strips snow off (not glaze) and keeps it off. */
    public static final double STRIP_SPEED = 20;

    /** How far a ship's up (x, y, z, in the world) leans from straight up, degrees. */
    public static double tiltDegrees(double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len <= 0) {
            return 0;
        }
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, y / len))));
    }

    /** Whether a deck leaning by up (x, y, z) collects snow and glaze. */
    public static boolean collects(double x, double y, double z) {
        return tiltDegrees(x, y, z) <= MAX_TILT_DEGREES;
    }

    /** Whether a ship going {@code speed} m/s is stripped of snow. */
    public static boolean strips(double speed) {
        return speed > STRIP_SPEED;
    }

    private ShipRules() {
    }
}
