package dev.brights0ng.enginesandempires.weather.cloud;

/**
 * How a cloud drifts between two velocities (Bright, 2026-10-07: movement was erratic when velocities changed). Shared
 * and pure, so the server and every client put a cloud in the same place: from its velocity {@code v0} at a reference
 * tick it eases into its new velocity {@code v1} along an S-curve (smoothstep) over {@link #EASE_TICKS}, then keeps
 * it. Positions are the exact integral of that velocity, so a cloud never jumps and never jerks.
 */
public final class CloudDrift {

    /** How long a cloud takes to settle into a new wind velocity, ticks (30 s). */
    public static final double EASE_TICKS = 600;

    /** How far a cloud moves in {@code dt} ticks after the reference tick (one axis), blocks. */
    public static double offset(double v0, double v1, double dt) {
        if (dt <= 0) {
            return v0 * dt;
        }
        double u = dt / EASE_TICKS;
        if (u >= 1) {
            // The whole ease (half its length at the average speed) and the new speed since.
            return v0 * dt + (v1 - v0) * (dt - EASE_TICKS / 2);
        }
        // The integral of smoothstep, u^3 - u^4 / 2.
        return v0 * dt + (v1 - v0) * EASE_TICKS * (u * u * u - u * u * u * u / 2);
    }

    /** The velocity {@code dt} ticks after the reference tick (one axis), blocks per tick. */
    public static double velocity(double v0, double v1, double dt) {
        if (dt <= 0) {
            return v0;
        }
        double u = Math.min(1, dt / EASE_TICKS);
        return v0 + (v1 - v0) * u * u * (3 - 2 * u);
    }

    private CloudDrift() {
    }
}
