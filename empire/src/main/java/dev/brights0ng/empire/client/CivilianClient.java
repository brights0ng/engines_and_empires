// Written by Claude; civilian inventory screen registration added by Gemini.
package dev.brights0ng.empire.client;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.civilian.CivilianSpeechStore;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Client registration for civilians: the renderer, menu screen, skin library, and clearing skins and bubbles on logout. */
@EventBusSubscriber(modid = Empire.MODID, value = Dist.CLIENT)
public final class CivilianClient {

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EmpireContent.CIVILIAN.get(), CivilianRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(EmpireContent.CIVILIAN_INVENTORY_MENU.get(), CivilianInventoryScreen::new);
    }

    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new CivilianSkinLibrary());
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        CivilianSkinCache.clear();
        CivilianSpeechStore.clear();
    }

    private CivilianClient() {
    }
}
