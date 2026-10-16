package dev.brights0ng.enginesandempires.weather.heat.world;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.brights0ng.enginesandempires.weather.heat.air.AirGrid;
import dev.brights0ng.meteodyne.Meteodyne;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * The heat simulation's debug view (weather phase 8f-4, Bright 2026-10-15): while a player has it on
 * ({@code /eae heat show}), the server sends them, once a second, every region within {@link #RANGE} blocks: each
 * region's air cells (offset, and whether open to the sky) and its wind, one message per region, then an end-of-frame
 * message so the client drops regions that are gone. Their client draws it every frame ({@code HeatViewClient}) until
 * they turn it off. Operators only; debugging, so no limit on how much is drawn (Bright, 2026-10-15).
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class HeatView {

    private static final String VERSION = "1";
    /** Ticks between updates. */
    private static final int INTERVAL = 20;
    /** Regions further than this from the player (from their box) are not sent, blocks. */
    private static final int RANGE = 128;
    /** The most cells in one region's message (a whole region at the largest size). */
    private static final int MAX_CELLS = 96 * 96 * 96;
    /** Offsets nearer 0 than this, C, are not sent (nothing to see). */
    private static final float MIN_OFFSET = 0.05f;
    /** Bytes per cell: dx, dy, dz, wind share (0-255), offset (2). */
    static final int CELL_BYTES = 6;

    private static final Set<UUID> WATCHING = ConcurrentHashMap.newKeySet();
    private static int frames;

    /**
     * One region of one update: the cells (origin + dx, dy, dz; the share of the wind the cell feels, 0-255; offset x 20
     * as a short) and the region's wind, m/s. {@code count} {@link #OFF_COUNT} turns the view off; {@link #END_COUNT}
     * ends frame {@code frame} (regions not sent in it are dropped).
     */
    public record Payload(int frame, int ox, int oy, int oz, float windX, float windZ, int count, byte[] cells)
            implements CustomPacketPayload {

        public static final int OFF_COUNT = -1;
        public static final int END_COUNT = -2;

        public static final Type<Payload> TYPE = new Type<>(
                Meteodyne.id("heat_view"));

        public static final StreamCodec<ByteBuf, Payload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeInt(p.frame);
                    buf.writeInt(p.ox);
                    buf.writeInt(p.oy);
                    buf.writeInt(p.oz);
                    buf.writeFloat(p.windX);
                    buf.writeFloat(p.windZ);
                    buf.writeInt(p.count);
                    if (p.count > 0) {
                        buf.writeBytes(p.cells, 0, p.count * CELL_BYTES);
                    }
                },
                buf -> {
                    int frame = buf.readInt();
                    int ox = buf.readInt();
                    int oy = buf.readInt();
                    int oz = buf.readInt();
                    float wx = buf.readFloat();
                    float wz = buf.readFloat();
                    int count = Math.min(MAX_CELLS, buf.readInt());
                    byte[] cells = new byte[Math.max(0, count) * CELL_BYTES];
                    if (count > 0) {
                        buf.readBytes(cells);
                    }
                    return new Payload(frame, ox, oy, oz, wx, wz, count, cells);
                });

        public static final Payload OFF = new Payload(0, 0, 0, 0, 0, 0, OFF_COUNT, new byte[0]);

        static Payload end(int frame) {
            return new Payload(frame, 0, 0, 0, 0, 0, END_COUNT, new byte[0]);
        }

        /** Cell {@code i}'s offset, C. */
        public float offset(int i) {
            int b = i * CELL_BYTES;
            return (short) (((cells[b + 4] & 0xFF) << 8) | (cells[b + 5] & 0xFF)) / 20f;
        }

        /** The share of the region's wind cell {@code i} feels, 0-1. */
        public float windShare(int i) {
            return (cells[i * CELL_BYTES + 3] & 0xFF) / 255f;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).optional().playToClient(Payload.TYPE, Payload.STREAM_CODEC,
                (payload, context) -> dev.brights0ng.enginesandempires.weather.heat.world.client.HeatViewClient.accept(payload));
    }

    /** Turns the view on or off for {@code player}; returns whether it is now on. */
    public static boolean toggle(ServerPlayer player) {
        if (WATCHING.remove(player.getUUID())) {
            PacketDistributor.sendToPlayer(player, Payload.OFF);
            return false;
        }
        WATCHING.add(player.getUUID());
        send(player);
        return true;
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (WATCHING.isEmpty() || !(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD || level.getGameTime() % INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (WATCHING.contains(player.getUUID())) {
                send(player);
            }
        }
    }

    private static void send(ServerPlayer player) {
        int frame = ++frames;
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            PacketDistributor.sendToPlayer(player, Payload.end(frame));
            return;
        }
        Vec3 at = player.position();
        double range2 = (double) RANGE * RANGE;
        for (HeatRegion r : HeatWorld.of(level).regions()) {
            if (r.published() == null) {
                continue;
            }
            int[] b = r.box();
            double minX = b[0], minY = b[1], minZ = b[2], maxX = b[3] + 1, maxY = b[4] + 1, maxZ = b[5] + 1;
            if (r.ship() != null) {
                // A ship's region (8f-5): by where the ship is in the world
                var wb = r.ship().boundingBox();
                minX = wb.minX();
                minY = wb.minY();
                minZ = wb.minZ();
                maxX = wb.maxX();
                maxY = wb.maxY();
                maxZ = wb.maxZ();
            }
            double dx = Math.max(0, Math.max(minX - at.x, at.x - maxX));
            double dy = Math.max(0, Math.max(minY - at.y, at.y - maxY));
            double dz = Math.max(0, Math.max(minZ - at.z, at.z - maxZ));
            if (dx * dx + dy * dy + dz * dz <= range2) {
                PacketDistributor.sendToPlayer(player, build(frame, r));
            }
        }
        PacketDistributor.sendToPlayer(player, Payload.end(frame));
    }

    /** A region's air cells, as one message of frame {@code frame}. */
    static Payload build(int frame, HeatRegion region) {
        HeatRegion.Published p = region.published();
        byte[] cells = new byte[Math.min(MAX_CELLS, p.nx() * p.ny() * p.nz()) * CELL_BYTES];
        int count = 0;
        for (int z = 0; z < p.nz() && count < MAX_CELLS; z++) {
            for (int y = 0; y < p.ny() && count < MAX_CELLS; y++) {
                for (int x = 0; x < p.nx() && count < MAX_CELLS; x++) {
                    int i = p.index(x, y, z);
                    byte kind = p.kinds()[i];
                    if (kind != AirGrid.AIR && kind != AirGrid.EXPOSED) {
                        continue;
                    }
                    float v = p.offsets()[i];
                    if (Math.abs(v) < MIN_OFFSET) {
                        continue;
                    }
                    int b = count * CELL_BYTES;
                    short s = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v * 20)));
                    cells[b] = (byte) x;
                    cells[b + 1] = (byte) y;
                    cells[b + 2] = (byte) z;
                    cells[b + 3] = (byte) Math.round(Math.max(0, Math.min(1, p.wind()[i])) * 255);
                    cells[b + 4] = (byte) (s >> 8);
                    cells[b + 5] = (byte) s;
                    count++;
                }
            }
        }
        return new Payload(frame, p.minX(), p.minY(), p.minZ(), (float) region.windX, (float) region.windZ, count,
                cells);
    }

    private HeatView() {
    }
}
