package dev.brights0ng.frontier;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Frontier on the client. Its settings moved from the core mod's config screen to its own when it became a mod (split
 * step 2d), so it offers NeoForge's config screen; it has no other client-side registration.
 */
@Mod(value = Frontier.MODID, dist = Dist.CLIENT)
public final class FrontierClient {

    public FrontierClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
