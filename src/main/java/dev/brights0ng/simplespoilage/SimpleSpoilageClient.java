package dev.brights0ng.simplespoilage;

import dev.brights0ng.simplespoilage.client.FoodClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Simple Spoilage's client-side registration. Only called on the client. */
public final class SimpleSpoilageClient {

    private SimpleSpoilageClient() {
    }

    public static void init(IEventBus modEventBus, ModContainer container) {
        // Food ages by the game clock; on the client, that is the client's copy of it.
        FoodClient.init();
    }
}
