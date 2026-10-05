package dev.brights0ng.enginesandempires.food;

import java.util.function.Supplier;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.food.icechest.IceChestBlock;
import dev.brights0ng.enginesandempires.food.icechest.IceChestBlockEntity;
import dev.brights0ng.enginesandempires.food.icechest.IceChestDataGen;
import dev.brights0ng.enginesandempires.food.icechest.IceChestMenu;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers what food spoilage adds to the game. */
public final class FoodContent {

    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, EnginesAndEmpiresMod.MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EnginesAndEmpiresMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EnginesAndEmpiresMod.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EnginesAndEmpiresMod.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, EnginesAndEmpiresMod.MODID);

    /**
     * How fresh the units in a food stack are. A food stack without it has not been looked at yet and counts as all born
     * now; it gets one as soon as it is (see {@link Spoilage#normalize}).
     */
    public static final Supplier<DataComponentType<FoodFreshness>> FRESHNESS = COMPONENTS.registerComponentType("food_freshness",
            builder -> builder.persistent(FoodCodecs.FRESHNESS).networkSynchronized(FoodCodecs.FRESHNESS_STREAM.cast()));

    /** The ice chest: a barrel framed in iron and lined with wool, that keeps food 90% longer while it has ice. */
    public static final DeferredBlock<IceChestBlock> ICE_CHEST = BLOCKS.register("ice_chest",
            () -> new IceChestBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BARREL).strength(3.0F, 6.0F)));

    public static final DeferredItem<BlockItem> ICE_CHEST_ITEM = ITEMS.registerSimpleBlockItem("ice_chest", ICE_CHEST);

    @SuppressWarnings("DataFlowIssue")
    public static final Supplier<BlockEntityType<IceChestBlockEntity>> ICE_CHEST_ENTITY = BLOCK_ENTITY_TYPES.register("ice_chest",
            () -> BlockEntityType.Builder.of(IceChestBlockEntity::new, ICE_CHEST.get()).build(null));

    public static final Supplier<MenuType<IceChestMenu>> ICE_CHEST_MENU = MENUS.register("ice_chest",
            () -> new MenuType<>(IceChestMenu::new, FeatureFlags.DEFAULT_FLAGS));

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENUS.register(modEventBus);
        modEventBus.addListener(FoodContent::registerCapabilities);
        modEventBus.addListener(FoodContent::addToCreativeTab);
        modEventBus.addListener(IceChestDataGen::gatherData);
    }

    /** Hoppers, Create's funnels and the like reach the ice chest by side (see {@link IceChestBlockEntity#handlerFor}). */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ICE_CHEST_ENTITY.get(), IceChestBlockEntity::handlerFor);
    }

    private static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ICE_CHEST_ITEM.get());
        }
    }

    private FoodContent() {
    }
}
