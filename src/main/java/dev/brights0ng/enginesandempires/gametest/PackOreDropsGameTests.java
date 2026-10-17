package dev.brights0ng.enginesandempires.gametest;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * What every ore block drops in the pack, read from the loot table the server actually resolved (every pack loaded, so
 * the core's tables win over Sparse Ores' and vanilla's where they share an id), against the amounts recorded from the
 * tables before split step 4. Step 4 moved the cut amounts of the rich ores from Sparse Ores (which now drops vanilla's
 * amounts five times over) to the core's loot cuts; the pack's final drops must not change, and this pins all of them:
 * the drop, its count before Fortune, Fortune's formula, and Silk Touch giving the block itself.
 */
@GameTestHolder(EnginesAndEmpires.MODID)
@PrefixGameTestTemplate(false)
public final class PackOreDropsGameTests {

    /** One block's drop: the item, its count range before Fortune, and Fortune's formula (with its multiplier, or 0). */
    private record Drop(String block, String item, int min, int max, String formula, int multiplier) {
    }

    private static final String ORE = "minecraft:ore_drops";
    private static final String UNIFORM = "minecraft:uniform_bonus_count";

    /** Recorded from the resolved tables before split step 4 (ordinary blocks: the core's cuts, vanilla's, Create's). */
    private static final List<Drop> BEFORE = List.of(
            new Drop("minecraft:coal_ore", "sparse_ores:coal_chip", 1, 1, ORE, 0),
            new Drop("minecraft:deepslate_coal_ore", "sparse_ores:coal_chip", 1, 1, ORE, 0),
            new Drop("minecraft:iron_ore", "minecraft:raw_iron", 1, 1, ORE, 0),
            new Drop("minecraft:deepslate_iron_ore", "minecraft:raw_iron", 1, 1, ORE, 0),
            new Drop("minecraft:copper_ore", "minecraft:raw_copper", 2, 5, ORE, 0),
            new Drop("minecraft:deepslate_copper_ore", "minecraft:raw_copper", 2, 5, ORE, 0),
            new Drop("create:zinc_ore", "create:raw_zinc", 1, 1, ORE, 0),
            new Drop("create:deepslate_zinc_ore", "create:raw_zinc", 1, 1, ORE, 0),
            new Drop("minecraft:redstone_ore", "minecraft:redstone", 1, 1, UNIFORM, 1),
            new Drop("minecraft:deepslate_redstone_ore", "minecraft:redstone", 1, 1, UNIFORM, 1),
            new Drop("minecraft:lapis_ore", "minecraft:lapis_lazuli", 1, 2, ORE, 0),
            new Drop("minecraft:deepslate_lapis_ore", "minecraft:lapis_lazuli", 1, 2, ORE, 0),
            new Drop("minecraft:gold_ore", "minecraft:raw_gold", 1, 1, ORE, 0),
            new Drop("minecraft:deepslate_gold_ore", "minecraft:raw_gold", 1, 1, ORE, 0),
            new Drop("minecraft:emerald_ore", "minecraft:emerald", 1, 1, ORE, 0),
            new Drop("minecraft:deepslate_emerald_ore", "minecraft:emerald", 1, 1, ORE, 0),
            new Drop("minecraft:diamond_ore", "sparse_ores:diamond_chip", 1, 1, ORE, 0),
            new Drop("minecraft:deepslate_diamond_ore", "sparse_ores:diamond_chip", 1, 1, ORE, 0),
            new Drop("minecraft:nether_gold_ore", "minecraft:gold_nugget", 1, 1, ORE, 0),
            new Drop("minecraft:nether_quartz_ore", "sparse_ores:quartz_chip", 1, 1, ORE, 0),
            new Drop("sparse_ores:rich_coal_ore", "sparse_ores:coal_chip", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_deepslate_coal_ore", "sparse_ores:coal_chip", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_iron_ore", "minecraft:raw_iron", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_deepslate_iron_ore", "minecraft:raw_iron", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_copper_ore", "minecraft:raw_copper", 10, 25, ORE, 0),
            new Drop("sparse_ores:rich_deepslate_copper_ore", "minecraft:raw_copper", 10, 25, ORE, 0),
            new Drop("sparse_ores:rich_zinc_ore", "create:raw_zinc", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_deepslate_zinc_ore", "create:raw_zinc", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_redstone_ore", "minecraft:redstone", 5, 5, UNIFORM, 5),
            new Drop("sparse_ores:rich_deepslate_redstone_ore", "minecraft:redstone", 5, 5, UNIFORM, 5),
            new Drop("sparse_ores:rich_lapis_ore", "minecraft:lapis_lazuli", 5, 10, ORE, 0),
            new Drop("sparse_ores:rich_deepslate_lapis_ore", "minecraft:lapis_lazuli", 5, 10, ORE, 0),
            new Drop("sparse_ores:rich_gold_ore", "minecraft:raw_gold", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_deepslate_gold_ore", "minecraft:raw_gold", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_emerald_ore", "minecraft:emerald", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_deepslate_emerald_ore", "minecraft:emerald", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_diamond_ore", "sparse_ores:diamond_chip", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_deepslate_diamond_ore", "sparse_ores:diamond_chip", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_nether_gold_ore", "minecraft:gold_nugget", 5, 5, ORE, 0),
            new Drop("sparse_ores:rich_nether_quartz_ore", "sparse_ores:quartz_chip", 5, 5, ORE, 0));

    @GameTest(template = GameTestStructures.EMPTY)
    public static void everyOreDropsWhatItDidBeforeStep4(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        for (Drop expected : BEFORE) {
            Drop resolved = resolved(helper.getLevel(), expected.block());
            if (!expected.equals(resolved)) {
                wrong.add(expected.block() + ": expected " + expected + ", resolved " + resolved);
            }
        }
        helper.assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        helper.succeed();
    }

    /**
     * The drop of a block's resolved loot table, or null if it has none or its Silk Touch entry does not give the block
     * itself. Reads the table as JSON (encoded with the server's registries), so it sees exactly what the game loaded.
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
        return new Drop(blockId, drop.get("name").getAsString(), min, max, formula, multiplier);
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

    private PackOreDropsGameTests() {
    }
}
