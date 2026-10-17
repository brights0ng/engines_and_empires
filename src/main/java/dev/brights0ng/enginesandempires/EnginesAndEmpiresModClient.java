package dev.brights0ng.enginesandempires;

import net.minecraft.client.Minecraft;
import dev.brights0ng.enginesandempires.geophone.client.TierOverlay;
import dev.brights0ng.seismicsurveyance.client.MapLayers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = EnginesAndEmpires.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = EnginesAndEmpires.MODID, value = Dist.CLIENT)
public class EnginesAndEmpiresModClient {
    public EnginesAndEmpiresModClient(IEventBus modEventBus, ModContainer container) {
        // No config screen: the core has no config of its own since split step 2d (each mod of the family registers
        // its own screen), so NeoForge's would be empty (dropped in split step 2f).
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
