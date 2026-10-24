// Written by Gemini; loot condition type, creative tab and bonfire solidity added by Claude.
package dev.brights0ng.empire;

import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.bonfire.BonfireBlockEntity;
import dev.brights0ng.empire.bonfire.BonfireItem;
import dev.brights0ng.empire.bonfire.BonfirePartBlock;
import dev.brights0ng.empire.loot.CorrectToolForDrops;
import dev.brights0ng.empire.talc.BlessedTalcItem;
import dev.brights0ng.empire.talc.TarnishedTalcItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class EmpireContent {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Empire.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Empire.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Empire.MODID);
    public static final DeferredRegister<LootItemConditionType> LOOT_CONDITION_TYPES =
            DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, Empire.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Empire.MODID);

    /** {@code empire:correct_tool_for_drops} (levitite's loot tables). */
    public static final DeferredHolder<LootItemConditionType, LootItemConditionType> CORRECT_TOOL_FOR_DROPS =
            LOOT_CONDITION_TYPES.register("correct_tool_for_drops", () -> new LootItemConditionType(CorrectToolForDrops.CODEC));

    public static final DeferredBlock<BonfireBlock> BONFIRE = BLOCKS.register("bonfire",
            () -> new BonfireBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(50.0F, 1200.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
                    // Solid whatever its shape, so water and lava cannot wash it away.
                    .forceSolidOn()
                    .isViewBlocking((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false)
                    .isRedstoneConductor((state, level, pos) -> false)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> state.getValue(BonfireBlock.ROARING) ? BonfireBlock.LIGHT_ROARING
                            : (state.getValue(BonfireBlock.LIT) ? BonfireBlock.LIGHT_LIT : BonfireBlock.LIGHT_UNLIT))));

    public static final DeferredBlock<BonfirePartBlock> BONFIRE_PART = BLOCKS.register("bonfire_part",
            () -> new BonfirePartBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(50.0F, 1200.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
                    // The flame-only parts have no shape; forced solid, fluids still cannot flow in and break the bonfire.
                    .forceSolidOn()
                    .isViewBlocking((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false)
                    .isRedstoneConductor((state, level, pos) -> false)
                    .noLootTable()
                    .requiresCorrectToolForDrops()));

    public static final DeferredBlock<Block> TALC_ORE = BLOCKS.register("talc_ore",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.0F, 3.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    public static final DeferredBlock<Block> DEEPSLATE_TALC_ORE = BLOCKS.register("deepslate_talc_ore",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DEEPSLATE)
                    .strength(4.5F, 3.0F)
                    .sound(SoundType.DEEPSLATE)
                    .requiresCorrectToolForDrops()));

    public static final DeferredHolder<Item, BonfireItem> BONFIRE_ITEM = ITEMS.register("bonfire",
            () -> new BonfireItem(BONFIRE.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> TALC_ORE_ITEM = ITEMS.register("talc_ore",
            () -> new BlockItem(TALC_ORE.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> DEEPSLATE_TALC_ORE_ITEM = ITEMS.register("deepslate_talc_ore",
            () -> new BlockItem(DEEPSLATE_TALC_ORE.get(), new Item.Properties()));

    public static final DeferredItem<Item> RAW_TALC = ITEMS.register("raw_talc",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<BlessedTalcItem> BLESSED_TALC = ITEMS.register("blessed_talc",
            () -> new BlessedTalcItem(new Item.Properties()));

    public static final DeferredItem<TarnishedTalcItem> TARNISHED_TALC = ITEMS.register("tarnished_talc",
            () -> new TarnishedTalcItem(new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BonfireBlockEntity>> BONFIRE_BE =
            BLOCK_ENTITY_TYPES.register("bonfire",
                    () -> BlockEntityType.Builder.of(BonfireBlockEntity::new, BONFIRE.get()).build(null));

    /**
     * The creative tab for everything Empire registers, icon the bonfire. The items are only in this tab, not also in the
     * game's own (same pattern as Seismic Surveyance's tab). The bonfire's structure part has no item.
     */
    public static final Supplier<CreativeModeTab> EMPIRE_TAB = TABS.register("empire",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.empire.empire"))
                    .icon(() -> new ItemStack(BONFIRE_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(BONFIRE_ITEM.get());
                        output.accept(TALC_ORE_ITEM.get());
                        output.accept(DEEPSLATE_TALC_ORE_ITEM.get());
                        output.accept(RAW_TALC.get());
                        output.accept(BLESSED_TALC.get());
                        output.accept(TARNISHED_TALC.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        LOOT_CONDITION_TYPES.register(modEventBus);
        TABS.register(modEventBus);
    }

    private EmpireContent() {
    }
}
