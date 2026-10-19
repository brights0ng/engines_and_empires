// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Minecraft serialization stays separate from the pure measurement and freshness rules. */
public final class InstrumentReadingCodecs {
    public static final Codec<InstrumentReading> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("dimension").forGetter(InstrumentReading::dimension),
            Codec.LONG.fieldOf("time").forGetter(InstrumentReading::time),
            Codec.FLOAT.fieldOf("value").forGetter(InstrumentReading::value)).apply(instance, InstrumentReading::new));
    public static final StreamCodec<ByteBuf, InstrumentReading> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, InstrumentReading::dimension,
            ByteBufCodecs.VAR_LONG, InstrumentReading::time,
            ByteBufCodecs.FLOAT, InstrumentReading::value, InstrumentReading::new);

    private InstrumentReadingCodecs() { }
}
