// Written by Claude; edited by Claude (SNAPSHOT_VERSION constant, M6f).
package dev.brights0ng.meteodyne.heat.scan;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** A read-only snapshot: absent chunks stay absent, with explicit sentinel palette entries. */
public final class ScanSnapshot {
    public static Path write(ServerLevel level, BlockPos centre, int rxz, int ry, String label, Path dir,
                             @Nullable ServerPlayer player) {
        if (rxz < 8 || rxz > 128 || ry < 8 || ry > 64 || !label.matches("[A-Za-z0-9_.+-]+")) {
            throw new IllegalArgumentException("Scan radii or label are invalid (use a word label)");
        }
        int nx = 2 * rxz, ny = 2 * ry, nz = nx;
        int x0 = centre.getX() - rxz, y0 = centre.getY() - ry, z0 = centre.getZ() - rxz;
        Map<BlockState, Integer> ids = new IdentityHashMap<>();
        List<ScanFormat.PaletteEntry> palette = new ArrayList<>();
        palette.add(new ScanFormat.PaletteEntry("#unloaded", ScanFormat.UNKNOWN, (byte) 0));
        palette.add(new ScanFormat.PaletteEntry("#outside", ScanFormat.UNKNOWN, (byte) 0));
        short[] cells = new short[nx * ny * nz];
        int[] motion = new int[nx * nz], surface = new int[nx * nz];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < nz; z++) {
            for (int x = 0; x < nx; x++) {
                int wx = x0 + x, wz = z0 + z;
                var chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
                int column = z * nx + x;
                motion[column] = chunk == null ? Integer.MIN_VALUE
                        : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, wx & 15, wz & 15) + 1;
                surface[column] = chunk == null ? Integer.MIN_VALUE
                        : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15) + 1;
                for (int y = 0; y < ny; y++) {
                    int wy = y0 + y, id;
                    if (level.isOutsideBuildHeight(wy)) id = 1;
                    else if (chunk == null) id = 0;
                    else {
                        BlockState state = chunk.getBlockState(pos.set(wx, wy, wz));
                        Integer known = ids.get(state);
                        if (known == null) {
                            id = palette.size();
                            if (id >= 65535) throw new IllegalStateException("Snapshot palette exceeds version 1 limit");
                            ids.put(state, id);
                            palette.add(new ScanFormat.PaletteEntry(ScanFormat.stateString(state),
                                    ScanFormat.classify(state), ScanFormat.flags(state)));
                        } else id = known;
                    }
                    cells[(y * nz + z) * nx + x] = (short) id;
                }
            }
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("snap-" + ScanFormat.timestamp() + "-" + label + ".gfsnap");
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))) {
                out.writeInt(ScanFormat.SNAPSHOT_MAGIC);
                out.writeInt(ScanFormat.SNAPSHOT_VERSION);
                out.writeUTF(label);
                out.writeUTF(level.dimension().location().toString());
                out.writeUTF(player == null || level.getChunkSource().getChunkNow(
                        player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4) == null
                        ? "#none" : level.getBiome(player.blockPosition()).unwrapKey()
                        .map(key -> key.location().toString()).orElse("#none"));
                out.writeLong(level.getGameTime());
                out.writeLong(level.getDayTime());
                out.writeBoolean(level.isRaining());
                out.writeBoolean(level.isThundering());
                out.writeDouble(player == null ? centre.getX() : player.getX());
                out.writeDouble(player == null ? centre.getY() : player.getY());
                out.writeDouble(player == null ? centre.getZ() : player.getZ());
                out.writeFloat(player == null ? 0 : player.getYRot());
                out.writeInt(x0); out.writeInt(y0); out.writeInt(z0);
                out.writeInt(nx); out.writeInt(ny); out.writeInt(nz);
                out.writeInt(palette.size());
                for (var entry : palette) {
                    out.writeUTF(entry.state()); out.writeByte(entry.kind()); out.writeByte(entry.flags());
                }
                for (short cell : cells) out.writeShort(cell);
                for (int height : motion) out.writeInt(height);
                for (int height : surface) out.writeInt(height);
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ScanSnapshot() { }
}
