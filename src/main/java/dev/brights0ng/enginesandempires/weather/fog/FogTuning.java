package dev.brights0ng.enginesandempires.weather.fog;

/**
 * The fog's numbers, as plain fields so pure Java (the in-cloud probe, tests) can read them without the config system.
 * {@code CloudConfig} copies its {@code fog} section into these on load and whenever the file changes; tests see the
 * defaults.
 *
 * <p>Decided 2026-10-04 (Bright): visual only; inside a cloud the fog snaps on and off with the camera, at a visibility
 * by cloud type (storm clouds thickest, 5 blocks), fading out as a cloud forms or dies; storm fog follows the rain
 * falling on the camera (more rain, thicker), with snow halving visibility.
 */
public final class FogTuning {

    /** Whether the pack's fog is on (and Project Atmosphere's off). */
    public static volatile boolean enabled = true;

    // ---- inside clouds: visibility in blocks (0 = no fog in that type)
    /** Cumulonimbus and nimbostratus. */
    public static volatile double stormCloudVisibility = 5;
    /** Cumulus congestus and stratus. */
    public static volatile double congestusVisibility = 8;
    /** Stratocumulus and fair-weather cumulus. */
    public static volatile double fairCloudVisibility = 12;
    /** Cirrus and cirrostratus (high and thin; 0 = none). */
    public static volatile double highCloudVisibility = 0;
    /** A cloud only just forming, or nearly gone: its visibility eases toward this as it erodes. */
    public static volatile double wispVisibility = 48;

    // ---- rain and snow
    /** Visibility in the heaviest rain (strength 1), blocks; lighter rain sees further, as this / strength. */
    public static volatile double heavyRainVisibility = 48;
    /** Snow's visibility as a share of rain's at the same strength. */
    public static volatile double snowVisibility = 0.5;
    /**
     * Where the rain fog starts in full rain, as a share of its visibility: 0 is right at the camera, so the fog
     * thickens continuously from you outward instead of standing as a wall at some distance.
     */
    public static volatile double rainFogStart = 0.0;
    /** The rain colour, a grey from 0 (black) to 1 (white), in full daylight (fog and sky). */
    public static volatile double rainFogBrightness = 0.62;
    /** The rain grey's brightness at night, as a share of its daylight value. */
    public static volatile double rainNightBrightness = 0.05;
    /** Rain strength at which the fog is fully that grey instead of the sky's colour (lighter rain blends). */
    public static volatile double rainFullGrey = 0.35;
    /** Seconds the rain fog takes to follow changes (so the edge of a rain core doesn't pop). */
    public static volatile double rainEaseSeconds = 1.0;

    /** Visibility inside a cloud of type {@code typeId} when fully formed, blocks; 0 for no fog. */
    public static double cloudVisibility(String typeId) {
        dev.brights0ng.enginesandempires.weather.cloud.CloudType t =
                dev.brights0ng.enginesandempires.weather.cloud.CloudType.of(typeId);
        if (t == null) {
            return fairCloudVisibility;
        }
        return switch (t.fog) {
            case STORM -> stormCloudVisibility;
            case THICK -> congestusVisibility;
            case HIGH -> highCloudVisibility;
            case FAIR -> fairCloudVisibility;
        };
    }

    /**
     * Visibility inside a cloud: its type's, eased toward {@link #wispVisibility} by how unformed or eroded it is
     * ({@code thin}, 0 fully formed to 1 barely there). 0 if the type has no fog.
     */
    public static double cloudVisibility(String typeId, double thin) {
        double base = cloudVisibility(typeId);
        if (base <= 0) {
            return 0;
        }
        double t = Math.max(0, Math.min(1, thin));
        return base + (Math.max(base, wispVisibility) - base) * t;
    }

    /**
     * Visibility in rain or snow of {@code strength} (0-1), blocks; infinite when nothing falls.
     */
    public static double rainVisibility(double strength, boolean snow) {
        return rainVisibility(strength, snow ? 1 : 0);
    }

    /**
     * The same, with {@code snowShare} (0-1) of the way from rain to snow, so a camera crossing the rain-snow line
     * eases between them instead of halving its visibility in one step.
     */
    public static double rainVisibility(double strength, double snowShare) {
        if (strength <= 1e-3) {
            return Double.POSITIVE_INFINITY;
        }
        double share = Math.max(0, Math.min(1, snowShare));
        return heavyRainVisibility / Math.min(1, strength) * (1 + (snowVisibility - 1) * share);
    }

    private FogTuning() {
    }
}
