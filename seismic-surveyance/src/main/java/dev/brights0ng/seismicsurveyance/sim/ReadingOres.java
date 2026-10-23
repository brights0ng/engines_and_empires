// Edited by Gemini: added oil display names and colours.
// Reorganized by Claude
package dev.brights0ng.seismicsurveyance.sim;

import dev.brights0ng.seismicsurveyance.data.OilTiers;
import dev.brights0ng.seismicsurveyance.logic.GeophoneGlow;
import dev.brights0ng.sparseores.SparseOres;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * How a reading's ore is shown: its name, and the colour it is marked in. The colours are the brass geophone's, so a deposit
 * that lit a geophone salmon is marked salmon on the smart logger's map too. A reading that does not know its ore (one taken
 * before that was kept) is called "Unknown ore" and marked grey. The names are Sparse Ores' ({@code sparse_ores.ore.<ore>}).
 */
public final class ReadingOres {

    /** The grey an unknown ore is marked in. */
    public static final int UNKNOWN_COLOUR = 0xFF9A9DA5;

    /** The ore's name, such as "Iron". */
    public static MutableComponent name(String ore) {
        if (OilTiers.isOil(ore)) {
            return OilTiers.name(ore);
        }
        return ore == null || ore.isEmpty()
                ? Component.translatable(SparseOres.MODID + ".ore.unknown")
                : Component.translatable(SparseOres.MODID + ".ore." + ore);
    }

    /** The ore's colour, as opaque ARGB. */
    public static int colour(String ore) {
        if (ore == null || ore.isEmpty()) {
            return UNKNOWN_COLOUR;
        }
        if (OilTiers.isOil(ore)) {
            return OilTiers.colorFor(ore);
        }
        float[] rgb = GeophoneGlow.colorFor(ore);
        return 0xFF000000 | (Math.round(rgb[0] * 255) << 16) | (Math.round(rgb[1] * 255) << 8) | Math.round(rgb[2] * 255);
    }

    /** The ore's name, in the ore's colour. */
    public static MutableComponent colouredName(String ore) {
        return name(ore).withStyle(style -> style.withColor(colour(ore) & 0xFFFFFF));
    }

    private ReadingOres() {
    }
}
