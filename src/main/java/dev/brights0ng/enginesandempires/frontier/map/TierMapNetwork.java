package dev.brights0ng.enginesandempires.frontier.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.incursion.Incursion;
import dev.brights0ng.enginesandempires.frontier.incursion.Incursions;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevels;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The maps' tier overlay over the network: a map on the client asks for the tiers around what it shows
 * ({@link TierMapPayloads.Request}), and the server answers with them and the incursions under way
 * ({@link TierMapPayloads.Answer}). Only the Overworld has tiers.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class TierMapNetwork {

    private static final String VERSION = "1";
    /** A player is answered at most this often, in ticks, however many maps they look at. */
    private static final int ANSWER_GAP = 5;
    /** How far from a player, in chunks, a map may ask about (a logger's view can be panned a long way). */
    private static final int MAX_DISTANCE = 512;

    private static final Map<UUID, Long> LAST_ANSWERED = new WeakHashMap<>();

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(TierMapPayloads.Request.TYPE, TierMapPayloads.Request.STREAM_CODEC, TierMapNetwork::onRequest);
        registrar.playToClient(TierMapPayloads.Answer.TYPE, TierMapPayloads.Answer.STREAM_CODEC,
                (answer, context) -> TierMapCache.accept(answer));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ANSWERED.remove(event.getEntity().getUUID());
    }

    private static void onRequest(TierMapPayloads.Request request, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            answer(player, request);
        }
    }

    /** Answers a map's request, if the player is in the Overworld, not asking too often, and asking about somewhere sane. */
    public static void answer(ServerPlayer player, TierMapPayloads.Request request) {
        ServerLevel level = player.serverLevel();
        FrontierLevel frontier = FrontierLevels.of(level);
        if (frontier == null) {
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
        PacketDistributor.sendToPlayer(player, build(frontier, request.x(), request.z(), request.radius()));
    }

    /** The answer for the columns within {@code radius} (capped) of chunk ({@code cx}, {@code cz}). */
    public static TierMapPayloads.Answer build(FrontierLevel frontier, int cx, int cz, int radius) {
        int r = Math.max(0, Math.min(TierMapPayloads.MAX_RADIUS, radius));
        int size = 2 * r + 1;
        byte[] tiers = new byte[size * size];
        TierMemory memory = TierMemory.of(frontier.level());
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                tiers[dz * size + dx] = (byte) memory.groundTier(frontier, cx - r + dx, cz - r + dz).ordinal();
            }
        }
        List<TierMapPayloads.Marker> markers = new ArrayList<>();
        for (Incursion incursion : Incursions.of(frontier.level()).active()) {
            markers.add(new TierMapPayloads.Marker(incursion.centre().getX(), incursion.centre().getZ(), incursion.kind().ordinal()));
        }
        return new TierMapPayloads.Answer(cx - r, cz - r, size, TierGrid.pack(tiers), markers);
    }

    private TierMapNetwork() {
    }
}
