// Written by Claude.
package dev.brights0ng.empire.client;

import dev.brights0ng.empire.map.ClaimMapCache;
import dev.brights0ng.empire.map.ClaimMapNetwork;
import dev.brights0ng.empire.map.ClaimMapPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Keeps the claim cache fresh around the player in the Overworld at all times (every
 * {@link dev.brights0ng.empire.rules.ClaimRequests#REFRESH_MILLIS}, and when the player moves), so the client can
 * predict divine protection ({@code DivineProtection}) and the talc borders have their claims. Forgets the cache on
 * leaving a server, so one server's claims never stand in for another's.
 */
public final class ClaimCacheKeeper {

    /** The {@link ClaimMapCache#shouldAsk} key of the area around the player. */
    public static final String KEY = "around_player";

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ClaimCacheKeeper::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClaimCacheKeeper::onLoggingOut);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.getConnection() == null
                || !mc.level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        int pcx = player.blockPosition().getX() >> 4;
        int pcz = player.blockPosition().getZ() >> 4;
        if (ClaimMapCache.shouldAsk(KEY, pcx, pcz, ClaimMapNetwork.AROUND_PLAYER_RADIUS, System.currentTimeMillis())) {
            PacketDistributor.sendToServer(new ClaimMapPayloads.Request(pcx, pcz, ClaimMapNetwork.AROUND_PLAYER_RADIUS));
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClaimMapCache.clear();
    }

    private ClaimCacheKeeper() {
    }
}
