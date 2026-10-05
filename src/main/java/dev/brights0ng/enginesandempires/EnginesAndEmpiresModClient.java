package dev.brights0ng.enginesandempires;

import net.minecraft.client.Minecraft;
import dev.brights0ng.enginesandempires.geophone.client.PrdItemRenderer;
import dev.brights0ng.enginesandempires.geophone.client.ThumperClient;
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
@Mod(value = EnginesAndEmpiresMod.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public class EnginesAndEmpiresModClient {
    public EnginesAndEmpiresModClient(IEventBus modEventBus, ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // Creates the thumper's partial models now, before models load, so Flywheel knows to bake them.
        ThumperClient.init();
        // Likewise the portable record display's model, which its item renderer draws.
        PrdItemRenderer.init();
        // Food ages by the game clock; on the client, that is the client's copy of it.
        dev.brights0ng.enginesandempires.food.client.FoodClient.init();
        // The voxel cloud renderer: draws Project Atmosphere's clouds (a CLIENT config of its own)
        dev.brights0ng.enginesandempires.weather.cloud.client.CloudClient.init(modEventBus, container);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        EnginesAndEmpiresMod.LOGGER.info("HELLO FROM CLIENT SETUP");
        EnginesAndEmpiresMod.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
