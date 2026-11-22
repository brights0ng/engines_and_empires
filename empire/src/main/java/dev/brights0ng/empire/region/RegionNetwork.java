// Written by Gemini.
package dev.brights0ng.empire.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
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
 * Syncs an empire's nearby regions to clients for in-world veil/chalk rendering.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class RegionNetwork {

    private static final String VERSION = "2";
    /** Minimum ticks between answering explicit client requests from the same player. */
    private static final int ANSWER_GAP = 5;
    /** Default chunk radius used when pushing region updates to online empire members. */
    public static final int RENDER_RADIUS = 5;

    private static final Map<UUID, Long> LAST_ANSWERED = new WeakHashMap<>();

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(RegionPayloads.Request.TYPE, RegionPayloads.Request.STREAM_CODEC, RegionNetwork::onRequest);
        registrar.playToClient(RegionPayloads.Sync.TYPE, RegionPayloads.Sync.STREAM_CODEC,
                (sync, context) -> RegionClientCache.accept(sync));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ANSWERED.remove(event.getEntity().getUUID());
    }

    private static void onRequest(RegionPayloads.Request request, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            answer(player, request);
        }
    }

    public static void answer(ServerPlayer player, RegionPayloads.Request request) {
        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD || !canReceive(player)) {
            return;
        }
        long now = level.getGameTime();
        Long last = LAST_ANSWERED.get(player.getUUID());
        if (last != null && now - last < ANSWER_GAP && now >= last) {
            return;
        }
        int pcx = player.blockPosition().getX() >> 4;
        int pcz = player.blockPosition().getZ() >> 4;
        if (Math.abs(request.x() - pcx) > RegionPayloads.MAX_RADIUS
                || Math.abs(request.z() - pcz) > RegionPayloads.MAX_RADIUS) {
            return;
        }
        LAST_ANSWERED.put(player.getUUID(), now);
        int radius = Math.max(0, Math.min(RegionPayloads.MAX_RADIUS, request.radius()));
        PacketDistributor.sendToPlayer(player, build(level, player.getUUID(), request.x(), request.z(), radius));
    }

    /** Pushes updated nearby regions to every online Overworld member of {@code empireId}. */
    public static void pushToEmpire(ServerLevel overworld, UUID empireId) {
        EmpireSavedData data = EmpireSavedData.of(overworld);
        EmpireRecord empire = data.getEmpire(empireId);
        if (empire == null) {
            return;
        }
        for (ServerPlayer player : overworld.players()) {
            if (empire.isMember(player.getUUID()) && canReceive(player)) {
                int pcx = player.blockPosition().getX() >> 4;
                int pcz = player.blockPosition().getZ() >> 4;
                PacketDistributor.sendToPlayer(player, build(overworld, player.getUUID(), pcx, pcz, RENDER_RADIUS));
            }
        }
    }

    /** Builds a {@link RegionPayloads.Sync} of {@code viewerUuid}'s empire's regions within {@code radius} chunks of {@code (cx, cz)}. */
    public static RegionPayloads.Sync build(ServerLevel overworld, UUID viewerUuid, int cx, int cz, int radius) {
        EmpireSavedData data = EmpireSavedData.of(overworld);
        EmpireRecord empire = viewerUuid != null ? data.getEmpireOf(viewerUuid) : null;
        if (empire == null) {
            return new RegionPayloads.Sync(List.of());
        }
        int minBlockX = (cx - radius) << 4;
        int maxBlockX = ((cx + radius + 1) << 4) - 1;
        int minBlockZ = (cz - radius) << 4;
        int maxBlockZ = ((cz + radius + 1) << 4) - 1;

        List<RegionPayloads.RegionSnapshot> out = new ArrayList<>();
        for (RegionRecord region : data.regionsOf(empire.id())) {
            if (region.blockCount() == 0) {
                continue;
            }
            if (region.maxX() < minBlockX || region.minX() > maxBlockX
                    || region.maxZ() < minBlockZ || region.minZ() > maxBlockZ) {
                continue;
            }
            out.add(new RegionPayloads.RegionSnapshot(
                    region.id(),
                    region.type(),
                    region.valid(),
                    region.minX(),
                    region.minZ(),
                    region.renderBlocks(),
                    region.renderSeparators()));
        }
        return new RegionPayloads.Sync(out);
    }

    /** Whether {@code player} has a client listening for sync payloads: game tests' mock players have no network channel. */
    private static boolean canReceive(ServerPlayer player) {
        return player.connection != null && player.connection.getConnection().channel() != null
                && player.connection.hasChannel(RegionPayloads.Sync.TYPE);
    }

    private RegionNetwork() {
    }
}
