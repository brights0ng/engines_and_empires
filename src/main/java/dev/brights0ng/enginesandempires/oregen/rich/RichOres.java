package dev.brights0ng.enginesandempires.oregen.rich;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes every rich ore block: which ore it is a richer version of, which block it copies, and what
 * that block drops. Rich ore is identical to its ordinary counterpart in every way (hardness, sounds,
 * tool needed, experience, tags, how it looks in the dark) except that it drops {@link #RICHNESS} times
 * as much.
 *
 * <p>Nothing here touches Minecraft, so it can be checked by ordinary unit tests. The blocks themselves
 * are registered in {@code RichOreBlocks}, and their loot tables, models and tags are generated from
 * this table by {@code RichOreDataGen}, so a fact is only ever written down once.
 *
 * <p>Every count and value below mirrors the ordinary block: drop counts and fortune formulas from its
 * loot table, experience from its block class, mining tier from its tags. For most ores the loot table is
 * vanilla's (or Create's). For the ores the pack cuts down, the loot table is the pack's own, generated from
 * this same table (see {@link #PACK_LOOT}), so the ordinary and rich blocks cannot drift apart:
 * <ul>
 *   <li>coal, diamond and nether quartz drop one chip ({@code refining.RefiningItems}) instead of a whole item;</li>
 *   <li>redstone, lapis and nether gold drop about a quarter of vanilla's amount.</li>
 * </ul>
 */
public final class RichOres {

    /** How many times as much a rich ore block drops as the block it copies. */
    public static final int RICHNESS = 5;

    /** The mining tier an ore needs, as vanilla tags express it. Every ore also needs a pickaxe. */
    public enum Tier {
        /** Any pickaxe. */
        NONE,
        /** Stone pickaxe or better ({@code #minecraft:needs_stone_tool}). */
        STONE,
        /** Iron pickaxe or better ({@code #minecraft:needs_iron_tool}). */
        IRON
    }

    /** How the Fortune enchantment increases an ore's drop. */
    public enum Bonus {
        /** Vanilla's {@code ore_drops} formula: the count is multiplied. Most ores. */
        ORE_DROPS,
        /** Vanilla's {@code uniform_bonus_count}: extra items are added. Redstone. */
        UNIFORM
    }

    /** One rich block: its name, the ordinary block it copies, and the rock it sits in. */
    public record Variant(String name, String counterpart, Ground ground) {
    }

    /** The kind of rock an ore block sits in, for the "ores in ground" tags. */
    public enum Ground {
        STONE("stone"), DEEPSLATE("deepslate"), NETHERRACK("netherrack");

        private final String tagName;

        Ground(String tagName) {
            this.tagName = tagName;
        }

        /** The last part of {@code #c:ores_in_ground/<name>}. */
        public String tagName() {
            return tagName;
        }
    }

    /**
     * @param oreId      the ore's id in {@code OreTypes}
     * @param stone      id of the ordinary block in stone-type rock (or the only block, for nether ores)
     * @param deepslate  id of the ordinary deepslate block, or null if the ore has none
     * @param drop       id of the item the ordinary block drops
     * @param minDrop    fewest of that item the ordinary block drops, before Fortune
     * @param maxDrop    most it drops, before Fortune
     * @param bonus      how Fortune improves the drop
     * @param minXp      least experience the ordinary block gives
     * @param maxXp      most experience it gives. Redstone ore has its own experience code and leaves this 0
     * @param tier       the mining tier the ordinary block needs
     * @param redstone   whether the block is redstone ore, which lights up when touched
     * @param metalTag   the ore's part of {@code #c:ores/<name>}, such as "iron"
     * @param vanillaTag the ore's vanilla tag, such as {@code minecraft:iron_ores}, or null if it has none
     */
    public record Spec(String oreId, String stone, String deepslate, String drop, int minDrop, int maxDrop,
                       Bonus bonus, int minXp, int maxXp, Tier tier, boolean redstone,
                       String metalTag, String vanillaTag) {

        public Spec {
            if (minDrop < 1 || maxDrop < minDrop) {
                throw new IllegalArgumentException("need 1 <= minDrop <= maxDrop for " + oreId);
            }
            if (minXp < 0 || maxXp < minXp) {
                throw new IllegalArgumentException("need 0 <= minXp <= maxXp for " + oreId);
            }
        }

        /** Fewest of the drop item a rich block gives, before Fortune. */
        public int richMinDrop() {
            return minDrop * RICHNESS;
        }

        /** Most of the drop item a rich block gives, before Fortune. */
        public int richMaxDrop() {
            return maxDrop * RICHNESS;
        }

        /** The rich blocks of this ore: one for stone-type rock, and one for deepslate if the ore has it. */
        public List<Variant> variants() {
            List<Variant> variants = new ArrayList<>(2);
            variants.add(new Variant(richName(stone), stone, oreId.startsWith("nether_") ? Ground.NETHERRACK : Ground.STONE));
            if (deepslate != null) {
                variants.add(new Variant(richName(deepslate), deepslate, Ground.DEEPSLATE));
            }
            return List.copyOf(variants);
        }

        /** The name of the rich block for stone-type rock (or the nether). */
        public String stoneName() {
            return richName(stone);
        }

        /** The name of the rich block for deepslate, or null if the ore has none. */
        public String deepslateName() {
            return deepslate == null ? null : richName(deepslate);
        }
    }

    /** {@code minecraft:iron_ore} becomes {@code rich_iron_ore}; {@code create:zinc_ore} becomes {@code rich_zinc_ore}. */
    static String richName(String counterpartId) {
        return "rich_" + counterpartId.substring(counterpartId.indexOf(':') + 1);
    }

    private static Spec spec(String oreId, String stone, String deepslate, String drop, int minDrop, int maxDrop,
                             Bonus bonus, int minXp, int maxXp, Tier tier, boolean redstone,
                             String metalTag, String vanillaTag) {
        return new Spec(oreId, stone, deepslate, drop, minDrop, maxDrop, bonus, minXp, maxXp, tier, redstone,
                metalTag, vanillaTag);
    }

    /**
     * Every ore that has rich blocks. Crystal does not: amethyst is not an ore block with a rich look yet, so
     * rich crystal deposits use ordinary amethyst.
     */
    public static final List<Spec> ALL = List.of(
            spec("coal", "minecraft:coal_ore", "minecraft:deepslate_coal_ore", "engines_and_empires:coal_chip", 1, 1,
                    Bonus.ORE_DROPS, 0, 2, Tier.NONE, false, "coal", "minecraft:coal_ores"),
            spec("iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore", "minecraft:raw_iron", 1, 1,
                    Bonus.ORE_DROPS, 0, 0, Tier.STONE, false, "iron", "minecraft:iron_ores"),
            spec("copper", "minecraft:copper_ore", "minecraft:deepslate_copper_ore", "minecraft:raw_copper", 2, 5,
                    Bonus.ORE_DROPS, 0, 0, Tier.STONE, false, "copper", "minecraft:copper_ores"),
            spec("zinc", "create:zinc_ore", "create:deepslate_zinc_ore", "create:raw_zinc", 1, 1,
                    Bonus.ORE_DROPS, 0, 0, Tier.IRON, false, "zinc", null),
            spec("redstone", "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", "minecraft:redstone", 1, 1,
                    Bonus.UNIFORM, 0, 0, Tier.IRON, true, "redstone", "minecraft:redstone_ores"),
            spec("lapis", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore", "minecraft:lapis_lazuli", 1, 2,
                    Bonus.ORE_DROPS, 2, 5, Tier.STONE, false, "lapis", "minecraft:lapis_ores"),
            spec("gold", "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:raw_gold", 1, 1,
                    Bonus.ORE_DROPS, 0, 0, Tier.IRON, false, "gold", "minecraft:gold_ores"),
            spec("emerald", "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", "minecraft:emerald", 1, 1,
                    Bonus.ORE_DROPS, 3, 7, Tier.IRON, false, "emerald", "minecraft:emerald_ores"),
            spec("diamond", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "engines_and_empires:diamond_chip", 1, 1,
                    Bonus.ORE_DROPS, 3, 7, Tier.IRON, false, "diamond", "minecraft:diamond_ores"),
            spec("nether_gold", "minecraft:nether_gold_ore", null, "minecraft:gold_nugget", 1, 1,
                    Bonus.ORE_DROPS, 0, 1, Tier.NONE, false, "gold", "minecraft:gold_ores"),
            spec("nether_quartz", "minecraft:nether_quartz_ore", null, "engines_and_empires:quartz_chip", 1, 1,
                    Bonus.ORE_DROPS, 2, 5, Tier.NONE, false, "quartz", null));

    /**
     * The ores whose ordinary blocks get the pack's own loot table, generated from {@link #ALL}, because their drop
     * differs from vanilla's. The others (iron, copper, zinc, gold, emerald) keep their original loot tables: the pack
     * cuts metals down in the furnace and the washer instead, and leaves emeralds alone.
     */
    public static final List<String> PACK_LOOT = List.of("coal", "redstone", "lapis", "diamond", "nether_gold", "nether_quartz");

    private static final Map<String, Spec> BY_ORE = new HashMap<>();

    static {
        for (Spec spec : ALL) {
            if (BY_ORE.put(spec.oreId(), spec) != null) {
                throw new IllegalStateException("Two rich ore entries for '" + spec.oreId() + "'");
            }
        }
    }

    /** The rich ore entry for an ore, or null if that ore has no rich blocks. */
    public static Spec forOre(String oreId) {
        return BY_ORE.get(oreId);
    }

    /** Every rich block of every ore. */
    public static List<Variant> allVariants() {
        List<Variant> all = new ArrayList<>();
        for (Spec spec : ALL) {
            all.addAll(spec.variants());
        }
        return List.copyOf(all);
    }

    private RichOres() {
    }
}
