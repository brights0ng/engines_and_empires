package dev.brights0ng.enginesandempires;

import net.minecraft.client.Minecraft;
import dev.brights0ng.enginesandempires.geophone.client.TierOverlay;
import dev.brights0ng.seismicsurveyance.client.MapLayers;
import dev.brights0ng.simplespoilage.SimpleSpoilageClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = EnginesAndEmpires.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = EnginesAndEmpires.MODID, value = Dist.CLIENT)
public class EnginesAndEmpiresModClient {
    public EnginesAndEmpiresModClient(IEventBus modEventBus, ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // Each future mod's client-side registration, in the order they ran before the split (Seismic Surveyance has its
        // own client entry point since split step 2e)
        SimpleSpoilageClient.init(modEventBus, container);

        // Frontier's tiers and incursions on the survey's maps (the smart logger and the portable record display)
        MapLayers.register(TierOverlay.INSTANCE);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        EnginesAndEmpires.LOGGER.info("HELLO FROM CLIENT SETUP");
        EnginesAndEmpires.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
