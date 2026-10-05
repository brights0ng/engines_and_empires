package dev.brights0ng.enginesandempires.geophone;

import java.util.List;
import java.util.function.Supplier;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import com.simibubi.create.api.stress.BlockStressValues;
import com.mojang.serialization.Codec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The seismic survey's blocks, items and entities: the sledgehammer, the strike plate, the geophone, the wind-up reader and
 * the mechanical thumper. The geophone and the reader are entities, staked or stood wherever they are clicked, and their
 * items are what place them; the thumper is a plain block, like the strike plate, since it is placed and driven the normal
 * Create way rather than by right-clicking something into the world.
 */
public final class SeismicContent {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EnginesAndEmpiresMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EnginesAndEmpiresMod.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, EnginesAndEmpiresMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EnginesAndEmpiresMod.MODID);
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, EnginesAndEmpiresMod.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, EnginesAndEmpiresMod.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EnginesAndEmpiresMod.MODID);

    public static final DeferredBlock<StrikePlateBlock> STRIKE_PLATE = BLOCKS.register("strike_plate",
            () -> new StrikePlateBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /**
     * The mechanical thumper: driven by Create's rotational power, wound up by {@link ThumperWinding}, and armed to wait
     * for a redstone pulse once fully wound. It needs a {@link #STRIKE_PLATE} directly beneath it (enforced by
     * {@link MechanicalThumperBlock#canSurvive}) and turns on a horizontal axis, driven by a meshing cogwheel rather than a
     * plugged-in shaft.
     */
    public static final DeferredBlock<MechanicalThumperBlock> MECHANICAL_THUMPER = BLOCKS.register("mechanical_thumper",
            () -> new MechanicalThumperBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(4.0F, 10.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /**
     * The combustive thumper: lifted by Create torque through a shaft, fired by a charge of fuel from its tank on a redstone
     * pulse. The top tier of the survey's shot sources. Like every thumper it needs a {@link #STRIKE_PLATE} beneath it.
     */
    public static final DeferredBlock<CombustiveThumperBlock> COMBUSTIVE_THUMPER = BLOCKS.register("combustive_thumper",
            () -> new CombustiveThumperBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(4.0F, 10.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)));

    /**
     * The two blocks above the combustive thumper's base: the rail section and the fuel cylinder. Placed only by the base,
     * with no item of their own and no drops: see {@link CombustiveThumperColumnBlock}.
     */
    public static final DeferredBlock<CombustiveThumperColumnBlock> COMBUSTIVE_THUMPER_COLUMN = BLOCKS.register(
            "combustive_thumper_column",
            () -> new CombustiveThumperColumnBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(4.0F, 10.0F)
                    .sound(SoundType.COPPER)
                    .noOcclusion()
                    .noLootTable()
                    .pushReaction(PushReaction.BLOCK)));

    /** A mechanical thumper an incursion has choked with sculk. See {@link ChokedThumperBlock}. */
    public static final DeferredBlock<ChokedThumperBlock> CHOKED_MECHANICAL_THUMPER = BLOCKS.register(
            "choked_mechanical_thumper",
            () -> new ChokedThumperBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(4.0F, 10.0F)
                    .sound(SoundType.SCULK_CATALYST)
                    .noOcclusion()));

    /** A combustive thumper an incursion has choked with sculk: just its base. See {@link ChokedThumperBlock}. */
    public static final DeferredBlock<ChokedThumperBlock> CHOKED_COMBUSTIVE_THUMPER = BLOCKS.register(
            "choked_combustive_thumper",
            () -> new ChokedThumperBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(4.0F, 10.0F)
                    .sound(SoundType.SCULK_CATALYST)
                    .noOcclusion()));

    /**
     * The smart logger: a 2x2 brass desk that listens through nearby geophones while it turns, keeps every deposit it
     * hears, and maps them across its top. All four quarters are this one block; see {@link SmartLoggerBlock}.
     */
    public static final DeferredBlock<SmartLoggerBlock> SMART_LOGGER = BLOCKS.register("smart_logger",
            () -> new SmartLoggerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_BROWN)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Item, SledgehammerItem> SLEDGEHAMMER = ITEMS.register("sledgehammer",
            () -> new SledgehammerItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, GeophoneItem> ANDESITE_GEOPHONE = ITEMS.register("andesite_geophone",
            () -> new GeophoneItem(new Item.Properties(), GeophoneTier.ANDESITE));

    public static final DeferredHolder<Item, GeophoneItem> BRASS_GEOPHONE = ITEMS.register("brass_geophone",
            () -> new GeophoneItem(new Item.Properties(), GeophoneTier.BRASS));

    /** The item a geophone of this tier is placed from, and turns back into when it is picked up or knocked out. */
    public static Item geophoneItemFor(GeophoneTier tier) {
        return switch (tier) {
            case ANDESITE -> ANDESITE_GEOPHONE.get();
            case BRASS -> BRASS_GEOPHONE.get();
        };
    }

    /** Not stackable: each carries its own reading. */
    public static final DeferredHolder<Item, WindupReaderItem> WINDUP_READER = ITEMS.register("windup_reader",
            () -> new WindupReaderItem(new Item.Properties().stacksTo(1)));

    static {
        ITEMS.registerSimpleBlockItem("strike_plate", STRIKE_PLATE);
        ITEMS.registerSimpleBlockItem("mechanical_thumper", MECHANICAL_THUMPER);
        ITEMS.registerSimpleBlockItem("combustive_thumper", COMBUSTIVE_THUMPER);
        ITEMS.registerSimpleBlockItem("choked_mechanical_thumper", CHOKED_MECHANICAL_THUMPER);
        ITEMS.registerSimpleBlockItem("choked_combustive_thumper", CHOKED_COMBUSTIVE_THUMPER);
        ITEMS.registerSimpleBlockItem("smart_logger", SMART_LOGGER);
    }

    /** Not stackable: each holds its own readings. */
    public static final DeferredHolder<Item, LogbookItem> LOGBOOK = ITEMS.register("logbook",
            () -> new LogbookItem(new Item.Properties().stacksTo(1)));

    /** Not stackable: each holds its own readings. See {@link PrdItem}. */
    public static final DeferredHolder<Item, PrdItem> PORTABLE_RECORD_DISPLAY = ITEMS.register("portable_record_display",
            () -> new PrdItem(new Item.Properties().stacksTo(1)));

    /** The reading a wind-up reader item carries: where a deposit is. An item without one has nothing to point at. */
    public static final Supplier<DataComponentType<ReaderReading>> READER_READING = COMPONENTS.registerComponentType("reader_reading",
            builder -> builder.persistent(ReaderCodecs.READING).networkSynchronized(ReaderCodecs.READING_STREAM));

    /**
     * How much of a wind a reader item has, from 0 (none) to 1 (enough to catch one reading). An item without this component
     * defaults to 0: never wound. See {@link ReaderWinding}.
     */
    public static final Supplier<DataComponentType<Float>> READER_CHARGE = COMPONENTS.registerComponentType("reader_charge",
            builder -> builder.persistent(Codec.FLOAT).networkSynchronized(ByteBufCodecs.FLOAT));

    /** The readings a logbook item holds. An item without any has an empty logbook. */
    public static final Supplier<DataComponentType<Logbook>> LOGBOOK_DATA = COMPONENTS.registerComponentType("logbook",
            builder -> builder.persistent(LogbookCodecs.LOGBOOK).networkSynchronized(LogbookCodecs.LOGBOOK_STREAM));

    /** The readings a portable record display holds: room for {@link PrdItem#CAPACITY}. */
    public static final Supplier<DataComponentType<Logbook>> PRD_DATA = COMPONENTS.registerComponentType("prd_records",
            builder -> builder.persistent(PrdItem.RECORDS_CODEC).networkSynchronized(PrdItem.RECORDS_STREAM));

    /**
     * The last flash of a portable record display's lights. Saved too, not only sent, so a display sitting in a smart
     * logger (whose items reach players as saved data) still shows it.
     */
    public static final Supplier<DataComponentType<PrdSignal>> PRD_SIGNAL = COMPONENTS.registerComponentType("prd_signal",
            builder -> builder.persistent(PrdItem.SIGNAL_CODEC).networkSynchronized(PrdItem.SIGNAL_STREAM));

    /** The screen behind a logbook. The server tells the client where in the inventory the book is. */
    public static final Supplier<MenuType<LogbookMenu>> LOGBOOK_MENU = MENUS.register("logbook",
            () -> IMenuTypeExtension.create(LogbookMenu::new));

    /** The smart logger's reader screen. The server tells the client where the logger is. */
    public static final Supplier<MenuType<SmartLoggerMenu>> SMART_LOGGER_MENU = MENUS.register("smart_logger",
            () -> IMenuTypeExtension.create(SmartLoggerMenu::new));

    /** The portable record display's records list. The server tells the client where in the inventory the display is. */
    public static final Supplier<MenuType<PrdMenu>> PRD_MENU = MENUS.register("portable_record_display",
            () -> IMenuTypeExtension.create(PrdMenu::new));

    /**
     * The creative tab for the whole seismic survey: the tools, in the order you would use them, then the readers and the book.
     * The items are only in this tab, not also in the game's own.
     */
    public static final Supplier<CreativeModeTab> SEISMOLOGY_TAB = TABS.register("seismology",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.engines_and_empires.seismology"))
                    .icon(() -> new ItemStack(SLEDGEHAMMER.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(SLEDGEHAMMER.get());
                        output.accept(STRIKE_PLATE.get());
                        output.accept(ANDESITE_GEOPHONE.get());
                        output.accept(BRASS_GEOPHONE.get());
                        output.accept(WINDUP_READER.get());
                        output.accept(LOGBOOK.get());
                        output.accept(MECHANICAL_THUMPER.get());
                        output.accept(COMBUSTIVE_THUMPER.get());
                        output.accept(CHOKED_MECHANICAL_THUMPER.get());
                        output.accept(CHOKED_COMBUSTIVE_THUMPER.get());
                        output.accept(SMART_LOGGER.get());
                        output.accept(PORTABLE_RECORD_DISPLAY.get());
                    })
                    .build());

    /**
     * The geophone. Its size is only a default: the hitbox is rebuilt to match the way it points, see
     * {@link GeophoneEntity}. It never moves, so it is tracked from far off but needs almost no updates.
     */
    public static final Supplier<EntityType<GeophoneEntity>> GEOPHONE_ENTITY = ENTITY_TYPES.register("geophone",
            () -> EntityType.Builder.<GeophoneEntity>of(GeophoneEntity::new, MobCategory.MISC)
                    .sized(0.28F, 0.28F)
                    .clientTrackingRange(8)
                    .updateInterval(20)
                    .fireImmune()
                    .noSummon()
                    .build("geophone"));

    /** The placed wind-up reader: a small box standing on the ground. It never moves, so it needs almost no updates. */
    public static final Supplier<EntityType<WindupReaderEntity>> WINDUP_READER_ENTITY = ENTITY_TYPES.register("windup_reader",
            () -> EntityType.Builder.<WindupReaderEntity>of(WindupReaderEntity::new, MobCategory.MISC)
                    .sized(0.6F, 0.4F)
                    .clientTrackingRange(8)
                    .updateInterval(20)
                    .fireImmune()
                    .noSummon()
                    .build("windup_reader"));

    /**
     * Holds the block entity type once built, so the factory below can hand it to each entity it creates. Plain
     * {@code BlockEntityType.Builder}'s factory only takes (pos, state), unlike Create's own Registrate-based helper, but
     * {@code KineticBlockEntity}'s constructor wants its type as a field regardless; this is the ordinary way to bridge
     * that without a "self-reference in initializer" error, which a direct reference to the field being declared runs into.
     */
    private static BlockEntityType<MechanicalThumperBlockEntity> mechanicalThumperEntityType;

    /** The mechanical thumper's block entity. It never moves and is not tracked to players the way an entity would: it just ticks. */
    public static final Supplier<BlockEntityType<MechanicalThumperBlockEntity>> MECHANICAL_THUMPER_ENTITY = BLOCK_ENTITY_TYPES.register(
            "mechanical_thumper", () -> {
                BlockEntityType<MechanicalThumperBlockEntity> type = BlockEntityType.Builder.of(
                        (pos, state) -> new MechanicalThumperBlockEntity(mechanicalThumperEntityType, pos, state),
                        MECHANICAL_THUMPER.get()).build(null);
                mechanicalThumperEntityType = type;
                return type;
            });

    /** Bridges the combustive thumper's block entity type into its own constructor; see {@link #mechanicalThumperEntityType}. */
    private static BlockEntityType<CombustiveThumperBlockEntity> combustiveThumperEntityType;

    public static final Supplier<BlockEntityType<CombustiveThumperBlockEntity>> COMBUSTIVE_THUMPER_ENTITY = BLOCK_ENTITY_TYPES.register(
            "combustive_thumper", () -> {
                BlockEntityType<CombustiveThumperBlockEntity> type = BlockEntityType.Builder.of(
                        (pos, state) -> new CombustiveThumperBlockEntity(combustiveThumperEntityType, pos, state),
                        COMBUSTIVE_THUMPER.get()).build(null);
                combustiveThumperEntityType = type;
                return type;
            });

    /** Bridges the smart logger's block entity type into its own constructor; see {@link #mechanicalThumperEntityType}. */
    private static BlockEntityType<SmartLoggerBlockEntity> smartLoggerEntityType;

    public static final Supplier<BlockEntityType<SmartLoggerBlockEntity>> SMART_LOGGER_ENTITY = BLOCK_ENTITY_TYPES.register(
            "smart_logger", () -> {
                BlockEntityType<SmartLoggerBlockEntity> type = BlockEntityType.Builder.of(
                        (pos, state) -> new SmartLoggerBlockEntity(smartLoggerEntityType, pos, state),
                        SMART_LOGGER.get()).build(null);
                smartLoggerEntityType = type;
                return type;
            });

    /** Every block of the survey, for the data generator. */
    public static List<Block> blocks() {
        return List.of(STRIKE_PLATE.get(), MECHANICAL_THUMPER.get(), COMBUSTIVE_THUMPER.get());
    }

    /** The choked thumpers, which have loot of their own (see the data generator). */
    public static List<Block> chokedThumpers() {
        return List.of(CHOKED_MECHANICAL_THUMPER.get(), CHOKED_COMBUSTIVE_THUMPER.get());
    }

    /**
     * Tells Create how much stress the thumper adds to a network while it is winding: an addon block registers this itself,
     * there is no separate Create-side entry to fill in. It contributes no capacity of its own, since it only consumes power.
     * Must run after the block exists, so it is called from mod setup, not from a static initialiser here.
     */
    public static void registerStressValues() {
        BlockStressValues.IMPACTS.register(MECHANICAL_THUMPER.get(), () -> 8.0);
        // The combustive thumper's torque only lifts the head (the fuel does the rest): a quarter of the load.
        BlockStressValues.IMPACTS.register(COMBUSTIVE_THUMPER.get(), () -> 2.0);
        // The smart logger's torque only runs its listening; the whole desk's load sits on its master quarter.
        BlockStressValues.IMPACTS.register(SMART_LOGGER.get(), () -> 4.0);
    }

    /** Exposes the combustive thumper's tank to pipes, pumps and anything else that moves fluid, from every side. */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, COMBUSTIVE_THUMPER_ENTITY.get(),
                (be, side) -> be.fluidCapability());
        // The column above hands pipes the base's tank, so fuel can be piped into the cylinder where it belongs.
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, be, side) ->
                level.getBlockEntity(CombustiveThumperColumnBlock.basePos(pos, state)) instanceof CombustiveThumperBlockEntity thumper
                        ? thumper.fluidCapability() : null,
                COMBUSTIVE_THUMPER_COLUMN.get());
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        COMPONENTS.register(modEventBus);
        MENUS.register(modEventBus);
        TABS.register(modEventBus);
    }

    private SeismicContent() {
    }
}
