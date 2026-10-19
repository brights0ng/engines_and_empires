// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public final class WindReadingCodecs {
    public static final Codec<WindReading> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("dimension").forGetter(WindReading::dimension),
            Codec.LONG.fieldOf("time").forGetter(WindReading::time),
            Codec.FLOAT.fieldOf("x").forGetter(WindReading::x),
            Codec.FLOAT.fieldOf("z").forGetter(WindReading::z)).apply(i, WindReading::new));
    public static final StreamCodec<ByteBuf, WindReading> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, WindReading::dimension, ByteBufCodecs.VAR_LONG, WindReading::time,
            ByteBufCodecs.FLOAT, WindReading::x, ByteBufCodecs.FLOAT, WindReading::z, WindReading::new);
    private WindReadingCodecs() { }
}
