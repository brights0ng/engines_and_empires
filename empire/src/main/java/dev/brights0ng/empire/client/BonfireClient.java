// Written by Claude.
package dev.brights0ng.empire.client;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client registration for the bonfire's GeckoLib model ({@link BonfireRenderer}, the placed bonfire). The item is an
 * ordinary sprite ({@code textures/item/bonfire.png}). Only ever loaded on a client.
 */
@EventBusSubscriber(modid = Empire.MODID, value = Dist.CLIENT)
public final class BonfireClient {

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(EmpireContent.BONFIRE_BE.get(), BonfireRenderer::new);
    }

    private BonfireClient() {
    }
}
