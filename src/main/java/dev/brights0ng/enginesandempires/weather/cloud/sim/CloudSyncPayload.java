package dev.brights0ng.enginesandempires.weather.cloud.sim;

import java.util.List;
import java.util.UUID;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Cloud changes for one player (phase 4a): clouds that are new to them or changed since they last heard
 * ({@link SimCloud#version}), and clouds they should forget (gone, or out of their range). Sent only when something
 * changed; the client moves and ages the clouds itself in between. The format is {@link CloudWire}'s.
 *
 * @param reset   forget every cloud first (joining, leaving the Overworld, {@code /eae weather clouds clear})
 * @param upserts new or changed clouds
 * @param removed ids to forget
 */
public record CloudSyncPayload(boolean reset, List<SimCloud> upserts, List<UUID> removed) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CloudSyncPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "cloud_sync"));

    /** At most this many clouds per packet; more go in further packets. */
    public static final int MAX_UPSERTS = 96;

    @SuppressWarnings("unchecked")
    public static final StreamCodec<ByteBuf, CloudSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> CloudWire.write(new ByteBufOutputStream(buf), p.reset, p.upserts, p.removed),
            buf -> {
                Object[] r = CloudWire.read(new ByteBufInputStream(buf));
                return new CloudSyncPayload((Boolean) r[0], (List<SimCloud>) r[1], (List<UUID>) r[2]);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
