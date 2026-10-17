package dev.brights0ng.simplespoilage;

import dev.brights0ng.simplespoilage.client.FoodClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Simple Spoilage on the client. Its spoilage settings moved from the core mod's config screen to its own when it became
 * a mod (split step 2d), so it offers NeoForge's config screen too.
 */
@Mod(value = SimpleSpoilage.MODID, dist = Dist.CLIENT)
public final class SimpleSpoilageClient {

    public SimpleSpoilageClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        init(modEventBus, container);
    }

    private static void init(IEventBus modEventBus, ModContainer container) {
        // Food ages by the game clock; on the client, that is the client's copy of it.
        FoodClient.init();
    }
}
