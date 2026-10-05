package dev.brights0ng.enginesandempires.oregen.rich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.oregen.OreType;
import dev.brights0ng.enginesandempires.oregen.OreTypes;
import dev.brights0ng.enginesandempires.oregen.Realm;

class RichOresTest {

    private static final String TEXTURES = "/assets/engines_and_empires/textures/block/";

    @Test
    void everyOreExceptCrystalHasRichBlocksAndNothingElseDoes() {
        Set<String> withRich = new HashSet<>();
        for (RichOres.Spec spec : RichOres.ALL) {
            withRich.add(spec.oreId());
        }
        Set<String> expected = new HashSet<>();
        for (OreType type : OreTypes.ALL) {
            expected.add(type.id());
        }
        expected.remove("crystal"); // amethyst has no rich look yet
        assertEquals(expected, withRich);
        assertNull(RichOres.forOre("crystal"));
    }

    @Test
    void thereAreTwentyRichBlocksWithUniqueNames() {
        List<RichOres.Variant> variants = RichOres.allVariants();
        assertEquals(20, variants.size());
        Set<String> names = new HashSet<>();
        for (RichOres.Variant variant : variants) {
            assertTrue(names.add(variant.name()), "duplicate name " + variant.name());
        }
    }

    @Test
    void namesFollowTheBlockTheyCopy() {
        assertEquals("rich_iron_ore", RichOres.richName("minecraft:iron_ore"));
        assertEquals("rich_deepslate_gold_ore", RichOres.richName("minecraft:deepslate_gold_ore"));
        assertEquals("rich_zinc_ore", RichOres.richName("create:zinc_ore"));
        assertEquals("rich_nether_quartz_ore", RichOres.richName("minecraft:nether_quartz_ore"));
        for (RichOres.Spec spec : RichOres.ALL) {
            for (RichOres.Variant variant : spec.variants()) {
                assertEquals(RichOres.richName(variant.counterpart()), variant.name());
            }
        }
    }

    /** The whole point of rich ore: five times what the ordinary block drops. */
    @Test
    void richOreDropsFiveTimesWhatTheOrdinaryBlockDrops() {
        assertEquals(5, RichOres.RICHNESS);
        for (RichOres.Spec spec : RichOres.ALL) {
            assertEquals(spec.minDrop() * 5, spec.richMinDrop(), spec.oreId());
            assertEquals(spec.maxDrop() * 5, spec.richMaxDrop(), spec.oreId());
        }
        // Spot checks: vanilla's loot for the ores the pack leaves alone, the pack's own for the ones it cuts.
        assertEquals(5, RichOres.forOre("iron").richMinDrop());
        assertEquals(5, RichOres.forOre("iron").richMaxDrop());
        assertEquals(10, RichOres.forOre("copper").richMinDrop());   // copper ore drops 2-5
        assertEquals(25, RichOres.forOre("copper").richMaxDrop());
        assertEquals(5, RichOres.forOre("lapis").richMinDrop());     // the pack's lapis ore drops 1-2
        assertEquals(10, RichOres.forOre("lapis").richMaxDrop());
        assertEquals(5, RichOres.forOre("redstone").richMinDrop());  // the pack's redstone ore drops 1
        assertEquals(5, RichOres.forOre("redstone").richMaxDrop());
        assertEquals(5, RichOres.forOre("nether_gold").richMinDrop()); // the pack's nether gold ore drops 1 nugget
        assertEquals(5, RichOres.forOre("nether_gold").richMaxDrop());
        assertEquals("minecraft:raw_iron", RichOres.forOre("iron").drop());
        assertEquals("create:raw_zinc", RichOres.forOre("zinc").drop());
        assertEquals("engines_and_empires:coal_chip", RichOres.forOre("coal").drop());
        assertEquals("engines_and_empires:diamond_chip", RichOres.forOre("diamond").drop());
        assertEquals("engines_and_empires:quartz_chip", RichOres.forOre("nether_quartz").drop());
        assertEquals("minecraft:emerald", RichOres.forOre("emerald").drop());
    }

    /** The ordinary blocks of the ores the pack cuts get a loot table generated from the same entry, once over. */
    @Test
    void cutOresReplaceTheOrdinaryBlocksLootTables() throws IOException {
        for (String oreId : RichOres.PACK_LOOT) {
            RichOres.Spec spec = RichOres.forOre(oreId);
            for (RichOres.Variant variant : spec.variants()) {
                String path = variant.counterpart().substring("minecraft:".length());
                String loot = read("/data/minecraft/loot_table/blocks/" + path + ".json");
                assertTrue(loot.contains("\"name\": \"" + spec.drop() + "\""), path + " drop item");
                assertTrue(loot.contains("\"name\": \"" + variant.counterpart() + "\""), path + " silk touch");
                if (spec.minDrop() == spec.maxDrop()) {
                    assertTrue(loot.contains("\"count\": " + spec.minDrop() + ".0"), path + " count");
                } else {
                    assertTrue(loot.contains("\"min\": " + spec.minDrop() + ".0"), path + " min count");
                    assertTrue(loot.contains("\"max\": " + spec.maxDrop() + ".0"), path + " max count");
                }
            }
        }
        assertNull(RichOresTest.class.getResource("/data/minecraft/loot_table/blocks/iron_ore.json"),
                "iron keeps vanilla's loot table: it is cut in the furnace instead");
        assertNull(RichOresTest.class.getResource("/data/minecraft/loot_table/blocks/emerald_ore.json"),
                "emerald is left alone");
    }

    @Test
    void fortuneUsesTheSameFormulaAsTheOrdinaryBlock() {
        for (RichOres.Spec spec : RichOres.ALL) {
            RichOres.Bonus expected = spec.oreId().equals("redstone") ? RichOres.Bonus.UNIFORM : RichOres.Bonus.ORE_DROPS;
            assertEquals(expected, spec.bonus(), spec.oreId());
        }
    }

    @Test
    void miningTiersMatchVanilla() {
        assertEquals(RichOres.Tier.NONE, RichOres.forOre("coal").tier());
        assertEquals(RichOres.Tier.STONE, RichOres.forOre("iron").tier());
        assertEquals(RichOres.Tier.STONE, RichOres.forOre("copper").tier());
        assertEquals(RichOres.Tier.STONE, RichOres.forOre("lapis").tier());
        assertEquals(RichOres.Tier.IRON, RichOres.forOre("gold").tier());
        assertEquals(RichOres.Tier.IRON, RichOres.forOre("redstone").tier());
        assertEquals(RichOres.Tier.IRON, RichOres.forOre("emerald").tier());
        assertEquals(RichOres.Tier.IRON, RichOres.forOre("diamond").tier());
        assertEquals(RichOres.Tier.IRON, RichOres.forOre("zinc").tier());   // Create: needs an iron pickaxe
        assertEquals(RichOres.Tier.NONE, RichOres.forOre("nether_gold").tier());
        assertEquals(RichOres.Tier.NONE, RichOres.forOre("nether_quartz").tier());
    }

    @Test
    void experienceMatchesVanilla() {
        assertEquals(0, RichOres.forOre("coal").minXp());
        assertEquals(2, RichOres.forOre("coal").maxXp());
        assertEquals(2, RichOres.forOre("lapis").minXp());
        assertEquals(5, RichOres.forOre("lapis").maxXp());
        assertEquals(3, RichOres.forOre("diamond").minXp());
        assertEquals(7, RichOres.forOre("diamond").maxXp());
        assertEquals(0, RichOres.forOre("zinc").maxXp());          // Create's zinc ore is a plain block
        assertEquals(0, RichOres.forOre("nether_gold").minXp());
        assertEquals(1, RichOres.forOre("nether_gold").maxXp());
        assertTrue(RichOres.forOre("redstone").redstone());
    }

    @Test
    void overworldOresHaveDeepslateVersionsAndNetherOresDoNot() {
        for (RichOres.Spec spec : RichOres.ALL) {
            Realm realm = OreTypes.byId(spec.oreId()).realm();
            assertEquals(spec.oreId().startsWith("nether_"), realm == Realm.NETHER, spec.oreId());
            if (realm == Realm.OVERWORLD) {
                assertNotNull(spec.deepslate(), spec.oreId() + " needs a deepslate version");
                assertEquals(2, spec.variants().size(), spec.oreId());
                assertEquals(RichOres.Ground.STONE, spec.variants().get(0).ground(), spec.oreId());
                assertEquals(RichOres.Ground.DEEPSLATE, spec.variants().get(1).ground(), spec.oreId());
                assertTrue(spec.deepslateName().startsWith("rich_deepslate_"), spec.oreId());
            } else {
                assertNull(spec.deepslate(), spec.oreId() + " has no deepslate version");
                assertEquals(1, spec.variants().size(), spec.oreId());
                assertEquals(RichOres.Ground.NETHERRACK, spec.variants().get(0).ground(), spec.oreId());
                assertNull(spec.deepslateName());
            }
        }
    }

    @Test
    void invalidEntriesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RichOres.Spec("x", "a:b", null, "a:c", 0, 1,
                RichOres.Bonus.ORE_DROPS, 0, 0, RichOres.Tier.NONE, false, "x", null));
        assertThrows(IllegalArgumentException.class, () -> new RichOres.Spec("x", "a:b", null, "a:c", 3, 2,
                RichOres.Bonus.ORE_DROPS, 0, 0, RichOres.Tier.NONE, false, "x", null));
        assertThrows(IllegalArgumentException.class, () -> new RichOres.Spec("x", "a:b", null, "a:c", 1, 1,
                RichOres.Bonus.ORE_DROPS, 5, 2, RichOres.Tier.NONE, false, "x", null));
    }

    /** The textures are art files placed by hand, so check they are really there, and the right size. */
    @Test
    void everyRichBlockHasA16By16Texture() throws IOException {
        for (RichOres.Variant variant : RichOres.allVariants()) {
            URL texture = RichOresTest.class.getResource(TEXTURES + variant.name() + ".png");
            assertNotNull(texture, "missing texture for " + variant.name());
            BufferedImage image = ImageIO.read(texture);
            assertEquals(16, image.getWidth(), variant.name());
            assertEquals(16, image.getHeight(), variant.name());
        }
    }

    @Test
    void everyRichBlockHasAnEnglishName() throws IOException {
        String lang;
        try (InputStream in = RichOresTest.class.getResourceAsStream("/assets/engines_and_empires/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is missing");
            lang = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (RichOres.Variant variant : RichOres.allVariants()) {
            assertTrue(lang.contains("\"block.engines_and_empires." + variant.name() + "\""),
                    "no English name for " + variant.name());
        }
        assertFalse(lang.contains("Rich Rich"), "a name got doubled");
    }

    /** Everything generated for each block must exist. If this fails, run the Data run configuration. */
    @Test
    void everyRichBlockHasItsGeneratedStateModelsAndLootTable() throws IOException {
        for (RichOres.Variant variant : RichOres.allVariants()) {
            String name = variant.name();
            for (String path : List.of(
                    "/assets/engines_and_empires/blockstates/" + name + ".json",
                    "/assets/engines_and_empires/models/block/" + name + ".json",
                    "/assets/engines_and_empires/models/item/" + name + ".json",
                    "/data/engines_and_empires/loot_table/blocks/" + name + ".json")) {
                assertNotNull(RichOresTest.class.getResource(path), "missing generated file " + path
                        + " (run the Data run configuration)");
            }
            assertTrue(read("/assets/engines_and_empires/models/block/" + name + ".json")
                    .contains("engines_and_empires:block/" + name), name + "'s model should use its own texture");
        }
    }

    /** The generated loot tables really say what the table says: silk touch, the drop, five times the count, the fortune formula. */
    @Test
    void generatedLootTablesDropFiveTimesTheOrdinaryAmount() throws IOException {
        for (RichOres.Spec spec : RichOres.ALL) {
            for (RichOres.Variant variant : spec.variants()) {
                String loot = read("/data/engines_and_empires/loot_table/blocks/" + variant.name() + ".json");
                String label = variant.name();
                assertTrue(loot.contains("\"name\": \"engines_and_empires:" + variant.name() + "\""), label + " silk touch");
                assertTrue(loot.contains("minecraft:silk_touch"), label + " silk touch");
                assertTrue(loot.contains("\"name\": \"" + spec.drop() + "\""), label + " drop item");
                if (spec.richMinDrop() == spec.richMaxDrop()) {
                    assertTrue(loot.contains("\"count\": " + spec.richMinDrop() + ".0"), label + " count");
                } else {
                    assertTrue(loot.contains("\"min\": " + spec.richMinDrop() + ".0"), label + " min count");
                    assertTrue(loot.contains("\"max\": " + spec.richMaxDrop() + ".0"), label + " max count");
                }
                String formula = spec.bonus() == RichOres.Bonus.UNIFORM ? "minecraft:uniform_bonus_count" : "minecraft:ore_drops";
                assertTrue(loot.contains(formula), label + " fortune formula");
            }
        }
    }

    @Test
    void generatedTagsPutEveryBlockInTheRightMiningTier() throws IOException {
        String stone = read("/data/minecraft/tags/block/needs_stone_tool.json");
        String iron = read("/data/minecraft/tags/block/needs_iron_tool.json");
        String pickaxe = read("/data/minecraft/tags/block/mineable/pickaxe.json");
        for (RichOres.Spec spec : RichOres.ALL) {
            for (RichOres.Variant variant : spec.variants()) {
                String id = "\"engines_and_empires:" + variant.name() + "\"";
                assertTrue(pickaxe.contains(id), variant.name() + " must be mineable with a pickaxe");
                assertEquals(spec.tier() == RichOres.Tier.STONE, stone.contains(id), variant.name() + " stone tier");
                assertEquals(spec.tier() == RichOres.Tier.IRON, iron.contains(id), variant.name() + " iron tier");
            }
        }
    }

    @Test
    void generatedTagsMarkEveryRichBlockAsAnOre() throws IOException {
        String ores = read("/data/c/tags/block/ores.json");
        for (RichOres.Variant variant : RichOres.allVariants()) {
            assertTrue(ores.contains("\"engines_and_empires:" + variant.name() + "\""), variant.name() + " should be in #c:ores");
        }
        assertTrue(read("/data/c/tags/block/ores/zinc.json").contains("engines_and_empires:rich_zinc_ore"));
        assertTrue(read("/data/c/tags/block/ores_in_ground/deepslate.json").contains("engines_and_empires:rich_deepslate_iron_ore"));
        assertTrue(read("/data/c/tags/block/ores_in_ground/netherrack.json").contains("engines_and_empires:rich_nether_gold_ore"));
        assertFalse(read("/data/c/tags/block/ores_in_ground/stone.json").contains("rich_deepslate"));
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = RichOresTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource " + resource + " (run the Data run configuration)");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
