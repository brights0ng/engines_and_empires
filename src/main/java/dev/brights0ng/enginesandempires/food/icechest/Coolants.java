package dev.brights0ng.enginesandempires.food.icechest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the ice chest burns, and how long each lasts: written in the config as {@code "<item id> <multiplier>"}, where the
 * multiplier is of one block of ice's time.
 *
 * <p>Bright's values (2026-09-28): ice 1 (2.5 minutes), packed ice 4, blue ice 16, snow block 1/4, snowball and snow layer
 * 1/16. Loose ice gives the most cooling for the ice gathered; compacted ice fits more into the chest's one slot.
 *
 * <p>Plain Java on purpose (no Minecraft types), so it can be unit tested.
 */
public final class Coolants {

    /** One block of ice: 2.5 minutes. */
    public static final long DEFAULT_ICE_TICKS = 3000;

    public static final List<String> DEFAULT_LINES = List.of(
            "minecraft:ice 1",
            "minecraft:packed_ice 4",
            "minecraft:blue_ice 16",
            "minecraft:snow_block 0.25",
            "minecraft:snowball 0.0625",
            "minecraft:snow 0.0625");

    /** A config line as (item id, multiplier), or empty if it is not one. */
    public static Optional<Map.Entry<String, Double>> parse(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 2 || parts[0].isEmpty()) {
            return Optional.empty();
        }
        try {
            double multiplier = Double.parseDouble(parts[1]);
            return multiplier > 0 && multiplier <= 10000 ? Optional.of(Map.entry(parts[0], multiplier)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Every item id to how many ticks one of it cools for. */
    public static Map<String, Long> ticksByItem(List<? extends String> lines, long iceTicks) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String line : lines) {
            parse(line).ifPresent(entry -> out.put(entry.getKey(), Math.max(1, Math.round(entry.getValue() * iceTicks))));
        }
        return out;
    }

    private Coolants() {
    }
}
