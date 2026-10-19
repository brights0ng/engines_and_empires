// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import dev.brights0ng.meteodyne.Meteodyne;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Changes only the sensor represented by the sender's currently open, server-validated menu. */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class InstrumentNetwork {
    public record Configure(int menuId, int zero, int full) implements CustomPacketPayload {
        public static final Type<Configure> TYPE = new Type<>(Meteodyne.id("configure_sensor"));
        public static final StreamCodec<ByteBuf, Configure> STREAM_CODEC = StreamCodec.of(
                (buffer, packet) -> { buffer.writeInt(packet.menuId); buffer.writeInt(packet.zero); buffer.writeInt(packet.full); },
                buffer -> new Configure(buffer.readInt(), buffer.readInt(), buffer.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(Configure.TYPE, Configure.STREAM_CODEC, (packet, context) -> {
            if (context.player().containerMenu instanceof SensorMenu menu && menu.containerId == packet.menuId) {
                menu.configure(context.player(), packet.zero, packet.full);
            }
        });
    }

    private InstrumentNetwork() { }
}
