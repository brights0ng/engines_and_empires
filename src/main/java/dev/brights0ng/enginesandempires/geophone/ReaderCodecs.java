package dev.brights0ng.enginesandempires.geophone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * How a {@link ReaderReading} is saved on a wind-up reader item or in a logbook, and sent to players so that the held reader
 * can draw its needle and the logbook can list its readings. One is for saving with the world, the other for the network.
 *
 * <p>A reading saved before it recorded which deposit it was of, when it was taken, or how confident it was, loads with 0 for
 * those (meaning "not known"; for confidence, 0 is {@link ReaderAccuracy.Confidence#UNKNOWN}), and works as before. One saved
 * before it recorded its ore loads with none.
 */
public final class ReaderCodecs {

    /** For saving. */
    public static final Codec<ReaderReading> READING = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("dimension").forGetter(ReaderReading::dimension),
            Codec.INT.fieldOf("x").forGetter(ReaderReading::x),
            Codec.INT.fieldOf("y").forGetter(ReaderReading::y),
            Codec.INT.fieldOf("z").forGetter(ReaderReading::z),
            Codec.BOOL.fieldOf("has_height").forGetter(ReaderReading::hasHeight),
            Codec.LONG.optionalFieldOf("deposit", 0L).forGetter(ReaderReading::deposit),
            Codec.LONG.optionalFieldOf("taken_at", 0L).forGetter(ReaderReading::takenAt),
            Codec.INT.optionalFieldOf("confidence", 0).forGetter(reading -> reading.confidence().ordinal()),
            Codec.STRING.optionalFieldOf("ore", "").forGetter(ReaderReading::ore)
    ).apply(instance, (dimension, x, y, z, hasHeight, deposit, takenAt, confidence, ore) ->
            new ReaderReading(dimension, x, y, z, hasHeight, deposit, takenAt, confidenceFromOrdinal(confidence), ore)));

    /**
     * For the network. Written out by hand, because a reading has more parts than the game's ready-made combiner takes.
     */
    public static final StreamCodec<ByteBuf, ReaderReading> READING_STREAM = new StreamCodec<>() {
        @Override
        public ReaderReading decode(ByteBuf buffer) {
            String dimension = ByteBufCodecs.STRING_UTF8.decode(buffer);
            int x = ByteBufCodecs.INT.decode(buffer);
            int y = ByteBufCodecs.INT.decode(buffer);
            int z = ByteBufCodecs.INT.decode(buffer);
            boolean hasHeight = ByteBufCodecs.BOOL.decode(buffer);
            long deposit = ByteBufCodecs.VAR_LONG.decode(buffer);
            long takenAt = ByteBufCodecs.VAR_LONG.decode(buffer);
            int confidence = ByteBufCodecs.VAR_INT.decode(buffer);
            String ore = ByteBufCodecs.STRING_UTF8.decode(buffer);
            return new ReaderReading(dimension, x, y, z, hasHeight, deposit, takenAt, confidenceFromOrdinal(confidence), ore);
        }

        @Override
        public void encode(ByteBuf buffer, ReaderReading reading) {
            ByteBufCodecs.STRING_UTF8.encode(buffer, reading.dimension());
            ByteBufCodecs.INT.encode(buffer, reading.x());
            ByteBufCodecs.INT.encode(buffer, reading.y());
            ByteBufCodecs.INT.encode(buffer, reading.z());
            ByteBufCodecs.BOOL.encode(buffer, reading.hasHeight());
            ByteBufCodecs.VAR_LONG.encode(buffer, reading.deposit());
            ByteBufCodecs.VAR_LONG.encode(buffer, reading.takenAt());
            ByteBufCodecs.VAR_INT.encode(buffer, reading.confidence().ordinal());
            ByteBufCodecs.STRING_UTF8.encode(buffer, reading.ore());
        }
    };

    /** An ordinal outside the enum's range (from a save made with a different version) falls back to the nearest real one. */
    private static ReaderAccuracy.Confidence confidenceFromOrdinal(int ordinal) {
        ReaderAccuracy.Confidence[] values = ReaderAccuracy.Confidence.values();
        return values[Math.max(0, Math.min(ordinal, values.length - 1))];
    }

    private ReaderCodecs() {
    }
}
