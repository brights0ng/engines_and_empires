// Written by Gemini.
package dev.brights0ng.empire.record;

import java.util.function.Consumer;

import dev.brights0ng.empire.Empire;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers player motion-recorder payloads and dispatches client-bound messages through callbacks registered by
 * {@code MotionRecorderClient} so common/server classes never touch client-only classes.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class MotionRecorderNetwork {

    private static final String VERSION = "1";

    private static volatile Consumer<RecordPayloads.Control> clientControlHandler = payload -> {
    };
    private static volatile Consumer<RecordPayloads.ServerEvent> clientEventHandler = payload -> {
    };

    /** Called once from {@code EmpireClient} to hook up the client recorder handlers. */
    public static void registerClientHandlers(Consumer<RecordPayloads.Control> controlHandler,
                                              Consumer<RecordPayloads.ServerEvent> eventHandler) {
        clientControlHandler = controlHandler != null ? controlHandler : p -> {
        };
        clientEventHandler = eventHandler != null ? eventHandler : p -> {
        };
    }

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(RecordPayloads.Control.TYPE, RecordPayloads.Control.STREAM_CODEC,
                (payload, context) -> clientControlHandler.accept(payload));
        registrar.playToClient(RecordPayloads.ServerEvent.TYPE, RecordPayloads.ServerEvent.STREAM_CODEC,
                (payload, context) -> clientEventHandler.accept(payload));
    }

    public static boolean canReceive(ServerPlayer player) {
        return player.connection != null
                && player.connection.getConnection().channel() != null
                && player.connection.hasChannel(RecordPayloads.Control.TYPE);
    }

    public static boolean sendControl(ServerPlayer player, RecordPayloads.Action action, String arg) {
        if (!canReceive(player)) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, new RecordPayloads.Control(action, arg != null ? arg : ""));
        return true;
    }

    public static void sendServerEvent(ServerPlayer player, long gameTime, String kind, String payloadJson) {
        if (!canReceive(player)) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
                new RecordPayloads.ServerEvent(gameTime, kind, payloadJson != null ? payloadJson : "{}"));
    }

    private MotionRecorderNetwork() {
    }
}
