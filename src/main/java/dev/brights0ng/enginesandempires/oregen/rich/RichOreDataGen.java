package dev.brights0ng.enginesandempires.oregen.rich;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import dev.brights0ng.enginesandempires.geophone.SmartLoggerBlock;
import dev.brights0ng.enginesandempires.geophone.SeismicDataGen;
import dev.brights0ng.enginesandempires.geophone.ThumperFluidTags;
import dev.brights0ng.enginesandempires.gametest.GameTestStructures;
import dev.brights0ng.enginesandempires.oregen.refining.RefiningDataGen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry;
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
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * The mod's data generator. It generates the resources of the rich ore blocks from {@link RichOres}, so they cannot
 * drift from it: loot tables, block states, block and item models, and tags. The rich ore textures are not generated;
 * they live in {@code src/main/resources/assets/engines_and_empires/textures/block}.
 *
 * <p>It also carries the seismic survey's resources, since only one loot, block state and tag provider can exist. The
 * survey contributes its own models, recipes and textures through {@link SeismicDataGen}.
 *
 * <p>Run the {@code Data} run configuration to (re)generate everything into {@code src/generated/resources}.
 */
public final class RichOreDataGen {

    /** Registered on the mod event bus by the mod class. */
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper existing = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();

        SeismicDataGen.trackTextures(existing);
        RefiningDataGen.trackTextures(existing);
        generator.addProvider(event.includeServer(), new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(Loot::new, LootContextParamSets.BLOCK),
                        new LootTableProvider.SubProviderEntry(RefiningDataGen.GolemLoot::new, LootContextParamSets.ENTITY)),
                lookup));
        generator.addProvider(event.includeClient(), new States(output, existing));
        BlockTagsGen blockTags = new BlockTagsGen(output, lookup, existing);
        generator.addProvider(event.includeServer(), blockTags);
        generator.addProvider(event.includeServer(), new ItemTagsGen(output, lookup, blockTags.contentsGetter(), existing));
        generator.addProvider(event.includeServer(), new SeismicDataGen.Recipes(output, lookup));
        generator.addProvider(event.includeServer(), new ThumperFluidTags(output, lookup, existing));
        generator.addProvider(event.includeServer(), new GameTestStructures(output));
        generator.addProvider(event.includeClient(), new SeismicDataGen.Textures(output));
        generator.addProvider(event.includeServer(), new RefiningDataGen.Recipes(output));
        generator.addProvider(event.includeClient(), new RefiningDataGen.Textures(output, existing));
    }

    /**
     * Loot: with Silk Touch a rich block drops itself, exactly as its counterpart does. Otherwise it
     * drops what its counterpart drops, {@link RichOres#RICHNESS} times over (a count of 1 becomes 5, a
     * range of 2-5 becomes 10-25), and Fortune improves that with the same formula the counterpart uses.
     *
     * <p>The ordinary blocks of the ores in {@link RichOres#PACK_LOOT} get their loot table from the same entry, once
     * over, replacing vanilla's.
     */
    private static final class Loot extends BlockLootSubProvider {

        Loot(HolderLookup.Provider registries) {
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
            for (Block block : SeismicContent.blocks()) {
                dropSelf(block);
            }
            // The smart logger is four blocks of one; only its master quarter drops the desk.
            add(SeismicContent.SMART_LOGGER.get(), createSinglePropConditionTable(SeismicContent.SMART_LOGGER.get(),
                    SmartLoggerBlock.PART, SmartLoggerBlock.Part.FRONT_LEFT));
            // Choked thumpers: Silk Touch gives the block (to be cooked clean); otherwise the iron block, and some of the
            // andesite alloy (mechanical, up to the 2 in its casing) or copper sheets (combustive, up to the 4 in its tank and
            // around its casing) it was made with.
            add(SeismicContent.CHOKED_MECHANICAL_THUMPER.get(), chokedDrop(SeismicContent.CHOKED_MECHANICAL_THUMPER.get(),
                    item("create:andesite_alloy"), 2));
            add(SeismicContent.CHOKED_COMBUSTIVE_THUMPER.get(), chokedDrop(SeismicContent.CHOKED_COMBUSTIVE_THUMPER.get(),
                    item("create:copper_sheet"), 4));
        }

        private LootTable.Builder chokedDrop(Block block, Item material, int most) {
            return LootTable.lootTable()
                    .withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                            .add(AlternativesEntry.alternatives(
                                    LootItem.lootTableItem(block).when(hasSilkTouch()),
                                    LootItem.lootTableItem(Items.IRON_BLOCK))))
                    .withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                            .when(doesNotHaveSilkTouch())
                            .add(LootItem.lootTableItem(material)
                                    .apply(SetItemCountFunction.setCount(UniformGenerator.between(1, most)))));
        }

        private static Item item(String id) {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id))
                    .orElseThrow(() -> new IllegalStateException("Item '" + id + "' does not exist. Is Create loaded?"));
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
            known.addAll(SeismicContent.blocks());
            known.addAll(SeismicContent.chokedThumpers());
            known.add(SeismicContent.SMART_LOGGER.get());
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
    private static final class States extends BlockStateProvider {

        States(PackOutput output, ExistingFileHelper existing) {
            super(output, EnginesAndEmpiresMod.MODID, existing);
        }

        @Override
        protected void registerStatesAndModels() {
            for (RichOres.Variant variant : RichOres.allVariants()) {
                Block block = RichOreBlocks.block(variant.name());
                ModelFile model = models().cubeAll(variant.name(), modLoc("block/" + variant.name()));
                // Rich redstone ore has a lit state; it looks the same as the unlit one, as redstone ore does.
                getVariantBuilder(block).forAllStates(state -> ConfiguredModel.builder().modelFile(model).build());
                simpleBlockItem(block, model);
            }
            SeismicDataGen.registerModels(this);
            RefiningDataGen.registerModels(this);
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

    /** Block tags: mineable with a pickaxe, the mining tier of the counterpart, and the ore tags. */
    private static final class BlockTagsGen extends BlockTagsProvider {

        BlockTagsGen(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper existing) {
            super(output, lookup, EnginesAndEmpiresMod.MODID, existing);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            for (RichOres.Spec spec : RichOres.ALL) {
                for (RichOres.Variant variant : spec.variants()) {
                    Block block = RichOreBlocks.block(variant.name());
                    tag(blockTag("minecraft:mineable/pickaxe")).add(block);
                    switch (spec.tier()) {
                        case STONE -> tag(blockTag("minecraft:needs_stone_tool")).add(block);
                        case IRON -> tag(blockTag("minecraft:needs_iron_tool")).add(block);
                        case NONE -> { }
                    }
                    for (String tag : oreTags(spec, variant)) {
                        tag(blockTag(tag)).add(block);
                    }
                }
            }
            for (Block block : SeismicContent.blocks()) {
                tag(blockTag("minecraft:mineable/pickaxe")).add(block);
            }
            // Not in blocks(): it drops nothing (breaking it drops the machine from its base), but mines like the rest.
            tag(blockTag("minecraft:mineable/pickaxe")).add(SeismicContent.COMBUSTIVE_THUMPER_COLUMN.get());
            tag(blockTag("minecraft:mineable/pickaxe")).add(SeismicContent.SMART_LOGGER.get());
            for (Block block : SeismicContent.chokedThumpers()) {
                tag(blockTag("minecraft:mineable/pickaxe")).add(block);
            }
        }
    }

    /** Item tags: the rich blocks' items are in the same ore tags as their blocks. */
    private static final class ItemTagsGen extends ItemTagsProvider {

        ItemTagsGen(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup,
                    CompletableFuture<TagsProvider.TagLookup<Block>> blockTags, ExistingFileHelper existing) {
            super(output, lookup, blockTags, EnginesAndEmpiresMod.MODID, existing);
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
