// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * World-side classification and item/block mapping for basic {@link CropBlock} farming, hoes, tillable soil, and
 * raw farm produce (supports vanilla crops and modded {@link CropBlock} subclasses such as Farm &amp; Charm).
 */
public final class FarmCrops {

    /** True if {@code stack} is a hoe usable for tilling soil. */
    public static boolean isHoe(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.is(ItemTags.HOES)
                || stack.getItem() instanceof HoeItem
                || stack.canPerformAction(ItemAbilities.HOE_TILL));
    }

    /** True if {@code pos} is dirt/grass/path/coarse-dirt with open air directly above it. */
    public static boolean isTillableSoil(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.COARSE_DIRT);
    }

    /** True if {@code state} is a basic {@link CropBlock}. */
    public static boolean isBasicCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock;
    }

    /** True if {@code state} is a fully mature {@link CropBlock} ready to harvest. */
    public static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    /** Resolves the planting seed/crop item for a growing {@link CropBlock} at {@code pos}. */
    @Nullable
    public static Item seedItemForCrop(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof CropBlock crop)) {
            return null;
        }
        ItemStack clone = crop.getCloneItemStack(level, pos, state);
        if (clone.isEmpty()) {
            return null;
        }
        return cropBlockForSeed(clone.getItem()) != null ? clone.getItem() : null;
    }

    @Nullable
    public static String seedIdForCrop(ServerLevel level, BlockPos pos, BlockState state) {
        Item seed = seedItemForCrop(level, pos, state);
        return seed != null ? itemId(seed) : null;
    }

    /** Returns the {@link CropBlock} planted by {@code item}, or {@code null} if {@code item} is not a crop seed. */
    @Nullable
    public static CropBlock cropBlockForSeed(@Nullable Item item) {
        if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof CropBlock crop) {
            return crop;
        }
        return null;
    }

    /** True if {@code stack} can be directly planted onto farmland as a basic {@link CropBlock}. */
    public static boolean isPlantableSeed(ItemStack stack) {
        return !stack.isEmpty() && cropBlockForSeed(stack.getItem()) != null;
    }

    /**
     * Maps either a crop seed OR its harvested produce item (e.g. wheat -&gt; wheat_seeds, beetroot -&gt; beetroot_seeds,
     * potato -&gt; potato, modded produce -&gt; matching {@code *_seeds}) to its canonical planting seed item.
     */
    @Nullable
    public static Item canonicalSeedForItem(@Nullable Item item) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        if (cropBlockForSeed(item) != null) {
            return item;
        }
        if (item == Items.WHEAT) {
            return Items.WHEAT_SEEDS;
        }
        if (item == Items.BEETROOT) {
            return Items.BEETROOT_SEEDS;
        }
        if (item == Items.POISONOUS_POTATO) {
            return Items.POTATO;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        if (key != null) {
            for (String suffix : new String[] {"_seeds", "_seed"}) {
                ResourceLocation candidateKey = ResourceLocation.fromNamespaceAndPath(
                        key.getNamespace(), key.getPath() + suffix);
                if (BuiltInRegistries.ITEM.containsKey(candidateKey)) {
                    Item candidate = BuiltInRegistries.ITEM.get(candidateKey);
                    if (cropBlockForSeed(candidate) != null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** Resolves a saved seed item ID string back to its {@link Item}, or {@code null} if unknown/non-crop. */
    @Nullable
    public static Item seedItemById(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(rl);
        return cropBlockForSeed(item) != null ? item : null;
    }

    @Nullable
    public static String itemId(@Nullable Item item) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key != null ? key.toString() : null;
    }

    /** True if {@code stack} is raw crop produce or crop seeds produced/used by basic {@link CropBlock} farming. */
    public static boolean isFarmProduceOrSeed(ItemStack stack) {
        return !stack.isEmpty() && canonicalSeedForItem(stack.getItem()) != null;
    }

    /**
     * True if {@code stack} is raw edible farm produce (e.g. raw potato, carrot, beetroot, poisonous potato).
     * Citizens only eat raw produce as an emergency fallback when out of proper foods and at/below snack hunger
     * (Bright, 2026-11-26).
     */
    public static boolean isRawFarmProduceFood(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getFoodProperties(null) != null
                && canonicalSeedForItem(stack.getItem()) != null;
    }

    /** True if {@code stack} is any farmer work item (hoe, crop seed, or raw crop produce) to stow at shift end. */
    public static boolean isFarmWorkItem(ItemStack stack) {
        return isHoe(stack) || isFarmProduceOrSeed(stack);
    }

    private FarmCrops() {
    }
}
