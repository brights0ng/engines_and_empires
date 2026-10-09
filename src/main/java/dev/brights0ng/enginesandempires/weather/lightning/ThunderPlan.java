package dev.brights0ng.enginesandempires.weather.lightning;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * What thunder a flash makes at a distance (weather phase 6c; Bright, 2026-10-08: vanilla sounds for now), pure Java.
 *
 * <p>Sound travels at {@link #SPEED} blocks a second (real sound speed, metre for block), so the old trick of counting
 * the seconds still works: about 3 seconds per kilometre. Vanilla's two lightning sounds, layered and pitched by
 * distance:
 * <ul>
 *   <li><b>Close</b> (under {@value #NEAR} blocks): a strike's crack ({@link Sound#IMPACT}) and loud thunder.</li>
 *   <li><b>Middle</b> (to {@value #MIDDLE}): thunder, lower and quieter with distance.</li>
 *   <li><b>Far</b> (to {@value #FAR}): low thunder with a second, later, lower layer: the long roll of distant
 *       thunder.</li>
 *   <li><b>Distant</b> (beyond): a faint, very low rumble, fading out at the flash range.</li>
 * </ul>
 * In-cloud flashes are a little quieter and never crack.
 */
public final class ThunderPlan {

    /** Blocks a second. */
    public static final double SPEED = 343;
    public static final double NEAR = 160;
    public static final double MIDDLE = 800;
    public static final double FAR = 1800;

    public enum Sound {
        IMPACT, THUNDER
    }

    /** One sound: what, how loud (0-1), its pitch, and when, seconds after the flash. */
    public record Layer(Sound sound, float volume, float pitch, double delay) {
    }

    /**
     * The thunder for a flash {@code distance} blocks away ({@code strikeDistance} from where it struck, or NaN), out
     * to {@code range}, from a seed the same on every client.
     */
    public static List<Layer> of(double distance, double strikeDistance, boolean strike, double range, long seed) {
        List<Layer> out = new ArrayList<>(3);
        if (distance > range) {
            return out;
        }
        SplittableRandom rng = new SplittableRandom(seed);
        double delay = distance / SPEED;
        float quiet = strike ? 1f : 0.8f;
        if (strike && !Double.isNaN(strikeDistance) && strikeDistance < NEAR) {
            out.add(new Layer(Sound.IMPACT, 1f, (float) (0.5 + 0.2 * rng.nextDouble()), strikeDistance / SPEED));
        }
        if (distance < NEAR) {
            out.add(new Layer(Sound.THUNDER, quiet, (float) (0.9 + 0.1 * rng.nextDouble()), delay));
        } else if (distance < MIDDLE) {
            double f = (distance - NEAR) / (MIDDLE - NEAR);
            out.add(new Layer(Sound.THUNDER, (float) (quiet * (1 - 0.4 * f)),
                    (float) (0.9 - 0.1 * f + 0.05 * rng.nextDouble()), delay));
        } else if (distance < FAR) {
            double f = (distance - MIDDLE) / (FAR - MIDDLE);
            float v = (float) (quiet * (0.6 - 0.3 * f));
            float p = (float) (0.75 - 0.1 * f + 0.05 * rng.nextDouble());
            out.add(new Layer(Sound.THUNDER, v, p, delay));
            out.add(new Layer(Sound.THUNDER, v * 0.6f, p - 0.08f, delay + 0.5 + 0.6 * rng.nextDouble()));
        } else {
            double f = Math.min(1, (distance - FAR) / Math.max(1, range - FAR));
            float v = (float) (quiet * (0.3 - 0.22 * f));
            float p = (float) (0.55 - 0.05 * f + 0.05 * rng.nextDouble());
            out.add(new Layer(Sound.THUNDER, v, p, delay));
            out.add(new Layer(Sound.THUNDER, v * 0.7f, p - 0.05f, delay + 0.8 + 0.8 * rng.nextDouble()));
        }
        return out;
    }

    private ThunderPlan() {
    }
}
