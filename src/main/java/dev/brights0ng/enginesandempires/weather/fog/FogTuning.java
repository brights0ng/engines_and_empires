package dev.brights0ng.enginesandempires.weather.fog;

/**
 * The fog's numbers, as plain fields so pure Java (the in-cloud probe, tests) can read them without the config system.
 * {@code CloudConfig} copies its {@code fog} section into these on load and whenever the file changes; tests see the
 * defaults.
 *
 * <p>Decided 2026-10-04 (Bright): visual only; inside a cloud the fog snaps on and off with the camera, at a visibility
 * by cloud type (storm clouds thickest, 5 blocks), fading out as a cloud forms or dies; storm fog follows the rain
 * falling on the camera (more rain, thicker), with snow halving visibility.
 *
 * <p>Reworked 2026-10-09 (phase 6d, Bright: continuous, driven by the cloud's darkness, not steps): the outdoor fog is
 * an extinction, how much each block of air dims what is behind it, summed from the rain or snow falling
 * ({@link #heavyRainVisibility}) and the storm's darkness overhead and around ({@link #stormHazeVisibility}). The
 * visibility, where the fog starts, and how far the fog and sky turn to the storm's colour ({@link #tint}) all follow
 * that one number smoothly, so there are no thresholds anywhere.
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
    /**
     * The rain and storm fog colour in full daylight (fog and sky), red, green, blue 0-1: a dark blue-grey (Bright,
     * 2026-10-09).
     */
    public static volatile double[] rainFogColour = {0.36, 0.41, 0.50};
    /** The snow fog colour in full daylight: a pale whitish grey with only a slight blue (Bright, 2026-10-09). */
    public static volatile double[] snowFogColour = {0.82, 0.84, 0.88};
    /** The fog colours' brightness at night, as a share of their daylight value. */
    public static volatile double rainNightBrightness = 0.05;
    /** Seconds the rain fog takes to follow changes (so the edge of a rain core doesn't pop). */
    public static volatile double rainEaseSeconds = 1.0;

    // ---- storms (phase 6d)
    /** Visibility in the darkest storm's air with nothing falling, blocks (gloom and haze under the storm). */
    public static volatile double stormHazeVisibility = 400;
    /**
     * How much fog turns the fog and sky to the storm's colour, as a visibility in blocks: at this visibility they are
     * about two-thirds of the way there, at a third of it all the way.
     */
    public static volatile double stormGreyVisibility = 300;
    /** How much darker the storm colour is under the darkest storm (0.5 = half as bright as light rain's colour). */
    public static volatile double stormDarkening = 0.5;
    /**
     * How much height counts toward the storm fog on the clouds, against horizontal distance (0 = a column: the cloud
     * straight overhead never fogs; 1 = as for anything else).
     */
    public static volatile double cloudFogHeightWeight = 0.05;

    /** A hex colour ({@code RRGGBB}, with or without {@code #}) as red, green, blue 0-1, or {@code fallback}. */
    public static double[] parseColour(String hex, double[] fallback) {
        if (hex == null) {
            return fallback;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(s, 16);
            return new double[] {((v >> 16) & 0xFF) / 255.0, ((v >> 8) & 0xFF) / 255.0, (v & 0xFF) / 255.0};
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The air's extinction, per block: rain or snow of {@code strength} ({@code snowShare} of the way to snow) plus the
     * storm's haze at {@code darkness} (0-1). Visibility is about its inverse.
     */
    public static double extinction(double strength, double snowShare, double darkness) {
        double ext = 0;
        if (strength > 0) {
            double share = Math.max(0, Math.min(1, snowShare));
            ext += Math.min(1, strength) / (heavyRainVisibility * (1 + (snowVisibility - 1) * share));
        }
        double d = Math.max(0, Math.min(1, darkness));
        if (d > 0 && stormHazeVisibility > 0) {
            ext += d * Math.sqrt(d) / stormHazeVisibility;
        }
        return ext;
    }

    /** The fog's distance for extinction {@code ext} on top of the game's own fog ending at {@code far}. */
    public static double visibility(double ext, double far) {
        return 1 / (1 / Math.max(1e-3, far) + Math.max(0, ext));
    }

    /** How far (0-1) the fog and sky turn to the storm's colour at extinction {@code ext}. */
    public static double tint(double ext) {
        return 1 - Math.exp(-Math.max(0, ext) * Math.max(1, stormGreyVisibility));
    }

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
