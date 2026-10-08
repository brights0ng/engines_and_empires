package dev.brights0ng.enginesandempires.weather.debug;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The messages behind the weather debug map ({@code /eae weather map}, ops only). */
public final class WeatherMapPayloads {

    /** The most pixels a side of the map may have. */
    public static final int MAX_SIZE = 128;
    /** The zoom levels, blocks per pixel. */
    public static final int MIN_SCALE = 16;
    public static final int MAX_SCALE = 1024;

    /** The server tells a client to open the map (after {@code /eae weather map}). */
    public record Open() implements CustomPacketPayload {

        public static final Type<Open> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "weather_map_open"));
        public static final StreamCodec<ByteBuf, Open> STREAM_CODEC = StreamCodec.unit(new Open());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The map asks for a {@code size} x {@code size} square of {@code scale}-block pixels centred on (x, z). */
    public record Request(int x, int z, int scale, int size) implements CustomPacketPayload {

        public static final Type<Request> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "weather_map_request"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Request::x,
                ByteBufCodecs.VAR_INT, Request::z,
                ByteBufCodecs.VAR_INT, Request::scale,
                ByteBufCodecs.VAR_INT, Request::size,
                Request::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The answer. Arrays are row by row from the north-west corner, x (east) fastest.
     *
     * @param now      the temperature at sea level now, C (season, band, time of day, frozen clamp)
     * @param mean     the annual mean at sea level, C (with the band and the frozen clamp)
     * @param humidity 0-1
     * @param surface  the surface ordinal, plus 4 if the biome is always frozen
     * @param pressure hPa
     * @param anomaly  the air-mass anomaly (departure from normal), C
     * @param moisture precipitable water, mm
     * @param relative relative humidity at sea level, 0-1
     * @param rain     recent precipitation (orographic so far), mm
     * @param arrows   points per side of the surface wind arrow grid (over the same square)
     * @param windX    surface wind at each arrow point, m/s
     * @param windZ    surface wind at each arrow point, m/s
     * @param systems  the lows and highs in view
     * @param fronts   the fronts in view
     * @param jets     the storm tracks in view, each a polyline of x, z pairs
     * @param info     one line about the world's moment: season, time of day, air-mass mode
     */
    public record Answer(int x, int z, int scale, int size, float[] now, float[] mean, float[] humidity,
                         byte[] surface, float[] pressure, float[] anomaly, float[] moisture, float[] relative,
                         float[] rain, int arrows, float[] windX, float[] windZ,
                         List<Marker> systems, List<FrontLine> fronts, List<float[]> jets, String info)
            implements CustomPacketPayload {

        public static final Type<Answer> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "weather_map_answer"));
        public static final StreamCodec<FriendlyByteBuf, Answer> STREAM_CODEC = StreamCodec.of(
                (buf, a) -> {
                    buf.writeVarInt(a.x);
                    buf.writeVarInt(a.z);
                    buf.writeVarInt(a.scale);
                    buf.writeVarInt(a.size);
                    writeFloats(buf, a.now);
                    writeFloats(buf, a.mean);
                    writeFloats(buf, a.humidity);
                    buf.writeByteArray(a.surface);
                    writeFloats(buf, a.pressure);
                    writeFloats(buf, a.anomaly);
                    writeFloats(buf, a.moisture);
                    writeFloats(buf, a.relative);
                    writeFloats(buf, a.rain);
                    buf.writeVarInt(a.arrows);
                    writeFloats(buf, a.windX);
                    writeFloats(buf, a.windZ);
                    buf.writeVarInt(a.systems.size());
                    for (Marker m : a.systems) {
                        m.write(buf);
                    }
                    buf.writeVarInt(a.fronts.size());
                    for (FrontLine f : a.fronts) {
                        f.write(buf);
                    }
                    buf.writeVarInt(a.jets.size());
                    for (float[] j : a.jets) {
                        buf.writeVarInt(j.length);
                        writeFloats(buf, j);
                    }
                    buf.writeUtf(a.info);
                },
                buf -> {
                    int x = buf.readVarInt(), z = buf.readVarInt(), scale = buf.readVarInt();
                    int size = Math.max(1, Math.min(MAX_SIZE, buf.readVarInt()));
                    int n = size * size;
                    float[] now = readFloats(buf, n), mean = readFloats(buf, n), humidity = readFloats(buf, n);
                    byte[] surface = buf.readByteArray(n);
                    float[] pressure = readFloats(buf, n);
                    float[] anomaly = readFloats(buf, n);
                    float[] moisture = readFloats(buf, n);
                    float[] relative = readFloats(buf, n);
                    float[] rain = readFloats(buf, n);
                    int arrows = Math.max(0, Math.min(64, buf.readVarInt()));
                    float[] wx = readFloats(buf, arrows * arrows), wz = readFloats(buf, arrows * arrows);
                    int ns = Math.min(1024, buf.readVarInt());
                    List<Marker> systems = new ArrayList<>(ns);
                    for (int i = 0; i < ns; i++) {
                        systems.add(Marker.read(buf));
                    }
                    int nf = Math.min(4096, buf.readVarInt());
                    List<FrontLine> fronts = new ArrayList<>(nf);
                    for (int i = 0; i < nf; i++) {
                        fronts.add(FrontLine.read(buf));
                    }
                    int nj = Math.min(64, buf.readVarInt());
                    List<float[]> jets = new ArrayList<>(nj);
                    for (int i = 0; i < nj; i++) {
                        jets.add(readFloats(buf, Math.min(1024, buf.readVarInt())));
                    }
                    return new Answer(x, z, scale, size, now, mean, humidity, surface, pressure, anomaly, moisture,
                            relative, rain, arrows, wx, wz, systems, fronts, jets, buf.readUtf());
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * A low or high on the map.
     *
     * @param strength hPa below (lows) or above (highs) normal now
     * @param stage    the {@code WeatherSystem.Stage} ordinal
     * @param life     how far through its life, 0-1
     * @param lifetime its whole life, ticks
     */
    public record Marker(float x, float z, boolean high, float strength, float radius, int stage, boolean blocking,
                         int hemisphere, float life, long lifetime) {

        void write(FriendlyByteBuf buf) {
            buf.writeFloat(x);
            buf.writeFloat(z);
            buf.writeBoolean(high);
            buf.writeFloat(strength);
            buf.writeFloat(radius);
            buf.writeVarInt(stage);
            buf.writeBoolean(blocking);
            buf.writeByte(hemisphere);
            buf.writeFloat(life);
            buf.writeVarLong(lifetime);
        }

        static Marker read(FriendlyByteBuf buf) {
            return new Marker(buf.readFloat(), buf.readFloat(), buf.readBoolean(), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readBoolean(), buf.readByte(), buf.readFloat(), buf.readVarLong());
        }
    }

    /**
     * A front on the map: the {@code FrontGeometry.Type} ordinal, the low's turning (+1 or -1, for which side the
     * symbols go), strength 0-1, and its points as x, z pairs from the low outward.
     */
    public record FrontLine(int type, int hemisphere, float strength, float[] points) {

        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(type);
            buf.writeByte(hemisphere);
            buf.writeFloat(strength);
            buf.writeVarInt(points.length);
            writeFloats(buf, points);
        }

        static FrontLine read(FriendlyByteBuf buf) {
            int type = buf.readVarInt();
            int hem = buf.readByte();
            float strength = buf.readFloat();
            return new FrontLine(type, hem, strength, readFloats(buf, Math.min(256, buf.readVarInt())));
        }
    }

    private static void writeFloats(FriendlyByteBuf buf, float[] values) {
        for (float v : values) {
            buf.writeFloat(v);
        }
    }

    private static float[] readFloats(FriendlyByteBuf buf, int n) {
        float[] values = new float[n];
        for (int i = 0; i < n; i++) {
            values[i] = buf.readFloat();
        }
        return values;
    }

    private WeatherMapPayloads() {
    }
}
