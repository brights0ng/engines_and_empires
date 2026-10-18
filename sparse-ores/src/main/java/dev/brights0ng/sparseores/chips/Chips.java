package dev.brights0ng.sparseores.chips;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.sparseores.SparseOres;
import dev.brights0ng.sparseores.rich.RichOres;

/**
 * The chips: a quarter of a coal, diamond or nether quartz. Those ores drop a single item in vanilla, and there is no
 * smaller unit to cut them down to, so Sparse Ores adds one: their ordinary blocks drop one chip instead of the whole
 * item, and their rich blocks five chips ({@link #chipped}; Bright's option A after split step 4). The ordinary blocks'
 * chips can be switched off in the startup config ({@code SparseOresConfig}, {@link ChipDropFunction}); the rich blocks'
 * cannot. Other mods refer to the chips by id (the Engines and Empires core's ore refining does).
 */
public final class Chips {

    /** Chips that craft into one whole item. */
    public static final int CHIPS_PER_ITEM = 4;

    /**
     * The id of the load condition the ordinary chip ores' smelting carries, true while chip drops are on
     * ({@code ChipDropsCondition}).
     */
    public static final String CHIP_DROPS_CONDITION = SparseOres.MODID + ":chip_drops";

    /** Burn time of a coal chip, in ticks: a quarter of coal's. */
    public static final int COAL_CHIP_BURN_TIME = 1600 / CHIPS_PER_ITEM;

    /** A chip, and the whole item four of them craft into. */
    public record Chip(String name, String item) {

        /** The chip's own item id, such as {@code sparse_ores:coal_chip}. */
        public String id() {
            return SparseOres.MODID + ":" + name;
        }
    }

    public static final List<Chip> ALL = List.of(
            new Chip("coal_chip", "minecraft:coal"),
            new Chip("diamond_chip", "minecraft:diamond"),
            new Chip("quartz_chip", "minecraft:quartz"));

    /** The chip of a whole item (such as {@code minecraft:coal}), or null if the item has none. */
    public static Chip forItem(String item) {
        for (Chip chip : ALL) {
            if (chip.item().equals(item)) {
                return chip;
            }
        }
        return null;
    }

    /** The chip with this item id (such as {@code sparse_ores:coal_chip}), or null if it is not a chip. */
    public static Chip forChipId(String id) {
        for (Chip chip : ALL) {
            if (chip.id().equals(id)) {
                return chip;
            }
        }
        return null;
    }

    /**
     * What an ore's blocks drop in Sparse Ores, from its {@link RichOres} entry (which holds vanilla's drop): an ore whose
     * vanilla drop is a whole item that has a chip drops one chip for each such item instead, Fortune and Silk Touch
     * working as before; every other ore keeps vanilla's drop. The rich blocks drop {@link RichOres#RICHNESS} times this.
     */
    public static RichOres.Spec chipped(RichOres.Spec spec) {
        Chip chip = forItem(spec.drop());
        return chip == null ? spec : spec.withDrop(chip.id(), spec.minDrop(), spec.maxDrop());
    }

    /** The ores whose blocks drop chips (coal, diamond and nether quartz), as their {@link RichOres} entries. */
    public static List<RichOres.Spec> ores() {
        List<RichOres.Spec> ores = new ArrayList<>();
        for (RichOres.Spec spec : RichOres.ALL) {
            if (forItem(spec.drop()) != null) {
                ores.add(spec);
            }
        }
        return List.copyOf(ores);
    }

    private Chips() {
    }
}
