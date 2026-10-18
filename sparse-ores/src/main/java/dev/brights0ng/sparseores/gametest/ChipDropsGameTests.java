package dev.brights0ng.sparseores.gametest;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.sparseores.SparseOres;
import dev.brights0ng.sparseores.SparseOresConfig;
import dev.brights0ng.sparseores.chips.Chips;
import dev.brights0ng.sparseores.rich.RichOreBlocks;
import dev.brights0ng.sparseores.rich.RichOres;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Sparse Ores' own drops, in game (split step 4b, Bright's option A): ordinary coal, diamond and nether quartz ore drop
 * one chip, Fortune and Silk Touch working as vanilla's; every rich ore drops five times its ordinary block (chips for
 * the chip ores); and the server config's {@code chipDrops} switch gives the ordinary ores vanilla's whole items back
 * while rich ores keep dropping chips.
 *
 * <p>In Sparse Ores' bare run (the library and Sparse Ores, nothing else) the ordinary blocks' tables are vanilla's,
 * apart from the five chip ores', so "five times its ordinary block" is five times vanilla. The checks hold in any run
 * that loads Sparse Ores: a pack that cuts an ore's drops on top (the Engines and Empires core) cuts both blocks.
 */
@GameTestHolder(SparseOres.MODID)
@PrefixGameTestTemplate(false)
public final class ChipDropsGameTests {

    /** One block's drop, read from its resolved loot table: the item, its count range before Fortune, Fortune's formula. */
    private record Drop(String item, int min, int max, String formula, int multiplier) {
    }

    @GameTest(template = GameTestStructures.EMPTY)
    public static void coalDiamondAndQuartzOresDropOneChip(GameTestHelper helper) {
        helper.assertTrue(SparseOresConfig.chipDrops(), "chip drops are on by default");
        for (Block ore : chipOres()) {
            Chips.Chip chip = chipOf(ore);
            assertMines(helper, ore, plainPickaxe(), chip(chip), 1, 1);
            assertMines(helper, ore, plainPickaxe(), whole(chip), 0, 0);
            assertMines(helper, ore, fortunePickaxe(helper), chip(chip), 1, 4);
            assertMines(helper, ore, silkPickaxe(helper), ore.asItem(), 1, 1);
            assertMines(helper, ore, silkPickaxe(helper), chip(chip), 0, 0);
        }
        helper.succeed();
    }

    /**
     * Every rich ore registered in this run drops five times what its ordinary block drops, the same item (chips for the
     * chip ores) with the same Fortune formula, read from the loot tables the server resolved; and mined, a rich coal ore
     * gives exactly five coal chips and a rich iron ore five raw iron.
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void richOresDropFiveTimesTheirOrdinaryBlock(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (RichOres.Spec spec : RichOres.ALL) {
            String item = Chips.chipped(spec).drop();
            for (RichOres.Variant variant : spec.variants()) {
                if (!RichOreBlocks.exists(variant.name())) {
                    continue; // rich zinc ore, without Create
                }
                Drop ordinary = resolved(helper.getLevel(), variant.counterpart());
                Drop rich = resolved(helper.getLevel(), SparseOres.MODID + ":" + variant.name());
                Drop expected = ordinary == null ? null : new Drop(ordinary.item(), ordinary.min() * RichOres.RICHNESS,
                        ordinary.max() * RichOres.RICHNESS, ordinary.formula(), ordinary.multiplier() * RichOres.RICHNESS);
                if (ordinary == null || !ordinary.item().equals(item)) {
                    wrong.add(variant.counterpart() + " drops " + ordinary + ", expected " + item);
                } else if (!expected.equals(rich)) {
                    wrong.add(variant.name() + ": expected " + expected + ", resolved " + rich);
                }
                checked++;
            }
        }
        helper.assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        helper.assertTrue(checked >= 18, "only " + checked + " rich ores checked");
        assertMines(helper, RichOreBlocks.block("rich_coal_ore"), plainPickaxe(), chip(Chips.forItem("minecraft:coal")), 5, 5);
        assertMines(helper, RichOreBlocks.block("rich_iron_ore"), plainPickaxe(), Items.RAW_IRON, 5, 5);
        helper.succeed();
    }

    /**
     * With {@code chipDrops} off, ordinary coal, diamond and nether quartz ore drop vanilla's whole items (one, and one to
     * four with Fortune III), and rich ores still drop five chips. The setting is put back before the test returns, all
     * on the server thread, so no other test sees it off.
     */
    @GameTest(template = GameTestStructures.EMPTY)
    public static void chipDropsOffGivesVanillasOrdinaryOresBack(GameTestHelper helper) {
        helper.assertTrue(SparseOresConfig.SPEC.isLoaded(), "Sparse Ores' server config is not loaded");
        boolean before = SparseOresConfig.CHIP_DROPS.get();
        SparseOresConfig.CHIP_DROPS.set(false);
        try {
            helper.assertFalse(SparseOresConfig.chipDrops(), "chip drops did not switch off");
            for (Block ore : chipOres()) {
                Chips.Chip chip = chipOf(ore);
                assertMines(helper, ore, plainPickaxe(), whole(chip), 1, 1);
                assertMines(helper, ore, plainPickaxe(), chip(chip), 0, 0);
                assertMines(helper, ore, fortunePickaxe(helper), whole(chip), 1, 4);
                assertMines(helper, ore, silkPickaxe(helper), ore.asItem(), 1, 1);
            }
            for (RichOres.Spec spec : Chips.ores()) {
                Chips.Chip chip = Chips.forItem(spec.drop());
                for (RichOres.Variant variant : spec.variants()) {
                    Block rich = RichOreBlocks.block(variant.name());
                    assertMines(helper, rich, plainPickaxe(), chip(chip), 5, 5);
                    assertMines(helper, rich, plainPickaxe(), whole(chip), 0, 0);
                }
            }
        } finally {
            SparseOresConfig.CHIP_DROPS.set(before);
        }
        assertMines(helper, chipOres().get(0), plainPickaxe(), chip(Chips.forItem("minecraft:coal")), 1, 1);
        helper.succeed();
    }

    // ---- helpers ----

    /** Vanilla's five chip ore blocks: coal, deepslate coal, diamond, deepslate diamond and nether quartz ore. */
    private static List<Block> chipOres() {
        List<Block> blocks = new ArrayList<>();
        for (RichOres.Spec spec : Chips.ores()) {
            for (RichOres.Variant variant : spec.variants()) {
                blocks.add(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(variant.counterpart())));
            }
        }
        return blocks;
    }

    /** The chip an ordinary chip ore drops. */
    private static Chips.Chip chipOf(Block ore) {
        String id = BuiltInRegistries.BLOCK.getKey(ore).toString();
        for (RichOres.Spec spec : Chips.ores()) {
            for (RichOres.Variant variant : spec.variants()) {
                if (variant.counterpart().equals(id)) {
                    return Chips.forItem(spec.drop());
                }
            }
        }
        throw new IllegalArgumentException(id + " is not a chip ore");
    }

    private static Item chip(Chips.Chip chip) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(chip.id()));
    }

    private static Item whole(Chips.Chip chip) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(chip.item()));
    }

    private static ItemStack plainPickaxe() {
        return new ItemStack(Items.DIAMOND_PICKAXE);
    }

    private static ItemStack fortunePickaxe(GameTestHelper helper) {
        return enchanted(helper, Enchantments.FORTUNE, 3);
    }

    private static ItemStack silkPickaxe(GameTestHelper helper) {
        return enchanted(helper, Enchantments.SILK_TOUCH, 1);
    }

    private static ItemStack enchanted(GameTestHelper helper, ResourceKey<Enchantment> enchantment, int level) {
        ItemStack tool = plainPickaxe();
        tool.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(enchantment), level);
        return tool;
    }

    /** Mines the block 40 times with this tool, checking that every time it drops between min and max of the item. */
    private static void assertMines(GameTestHelper helper, Block block, ItemStack tool, Item item, int min, int max) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        for (int i = 0; i < 40; i++) {
            List<ItemStack> drops = Block.getDrops(block.defaultBlockState(), helper.getLevel(), pos, null, null, tool);
            int n = 0;
            for (ItemStack stack : drops) {
                if (stack.is(item)) {
                    n += stack.getCount();
                }
            }
            helper.assertTrue(n >= min && n <= max, BuiltInRegistries.BLOCK.getKey(block) + " mined with " + tool
                    + " dropped " + drops + ", expected " + min + "-" + max + " " + BuiltInRegistries.ITEM.getKey(item)
                    + " (chip drops " + (SparseOresConfig.chipDrops() ? "on" : "off") + ")");
        }
    }

    /**
     * The drop of a block's resolved loot table: its Silk Touch entry must give the block itself; the other item entry is
     * the drop. Null if the table has no such pair. Reads the table as JSON (encoded with the server's registries), so it
     * sees exactly what the game loaded.
     */
    private static Drop resolved(ServerLevel level, String blockId) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
        LootTable table = level.getServer().reloadableRegistries().getLootTable(block.getLootTable());
        JsonElement json = LootTable.DIRECT_CODEC
                .encodeStart(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), table).getOrThrow();
        List<JsonObject> items = new ArrayList<>();
        collectItems(json, items);
        JsonObject silk = null;
        JsonObject drop = null;
        for (JsonObject item : items) {
            if (isSilkTouch(item)) {
                silk = item;
            } else {
                drop = item;
            }
        }
        if (silk == null || drop == null || !silk.get("name").getAsString().equals(blockId)) {
            return null;
        }
        int min = 1;
        int max = 1;
        String formula = null;
        int multiplier = 0;
        if (drop.has("functions")) {
            for (JsonElement f : drop.getAsJsonArray("functions")) {
                JsonObject function = f.getAsJsonObject();
                String name = function.get("function").getAsString();
                if (name.equals("minecraft:set_count")) {
                    JsonElement count = function.get("count");
                    if (count.isJsonPrimitive()) {
                        min = max = (int) count.getAsDouble();
                    } else if (count.getAsJsonObject().has("min")) {
                        min = (int) count.getAsJsonObject().get("min").getAsDouble();
                        max = (int) count.getAsJsonObject().get("max").getAsDouble();
                    } else {
                        min = max = (int) count.getAsJsonObject().get("value").getAsDouble();
                    }
                } else if (name.equals("minecraft:apply_bonus")) {
                    formula = function.get("formula").getAsString();
                    if (function.has("parameters")) {
                        multiplier = function.getAsJsonObject("parameters").get("bonusMultiplier").getAsInt();
                    }
                }
            }
        }
        return new Drop(drop.get("name").getAsString(), min, max, formula, multiplier);
    }

    /** Every {@code minecraft:item} entry anywhere in a loot table. */
    private static void collectItems(JsonElement element, List<JsonObject> into) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement type = object.get("type");
            if (type != null && type.isJsonPrimitive() && type.getAsString().equals("minecraft:item")) {
                into.add(object);
            }
            for (var entry : object.entrySet()) {
                collectItems(entry.getValue(), into);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectItems(child, into);
            }
        }
    }

    /** Whether an item entry is the Silk Touch branch (a match_tool condition). */
    private static boolean isSilkTouch(JsonObject item) {
        if (!item.has("conditions")) {
            return false;
        }
        for (JsonElement condition : item.getAsJsonArray("conditions")) {
            if (condition.getAsJsonObject().get("condition").getAsString().equals("minecraft:match_tool")) {
                return true;
            }
        }
        return false;
    }

    private ChipDropsGameTests() {
    }
}
