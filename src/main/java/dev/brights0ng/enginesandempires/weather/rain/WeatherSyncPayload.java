package dev.brights0ng.enginesandempires.weather.rain;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * What a client needs to work out the weather around its player the way the server does, sent once a second
 * ({@link WeatherNetwork}): the wind and the air temperature live on the server.
 *
 * @param localized   whether the pack's rain model is on (the server config)
 * @param surfaceX    surface wind at the player, m/s
 * @param aloftX      aloft wind at the player, m/s
 * @param driftX      the wind rain drifts in at the player (surface and aloft, averaged over its fall), m/s
 * @param seaLevel    the level's sea level
 * @param coolingScale the height cooling's scale ({@code Temperature.heightCorrection})
 * @param maxCooling  its cap, C
 * @param originX     the temperature grid's first point, world x
 * @param originZ     world z
 * @param spacing     blocks between grid points
 * @param size        points along each side
 * @param temperatures the air temperature at sea level at each grid point (row by row along x), C
 */
public record WeatherSyncPayload(boolean localized, float surfaceX, float surfaceZ, float aloftX, float aloftZ,
                                 float driftX, float driftZ,
                                 int seaLevel, float coolingScale, float maxCooling, int originX, int originZ,
                                 int spacing, int size, float[] temperatures) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WeatherSyncPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "weather_sync"));

    public static final StreamCodec<ByteBuf, WeatherSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.localized);
                buf.writeFloat(p.surfaceX);
                buf.writeFloat(p.surfaceZ);
                buf.writeFloat(p.aloftX);
                buf.writeFloat(p.aloftZ);
                buf.writeFloat(p.driftX);
                buf.writeFloat(p.driftZ);
                buf.writeInt(p.seaLevel);
                buf.writeFloat(p.coolingScale);
                buf.writeFloat(p.maxCooling);
                buf.writeInt(p.originX);
                buf.writeInt(p.originZ);
                buf.writeInt(p.spacing);
                buf.writeByte(p.size);
                for (int i = 0; i < p.size * p.size; i++) {
                    buf.writeFloat(p.temperatures[i]);
                }
            },
            buf -> {
                boolean localized = buf.readBoolean();
                float sx = buf.readFloat(), sz = buf.readFloat(), ax = buf.readFloat(), az = buf.readFloat();
                float dx = buf.readFloat(), dz = buf.readFloat();
                int sea = buf.readInt();
                float scale = buf.readFloat(), max = buf.readFloat();
                int ox = buf.readInt(), oz = buf.readInt(), spacing = buf.readInt();
                int size = Math.max(0, Math.min(16, buf.readUnsignedByte()));
                float[] t = new float[size * size];
                for (int i = 0; i < t.length; i++) {
                    t[i] = buf.readFloat();
                }
                return new WeatherSyncPayload(localized, sx, sz, ax, az, dx, dz, sea, scale, max, ox, oz, spacing, size,
                        t);
            });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** As the client keeps it. */
    public ClientWeather.State toState() {
        return new ClientWeather.State(localized, surfaceX, surfaceZ, aloftX, aloftZ, driftX, driftZ, seaLevel,
                coolingScale, maxCooling, originX, originZ, spacing, size, temperatures);
    }
}
