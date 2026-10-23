// Written by Gemini; viewer lookup by membership edited by Claude.
package dev.brights0ng.empire.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.ClaimGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The maps' empire claim overlay over the network: a map on the client asks for the claims around what it shows
 * ({@link ClaimMapPayloads.Request}), and the server answers with them and the active main bonfires
 * ({@link ClaimMapPayloads.Answer}). Only the Overworld has empires.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class ClaimMapNetwork {

    private static final String VERSION = "1";
    /** A player is answered at most this often, in ticks, however many maps they look at. */
    private static final int ANSWER_GAP = 5;
    /** How far from a player, in chunks, a map may ask about. */
    private static final int MAX_DISTANCE = 512;

    private static final Map<UUID, Long> LAST_ANSWERED = new WeakHashMap<>();

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(ClaimMapPayloads.Request.TYPE, ClaimMapPayloads.Request.STREAM_CODEC, ClaimMapNetwork::onRequest);
        registrar.playToClient(ClaimMapPayloads.Answer.TYPE, ClaimMapPayloads.Answer.STREAM_CODEC,
                (answer, context) -> ClaimMapCache.accept(answer));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ANSWERED.remove(event.getEntity().getUUID());
    }

    private static void onRequest(ClaimMapPayloads.Request request, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            answer(player, request);
        }
    }

    /** Answers a map's request, if the player is in the Overworld, not asking too often, and asking about somewhere sane. */
    public static void answer(ServerPlayer player, ClaimMapPayloads.Request request) {
        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        long now = level.getGameTime();
        Long last = LAST_ANSWERED.get(player.getUUID());
        if (last != null && now - last < ANSWER_GAP && now >= last) {
            return;
        }
        int pcx = player.blockPosition().getX() >> 4;
        int pcz = player.blockPosition().getZ() >> 4;
        if (Math.abs(request.x() - pcx) > MAX_DISTANCE || Math.abs(request.z() - pcz) > MAX_DISTANCE) {
            return;
        }
        LAST_ANSWERED.put(player.getUUID(), now);
        PacketDistributor.sendToPlayer(player, build(level, player.getUUID(), request.x(), request.z(), request.radius()));
    }

    /** The answer for the columns within {@code radius} (capped) of chunk ({@code cx}, {@code cz}). */
    public static ClaimMapPayloads.Answer build(ServerLevel level, java.util.UUID viewerUuid, int cx, int cz, int radius) {
        int r = Math.max(0, Math.min(ClaimMapPayloads.MAX_RADIUS, radius));
        int size = 2 * r + 1;
        byte[] states = new byte[size * size];

        EmpireSavedData savedData = EmpireSavedData.of(level);
        EmpireRecord playerEmpire = viewerUuid != null ? savedData.getEmpireOf(viewerUuid) : null;

        int minChunkX = cx - r;
        int minChunkZ = cz - r;

        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int curCx = minChunkX + dx;
                int curCz = minChunkZ + dz;
                long key = ChunkKeys.pack(curCx, curCz);
                EmpireRecord owner = savedData.getEmpireByChunk(key);
                byte state = ClaimGrid.UNCLAIMED;
                if (owner != null) {
                    if (playerEmpire != null && owner.id().equals(playerEmpire.id())) {
                        state = ClaimGrid.OUR_EMPIRE;
                    } else {
                        state = ClaimGrid.OTHER_EMPIRE;
                    }
                }
                states[dz * size + dx] = state;
            }
        }

        List<ClaimMapPayloads.BonfireMarker> bonfires = new ArrayList<>();
        int minBlockX = minChunkX << 4;
        int maxBlockX = (minChunkX + size) << 4;
        int minBlockZ = minChunkZ << 4;
        int maxBlockZ = (minChunkZ + size) << 4;

        for (EmpireRecord record : savedData.allEmpires()) {
            BlockPos mainPos = record.mainBonfire();
            if (mainPos != null) {
                int bx = mainPos.getX();
                int bz = mainPos.getZ();
                if (bx >= minBlockX && bx <= maxBlockX && bz >= minBlockZ && bz <= maxBlockZ) {
                    boolean ourEmpire = playerEmpire != null && record.id().equals(playerEmpire.id());
                    bonfires.add(new ClaimMapPayloads.BonfireMarker(bx, bz, ourEmpire));
                }
            }
        }

        return new ClaimMapPayloads.Answer(minChunkX, minChunkZ, size, ClaimGrid.pack(states), bonfires);
    }

    private ClaimMapNetwork() {
    }
}
