package dev.brights0ng.enginesandempires.weather.lightning;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * How bright a flash is over time (weather phase 6c), pure Java.
 *
 * <p>Real lightning flickers: a strike is usually several strokes down the same channel, tens of milliseconds apart,
 * each a spike of light that dies away in a few tens of milliseconds; a flash inside a cloud is a softer, longer
 * flicker. So a flash is 1-4 strokes (strikes: crisp, 2-4 usually; in-cloud: 2-5, softer and spread wider), seeded
 * from the flash so every player's flicker is the same. Brightness is 0-1, scaled by the storm's strength.
 */
public final class FlashLight {

    /** One stroke: when it starts (milliseconds from the flash) and how bright it peaks. */
    public record Stroke(double start, double peak) {
    }

    private final List<Stroke> strokes;
    private final double tau;

    private FlashLight(List<Stroke> strokes, double tau) {
        this.strokes = strokes;
        this.tau = tau;
    }

    /**
     * The flicker of a flash.
     *
     * @param seed     the same on every client (from the flash's position)
     * @param strike   whether it struck something (crisp strokes) or stayed in the cloud (soft)
     * @param strength the storm's strength, 0-1 (weak storms flash dimmer)
     */
    public static FlashLight of(long seed, boolean strike, double strength) {
        SplittableRandom rng = new SplittableRandom(seed);
        double scale = 0.6 + 0.4 * Math.max(0, Math.min(1, strength));
        int n;
        double u = rng.nextDouble();
        if (strike) {
            n = u < 0.15 ? 1 : u < 0.5 ? 2 : u < 0.85 ? 3 : 4;
        } else {
            n = u < 0.3 ? 2 : u < 0.65 ? 3 : u < 0.9 ? 4 : 5;
        }
        List<Stroke> list = new ArrayList<>(n);
        double t = 0;
        for (int i = 0; i < n; i++) {
            // The first stroke is the brightest; later ones a little dimmer, now and then a bright re-strike.
            double peak = i == 0 ? 1.0 : 0.45 + 0.5 * rng.nextDouble();
            if (!strike) {
                peak *= 0.75;
            }
            list.add(new Stroke(t, peak * scale));
            t += strike ? 40 + 110 * rng.nextDouble() : 60 + 220 * rng.nextDouble();
        }
        return new FlashLight(List.copyOf(list), strike ? 55 : 110);
    }

    /** Brightness at {@code ms} milliseconds after the flash, 0-1. */
    public double at(double ms) {
        double b = 0;
        for (Stroke s : strokes) {
            if (ms >= s.start()) {
                b = Math.max(b, s.peak() * Math.exp(-(ms - s.start()) / tau));
            }
        }
        return b;
    }

    /** When it has faded to nothing, milliseconds after the flash. */
    public double duration() {
        return strokes.get(strokes.size() - 1).start() + 6 * tau;
    }

    public List<Stroke> strokes() {
        return strokes;
    }

    /** Whether a stroke starts in [{@code fromMs}, {@code toMs}). */
    public boolean strokeStarts(double fromMs, double toMs) {
        for (Stroke s : strokes) {
            if (s.start() >= fromMs && s.start() < toMs) {
                return true;
            }
        }
        return false;
    }

    /** A seed for a flash, the same on every client. */
    public static long seed(double x, double y, double z) {
        long h = Double.doubleToLongBits(x) * 0x9E3779B97F4A7C15L;
        h ^= Double.doubleToLongBits(y) * 0xC2B2AE3D27D4EB4FL;
        h ^= Double.doubleToLongBits(z) * 0x165667B19E3779F9L;
        return h ^ (h >>> 29);
    }
}
