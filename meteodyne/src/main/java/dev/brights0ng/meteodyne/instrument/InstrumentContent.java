// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import java.util.function.Supplier;
import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registration and generated assets for Meteodyne's weather instruments. */
public final class InstrumentContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Meteodyne.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Meteodyne.MODID);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Meteodyne.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Meteodyne.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Meteodyne.MODID);

    public static final Supplier<DataComponentType<InstrumentReading>> READING = COMPONENTS.registerComponentType(
            "instrument_reading", builder -> builder.persistent(InstrumentReadingCodecs.CODEC).networkSynchronized(InstrumentReadingCodecs.STREAM_CODEC));
    public static final Supplier<DataComponentType<WindReading>> WIND_READING = COMPONENTS.registerComponentType(
            "wind_reading", builder -> builder.persistent(WindReadingCodecs.CODEC).networkSynchronized(WindReadingCodecs.STREAM_CODEC));
    public static final DeferredItem<WindGaugeItem> WIND_GAUGE = ITEMS.register("wind_gauge",
            () -> new WindGaugeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<WindSensorBlock> WIND_SENSOR = BLOCKS.register("wind_sensor",
            () -> new WindSensorBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                    .strength(2.5F).sound(SoundType.METAL).noOcclusion().isRedstoneConductor((s, l, p) -> false)));
    public static final DeferredItem<BlockItem> WIND_SENSOR_ITEM = ITEMS.registerSimpleBlockItem("wind_sensor", WIND_SENSOR);
    public static final Supplier<BlockEntityType<WindSensorBlockEntity>> WIND_SENSOR_ENTITY = BLOCK_ENTITIES.register(
            "wind_sensor", () -> BlockEntityType.Builder.of(WindSensorBlockEntity::new, WIND_SENSOR.get()).build(null));
    public static final DeferredItem<ThermometerItem> THERMOMETER = ITEMS.register("thermometer",
            () -> new ThermometerItem(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<TemperatureSensorBlock> TEMPERATURE_SENSOR = BLOCKS.register("temperature_sensor",
            () -> new TemperatureSensorBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                    .strength(2.5F).sound(SoundType.METAL).noOcclusion().isRedstoneConductor((s, l, p) -> false)));
    public static final DeferredItem<BlockItem> TEMPERATURE_SENSOR_ITEM = ITEMS.registerSimpleBlockItem(
            "temperature_sensor", TEMPERATURE_SENSOR);
    public static final Supplier<BlockEntityType<TemperatureSensorBlockEntity>> TEMPERATURE_SENSOR_ENTITY = BLOCK_ENTITIES.register(
            "temperature_sensor", () -> BlockEntityType.Builder.of(TemperatureSensorBlockEntity::new, TEMPERATURE_SENSOR.get()).build(null));
    public static final Supplier<MenuType<SensorMenu>> SENSOR_MENU = MENUS.register("sensor", () -> IMenuTypeExtension.create(SensorMenu::new));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        COMPONENTS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        bus.addListener(InstrumentContent::creativeTab);
        bus.addListener(InstrumentDataGen::gatherData);
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(THERMOMETER);
            event.accept(WIND_GAUGE);
        } else if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(TEMPERATURE_SENSOR_ITEM);
            event.accept(WIND_SENSOR_ITEM);
        }
    }

    private InstrumentContent() { }
}
