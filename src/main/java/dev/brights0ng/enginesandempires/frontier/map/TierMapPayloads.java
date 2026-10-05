package dev.brights0ng.enginesandempires.frontier.map;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The messages behind the maps' tier overlay (Frontier phase 7). */
public final class TierMapPayloads {

    /** The furthest out, in chunks, a map may ask for: enough for the display's widest zoom, turned. */
    public static final int MAX_RADIUS = 96;

    /** A map asks for the tiers of the chunk columns within {@code radius} of chunk ({@code x}, {@code z}). */
    public record Request(int x, int z, int radius) implements CustomPacketPayload {

        public static final Type<Request> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "tier_map_request"));

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

    /** An incursion under way, for the maps: where it is centred, and what kind (the {@code Incursion.Kind} ordinal). */
    public record Marker(int x, int z, int kind) {

        public static final StreamCodec<ByteBuf, Marker> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Marker::x,
                ByteBufCodecs.VAR_INT, Marker::z,
                ByteBufCodecs.VAR_INT, Marker::kind,
                Marker::new);
    }

    /**
     * The answer: a {@code size} x {@code size} square of tiers from chunk ({@code x}, {@code z}) at its north-west corner,
     * packed by {@link TierGrid}, and the incursions under way in the Overworld.
     */
    public record Answer(int x, int z, int size, byte[] packed, List<Marker> markers) implements CustomPacketPayload {

        public static final Type<Answer> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "tier_map_answer"));

        public static final StreamCodec<ByteBuf, Answer> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Answer::x,
                ByteBufCodecs.VAR_INT, Answer::z,
                ByteBufCodecs.VAR_INT, Answer::size,
                ByteBufCodecs.BYTE_ARRAY, Answer::packed,
                Marker.STREAM_CODEC.apply(ByteBufCodecs.collection(ArrayList::new)), Answer::markers,
                Answer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private TierMapPayloads() {
    }
}
