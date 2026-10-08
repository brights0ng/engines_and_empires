package dev.brights0ng.enginesandempires.weather.sim;

import java.util.Locale;

/**
 * One low or high: a pressure system drifting with the jet through its life. Mutable, stepped by
 * {@link SystemsSim}. Pure (saved by the server's {@code WeatherSimData}).
 *
 * <h2>Lows (cyclones)</h2>
 * Born weak on a storm track (a "wave"), they deepen over the first ~45% of their life, then fill. Their size grows over
 * the first half. Fronts grow out of them ({@link FrontGeometry}) and occlude as they age.
 *
 * <h2>Highs (anticyclones)</h2>
 * Build over the first fifth of their life, hold, and fade over the last fifth. A <b>blocking</b> high barely moves,
 * lasts about twice as long, is stronger and bigger, and steers lows around it.
 */
public final class WeatherSystem {

    public enum Kind { LOW, HIGH }

    public enum Stage {
        WAVE, DEEPENING, MATURE, OCCLUDING, FILLING, BUILDING, HOLDING, FADING;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public final long id;
    public final Kind kind;
    public double x;
    public double z;
    /** The storm track it was born on. */
    public final int track;
    /** +1 northern-style turning, -1 mirrored. */
    public final int hemisphere;
    public long age;
    public final long lifetime;
    /** The deepest (lows) or strongest (highs) it gets, hPa. */
    public final double peak;
    /** Its largest radius, blocks. */
    public final double maxRadius;
    public final boolean blocking;
    /** The live simulation's slow random wander of its strength (1 = none). */
    public double nudge = 1;

    public WeatherSystem(long id, Kind kind, double x, double z, int track, int hemisphere, long age, long lifetime,
                         double peak, double maxRadius, boolean blocking) {
        this.id = id;
        this.kind = kind;
        this.x = x;
        this.z = z;
        this.track = track;
        this.hemisphere = hemisphere;
        this.age = age;
        this.lifetime = Math.max(1, lifetime);
        this.peak = peak;
        this.maxRadius = maxRadius;
        this.blocking = blocking;
    }

    public WeatherSystem copy() {
        WeatherSystem c = new WeatherSystem(id, kind, x, z, track, hemisphere, age, lifetime, peak, maxRadius, blocking);
        c.nudge = nudge;
        return c;
    }

    /** How far through its life it is, 0-1. */
    public double life() {
        return SimMath.clamp01((double) age / lifetime);
    }

    public boolean dead() {
        return age >= lifetime;
    }

    /** How strong it is now, hPa below (lows) or above (highs) normal. */
    public double strength() {
        double f = life();
        double shape;
        if (kind == Kind.LOW) {
            shape = f < 0.45 ? 0.08 + 0.92 * SimMath.smooth(f / 0.45) : 1 - 0.9 * SimMath.smooth((f - 0.45) / 0.55);
        } else {
            shape = SimMath.smooth(f / 0.2) * (1 - SimMath.smooth((f - 0.8) / 0.2));
        }
        return peak * shape * nudge;
    }

    /** Its radius now, blocks (pressure falls off as exp(-(d/r)^2)). */
    public double radius() {
        double f = life();
        double start = kind == Kind.LOW ? 0.45 : 0.7;
        return maxRadius * (start + (1 - start) * SimMath.smooth(f / 0.5));
    }

    /** Signed pressure anomaly at its centre: negative for lows. */
    public double signedStrength() {
        return kind == Kind.LOW ? -strength() : strength();
    }

    public Stage stage() {
        double f = life();
        if (kind == Kind.HIGH) {
            return f < 0.2 ? Stage.BUILDING : f < 0.8 ? Stage.HOLDING : Stage.FADING;
        }
        if (f < 0.15) {
            return Stage.WAVE;
        }
        if (f < 0.35) {
            return Stage.DEEPENING;
        }
        if (f < 0.55) {
            return Stage.MATURE;
        }
        return f < 0.8 ? Stage.OCCLUDING : Stage.FILLING;
    }
}
