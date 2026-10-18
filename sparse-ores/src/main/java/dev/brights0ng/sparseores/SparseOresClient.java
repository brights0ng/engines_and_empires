package dev.brights0ng.sparseores;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Sparse Ores on the client. It has a config since split step 4b (whether ordinary coal, diamond and nether quartz ore
 * drop chips, {@link SparseOresConfig}), so it offers NeoForge's config screen, as the family's other mods with settings
 * do; it has no other client-side registration.
 */
@Mod(value = SparseOres.MODID, dist = Dist.CLIENT)
public final class SparseOresClient {

    public SparseOresClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
