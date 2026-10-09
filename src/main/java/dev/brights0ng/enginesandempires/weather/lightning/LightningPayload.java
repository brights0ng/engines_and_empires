package dev.brights0ng.enginesandempires.weather.lightning;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A flash, from the server to the players within range (weather phase 6b): where it is in the cloud, the radius of the
 * tower it lights, how strong its storm is, what it did, and where it struck (NaN when it struck nothing). Every
 * player gets the same flash at the same moment; 6c draws the glow and plays the thunder from it.
 */
public record LightningPayload(double x, double y, double z, float radius, float strength, LightningModel.Kind kind,
                               double targetX, double targetY, double targetZ) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<LightningPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "lightning"));

    public static final StreamCodec<ByteBuf, LightningPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeDouble(p.x);
                buf.writeFloat((float) p.y);
                buf.writeDouble(p.z);
                buf.writeFloat(p.radius);
                buf.writeFloat(p.strength);
                buf.writeByte(p.kind.ordinal());
                buf.writeDouble(p.targetX);
                buf.writeFloat((float) p.targetY);
                buf.writeDouble(p.targetZ);
            },
            buf -> new LightningPayload(buf.readDouble(), buf.readFloat(), buf.readDouble(), buf.readFloat(),
                    buf.readFloat(), LightningModel.Kind.values()[buf.readByte()], buf.readDouble(), buf.readFloat(),
                    buf.readDouble()));

    public boolean struck() {
        return !Double.isNaN(targetX);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
