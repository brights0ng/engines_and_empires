// Written by Claude.
package dev.brights0ng.empire.civilian;

import dev.brights0ng.empire.Empire;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Tells the players tracking a civilian which line it just said (a lang key); the client shows it as a bubble. */
@EventBusSubscriber(modid = Empire.MODID)
public final class CivilianNetwork {

    private static final String VERSION = "1";

    public record CivilianSpeechPayload(int entityId, String key) implements CustomPacketPayload {

        public static final Type<CivilianSpeechPayload> TYPE = new Type<>(Empire.id("civilian_speech"));

        public static final StreamCodec<ByteBuf, CivilianSpeechPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, CivilianSpeechPayload::entityId,
                ByteBufCodecs.STRING_UTF8, CivilianSpeechPayload::key,
                CivilianSpeechPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).playToClient(CivilianSpeechPayload.TYPE, CivilianSpeechPayload.STREAM_CODEC,
                (payload, context) -> CivilianSpeechStore.put(payload.entityId(), payload.key(),
                        context.player().level().getGameTime()));
    }

    private CivilianNetwork() {
    }
}
