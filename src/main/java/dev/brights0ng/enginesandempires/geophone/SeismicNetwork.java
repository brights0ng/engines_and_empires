package dev.brights0ng.enginesandempires.geophone;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The messages the seismic tools send from client to server: a board-of-readings screen asking for something, the portable
 * record display's map asking for its records list, the display worked by hand (deleting or renaming a reading on it), and
 * a smart logger's map being worked.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class SeismicNetwork {

    /** Bumped if a message's format ever changes, so old and new clients are told apart. */
    private static final String VERSION = "4";

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(ReadingBoardActionPayload.TYPE, ReadingBoardActionPayload.STREAM_CODEC, SeismicNetwork::onBoardAction);
        registrar.playToServer(OpenPrdRecordsPayload.TYPE, OpenPrdRecordsPayload.STREAM_CODEC, SeismicNetwork::onOpenPrdRecords);
        registrar.playToServer(PrdActionPayload.TYPE, PrdActionPayload.STREAM_CODEC,
                (payload, context) -> PrdItem.act(context.player(), payload.slot(), payload.action(), payload.number(), payload.name()));
        registrar.playToServer(LoggerMapPayloads.View.TYPE, LoggerMapPayloads.View.STREAM_CODEC, SeismicNetwork::onLoggerView);
        registrar.playToServer(LoggerMapPayloads.Send.TYPE, LoggerMapPayloads.Send.STREAM_CODEC, SeismicNetwork::onLoggerSend);
    }

    /** The logger at {@code pos}, if it is loaded and the player is near enough to work it. */
    private static SmartLoggerBlockEntity logger(Player player, net.minecraft.core.BlockPos pos) {
        if (!player.level().isLoaded(pos)) {
            return null;
        }
        return player.level().getBlockEntity(pos) instanceof SmartLoggerBlockEntity logger && logger.isMaster()
                && logger.inReach(player) ? logger : null;
    }

    private static void onLoggerView(LoggerMapPayloads.View payload, IPayloadContext context) {
        SmartLoggerBlockEntity logger = logger(context.player(), payload.pos());
        if (logger != null) {
            logger.setView(context.player(), payload.view());
        }
    }

    private static void onLoggerSend(LoggerMapPayloads.Send payload, IPayloadContext context) {
        SmartLoggerBlockEntity logger = logger(context.player(), payload.pos());
        if (logger != null) {
            logger.sendToDisplay(context.player(), payload.number());
        }
    }

    /** Runs on the server's main thread. Only means anything if the player has a board of readings open. */
    private static void onBoardAction(ReadingBoardActionPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (player.containerMenu instanceof ReadingBoardMenu menu && menu.stillValid(player)) {
            menu.act(player, payload.action(), payload.number(), payload.name());
        }
    }

    /** Runs on the server's main thread. Only for a display the player is actually holding. */
    private static void onOpenPrdRecords(OpenPrdRecordsPayload payload, IPayloadContext context) {
        Player player = context.player();
        int slot = payload.slot();
        if (slot != player.getInventory().selected && slot != Inventory.SLOT_OFFHAND) {
            return;
        }
        if (player.getInventory().getItem(slot).getItem() instanceof PrdItem) {
            PrdItem.openRecords(player, slot);
        }
    }

    private SeismicNetwork() {
    }
}
