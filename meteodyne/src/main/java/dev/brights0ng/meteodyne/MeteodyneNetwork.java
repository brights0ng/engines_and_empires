// Written by Claude (GreedyFluids M7b follow-up).
package dev.brights0ng.meteodyne;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sending Meteodyne's play-to-client payloads. Every one is registered optional, so a client may lack the channel (no
 * Meteodyne, or a game test's mock player); NeoForge throws on a payload a connection never agreed to, which crashed
 * the server. {@link #sendTo} skips such players.
 */
public final class MeteodyneNetwork {

    /** Sends {@code payload} to {@code player} if their connection has its channel; returns whether it was sent. */
    public static boolean sendTo(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection == null || !player.connection.hasChannel(payload.type())) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, payload);
        return true;
    }

    private MeteodyneNetwork() {
    }
}
