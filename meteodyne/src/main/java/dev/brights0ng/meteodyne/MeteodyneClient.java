package dev.brights0ng.meteodyne;

import dev.brights0ng.meteodyne.cloud.client.CloudClient;
import dev.brights0ng.meteodyne.heat.world.client.HeatViewClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Meteodyne on the client. Its weather and cloud settings moved from the core mod's config screen to Meteodyne's own
 * when it became a mod (split step 2c), so it offers NeoForge's config screen too.
 */
@Mod(value = Meteodyne.MODID, dist = Dist.CLIENT)
public final class MeteodyneClient {

    public MeteodyneClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        init(modEventBus, container);
    }

    private static void init(IEventBus modEventBus, ModContainer container) {
        // The voxel cloud renderer: draws Project Atmosphere's clouds (a CLIENT config of its own)
        CloudClient.init(modEventBus, container);
        // The heat simulation's debug view (/meteodyne heat show)
        NeoForge.EVENT_BUS.addListener(HeatViewClient::onRenderStage);
    }
}
