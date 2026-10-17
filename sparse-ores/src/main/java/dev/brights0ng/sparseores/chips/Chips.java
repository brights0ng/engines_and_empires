package dev.brights0ng.sparseores.chips;

import java.util.List;

/**
 * The chips: a quarter of a coal, diamond or nether quartz. Those ores drop a single item in vanilla, and there is no
 * smaller unit to cut them down to, so the pack adds one. They live in Sparse Ores (split seam S15) next to the rich
 * ores; the core's ore refining refers to them by id, and its loot cuts make the ordinary and rich ores drop them
 * (without the core, those ores drop vanilla's whole items; split step 4).
 */
public final class Chips {

    /** Chips that craft into one whole item. */
    public static final int CHIPS_PER_ITEM = 4;

    /** Burn time of a coal chip, in ticks: a quarter of coal's. */
    public static final int COAL_CHIP_BURN_TIME = 1600 / CHIPS_PER_ITEM;

    /** A chip, and the whole item four of them craft into. */
    public record Chip(String name, String item) {
    }

    public static final List<Chip> ALL = List.of(
            new Chip("coal_chip", "minecraft:coal"),
            new Chip("diamond_chip", "minecraft:diamond"),
            new Chip("quartz_chip", "minecraft:quartz"));

    private Chips() {
    }
}
