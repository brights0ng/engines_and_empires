package dev.brights0ng.enginesandempires.oregen.refining;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.enginesandempires.oregen.rich.RichOres;

/**
 * How much every ore is worth once it is refined, in one place. Nothing here touches Minecraft, so the rules can be
 * checked by plain unit tests; {@link RefiningDataGen} turns them into recipes and loot tables.
 *
 * <p>The pack cuts ore down to roughly a quarter of what vanilla and Create give. Deposits are huge, so each block has
 * to be worth less. What an ore block <em>drops</em> mostly stays as it was: the cut happens when it is refined.
 * <ul>
 *   <li><b>Metals</b> (iron, gold, copper, zinc). A raw ore, or a crushed ore, smelts into {@link #NUGGETS_PER_RAW}
 *       nuggets instead of an ingot. Washing crushed ore gives the same plus a {@link #WASH_BONUS_CHANCE} chance of
 *       one more nugget, so a Create line beats the furnace. Crushing an ore block keeps Create's numbers, which were
 *       already better than mining it.</li>
 *   <li><b>Chips</b> (coal, diamond, nether quartz). These ores drop a single item, so they drop a chip instead, a
 *       {@link #CHIPS_PER_ITEM quarter} of one.</li>
 *   <li><b>Group drops</b> (redstone, lapis, nether gold). These drop several items, so they simply drop fewer (see
 *       {@link RichOres}).</li>
 *   <li><b>Emerald</b> is left alone: villagers set its value.</li>
 * </ul>
 *
 * <p>Crushing an ore block beats mining it by the same margin for every ore: the margin Create gives metals once
 * washing is counted (see {@link #crushingLead}). Chips and group drops use it directly, since they have no washing
 * step of their own.
 *
 * <p>A silk-touched ore block smelts into what mining it would have dropped, so Silk Touch plus a furnace is no
 * shortcut. A rich block gives {@link RichOres#RICHNESS} times its ordinary block, whichever way it is refined.
 */
public final class Refining {

    /** Nuggets from one raw ore or one crushed ore in a furnace (vanilla: 9, a whole ingot). */
    public static final int NUGGETS_PER_RAW = 2;

    /** Nuggets always given for washing one crushed ore. */
    public static final int WASHED_NUGGETS = 2;

    /** The chance of one more nugget when washing crushed ore. */
    public static final double WASH_BONUS_CHANCE = 0.5;

    /** Chips that craft into one whole item. */
    public static final int CHIPS_PER_ITEM = 4;

    /** How much other sources of metal keep (iron golems, washing gravel, red sand and soul sand): the same as raw ore. */
    public static final double OTHER_SOURCES = NUGGETS_PER_RAW / 9.0;

    /** Burn time of a coal chip, in ticks: a quarter of coal's. */
    public static final int COAL_CHIP_BURN_TIME = 1600 / CHIPS_PER_ITEM;

    /** Create's crushing wheels turn a stone-type ore block into this many crushed ores on average (1 + 75% of 1). */
    public static final double CREATE_CRUSHED_PER_STONE_ORE = 1.75;

    /** ...and a deepslate ore block into this many (2 + 25% of 1). Nether ores count as deep. */
    public static final double CREATE_CRUSHED_PER_DEEP_ORE = 2.25;

    /** The chance Create gives of the rock itself (cobblestone, cobbled deepslate, netherrack) when crushing an ore. */
    public static final double ROCK_CHANCE = 0.125;

    /** The chance Create gives of its experience nuggets when crushing an ore. */
    public static final double XP_CHANCE = 0.75;

    /** Average nuggets from washing one crushed ore. */
    public static double washedNuggets() {
        return WASHED_NUGGETS + WASH_BONUS_CHANCE;
    }

    /**
     * How many times more an ore block gives through crushing wheels and a washer than mined and smelted: 2.19 for
     * stone-type ore, 2.81 for deepslate and nether ore.
     */
    public static double crushingLead(boolean deep) {
        double crushed = deep ? CREATE_CRUSHED_PER_DEEP_ORE : CREATE_CRUSHED_PER_STONE_ORE;
        return crushed * washedNuggets() / NUGGETS_PER_RAW;
    }

    /** How an ore is cut. */
    public enum Kind {
        /** Refined into nuggets. */
        METAL,
        /** Drops a chip instead of the whole item. */
        CHIP,
        /** Drops fewer of its item. */
        GROUP,
        /** Untouched (emerald). */
        KEPT
    }

    /**
     * An average amount as a recipe can give it: a fixed count, plus a chance of one more. 2.3 is 2, and a 30% chance
     * of a third. The chance is rounded to 5%.
     */
    public record Amount(int count, double chance) {

        public static Amount of(double mean) {
            if (mean < 0) {
                throw new IllegalArgumentException("negative amount " + mean);
            }
            int count = (int) Math.floor(mean);
            double chance = Math.round((mean - count) * 20) / 20.0;
            if (chance >= 1) {
                count++;
                chance = 0;
            }
            return new Amount(count, chance);
        }

        public double mean() {
            return count + chance;
        }
    }

    /**
     * One ore and where its refining numbers come from.
     *
     * @param oreId        the ore's id in {@code OreTypes} and {@link RichOres}
     * @param kind         how it is cut
     * @param furnaceItem  what its ore block smelts into
     * @param crushedItem  what crushing its ore block gives
     * @param stoneTime    Create's crushing time for the stone-type block, in ticks
     * @param deepTime     Create's crushing time for the deepslate or nether block
     * @param xpNuggets    how many experience nuggets Create's crushing gives (at {@link #XP_CHANCE})
     * @param smeltXp      the experience the furnace gives for the ore block
     */
    public record Ore(String oreId, Kind kind, String furnaceItem, String crushedItem, int stoneTime, int deepTime,
                      int xpNuggets, double smeltXp) {

        /** The ore's entry in {@link RichOres}, which holds what its ordinary block drops. */
        public RichOres.Spec spec() {
            return RichOres.forOre(oreId);
        }

        /** What the ordinary ore block drops on average when mined without Fortune. */
        public double dropMean() {
            RichOres.Spec spec = spec();
            return (spec.minDrop() + spec.maxDrop()) / 2.0;
        }

        /**
         * What a silk-touched ordinary ore block smelts into: what mining it would give. For a metal, that is its raw
         * drop smelted (copper drops 3.5 raw on average, so 7 nuggets); for the rest, the fewest it drops.
         */
        public int furnaceCount() {
            return kind == Kind.METAL ? (int) Math.round(NUGGETS_PER_RAW * dropMean()) : spec().minDrop();
        }

        /** The average number of {@link #crushedItem} from crushing one ordinary block. */
        public double crushedMean(boolean deep) {
            if (kind == Kind.METAL || kind == Kind.KEPT) {
                double[] create = CREATE_CRUSHING.get(oreId);
                return deep ? create[1] : create[0];
            }
            return crushingLead(deep) * dropMean();
        }

        /** Whether the pack replaces Create's recipe for crushing the ordinary block (only chips and group drops). */
        public boolean overridesCrushing() {
            return kind == Kind.CHIP || kind == Kind.GROUP;
        }

        /** Whether the pack replaces the furnace recipe for the ordinary block. Redstone and lapis already give 1. */
        public boolean overridesFurnace() {
            return kind == Kind.METAL || kind == Kind.CHIP || oreId.equals("nether_gold");
        }

        public int crushingTime(boolean deep) {
            return deep ? deepTime : stoneTime;
        }
    }

    /** Create's own crushing output for the ores the pack does not override: {stone, deepslate} averages. */
    private static final Map<String, double[]> CREATE_CRUSHING = new HashMap<>();

    static {
        CREATE_CRUSHING.put("iron", new double[] {1.75, 2.25});
        CREATE_CRUSHING.put("gold", new double[] {1.75, 2.25});
        CREATE_CRUSHING.put("zinc", new double[] {1.75, 2.25});
        CREATE_CRUSHING.put("copper", new double[] {5.25, 7.25});
        CREATE_CRUSHING.put("emerald", new double[] {1.75, 2.25});
    }

    public static final List<Ore> ORES = List.of(
            new Ore("coal", Kind.CHIP, "engines_and_empires:coal_chip", "engines_and_empires:coal_chip", 150, 300, 1, 0.1),
            new Ore("iron", Kind.METAL, "minecraft:iron_nugget", "create:crushed_raw_iron", 250, 350, 1, 0.7),
            new Ore("copper", Kind.METAL, "create:copper_nugget", "create:crushed_raw_copper", 250, 350, 1, 0.7),
            new Ore("zinc", Kind.METAL, "create:zinc_nugget", "create:crushed_raw_zinc", 250, 350, 1, 1.0),
            new Ore("redstone", Kind.GROUP, "minecraft:redstone", "minecraft:redstone", 250, 350, 1, 0.7),
            new Ore("lapis", Kind.GROUP, "minecraft:lapis_lazuli", "minecraft:lapis_lazuli", 250, 350, 1, 0.2),
            new Ore("gold", Kind.METAL, "minecraft:gold_nugget", "create:crushed_raw_gold", 250, 350, 2, 1.0),
            new Ore("emerald", Kind.KEPT, "minecraft:emerald", "minecraft:emerald", 350, 450, 1, 1.0),
            new Ore("diamond", Kind.CHIP, "engines_and_empires:diamond_chip", "engines_and_empires:diamond_chip", 350, 450, 1, 1.0),
            new Ore("nether_gold", Kind.GROUP, "minecraft:gold_nugget", "minecraft:gold_nugget", 350, 350, 1, 1.0),
            new Ore("nether_quartz", Kind.CHIP, "engines_and_empires:quartz_chip", "engines_and_empires:quartz_chip", 350, 350, 1, 0.2));

    /**
     * A metal: its raw ore, crushed ore and nugget, and what washing its crushed ore gives besides nuggets (kept as
     * Create has it).
     *
     * @param create whether the metal and its recipes are Create's (zinc) rather than vanilla's
     */
    public record Metal(String oreId, String name, String raw, String crushed, String nugget, String byproduct,
                        double byproductChance, double rawXp, boolean create) {
    }

    public static final List<Metal> METALS = List.of(
            new Metal("iron", "iron", "minecraft:raw_iron", "create:crushed_raw_iron", "minecraft:iron_nugget",
                    "minecraft:redstone", 0.75, 0.7, false),
            new Metal("gold", "gold", "minecraft:raw_gold", "create:crushed_raw_gold", "minecraft:gold_nugget",
                    "minecraft:quartz", 0.5, 1.0, false),
            new Metal("copper", "copper", "minecraft:raw_copper", "create:crushed_raw_copper", "create:copper_nugget",
                    "minecraft:clay_ball", 0.5, 0.7, false),
            new Metal("zinc", "zinc", "create:raw_zinc", "create:crushed_raw_zinc", "create:zinc_nugget",
                    "minecraft:gunpowder", 0.25, 0.7, true));

    /** A chip, and the whole item four of them craft into. */
    public record Chip(String name, String item) {
    }

    public static final List<Chip> CHIPS = List.of(
            new Chip("coal_chip", "minecraft:coal"),
            new Chip("diamond_chip", "minecraft:diamond"),
            new Chip("quartz_chip", "minecraft:quartz"));

    /** The ore with this id. */
    public static Ore ore(String oreId) {
        for (Ore ore : ORES) {
            if (ore.oreId().equals(oreId)) {
                return ore;
            }
        }
        throw new IllegalArgumentException("No refining entry for ore '" + oreId + "'");
    }

    /** A chance cut down like other metal sources, rounded to half a percent. */
    public static double otherSourceChance(double vanillaMean) {
        return Math.round(vanillaMean * OTHER_SOURCES * 200) / 200.0;
    }

    private Refining() {
    }
}
