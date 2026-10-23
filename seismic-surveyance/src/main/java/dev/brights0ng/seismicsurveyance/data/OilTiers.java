// Written by Gemini.
// Edited by Gemini: added multi-chunk shrinking reservoir shape and trap metadata helpers; default oil polygon resolution to 96 points; support reading maxRadius to reconstruct scaled non-overlapping reservoirs.
// Reorganized by Claude
package dev.brights0ng.seismicsurveyance.data;

import dev.brights0ng.sparseores.shape.oil.OilDepositGenerator;
import dev.brights0ng.sparseores.shape.oil.OilTrapType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Coarse tiers, identification and colours for Create: Diesel Generators oil deposits captured by seismic tools.
 */
public final class OilTiers {

    public static final String CRUDE_OIL = "crude_oil";
    public static final String CRUDE_OIL_RICH = "crude_oil_rich";
    public static final String CRUDE_OIL_BOTTOMLESS = "crude_oil_bottomless";

    /** Default polygon resolution for smooth, crease-free reservoir outline curves. */
    public static final int DEFAULT_POLYGON_POINTS = 96;

    /** Base iridescent violet-teal for normal oil. */
    public static final int COLOR_NORMAL = 0xFF6A4FB0;
    /** Brighter iridescent violet-teal for rich oil. */
    public static final int COLOR_RICH = 0xFF8A6FE0;
    /** High intensity glowing violet-teal for bottomless oil. */
    public static final int COLOR_BOTTOMLESS = 0xFFBA9FFF;

    public enum Tier {
        NORMAL(CRUDE_OIL, COLOR_NORMAL),
        RICH(CRUDE_OIL_RICH, COLOR_RICH),
        BOTTOMLESS(CRUDE_OIL_BOTTOMLESS, COLOR_BOTTOMLESS);

        private final String oreId;
        private final int color;

        Tier(String oreId, int color) {
            this.oreId = oreId;
            this.color = color;
        }

        public String oreId() {
            return oreId;
        }

        public int color() {
            return color;
        }
    }

    /** Whether an ore identifier represents an oil deposit. */
    public static boolean isOil(String oreId) {
        return oreId != null && oreId.startsWith("crude_oil");
    }

    /** The geological trap type from an oil reading identifier. */
    public static OilTrapType trapOf(String oreId) {
        if (oreId == null) {
            return OilTrapType.ANTICLINE;
        }
        String[] parts = oreId.split(":");
        if (parts.length >= 2) {
            return OilTrapType.byId(parts[1]);
        }
        return OilTrapType.ANTICLINE;
    }

    /** The strike orientation angle (in radians) from an oil reading identifier. */
    public static double strikeAngleOf(String oreId) {
        if (oreId == null) {
            return 0.0;
        }
        String[] parts = oreId.split(":");
        if (parts.length >= 3) {
            try {
                return Math.toRadians(Double.parseDouble(parts[2]));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    /** The reservoir fullness fraction [0, 1] from an oil reading identifier. */
    public static double fullnessOf(String oreId) {
        if (oreId == null) {
            return 1.0;
        }
        String[] parts = oreId.split(":");
        if (parts.length >= 4) {
            try {
                return Math.max(0.0, Math.min(1.0, Integer.parseInt(parts[3]) / 100.0));
            } catch (NumberFormatException ignored) {
            }
        }
        return 1.0;
    }

    /** The reservoir max radius from an oil reading identifier, or -1 if not encoded. */
    public static double maxRadiusOf(String oreId) {
        if (oreId == null) {
            return -1.0;
        }
        String[] parts = oreId.split(":");
        if (parts.length >= 5) {
            try {
                return Double.parseDouble(parts[4]);
            } catch (NumberFormatException ignored) {
            }
        }
        return -1.0;
    }

    /** Reconstructs the 2D boundary polygon in world coordinates for an oil deposit reading with smooth default resolution. */
    public static double[][] getPolygon(ReaderReading reading) {
        return getPolygon(reading, DEFAULT_POLYGON_POINTS);
    }

    /** Reconstructs the 2D boundary polygon in world coordinates for an oil deposit reading. */
    public static double[][] getPolygon(ReaderReading reading, int points) {
        OilTrapType trap = trapOf(reading.ore());
        double angle = strikeAngleOf(reading.ore());
        double fullness = fullnessOf(reading.ore());
        long seed = reading.deposit() != 0 ? reading.deposit() : ((long) reading.x() * 31L + reading.z());
        double maxRadius = maxRadiusOf(reading.ore());
        OilDepositGenerator.GeneratedReservoir res = (maxRadius > 0)
                ? OilDepositGenerator.buildReservoirWithRadius(trap, reading.x(), reading.z(), seed, angle, maxRadius)
                : OilDepositGenerator.buildReservoir(trap, reading.x(), reading.z(), seed, angle);
        return res.field().polygon(fullness, points);
    }

    /** Whether a world coordinate falls within this oil deposit's active reservoir. */
    public static boolean contains(ReaderReading reading, double worldX, double worldZ) {
        OilTrapType trap = trapOf(reading.ore());
        double angle = strikeAngleOf(reading.ore());
        double fullness = fullnessOf(reading.ore());
        long seed = reading.deposit() != 0 ? reading.deposit() : ((long) reading.x() * 31L + reading.z());
        double maxRadius = maxRadiusOf(reading.ore());
        OilDepositGenerator.GeneratedReservoir res = (maxRadius > 0)
                ? OilDepositGenerator.buildReservoirWithRadius(trap, reading.x(), reading.z(), seed, angle, maxRadius)
                : OilDepositGenerator.buildReservoir(trap, reading.x(), reading.z(), seed, angle);
        return res.field().contains(worldX, worldZ, fullness);
    }

    /** The coarse tier of an oil reading, or NORMAL by default. */
    public static Tier tierOf(String oreId) {
        if (oreId == null) {
            return Tier.NORMAL;
        }
        if (CRUDE_OIL_BOTTOMLESS.equals(oreId) || oreId.contains("bottomless")) {
            return Tier.BOTTOMLESS;
        }
        if (CRUDE_OIL_RICH.equals(oreId) || oreId.contains("rich")) {
            return Tier.RICH;
        }
        return Tier.NORMAL;
    }

    /** Display color (ARGB) for this oil reading (British spelling convention for codebase). */
    public static int colour(String oreId) {
        return colorFor(oreId);
    }

    /** Display color (ARGB) for this oil reading. */
    public static int colorFor(String oreId) {
        return tierOf(oreId).color();
    }

    /** Pure String display name for this oil reading (usable in pure unit tests). */
    public static String displayName(String oreId) {
        if (oreId != null && oreId.contains(":")) {
            OilTrapType trap = trapOf(oreId);
            int percent = (int) Math.round(fullnessOf(oreId) * 100.0);
            return trap.displayName() + " (" + percent + "%)";
        }
        return "crude_oil";
    }

    /** Translatable component for this oil tier or trap. */
    public static MutableComponent name(String oreId) {
        if (oreId != null && oreId.contains(":")) {
            return Component.literal(displayName(oreId));
        }
        return Component.translatable("seismic_surveyance.oil." + tierOf(oreId).oreId());
    }

    /** Coarse tier given the oil amount in millibuckets and default or configured thresholds. */
    public static String tierForAmount(int amount) {
        return tierForAmount(amount, 4_000_000, 10_000_000);
    }

    /** Coarse tier given the oil amount in millibuckets and custom thresholds. */
    public static String tierForAmount(int amount, int threshold, int infiniteThreshold) {
        if (amount < threshold) {
            return "";
        }
        if (amount >= infiniteThreshold || amount == Integer.MAX_VALUE) {
            return CRUDE_OIL_BOTTOMLESS;
        }
        if (amount >= 7_000_000) {
            return CRUDE_OIL_RICH;
        }
        return CRUDE_OIL;
    }

    private OilTiers() {
    }
}
