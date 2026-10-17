package dev.brights0ng.sparseores.rich;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.mojang.serialization.Codec;

import dev.brights0ng.sparseores.SparseOres;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.data.tags.IntrinsicHolderTagsProvider;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceKey;
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
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import net.neoforged.neoforge.common.conditions.ModLoadedCondition;
import net.neoforged.neoforge.common.conditions.WithConditions;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * Sparse Ores' parts of the data generator. It generates the resources of the rich ore blocks from {@link RichOres}, so
 * they cannot drift from it: loot tables, block states, block and item models, and tags. The rich ore textures are not
 * generated; they live in {@code src/main/resources/assets/sparse_ores/textures/block}.
 *
 * <p>A run can have only one loot table provider (its name is fixed), and only one block state and block tag provider
 * (two would write the same files), so {@code SparseOresDataGen} puts those together from these parts and the chips'.
 * The rich ores drop what their vanilla counterparts drop, {@link RichOres#RICHNESS} times over (split step 4). The pack
 * cuts some ores' drops in the core (its loot cuts), which builds both the ordinary and the rich blocks' tables with
 * {@link OreLoot}, so the two still cannot drift apart.
 *
 * <p>Run Sparse Ores' {@code data} run to (re)generate everything into {@code sparse-ores/src/generated/resources}.
 */
public final class RichOreDataGen {

    /**
     * The loot of an ore block: with Silk Touch it drops itself; otherwise it drops what its {@link RichOres} entry says,
     * some number of times over, and Fortune improves that with the same formula the ordinary block uses. The rich ores'
     * tables ({@link Loot}) and the pack's cut ones, ordinary and rich (the core's loot cuts, since split steps 2f and 4),
     * are built with it, each pair from the same entry, so the two cannot drift apart.
     */
    public abstract static class OreLoot extends BlockLootSubProvider {

        protected OreLoot(HolderLookup.Provider registries) {
            super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
        }

        /** Silk Touch gives the block; otherwise the spec's drop, {@code times} over, improved by Fortune. */
        protected LootTable.Builder oreDrop(Block block, RichOres.Spec spec, int times) {
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
    }

    /**
     * Loot of the rich ores: what the counterpart drops, {@link RichOres#RICHNESS} times over (a count of 1 becomes 5, a
     * range of 2-5 becomes 10-25). One instance covers either the rich ores of vanilla's ores, for the loot table
     * provider, or those of other mods' ores (rich zinc ore, from Create's), for {@link OtherModsLoot}, which writes them
     * with a condition on that mod.
     */
    public static final class Loot extends OreLoot {

        private final boolean otherMods;

        public Loot(HolderLookup.Provider registries) {
            this(registries, false);
        }

        Loot(HolderLookup.Provider registries, boolean otherMods) {
            super(registries);
            this.otherMods = otherMods;
        }

        @Override
        protected void generate() {
            for (RichOres.Variant variant : variants()) {
                Block block = RichOreBlocks.block(variant.name());
                add(block, oreDrop(block, RichOres.forOre(oreOf(variant)), RichOres.RICHNESS));
            }
        }

        @Override
        protected Iterable<Block> getKnownBlocks() {
            List<Block> known = new ArrayList<>();
            for (RichOres.Variant variant : variants()) {
                known.add(RichOreBlocks.block(variant.name()));
            }
            return known;
        }

        private List<RichOres.Variant> variants() {
            List<RichOres.Variant> variants = new ArrayList<>();
            for (RichOres.Variant variant : RichOres.allVariants()) {
                if (otherModOf(variant) != null == otherMods) {
                    variants.add(variant);
                }
            }
            return variants;
        }
    }

    /**
     * The loot tables of the rich ores of other mods' ores (rich zinc ore, from Create's), each written with a
     * {@code neoforge:mod_loaded} condition on that mod. Such a block is only registered with its mod (see
     * {@link RichOreBlocks}), and its table names that mod's drop ({@code create:raw_zinc}), so without the mod the game
     * would log a parse error for it; with the condition it skips the table quietly (split step 2f). The loot table
     * provider writes tables without conditions, and a run can have only one of it, hence this provider of its own.
     */
    public static final class OtherModsLoot implements DataProvider {

        private final PackOutput.PathProvider paths;
        private final CompletableFuture<HolderLookup.Provider> lookup;

        public OtherModsLoot(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
            this.paths = output.createRegistryElementsPathProvider(Registries.LOOT_TABLE);
            this.lookup = lookup;
        }

        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            return lookup.thenCompose(registries -> {
                Codec<Optional<WithConditions<LootTable>>> codec =
                        ConditionalOps.createConditionalCodecWithConditions(LootTable.DIRECT_CODEC);
                Map<ResourceKey<LootTable>, String> mods = new HashMap<>();
                for (RichOres.Variant variant : RichOres.allVariants()) {
                    if (otherModOf(variant) != null) {
                        mods.put(RichOreBlocks.block(variant.name()).getLootTable(), otherModOf(variant));
                    }
                }
                List<CompletableFuture<?>> writes = new ArrayList<>();
                new Loot(registries, true).generate((key, builder) -> {
                    String mod = mods.get(key);
                    LootTable table = builder.setRandomSequence(key.location()).setParamSet(LootContextParamSets.BLOCK).build();
                    writes.add(DataProvider.saveStable(cache, registries, codec,
                            Optional.of(new WithConditions<>(List.of(new ModLoadedCondition(mod)), table)),
                            paths.json(key.location())));
                });
                return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
            });
        }

        @Override
        public String getName() {
            return "Rich ores of other mods' ores: loot tables";
        }
    }

    /** The mod whose ore a rich block copies, or null for one of vanilla's ores. */
    private static String otherModOf(RichOres.Variant variant) {
        String namespace = ResourceLocation.parse(variant.counterpart()).getNamespace();
        return namespace.equals(ResourceLocation.DEFAULT_NAMESPACE) ? null : namespace;
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
                addTo(tag.apply(blockTag("minecraft:mineable/pickaxe")), block, variant);
                switch (spec.tier()) {
                    case STONE -> addTo(tag.apply(blockTag("minecraft:needs_stone_tool")), block, variant);
                    case IRON -> addTo(tag.apply(blockTag("minecraft:needs_iron_tool")), block, variant);
                    case NONE -> { }
                }
                for (String t : oreTags(spec, variant)) {
                    addTo(tag.apply(blockTag(t)), block, variant);
                }
            }
        }
    }

    /**
     * Adds a rich block to a tag. One that copies another mod's ore (rich zinc ore, from Create's) is only registered
     * with that mod, so it goes in as an optional entry: a missing required entry would fail the whole tag, such as
     * {@code minecraft:mineable/pickaxe}, in a game without Create. The item tags copy these entries as they are.
     */
    private static void addTo(IntrinsicHolderTagsProvider.IntrinsicTagAppender<Block> tag, Block block, RichOres.Variant variant) {
        if (otherModOf(variant) == null) {
            tag.add(block);
        } else {
            tag.addOptional(BuiltInRegistries.BLOCK.getKey(block));
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
