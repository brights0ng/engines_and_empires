// Written by Claude.
package dev.brights0ng.meteodyne.heat.scan;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import dev.brights0ng.meteodyne.heat.world.HeatTables;
import dev.brights0ng.paradynelib.heat.air.AirGrid;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Version 1 wire constants, state rules and small readers for scanner consumers and game tests. */
public final class ScanFormat {
    public static final int SNAPSHOT_MAGIC = 0x4746534E, RECORDING_MAGIC = 0x47465243, VERSION = 1;
    public static final byte AIR = 0, LAVA = 1, WATER = 2, OPENING = 3, POROUS = 4, PARTIAL = 5,
            SOLID = 6, UNKNOWN = 7;
    public static final byte END = 0, STATE = 1, DIM = 2, POS = 3, BLOCK = 4, ACTION = 5,
            SECTION = 6, LOAD = 7, MARK = 8;
    public static final int BLOCK_RADIUS = 48, SECTION_RADIUS = 3, TRAJECTORY_INTERVAL = 10;
    private static final Map<BlockState, Byte> CLASSES = new IdentityHashMap<>();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static byte classify(BlockState state) {
        Byte cached = CLASSES.get(state);
        if (cached != null) return cached;
        var cell = HeatTables.cell(state);
        byte kind;
        if (cell.kind() == AirGrid.AIR) kind = AIR;
        else if (state.getFluidState().is(FluidTags.LAVA)) kind = LAVA;
        else if (!state.getFluidState().isEmpty()) kind = WATER;
        else if (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock) kind = OPENING;
        else if (state.is(BlockTags.LEAVES)) kind = POROUS;
        else if (!state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                && cell.openFaces() != 0) kind = PARTIAL;
        else kind = SOLID;
        CLASSES.put(state, kind);
        return kind;
    }

    public static byte flags(BlockState state) {
        return (byte) ((HeatTables.power(state) != null ? 1 : 0)
                | (HeatTables.airSourceCandidate(state.getBlock()) ? 2 : 0));
    }

    public static String stateString(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    static String timestamp() {
        return LocalDateTime.now().format(STAMP);
    }

    public record PaletteEntry(String state, byte kind, byte flags) { }
    public record Snapshot(String label, String dimension, String biome, long gameTime, long dayTime,
                           boolean raining, boolean thundering, double px, double py, double pz, float yaw,
                           int x0, int y0, int z0, int nx, int ny, int nz, List<PaletteEntry> palette,
                           short[] cells, int[] motionBlocking, int[] worldSurface) {
        public PaletteEntry cell(BlockPos pos) {
            int index = ((pos.getY() - y0) * nz + pos.getZ() - z0) * nx + pos.getX() - x0;
            return palette.get(Short.toUnsignedInt(cells[index]));
        }
    }

    public interface Entry { }
    public record End(long tick, int droppedOffThread, int sectionsPending) implements Entry { }
    public record State(int id, String state, byte kind, byte flags) implements Entry { }
    public record Dimension(long tick, String dimension) implements Entry { }
    public record Position(long tick, float x, float y, float z, byte flags) implements Entry { }
    public record BlockChange(long tick, int x, int y, int z, int beforeId, int afterId) implements Entry { }
    public record Action(long tick, int x, int y, int z, byte kind) implements Entry { }
    public record Section(long tick, int sx, int sy, int sz, int[] counts, int sourceCount,
                          int uniformId) implements Entry { }
    public record Load(long tick, float msptAvg, byte weather) implements Entry { }
    public record Mark(long tick, String text) implements Entry { }
    public record Recording(long startGameTime, long startEpochMillis, int blockRadius, int sectionRadius,
                            int trajectoryInterval, List<Entry> entries) { }

    private static DataInputStream open(Path file, int magic) throws IOException {
        DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)));
        try {
            if (in.readInt() != magic || in.readInt() != VERSION) throw new IOException("Unknown scan format");
            return in;
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    public static Snapshot readSnapshot(Path file) throws IOException {
        try (var in = open(file, SNAPSHOT_MAGIC)) {
            String label = in.readUTF(), dimension = in.readUTF(), biome = in.readUTF();
            long time = in.readLong(), day = in.readLong();
            boolean rain = in.readBoolean(), thunder = in.readBoolean();
            double px = in.readDouble(), py = in.readDouble(), pz = in.readDouble();
            float yaw = in.readFloat();
            int x0 = in.readInt(), y0 = in.readInt(), z0 = in.readInt();
            int nx = in.readInt(), ny = in.readInt(), nz = in.readInt(), size = in.readInt();
            if (nx < 1 || nx > 256 || ny < 1 || ny > 128 || nz < 1 || nz > 256
                    || size < 2 || size >= 65536) throw new IOException("Invalid snapshot dimensions or palette");
            List<PaletteEntry> palette = new ArrayList<>(size);
            for (int i = 0; i < size; i++) palette.add(new PaletteEntry(in.readUTF(), in.readByte(), in.readByte()));
            short[] cells = new short[nx * ny * nz];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = in.readShort();
                if (Short.toUnsignedInt(cells[i]) >= size) throw new IOException("Invalid palette index");
            }
            int[] motion = new int[nx * nz], surface = new int[nx * nz];
            for (int i = 0; i < motion.length; i++) motion[i] = in.readInt();
            for (int i = 0; i < surface.length; i++) surface[i] = in.readInt();
            return new Snapshot(label, dimension, biome, time, day, rain, thunder, px, py, pz, yaw,
                    x0, y0, z0, nx, ny, nz, List.copyOf(palette), cells, motion, surface);
        }
    }

    public static Recording readRecording(Path file) throws IOException {
        try (var in = open(file, RECORDING_MAGIC)) {
            long start = in.readLong(), epoch = in.readLong();
            int blockRadius = in.readInt(), sectionRadius = in.readInt(), interval = in.readInt();
            List<Entry> entries = new ArrayList<>();
            boolean ended = false;
            while (!ended) {
                int tag = in.readUnsignedByte(); // EOF without END is a truncated recording.
                Entry entry = switch (tag) {
                    case END -> new End(in.readLong(), in.readInt(), in.readInt());
                    case STATE -> new State(in.readInt(), in.readUTF(), in.readByte(), in.readByte());
                    case DIM -> new Dimension(in.readLong(), in.readUTF());
                    case POS -> new Position(in.readLong(), in.readFloat(), in.readFloat(), in.readFloat(), in.readByte());
                    case BLOCK -> new BlockChange(in.readLong(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
                    case ACTION -> new Action(in.readLong(), in.readInt(), in.readInt(), in.readInt(), in.readByte());
                    case SECTION -> {
                        long tick = in.readLong();
                        int sx = in.readInt(), sy = in.readInt(), sz = in.readInt();
                        int[] counts = new int[8];
                        for (int i = 0; i < 8; i++) counts[i] = in.readUnsignedShort();
                        yield new Section(tick, sx, sy, sz, counts, in.readUnsignedShort(), in.readInt());
                    }
                    case LOAD -> new Load(in.readLong(), in.readFloat(), in.readByte());
                    case MARK -> new Mark(in.readLong(), in.readUTF());
                    default -> throw new IOException("Unknown recording tag " + tag);
                };
                entries.add(entry);
                ended = entry instanceof End;
            }
            if (in.read() != -1) throw new IOException("Trailing recording data");
            return new Recording(start, epoch, blockRadius, sectionRadius, interval, List.copyOf(entries));
        }
    }

    private ScanFormat() { }
}
