// Written by Gemini.
package dev.brights0ng.empire.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * Network payloads for syncing an empire's nearby 3D regions and active Administrative Ledger selection.
 */
public final class RegionPayloads {

    /** Maximum chunk radius a client may request regions for. */
    public static final int MAX_RADIUS = 16;
    /** Safety bound on decoded block count per block array in a region snapshot. */
    private static final int MAX_SNAPSHOT_BLOCKS = 131_072;

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

    /** Client notifies the server that the player left-clicked while holding the ledger to clear region selection. */
    public record Deselect() implements CustomPacketPayload {

        public static final Deselect INSTANCE = new Deselect();
        public static final Type<Deselect> TYPE = new Type<>(Empire.id("region_deselect"));
        public static final StreamCodec<ByteBuf, Deselect> STREAM_CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Compact client-side view of one region: id, type, validity, mesh origin, primary blocks ({@code HOME} indoor
     * rooms, {@code FARM} shed rooms when valid, or all non-separator blocks when invalid), secondary blocks
     * ({@code FARM} field rooms when valid), and room separator blocks.
     */
    public record RegionSnapshot(UUID id, RegionType type, boolean valid, int originX, int originZ,
                                 long[] blocks, long[] secondaryBlocks, long[] separators) {

        public static final StreamCodec<ByteBuf, RegionSnapshot> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public RegionSnapshot decode(ByteBuf buf) {
                UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
                RegionType type = REGION_TYPE_CODEC.decode(buf);
                boolean valid = buf.readBoolean();
                int originX = VarInt.read(buf);
                int originZ = VarInt.read(buf);
                long[] blocks = LONG_ARRAY_CODEC.decode(buf);
                long[] secondaryBlocks = LONG_ARRAY_CODEC.decode(buf);
                long[] separators = LONG_ARRAY_CODEC.decode(buf);
                return new RegionSnapshot(id, type, valid, originX, originZ, blocks, secondaryBlocks, separators);
            }

            @Override
            public void encode(ByteBuf buf, RegionSnapshot value) {
                UUIDUtil.STREAM_CODEC.encode(buf, value.id());
                REGION_TYPE_CODEC.encode(buf, value.type());
                buf.writeBoolean(value.valid());
                VarInt.write(buf, value.originX());
                VarInt.write(buf, value.originZ());
                LONG_ARRAY_CODEC.encode(buf, value.blocks());
                LONG_ARRAY_CODEC.encode(buf, value.secondaryBlocks());
                LONG_ARRAY_CODEC.encode(buf, value.separators());
            }
        };
    }

    /** Server sends the player's empire's nearby regions and currently selected region id (if any) for rendering. */
    public record Sync(List<RegionSnapshot> regions, Optional<UUID> selectedRegionId) implements CustomPacketPayload {

        public static final Type<Sync> TYPE = new Type<>(Empire.id("region_render_sync"));

        public static final StreamCodec<ByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                RegionSnapshot.STREAM_CODEC.apply(ByteBufCodecs.collection(ArrayList::new)), Sync::regions,
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Sync::selectedRegionId,
                Sync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private RegionPayloads() {
    }
}
