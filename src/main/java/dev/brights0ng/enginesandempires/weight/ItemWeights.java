package dev.brights0ng.enginesandempires.weight;

import java.math.BigDecimal;
import java.math.RoundingMode;

import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How much an item weighs, in kpg (Create Aeronautics' mass unit). Encumbered asks this for every item it counts
 * (see {@code EncumberedWeightMixin}), so the carry weight of a block matches its mass on an Aeronautics vehicle.
 *
 * <p>The rules, first match wins:
 * <ol>
 *   <li>The pack's data map ({@link WeightDataMaps#ITEM_WEIGHT}): the weight groups, by tag, and any exceptions.</li>
 *   <li>Armour, by material: leather 0.5; chainmail, iron, gold, turtle and anything modded 1; diamond, netherite 2.</li>
 *   <li>Tools and weapons, by tier: wood 0.5; stone, iron, gold and anything modded 1; diamond, netherite 2.</li>
 *   <li>Anything else with durability (bows, shields, shears, elytra...): 1.</li>
 *   <li>A block: its Aeronautics mass ({@code sable:mass} of its default state). A block you can walk through, which
 *       Aeronautics counts as 0, weighs {@link #SMALL_PLACEABLE} instead, so nothing you carry is free.</li>
 *   <li>Anything left: {@link #FALLBACK}. {@link WeightReport} logs these so they can be sorted into a group.</li>
 * </ol>
 *
 * <p>Every weight should be one of {@link #STEPS}, so players can add them up in their head.
 */
public final class ItemWeights {

    /** The only weights the pack uses (the gametest checks every item against these). */
    public static final float[] STEPS = {0f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 4f};

    /** An item nothing else covers (Create's crafting parts and the like): the same as an ingot. */
    public static final float FALLBACK = 0.5f;
    /** A block with no collision (torches, rails, flowers, redstone parts). */
    public static final float SMALL_PLACEABLE = 0.05f;

    private static final float LIGHT = 0.5f;
    private static final float NORMAL = 1f;
    private static final float HEAVY = 2f;

    /** Which rule gave an item its weight. */
    public enum Source {
        DATA_MAP, ARMOUR, TOOL, DURABLE, BLOCK, SMALL_PLACEABLE, FALLBACK
    }

    public record Result(float weight, Source source) {
    }

    public static float of(Holder<Item> item) {
        return resolve(item).weight();
    }

    public static float of(Item item) {
        return of(item.builtInRegistryHolder());
    }

    public static Result resolve(Holder<Item> holder) {
        ItemWeight mapped = holder.getData(WeightDataMaps.ITEM_WEIGHT);
        if (mapped != null) {
            return new Result(mapped.weight(), Source.DATA_MAP);
        }
        Item item = holder.value();
        if (item instanceof ArmorItem armour) {
            return new Result(armourWeight(armour.getMaterial()), Source.ARMOUR);
        }
        if (item instanceof TieredItem tool) {
            return new Result(tierWeight(tool.getTier()), Source.TOOL);
        }
        if (item.getDefaultInstance().isDamageableItem()) {
            return new Result(NORMAL, Source.DURABLE);
        }
        if (item instanceof BlockItem blockItem) {
            float mass = blockMass(blockItem.getBlock().defaultBlockState());
            return mass > 0 ? new Result(mass, Source.BLOCK) : new Result(SMALL_PLACEABLE, Source.SMALL_PLACEABLE);
        }
        return new Result(FALLBACK, Source.FALLBACK);
    }

    /**
     * A block's Aeronautics mass, read the way Aeronautics reads it on a vehicle: 0 if you can walk through it. Uses
     * Sable's helper rather than the property registry directly, since that registry's classes (Veil) aren't on our
     * compile classpath.
     */
    static float blockMass(BlockState state) {
        try {
            return (float) PhysicsBlockPropertyHelper.getMass(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state);
        } catch (RuntimeException e) {
            // A block whose shape needs a real world to work out. Treat it as an ordinary block.
            return NORMAL;
        }
    }

    static float armourWeight(Holder<ArmorMaterial> material) {
        ArmorMaterial value = material.value();
        if (value == ArmorMaterials.LEATHER.value()) {
            return LIGHT;
        }
        if (value == ArmorMaterials.DIAMOND.value() || value == ArmorMaterials.NETHERITE.value()) {
            return HEAVY;
        }
        return NORMAL;
    }

    static float tierWeight(Tier tier) {
        if (tier == Tiers.WOOD) {
            return LIGHT;
        }
        if (tier == Tiers.DIAMOND || tier == Tiers.NETHERITE) {
            return HEAVY;
        }
        return NORMAL;
    }

    /** A weight for players to read: at most two decimals, no trailing zeros ("0.05", "0.5", "212"). */
    public static String format(double kpg) {
        return BigDecimal.valueOf(kpg).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private ItemWeights() {
    }
}
