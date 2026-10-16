package dev.brights0ng.meteodyne;

import dev.brights0ng.enginesandempires.weather.cloud.client.CloudClient;
import dev.brights0ng.enginesandempires.weather.heat.world.client.HeatViewClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;

/** Meteodyne's client-side registration. Only called on the client. */
public final class MeteodyneClient {

    private MeteodyneClient() {
    }

    public static void init(IEventBus modEventBus, ModContainer container) {
        // The voxel cloud renderer: draws Project Atmosphere's clouds (a CLIENT config of its own)
        CloudClient.init(modEventBus, container);
        // The heat simulation's debug view (/eae heat show)
        NeoForge.EVENT_BUS.addListener(HeatViewClient::onRenderStage);
    }
}
