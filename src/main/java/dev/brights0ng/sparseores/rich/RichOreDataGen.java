package dev.brights0ng.sparseores.rich;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import dev.brights0ng.sparseores.SparseOres;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.data.tags.IntrinsicHolderTagsProvider;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.ApplyBonusCount;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * Sparse Ores' parts of the data generator. It generates the resources of the rich ore blocks from {@link RichOres}, so
 * they cannot drift from it: loot tables, block states, block and item models, and tags. The rich ore textures are not
 * generated; they live in {@code src/main/resources/assets/engines_and_empires/textures/block}.
 *
 * <p>Until the split's step 2 gives each mod its own output, the mods write into one namespace and one folder, so one run
 * can have only one loot table provider (its name is fixed), and only one block state and block tag provider (two would
 * write the same {@code mineable/pickaxe} tag). The core's data generator puts those together from each mod's parts
 * ({@code enginesandempires/datagen/SharedDataGen}, split seam S9); these are Sparse Ores'.
 *
 * <p>Run the {@code Data} run configuration to (re)generate everything into {@code src/generated/resources}.
 */
public final class RichOreDataGen {

    /**
     * Loot: with Silk Touch a rich block drops itself, exactly as its counterpart does. Otherwise it
     * drops what its counterpart drops, {@link RichOres#RICHNESS} times over (a count of 1 becomes 5, a
     * range of 2-5 becomes 10-25), and Fortune improves that with the same formula the counterpart uses.
     *
     * <p>The ordinary blocks of the ores in {@link RichOres#PACK_LOOT} get their loot table from the same entry, once
     * over, replacing vanilla's.
     */
    public static final class Loot extends BlockLootSubProvider {

        public Loot(HolderLookup.Provider registries) {
            super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
        }

        @Override
        protected void generate() {
            for (RichOres.Variant variant : RichOres.allVariants()) {
                Block block = RichOreBlocks.block(variant.name());
                add(block, oreDrop(block, RichOres.forOre(oreOf(variant)), RichOres.RICHNESS));
            }
            for (Block block : packLootBlocks()) {
                add(block, oreDrop(block, specOf(block), 1));
            }
        }

        /** Silk Touch gives the block; otherwise the spec's drop, {@code times} over, improved by Fortune. */
        private LootTable.Builder oreDrop(Block block, RichOres.Spec spec, int times) {
            Item drop = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(spec.drop()))
                    .orElseThrow(() -> new IllegalStateException("Item '" + spec.drop() + "' does not exist. "
                            + "Is the mod that adds it loaded?"));
            Holder<Enchantment> fortune = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE);

            int min = spec.minDrop() * times;
            int max = spec.maxDrop() * times;
            NumberProvider count = min == max ? ConstantValue.exactly(min) : UniformGenerator.between(min, max);
            LootItemFunction.Builder bonus = spec.bonus() == RichOres.Bonus.UNIFORM
                    ? ApplyBonusCount.addUniformBonusCount(fortune, times)
                    : ApplyBonusCount.addOreBonusCount(fortune);

            return createSilkTouchDispatchTable(block, applyExplosionDecay(block,
                    LootItem.lootTableItem(drop)
                            .apply(SetItemCountFunction.setCount(count))
                            .apply(bonus)));
        }

        @Override
        protected Iterable<Block> getKnownBlocks() {
            List<Block> known = new ArrayList<>();
            RichOreBlocks.all().forEach(known::add);
            known.addAll(packLootBlocks());
            return known;
        }
    }

    /** The ordinary (vanilla) ore blocks whose loot tables the pack replaces. */
    private static List<Block> packLootBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (String oreId : RichOres.PACK_LOOT) {
            for (RichOres.Variant variant : RichOres.forOre(oreId).variants()) {
                blocks.add(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(variant.counterpart())));
            }
        }
        return blocks;
    }

    /** The entry of the ore an ordinary block belongs to. */
    private static RichOres.Spec specOf(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        for (RichOres.Spec spec : RichOres.ALL) {
            for (RichOres.Variant variant : spec.variants()) {
                if (variant.counterpart().equals(id)) {
                    return spec;
                }
            }
        }
        throw new IllegalStateException("No ore owns " + id);
    }

    /** The ore a rich block belongs to. */
    private static String oreOf(RichOres.Variant variant) {
        for (RichOres.Spec spec : RichOres.ALL) {
            if (spec.variants().contains(variant)) {
                return spec.oreId();
            }
        }
        throw new IllegalStateException("No ore owns " + variant.name());
    }

    /** Block states and models: a plain cube with the block's own texture, for every state, and the same model as an item. */
    public static void registerModels(BlockStateProvider provider) {
        for (RichOres.Variant variant : RichOres.allVariants()) {
            Block block = RichOreBlocks.block(variant.name());
            ModelFile model = provider.models().cubeAll(variant.name(), provider.modLoc("block/" + variant.name()));
            // Rich redstone ore has a lit state; it looks the same as the unlit one, as redstone ore does.
            provider.getVariantBuilder(block).forAllStates(state -> ConfiguredModel.builder().modelFile(model).build());
            provider.simpleBlockItem(block, model);
        }
    }

    /**
     * The names of the tags a rich block is in besides the mining ones: every tag that says "this is an
     * ore", so other mods and datapacks treat it as one, exactly as they treat its counterpart.
     */
    private static List<String> oreTags(RichOres.Spec spec, RichOres.Variant variant) {
        List<String> tags = new ArrayList<>();
        tags.add("c:ores");
        tags.add("c:ores/" + spec.metalTag());
        tags.add("c:ores_in_ground/" + variant.ground().tagName());
        if (spec.vanillaTag() != null) {
            tags.add(spec.vanillaTag());
        }
        return tags;
    }

    private static TagKey<Block> blockTag(String id) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.parse(id));
    }

    private static TagKey<Item> itemTag(String id) {
        return TagKey.create(Registries.ITEM, ResourceLocation.parse(id));
    }

    /**
     * Block tags: mineable with a pickaxe, the mining tier of the counterpart, and the ore tags. {@code tag} is the block
     * tag provider's own {@code tag}.
     */
    public static void addBlockTags(Function<TagKey<Block>, IntrinsicHolderTagsProvider.IntrinsicTagAppender<Block>> tag) {
        for (RichOres.Spec spec : RichOres.ALL) {
            for (RichOres.Variant variant : spec.variants()) {
                Block block = RichOreBlocks.block(variant.name());
                tag.apply(blockTag("minecraft:mineable/pickaxe")).add(block);
                switch (spec.tier()) {
                    case STONE -> tag.apply(blockTag("minecraft:needs_stone_tool")).add(block);
                    case IRON -> tag.apply(blockTag("minecraft:needs_iron_tool")).add(block);
                    case NONE -> { }
                }
                for (String t : oreTags(spec, variant)) {
                    tag.apply(blockTag(t)).add(block);
                }
            }
        }
    }

    /** Item tags: the rich blocks' items are in the same ore tags as their blocks. */
    public static final class ItemTagsGen extends ItemTagsProvider {

        public ItemTagsGen(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup,
                    CompletableFuture<TagsProvider.TagLookup<Block>> blockTags, ExistingFileHelper existing) {
            super(output, lookup, blockTags, SparseOres.MODID, existing);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            Set<String> tags = new LinkedHashSet<>();
            for (RichOres.Spec spec : RichOres.ALL) {
                for (RichOres.Variant variant : spec.variants()) {
                    tags.addAll(oreTags(spec, variant));
                }
            }
            for (String tag : tags) {
                copy(blockTag(tag), itemTag(tag));
            }
        }
    }

    private RichOreDataGen() {
    }
}
