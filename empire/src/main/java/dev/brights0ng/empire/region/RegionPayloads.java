// Written by Gemini.
package dev.brights0ng.empire.region;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.rules.RegionType;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Network payloads for syncing an empire's nearby 3D regions to clients for in-world rendering.
 */
public final class RegionPayloads {

    /** Maximum chunk radius a client may request regions for. */
    public static final int MAX_RADIUS = 16;
    /** Safety bound on decoded block count per region snapshot. */
    private static final int MAX_SNAPSHOT_BLOCKS = 16_384;

    private static final StreamCodec<ByteBuf, RegionType> REGION_TYPE_CODEC = ByteBufCodecs.STRING_UTF8.map(
            id -> {
                RegionType t = RegionType.byId(id);
                return t != null ? t : RegionType.HOME;
            },
            RegionType::id);

    private static final StreamCodec<ByteBuf, long[]> LONG_ARRAY_CODEC = new StreamCodec<>() {
        @Override
        public long[] decode(ByteBuf buf) {
            int len = Math.max(0, Math.min(MAX_SNAPSHOT_BLOCKS, VarInt.read(buf)));
            long[] out = new long[len];
            for (int i = 0; i < len; i++) {
                out[i] = buf.readLong();
            }
            return out;
        }

        @Override
        public void encode(ByteBuf buf, long[] value) {
            int len = Math.min(value.length, MAX_SNAPSHOT_BLOCKS);
            VarInt.write(buf, len);
            for (int i = 0; i < len; i++) {
                buf.writeLong(value[i]);
            }
        }
    };

    /** Client asks for its empire's regions intersecting chunks within {@code radius} of {@code (x, z)}. */
    public record Request(int x, int z, int radius) implements CustomPacketPayload {

        public static final Type<Request> TYPE = new Type<>(Empire.id("region_render_request"));

        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Request::x,
                ByteBufCodecs.VAR_INT, Request::z,
                ByteBufCodecs.VAR_INT, Request::radius,
                Request::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Compact client-side view of one region: id, type, validity, mesh origin and packed block coordinates. */
    public record RegionSnapshot(UUID id, RegionType type, boolean valid, int originX, int originZ, long[] blocks) {

        public static final StreamCodec<ByteBuf, RegionSnapshot> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, RegionSnapshot::id,
                REGION_TYPE_CODEC, RegionSnapshot::type,
                ByteBufCodecs.BOOL, RegionSnapshot::valid,
                ByteBufCodecs.VAR_INT, RegionSnapshot::originX,
                ByteBufCodecs.VAR_INT, RegionSnapshot::originZ,
                LONG_ARRAY_CODEC, RegionSnapshot::blocks,
                RegionSnapshot::new);
    }

    /** Server sends the player's empire's nearby regions for rendering. */
    public record Sync(List<RegionSnapshot> regions) implements CustomPacketPayload {

        public static final Type<Sync> TYPE = new Type<>(Empire.id("region_render_sync"));

        public static final StreamCodec<ByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                RegionSnapshot.STREAM_CODEC.apply(ByteBufCodecs.collection(ArrayList::new)), Sync::regions,
                Sync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private RegionPayloads() {
    }
}
