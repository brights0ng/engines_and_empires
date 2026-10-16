package dev.brights0ng.enginesandempires.weather.climate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Underground temperature, pure maths (weather phase 8c, {@code claude/weather-backbone-phase8c.md}; Bright,
 * 2026-10-15). {@link UndergroundProbe} gathers the inputs from the world; the numbers are server config
 * ({@code temperature.underground}).
 *
 * <pre>
 *   T           = lerp(outside, underground, insulation), then the cave biome's caps
 *   insulation  = ease(depth / 20) x (1 - skylight / 15)
 *   underground = lerp(annual mean at the ground, rock(y), pull(y)) + pull(y) x noise x 3 C + cave offset
 *   pull(y)     = 0 at y40 and above, rising linearly to 1 at y0
 *   rock(y)     = 21 C at y0 and above, rising linearly to 45 C at the bottom of the world
 * </pre>
 * <ul>
 *   <li><b>Above the band</b> (y40): the year's mean at the surface, eased in over the first 20 blocks of cover. No
 *       warming with depth there (Bright: mountains stay cool inside; the deep is the hot place).</li>
 *   <li><b>The band</b> (y40 to y0): the surface's mean gives way to a flat 21 C, so nobody needs to know what biome
 *       is overhead to know what a mine feels like.</li>
 *   <li><b>Below y0</b>: one lithospheric gradient everywhere, 21 C to 45 C at bedrock (0.375 C a block).</li>
 *   <li><b>Texture</b>: a smooth 3D noise of about ±3 C on a ~50-block scale, in full below y0 and growing through
 *       the band with the pull.</li>
 *   <li><b>Cave biomes</b>: an offset on the underground value, then optional min and max caps on the result
 *       anywhere in that biome under cover (a lush cave's mouth never freezes; the deep dark never warms past its
 *       cap).</li>
 *   <li><b>Sky</b>: sky light lets outside air into cave mouths and keeps sky-open pits and ravines outside air.</li>
 *   <li><b>Humidity</b> (Bright, 2026-10-15): under cover the air's relative humidity is one flat value (config
 *       {@code humidity}), reached by the same insulation as the temperature, so nobody needs to know the biome
 *       overhead; a cave biome rule may set its own. It feeds the heat index only.</li>
 * </ul>
 */
public final class Underground {

    /**
     * One cave biome's rule.
     *
     * @param biome  the biome id, e.g. {@code minecraft:lush_caves}
     * @param offset added to the underground value, C
     * @param useMin whether {@code min} applies
     * @param min    the coldest it gets anywhere in the biome under cover, C
     * @param useMax whether {@code max} applies
     * @param max    the warmest it gets anywhere in the biome under cover, C
     * @param humidity the relative humidity (0-1) of the air in the biome under cover, or NaN to use the flat value
     */
    public record CaveRule(String biome, double offset, boolean useMin, double min, boolean useMax, double max,
                           double humidity) {

        /** A rule that leaves the humidity to the flat underground value. */
        public CaveRule(String biome, double offset, boolean useMin, double min, boolean useMax, double max) {
            this(biome, offset, useMin, min, useMax, max, Double.NaN);
        }

        /** Whether this biome sets its own humidity. */
        public boolean hasHumidity() {
            return !Double.isNaN(humidity);
        }

        /** {@code t} held within the caps that are on. */
        public double cap(double t) {
            if (useMin) {
                t = Math.max(min, t);
            }
            if (useMax) {
                t = Math.min(max, t);
            }
            return t;
        }

        /**
         * Parses a config entry: {@code biome;offset=3;useMin=true;min=1;useMax=false;max=40}. Everything after the
         * biome is optional (offset 0, caps off, humidity left to the flat value); {@code humidity=0.85} (0 to 1) gives
         * the biome its own humidity. Throws {@link IllegalArgumentException} on a bad entry.
         */
        public static CaveRule parse(String entry) {
            String[] parts = entry.split(";");
            String biome = parts[0].trim();
            if (biome.isEmpty() || !biome.contains(":")) {
                throw new IllegalArgumentException("biome id expected first, like minecraft:lush_caves: " + entry);
            }
            double offset = 0;
            boolean useMin = false;
            double min = 0;
            boolean useMax = false;
            double max = 0;
            double humidity = Double.NaN;
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) {
                    continue;
                }
                int eq = p.indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException("key=value expected: " + p);
                }
                String key = p.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = p.substring(eq + 1).trim();
                switch (key) {
                    case "offset" -> offset = Double.parseDouble(value);
                    case "usemin" -> useMin = Boolean.parseBoolean(value);
                    case "min" -> min = Double.parseDouble(value);
                    case "usemax" -> useMax = Boolean.parseBoolean(value);
                    case "max" -> max = Double.parseDouble(value);
                    case "humidity" -> {
                        humidity = Double.parseDouble(value);
                        if (!(humidity >= 0 && humidity <= 1)) {
                            throw new IllegalArgumentException("humidity must be 0 to 1: " + entry);
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown key " + key + " in " + entry);
                }
            }
            return new CaveRule(biome, offset, useMin, min, useMax, max, humidity);
        }

        /** Whether {@code entry} parses. */
        public static boolean valid(Object entry) {
            if (!(entry instanceof String s)) {
                return false;
            }
            try {
                parse(s);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /**
     * The settings (server config {@code temperature.underground}).
     *
     * @param easeDepth   blocks of cover over which the outside air gives way to the underground value
     * @param bandTop     y where the pull toward the rock's temperature starts
     * @param bandBottom  y where it is complete and the rock's gradient begins
     * @param bandTemp    the rock's temperature through the band and at its bottom, C
     * @param bedrockTemp the rock's temperature at the bottom of the world, C
     * @param noiseAmp    the texture's strength, C
     * @param noiseScale  the texture's feature size, blocks
     * @param humidity    the relative humidity (0-1) of air under cover, the same everywhere (a cave biome may differ)
     * @param caves       cave biome rules by biome id
     */
    public record Settings(double easeDepth, double bandTop, double bandBottom, double bandTemp, double bedrockTemp,
                           double noiseAmp, double noiseScale, double humidity, Map<String, CaveRule> caves) {

        /** The flat underground humidity, 0-1 (a starting value, to be tuned in play). */
        public static final double DEFAULT_HUMIDITY = 0.6;

        /** Settings with the default flat humidity. */
        public Settings(double easeDepth, double bandTop, double bandBottom, double bandTemp, double bedrockTemp,
                        double noiseAmp, double noiseScale, Map<String, CaveRule> caves) {
            this(easeDepth, bandTop, bandBottom, bandTemp, bedrockTemp, noiseAmp, noiseScale, DEFAULT_HUMIDITY, caves);
        }

        public static final List<String> DEFAULT_CAVES = List.of(
                "minecraft:lush_caves;offset=3;useMin=true;min=1;useMax=false;max=40;humidity=0.85",
                "minecraft:dripstone_caves;offset=0;useMin=false;min=0;useMax=false;max=40",
                "minecraft:deep_dark;offset=-15;useMin=false;min=-10;useMax=true;max=5;humidity=0.35");

        public static final Settings DEFAULT = new Settings(20, 40, 0, 21, 45, 3, 50, DEFAULT_HUMIDITY,
                caves(DEFAULT_CAVES));

        /** Parses cave entries, skipping bad ones. */
        public static Map<String, CaveRule> caves(List<? extends String> entries) {
            Map<String, CaveRule> map = new LinkedHashMap<>();
            for (String e : entries) {
                if (CaveRule.valid(e)) {
                    CaveRule r = CaveRule.parse(e);
                    map.put(r.biome(), r);
                }
            }
            return Map.copyOf(map);
        }
    }

    /**
     * Every part of an underground reading, for the temperature service and the debug readout.
     *
     * @param depth       blocks of cover overhead (0 in the open)
     * @param skylight    the sky light level at the position, 0-15
     * @param insulation  how far the temperature has gone from the outside air to the underground value, 0-1
     * @param annualMean  the year's mean at the ground above, unclamped, C (NaN in the open)
     * @param pull        how far toward the rock's temperature, 0 (y40 and up) to 1 (y0 and down)
     * @param rock        the rock's temperature at this y, C
     * @param noise       the texture here, C (already scaled by the pull)
     * @param cave        the cave biome rule here, or null
     * @param outside     the outside air's temperature at the position, C
     * @param underground the underground value, C (NaN in the open)
     * @param temperature the result, C
     */
    public record Reading(double depth, int skylight, double insulation, double annualMean, double pull, double rock,
                          double noise, CaveRule cave, double outside, double underground, double temperature) {

        /** A reading in the open (or under cover the sky still sees into): the outside air, unchanged. */
        public static Reading open(double depth, double outside, int skylight) {
            return new Reading(Math.max(0, depth), skylight, 0, Double.NaN, 0, Double.NaN, 0, null, outside,
                    Double.NaN, outside);
        }
    }

    /** How much of the way to the underground value {@code depth} blocks of cover take the air, 0-1. */
    public static double ease(double depth, Settings s) {
        return s.easeDepth() <= 0 ? (depth > 0 ? 1 : 0) : smooth(depth / s.easeDepth());
    }

    /** The insulation at {@code depth} blocks of cover with sky light {@code skylight} (0-15), 0-1. */
    public static double insulation(double depth, int skylight, Settings s) {
        double open = Math.max(0, Math.min(15, skylight)) / 15.0;
        return ease(Math.max(0, depth), s) * (1 - open);
    }

    /**
     * The relative humidity (0-1) of the air given the outside air's {@code outside}, the {@code insulation} (0-1, as
     * for the temperature) and the cave biome rule here (or null): the outside humidity in the open, giving way to the
     * flat underground value (or the cave biome's own) as the cover takes hold.
     */
    public static double humidity(double outside, double insulation, CaveRule cave, Settings s) {
        if (insulation <= 0) {
            return outside;
        }
        double under = cave != null && cave.hasHumidity() ? cave.humidity() : s.humidity();
        double rh = outside + Math.min(1, insulation) * (under - outside);
        return Math.max(0, Math.min(1, rh));
    }

    /** How far toward the rock's temperature at height {@code y}, 0-1 (linear through the band). */
    public static double pull(double y, Settings s) {
        double span = s.bandTop() - s.bandBottom();
        if (span <= 0) {
            return y < s.bandTop() ? 1 : 0;
        }
        return clamp01((s.bandTop() - y) / span);
    }

    /** The rock's temperature at height {@code y} in a world whose bottom is {@code minY}, C. */
    public static double rock(double y, double minY, Settings s) {
        if (y >= s.bandBottom()) {
            return s.bandTemp();
        }
        double span = s.bandBottom() - minY;
        double f = span <= 0 ? 1 : clamp01((s.bandBottom() - y) / span);
        return s.bandTemp() + f * (s.bedrockTemp() - s.bandTemp());
    }

    /**
     * The full reading.
     *
     * @param outside    the outside air's temperature here, C
     * @param annualMean the year's mean at the ground above, unclamped, C
     * @param depth      blocks of cover overhead
     * @param skylight   sky light here, 0-15
     * @param y          the position's height
     * @param minY       the bottom of the world
     * @param noise      the raw texture noise here, -1 to 1
     * @param cave       the cave biome rule here, or null
     */
    public static Reading read(double outside, double annualMean, double depth, int skylight, double y, double minY,
                               double noise, CaveRule cave, Settings s) {
        double d = Math.max(0, depth);
        double ins = insulation(d, skylight, s);
        if (ins <= 0) {
            return Reading.open(d, outside, skylight);
        }
        double pull = pull(y, s);
        double rock = rock(y, minY, s);
        double texture = pull * Math.max(-1, Math.min(1, noise)) * s.noiseAmp();
        double under = annualMean + pull * (rock - annualMean) + texture + (cave == null ? 0 : cave.offset());
        double t = outside + ins * (under - outside);
        if (cave != null) {
            t = cave.cap(t);
        }
        return new Reading(d, skylight, ins, annualMean, pull, rock, texture, cave, outside, under, t);
    }

    private static double clamp01(double x) {
        return Math.max(0, Math.min(1, x));
    }

    private static double smooth(double x) {
        double c = clamp01(x);
        return c * c * (3 - 2 * c);
    }

    private Underground() {
    }
}
