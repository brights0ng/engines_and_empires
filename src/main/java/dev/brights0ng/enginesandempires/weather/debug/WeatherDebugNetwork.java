package dev.brights0ng.enginesandempires.weather.debug;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.debug.client.WeatherMapClient;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The weather debug map over the network. Only operators (permission level 2, Bright 2026-10-05) are answered, at most
 * twice a second, about somewhere within {@link #MAX_DISTANCE} blocks of them. The map always shows the Overworld,
 * where the pack's weather lives. Each map is built a few rows at a time within {@link #BUDGET_NANOS} per server tick
 * (shared by everyone's maps) and sent when complete, so a map never stalls the server; a newer request from the same
 * player replaces an unfinished one.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class WeatherDebugNetwork {

    private static final String VERSION = "1";
    public static final int PERMISSION = 2;
    private static final int ANSWER_GAP = 10;
    private static final int MAX_DISTANCE = 200_000;
    /** Time per server tick for building maps, nanoseconds. */
    static final long BUDGET_NANOS = 4_000_000;

    private static final Map<UUID, Long> LAST_ANSWERED = new WeakHashMap<>();
    private static final Map<UUID, PendingMap> JOBS = new java.util.LinkedHashMap<>();

    private record PendingMap(ServerPlayer player, WeatherMapBuilder.Job job) {
    }

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION).optional();
        registrar.playToClient(WeatherMapPayloads.Open.TYPE, WeatherMapPayloads.Open.STREAM_CODEC,
                (payload, context) -> WeatherMapClient.open());
        registrar.playToClient(WeatherMapPayloads.Answer.TYPE, WeatherMapPayloads.Answer.STREAM_CODEC,
                (payload, context) -> WeatherMapClient.accept(payload));
        registrar.playToServer(WeatherMapPayloads.Request.TYPE, WeatherMapPayloads.Request.STREAM_CODEC,
                WeatherDebugNetwork::onRequest);
    }

    /** Opens the map on {@code player}'s screen. */
    public static void open(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new WeatherMapPayloads.Open());
    }

    private static void onRequest(WeatherMapPayloads.Request request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(PERMISSION)) {
            return;
        }
        ServerLevel overworld = player.server.overworld();
        long now = overworld.getGameTime();
        Long last = LAST_ANSWERED.get(player.getUUID());
        if (last != null && now - last < ANSWER_GAP && now >= last) {
            return;
        }
        if (Math.abs(request.x() - player.getX()) > MAX_DISTANCE || Math.abs(request.z() - player.getZ()) > MAX_DISTANCE) {
            return;
        }
        LAST_ANSWERED.put(player.getUUID(), now);
        JOBS.put(player.getUUID(), new PendingMap(player,
                new WeatherMapBuilder.Job(overworld, request.x(), request.z(), request.scale(), request.size())));
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + BUDGET_NANOS;
        // A snapshot: biome lookups while building can run queued tasks, including a new map request (re-entrancy).
        for (Map.Entry<UUID, PendingMap> e : new java.util.ArrayList<>(JOBS.entrySet())) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            PendingMap p = e.getValue();
            if (p.player().isRemoved() || p.player().hasDisconnected()) {
                JOBS.remove(e.getKey(), p);
                continue;
            }
            if (p.job().work(deadline)) {
                if (JOBS.remove(e.getKey(), p)) {
                    PacketDistributor.sendToPlayer(p.player(), p.job().finish());
                }
            }
        }
    }

    private WeatherDebugNetwork() {
    }
}
