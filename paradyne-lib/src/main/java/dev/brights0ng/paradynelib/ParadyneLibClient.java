package dev.brights0ng.paradynelib;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Paradyne Lib on the client. The wind settings ({@code WindConfig}) moved from the core mod's config screen to the
 * library's own when the library became a mod (split step 2b), so the library offers NeoForge's config screen too.
 */
@Mod(value = ParadyneLib.MODID, dist = Dist.CLIENT)
public final class ParadyneLibClient {

    public ParadyneLibClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
