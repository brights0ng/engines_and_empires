// Written by Claude.
package dev.brights0ng.meteodyne;

import java.util.function.Supplier;

import dev.brights0ng.meteodyne.instrument.InstrumentContent;
import dev.brights0ng.meteodyne.surface.SurfaceContent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The creative tab for everything Meteodyne registers: the instruments (each handheld next to its mounted sensor), then
 * glaze. The items are only in this tab, not also in the game's own (same pattern as Seismic Surveyance's tab).
 */
public final class MeteodyneTab {

    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Meteodyne.MODID);

    public static final Supplier<CreativeModeTab> METEODYNE_TAB = TABS.register("meteodyne",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.meteodyne.meteodyne"))
                    .icon(() -> new ItemStack(InstrumentContent.THERMOMETER.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(InstrumentContent.THERMOMETER.get());
                        output.accept(InstrumentContent.TEMPERATURE_SENSOR_ITEM.get());
                        output.accept(InstrumentContent.WIND_GAUGE.get());
                        output.accept(InstrumentContent.WIND_SENSOR_ITEM.get());
                        output.accept(SurfaceContent.GLAZE_ITEM.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }

    private MeteodyneTab() {
    }
}
