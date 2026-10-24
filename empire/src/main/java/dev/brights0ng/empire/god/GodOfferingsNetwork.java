// Written by Claude.
package dev.brights0ng.empire.god;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.rules.OfferingKeys;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Sends each client what the gods could take ({@link OfferingKeys}) when it joins and after every {@code /reload}, when
 * the gods may have changed.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class GodOfferingsNetwork {

    private static final String VERSION = "1";

    public record Payload(List<String> items, List<String> tags, List<String> fluids) implements CustomPacketPayload {

        public static final Type<Payload> TYPE = new Type<>(Empire.id("god_offerings"));

        public static final StreamCodec<ByteBuf, Payload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Payload::items,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Payload::tags,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Payload::fluids,
                Payload::new);

        static Payload of(OfferingKeys keys) {
            return new Payload(sorted(keys.items()), sorted(keys.tags()), sorted(keys.fluids()));
        }

        OfferingKeys keys() {
            return new OfferingKeys(Set.copyOf(items), Set.copyOf(tags), Set.copyOf(fluids));
        }

        private static List<String> sorted(Set<String> ids) {
            List<String> list = new ArrayList<>(ids);
            list.sort(null);
            return list;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).playToClient(Payload.TYPE, Payload.STREAM_CODEC,
                (payload, context) -> GodOfferings.acceptOnClient(payload.keys()));
    }

    @SubscribeEvent
    static void onDatapackSync(OnDatapackSyncEvent event) {
        Payload payload = Payload.of(GodOfferings.fromGods());
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    private GodOfferingsNetwork() {
    }
}
