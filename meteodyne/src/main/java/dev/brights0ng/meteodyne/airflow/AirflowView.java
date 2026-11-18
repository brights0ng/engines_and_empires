// Written by Claude (GreedyFluids M7d, the debug view; Bright, 2026-11-15); edited by Claude (M8b b4: secondaries;
// M8b5a: the air's temperature as it is, not its offset).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.brights0ng.greedyfluids.region.AirFrame;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.MeteodyneNetwork;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Airflow's debug view ({@code /meteodyne airflow show}), the legacy heat view's look ({@code HeatView}) with the tiles'
 * boundaries added (Bright, 2026-11-15). While a player has it on, the server sends them once a second every tile
 * within {@link #RANGE} blocks:
 * <ul>
 *   <li>its bounds, id and state, so the client draws its outline (tiles still reading their sections or building
 *       included);</li>
 *   <li>from its latest frame, each air cell at least {@link #MIN_OFFSET} warmer or colder than its background: its
 *       temperature as it is (M8b5a; Bright, 2026-11-17: the view's colours match what a thermometer there reads) and
 *       the air's velocity (its box's, m/s).</li>
 * </ul>
 * A tile's cells go in parts of at most {@link #PART_CELLS} (a whole tile wouldn't fit one message), then an
 * end-of-frame message lets the client drop tiles that are gone. Operators only; debugging, so no limit on how much
 * is drawn. Server thread.
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class AirflowView {

    private static final String VERSION = "2";
    /** Ticks between updates. */
    static final int INTERVAL = 20;
    /** Tiles further than this from the player (from their bounds) are not sent, blocks. */
    static final int RANGE = 128;
    /** Offsets nearer 0 than this, C, are not sent. */
    static final float MIN_OFFSET = 0.05f;
    /** Bytes per cell: dx, dy, dz, temperature ×20 (2), velocity ×10 per axis (3). */
    public static final int CELL_BYTES = 8;
    /** Cells per message: 256 KiB of cells, well under the 1 MiB payload limit. */
    static final int PART_CELLS = 32_768;

    /** A tile's state as drawn: the colour of its outline. */
    public static final int READING = 0, BUILDING = 1, RUNNING = 2, FAILED = 3;
    /** Added to a state when no player keeps the tile alive (it is in its grace period). */
    public static final int EMPTY = 8;
    /** Added to a state when a player's tile keeps it running and no player is in it (M8b b4: a secondary). */
    public static final int SECONDARY = 16;

    private static final Set<UUID> WATCHING = ConcurrentHashMap.newKeySet();
    private static int frames;

    /**
     * One part of one tile in update {@code frame}: the tile's origin and size, its id and state (plus {@link #EMPTY}),
     * and {@code count} cells. {@code kind} {@link #END} closes the frame (tiles not in it are dropped); {@link #OFF}
     * turns the view off.
     */
    public record Payload(int kind, int frame, int id, int state, int ox, int oy, int oz, int sx, int sy, int sz,
                          int count, byte[] cells) implements CustomPacketPayload {

        public static final int TILE = 0, END = 1, OFF = 2;

        public static final Type<Payload> TYPE = new Type<>(Meteodyne.id("airflow_view"));

        public static final StreamCodec<ByteBuf, Payload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeByte(p.kind);
                    buf.writeInt(p.frame);
                    buf.writeInt(p.id);
                    buf.writeByte(p.state);
                    buf.writeInt(p.ox);
                    buf.writeInt(p.oy);
                    buf.writeInt(p.oz);
                    buf.writeShort(p.sx);
                    buf.writeShort(p.sy);
                    buf.writeShort(p.sz);
                    buf.writeInt(p.count);
                    buf.writeBytes(p.cells, 0, p.count * CELL_BYTES);
                },
                buf -> {
                    int kind = buf.readByte(), frame = buf.readInt(), id = buf.readInt(), state = buf.readByte();
                    int ox = buf.readInt(), oy = buf.readInt(), oz = buf.readInt();
                    int sx = buf.readShort(), sy = buf.readShort(), sz = buf.readShort();
                    int count = Math.max(0, Math.min(PART_CELLS, buf.readInt()));
                    byte[] cells = new byte[count * CELL_BYTES];
                    buf.readBytes(cells);
                    return new Payload(kind, frame, id, state, ox, oy, oz, sx, sy, sz, count, cells);
                });

        static final Payload OFF_PAYLOAD = new Payload(OFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, new byte[0]);

        /** Cell {@code i}'s position in the tile. */
        public int dx(int i) {
            return cells[i * CELL_BYTES] & 0xFF;
        }

        public int dy(int i) {
            return cells[i * CELL_BYTES + 1] & 0xFF;
        }

        public int dz(int i) {
            return cells[i * CELL_BYTES + 2] & 0xFF;
        }

        /** Cell {@code i}'s air temperature, C. */
        public float celsius(int i) {
            int b = i * CELL_BYTES;
            return (short) (((cells[b + 3] & 0xFF) << 8) | (cells[b + 4] & 0xFF)) / 20f;
        }

        /** Cell {@code i}'s air velocity along {@code axis} (0 x, 1 y, 2 z), m/s. */
        public float velocity(int i, int axis) {
            return cells[i * CELL_BYTES + 5 + axis] / 10f;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).optional().playToClient(Payload.TYPE, Payload.STREAM_CODEC,
                (payload, context) -> dev.brights0ng.meteodyne.airflow.client.AirflowViewClient.accept(payload));
    }

    /** Turns the view on or off for {@code player}; returns whether it is now on. */
    public static boolean toggle(ServerPlayer player) {
        if (WATCHING.remove(player.getUUID())) {
            MeteodyneNetwork.sendTo(player, Payload.OFF_PAYLOAD);
            return false;
        }
        WATCHING.add(player.getUUID());
        AirflowRegions regions = AirflowRegions.get();
        if (regions != null) {
            send(player, regions, build(regions));
        }
        return true;
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        AirflowRegions regions = AirflowRegions.get();
        if (WATCHING.isEmpty() || regions == null || event.getLevel() != regions.level()
                || regions.level().getGameTime() % INTERVAL != 0) {
            return;
        }
        List<Payload> tiles = null;
        for (ServerPlayer player : regions.level().players()) {
            if (WATCHING.contains(player.getUUID())) {
                if (tiles == null) {
                    tiles = build(regions); // once per update, whoever watches
                }
                send(player, regions, tiles);
            }
        }
    }

    private static void send(ServerPlayer player, AirflowRegions regions, List<Payload> tiles) {
        int frame = tiles.isEmpty() ? ++frames : tiles.get(0).frame();
        if (player.level() == regions.level()) {
            Vec3 at = player.position();
            for (Payload p : tiles) {
                double dx = Math.max(0, Math.max(p.ox() - at.x, at.x - (p.ox() + p.sx())));
                double dy = Math.max(0, Math.max(p.oy() - at.y, at.y - (p.oy() + p.sy())));
                double dz = Math.max(0, Math.max(p.oz() - at.z, at.z - (p.oz() + p.sz())));
                if (dx * dx + dy * dy + dz * dz <= (double) RANGE * RANGE) {
                    MeteodyneNetwork.sendTo(player, p);
                }
            }
        }
        MeteodyneNetwork.sendTo(player, new Payload(Payload.END, frame, 0, 0, 0, 0, 0, 0, 0, 0, 0, new byte[0]));
    }

    /** Every tile's parts for one update (server thread). */
    static List<Payload> build(AirflowRegions regions) {
        int frame = ++frames;
        List<Payload> out = new ArrayList<>();
        for (AirflowRegions.Region r : regions.regions()) {
            AirflowRuntime.Slot slot = r.slot();
            int state = slot == null ? READING : switch (slot.status()) {
                case PENDING -> BUILDING;
                case RUNNING -> RUNNING;
                case FAILED -> FAILED;
                case RETIRED -> READING; // being replaced
            };
            if (r.secondary()) {
                state |= SECONDARY;
            } else if (!r.occupied()) {
                state |= EMPTY;
            }
            AirflowRuntime.Frame f = slot == null ? null : slot.frame();
            parts(out, frame, r, state, f == null ? null : f.frame());
        }
        return out;
    }

    private static void parts(List<Payload> out, int frame, AirflowRegions.Region r, int state, AirFrame air) {
        int nx = AirflowRegions.WX, ny = AirflowRegions.WY, nz = AirflowRegions.WZ;
        byte[] cells = new byte[PART_CELLS * CELL_BYTES];
        int count = 0, sent = 0;
        double[] vel = new double[3];
        if (air != null) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    for (int x = 0; x < nx; x++) {
                        if (!air.velocity(x + 0.5, y + 0.5, z + 0.5, vel)) {
                            continue; // solid
                        }
                        double t = air.temperature(x + 0.5, y + 0.5, z + 0.5);
                        double offset = t - air.background().cell((y * nz + z) * nx + x);
                        if (!(Math.abs(offset) >= MIN_OFFSET)) {
                            continue;
                        }
                        int b = count * CELL_BYTES;
                        short s = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(t * 20)));
                        cells[b] = (byte) x;
                        cells[b + 1] = (byte) y;
                        cells[b + 2] = (byte) z;
                        cells[b + 3] = (byte) (s >> 8);
                        cells[b + 4] = (byte) s;
                        for (int a = 0; a < 3; a++) {
                            cells[b + 5 + a] = (byte) Math.max(-127, Math.min(127, Math.round(vel[a] * 10)));
                        }
                        if (++count == PART_CELLS) {
                            out.add(new Payload(Payload.TILE, frame, r.id(), state, r.x0(), r.y0(), r.z0(), nx, ny, nz,
                                    count, cells));
                            cells = new byte[PART_CELLS * CELL_BYTES];
                            count = 0;
                            sent++;
                        }
                    }
                }
            }
        }
        if (count > 0 || sent == 0) { // a tile with nothing to show still sends its outline
            out.add(new Payload(Payload.TILE, frame, r.id(), state, r.x0(), r.y0(), r.z0(), nx, ny, nz, count,
                    java.util.Arrays.copyOf(cells, count * CELL_BYTES)));
        }
    }

    /** A player who leaves stops watching (they toggle it again to watch). */
    @SubscribeEvent
    static void onLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        WATCHING.remove(event.getEntity().getUUID());
    }

    private AirflowView() {
    }
}
