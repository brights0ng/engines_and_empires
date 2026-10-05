package dev.brights0ng.enginesandempires.geophone;

import java.util.List;
import java.util.Map;

import dev.brights0ng.enginesandempires.oregen.OreTypes;

/**
 * What a brass geophone shows while it glows: which ore's colour to use when several pulses overlap, and what that
 * colour is.
 *
 * <p>A brass geophone hears every ore, so more than one deposit's pulse can be lighting it at once. Rather than flicker
 * between them, it keeps showing whichever one it is already showing for as long as that pulse is still going, and only
 * picks a new one once that pulse ends. When it does have to pick, it takes the one that has been running longest (the
 * earliest to start among those still active), so a newer, shorter pulse arriving on top of a long one does not steal
 * the display and immediately hand it back.
 *
 * <p>The colours themselves are a fixed palette, one per ore, chosen to read apart from each other at a glance; they make
 * no attempt to be colour-blind safe on their own; a blink pattern is set aside for later. An ore this pack does not know
 * about falls back to plain white rather than failing.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class GeophoneGlow {

    /** One of a geophone's currently active pulses, just enough of it to choose between them. */
    public record ActivePulse(long start, String oreId) {
    }

    /** Plain white: shown for an ore with no colour of its own. Never returned for anything in {@link #COLORS}. */
    public static final float[] FALLBACK_COLOR = {1.0F, 1.0F, 1.0F};

    private static final Map<String, float[]> COLORS = Map.ofEntries(
            Map.entry("iron", rgb(0xE8998D)),         // salmon
            Map.entry("copper", rgb(0xE37B3C)),        // orange
            Map.entry("zinc", rgb(0xA8D8E8)),          // pale blue
            Map.entry("gold", rgb(0xF4D160)),          // yellow
            Map.entry("nether_gold", rgb(0xE0942A)),   // amber: distinct from gold's yellow
            Map.entry("redstone", rgb(0xD93B3B)),      // red
            Map.entry("lapis", rgb(0x3B6FD9)),         // blue
            Map.entry("diamond", rgb(0x4DE0E0)),       // cyan
            Map.entry("emerald", rgb(0x3BD96B)),       // green
            Map.entry("nether_quartz", rgb(0xF0F0F0)), // white
            Map.entry("crystal", rgb(0xB45CE0)),       // purple
            Map.entry("coal", rgb(0x707070))           // dim smoke
    );

    /**
     * Which ore's pulse a geophone should show now, given every pulse currently active on it and whichever it was already
     * showing (or null if it was dark). Returns null if nothing is active at all.
     */
    public static String choose(List<ActivePulse> active, String previousOreId) {
        if (active.isEmpty()) {
            return null;
        }
        if (previousOreId != null) {
            for (ActivePulse pulse : active) {
                if (pulse.oreId().equals(previousOreId)) {
                    return previousOreId;
                }
            }
        }
        ActivePulse earliest = active.get(0);
        for (ActivePulse pulse : active) {
            if (pulse.start() < earliest.start()) {
                earliest = pulse;
            }
        }
        return earliest.oreId();
    }

    /** The colour to tint the sensor with for this ore, as {r, g, b} floats from 0 to 1. */
    public static float[] colorFor(String oreId) {
        return COLORS.getOrDefault(oreId, FALLBACK_COLOR);
    }

    private static float[] rgb(int packed) {
        return new float[]{
                ((packed >> 16) & 0xFF) / 255.0F,
                ((packed >> 8) & 0xFF) / 255.0F,
                (packed & 0xFF) / 255.0F
        };
    }

    /** Every ore this pack generates has its own colour: nothing falls back to plain white by accident. */
    static boolean coversEveryOre() {
        for (var type : OreTypes.ALL) {
            if (!COLORS.containsKey(type.id())) {
                return false;
            }
        }
        return true;
    }

    private GeophoneGlow() {
    }
}
