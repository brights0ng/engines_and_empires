// Written by Gemini.
package dev.brights0ng.empire.map;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.empire.Empire;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The messages behind the maps' empire claim overlay (Phase 6). */
public final class ClaimMapPayloads {

    /** The furthest out, in chunks, a map may ask for: enough for the display's widest zoom, turned. */
    public static final int MAX_RADIUS = 96;

    /** A map asks for the claims of the chunk columns within {@code radius} of chunk ({@code x}, {@code z}). */
    public record Request(int x, int z, int radius) implements CustomPacketPayload {

        public static final Type<Request> TYPE = new Type<>(
                Empire.id("claim_map_request"));

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

    /** A main bonfire location for the maps: block coordinate (x, z) and whether it belongs to the player's empire. */
    public record BonfireMarker(int x, int z, boolean ourEmpire) {

        public static final StreamCodec<ByteBuf, BonfireMarker> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BonfireMarker::x,
                ByteBufCodecs.VAR_INT, BonfireMarker::z,
                ByteBufCodecs.BOOL, BonfireMarker::ourEmpire,
                BonfireMarker::new);
    }

    /**
     * The answer: a {@code size} x {@code size} square of claim states from chunk ({@code x}, {@code z}) at its north-west corner,
     * packed by {@link dev.brights0ng.empire.rules.ClaimGrid}, and the main bonfires in the region.
     */
    public record Answer(int x, int z, int size, byte[] packed, List<BonfireMarker> bonfires) implements CustomPacketPayload {

        public static final Type<Answer> TYPE = new Type<>(
                Empire.id("claim_map_answer"));

        public static final StreamCodec<ByteBuf, Answer> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Answer::x,
                ByteBufCodecs.VAR_INT, Answer::z,
                ByteBufCodecs.VAR_INT, Answer::size,
                ByteBufCodecs.BYTE_ARRAY, Answer::packed,
                BonfireMarker.STREAM_CODEC.apply(ByteBufCodecs.collection(ArrayList::new)), Answer::bonfires,
                Answer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private ClaimMapPayloads() {
    }
}
