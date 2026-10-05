package dev.brights0ng.enginesandempires.geophone.client;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client-side registration for the logbook: which screen is drawn for its menu. Only ever loaded on a client.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class LogbookClient {

    @SubscribeEvent
    static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(SeismicContent.LOGBOOK_MENU.get(), LogbookScreen::new);
        event.register(SeismicContent.SMART_LOGGER_MENU.get(), SmartLoggerScreen::new);
    }

    private LogbookClient() {
    }
}
