package dev.brights0ng.enginesandempires.weather.climate;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Sends each player how much wind, humidity and sun move what their body feels (weather phase 8e-2) once a second, so
 * their client's copy of the Cold Sweat temperature agrees with the server's: the wind, the shelter and the sun live
 * on the server. Only when Cold Sweat is installed (nothing reads it otherwise).
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class ApparentSync {

    private static final String VERSION = "1";
    private static final int INTERVAL = 20;
    /** A client value older than this is dropped, ms. */
    private static final long STALE_MS = 6_000;

    /** The offset, C, on the player's felt temperature. */
    public record Payload(float offsetC) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Payload> TYPE = new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "apparent_offset"));

        public static final StreamCodec<ByteBuf, Payload> STREAM_CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeFloat(payload.offsetC),
                buf -> new Payload(buf.readFloat()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static volatile float clientOffset;
    private static volatile long clientTime;

    /** The server's last offset for this client's player, C; 0 if none arrived lately. */
    public static double clientOffsetC() {
        return System.currentTimeMillis() - clientTime > STALE_MS ? 0 : clientOffset;
    }

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).optional().playToClient(Payload.TYPE, Payload.STREAM_CODEC, (payload, context) -> {
            clientOffset = payload.offsetC();
            clientTime = System.currentTimeMillis();
        });
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % INTERVAL != 0
                || level.players().isEmpty() || !WeatherOwnership.owns(level)
                || !ModList.get().isLoaded("cold_sweat")) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, new Payload((float) offsetOn(level, player)));
        }
    }

    /** The offset on a player now, C (what the Cold Sweat modifier adds, before the unit change). */
    public static double offsetOn(ServerLevel level, net.minecraft.world.entity.player.Player player) {
        Vec3 at = PersonClimate.worldPosition(level, player);
        double air = Temperature.at(level, BlockPos.containing(at));
        if (!Double.isFinite(air)) {
            return 0;
        }
        return PersonClimate.offsetC(PersonClimate.sample(level, player), air);
    }

    private ApparentSync() {
    }
}
