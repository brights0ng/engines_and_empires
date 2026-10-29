// Written by Claude.
package dev.brights0ng.meteodyne.heat.airgrid;

import java.util.Arrays;

import dev.brights0ng.meteodyne.heat.world.HeatTables;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * A box of the world as {@link AirCellType} ordinals (y-major, then z, then x), read straight from chunk sections on the
 * server thread, with one byte per simulated block that is 1 where sky light is 15, and the blocks that may heat the
 * air ({@link HeatTables#airSourceCandidate}).
 */
public final class AirSnapshot {
    public static final long MAX_BLOCKS = 4_000_000;

    /** Why a snapshot could not be taken. */
    public static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    /** The grid, heat-source blocks (box-local indices), whether the box was cut to the build height, and the time. */
    public record Result(CellCountStudy.Grid grid, int[] sources, boolean clampedToBuildHeight, double ms) {
    }

    /** Reads the box {@code min..max} (inclusive), cut to the level's build height. */
    public static Result take(ServerLevel level, BlockPos min, BlockPos max) throws Failure {
        long start = System.nanoTime();
        int y0 = Math.max(min.getY(), level.getMinBuildHeight());
        int y1 = Math.min(max.getY(), level.getMaxBuildHeight() - 1);
        if (y0 > y1) {
            throw new Failure("The box lies outside the build height.");
        }
        int x0 = min.getX(), z0 = min.getZ(), x1 = max.getX(), z1 = max.getZ();
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        long blocks = (long) sx * sy * sz;
        if (blocks > MAX_BLOCKS) {
            throw new Failure(String.format(java.util.Locale.ROOT, "The box is %dx%dx%d = %,d blocks; the cap is %,d.",
                    sx, sy, sz, blocks, MAX_BLOCKS));
        }
        int cx0 = x0 >> 4, cx1 = x1 >> 4, cz0 = z0 >> 4, cz1 = z1 >> 4;
        LevelChunk[] chunks = new LevelChunk[(cx1 - cx0 + 1) * (cz1 - cz0 + 1)];
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    throw new Failure("Chunk [" + cx + ", " + cz + "] (blocks " + (cx << 4) + ".." + ((cx << 4) + 15) + ", "
                            + (cz << 4) + ".." + ((cz << 4) + 15) + ") is not loaded.");
                }
                chunks[(cz - cz0) * (cx1 - cx0 + 1) + cx - cx0] = chunk;
            }
        }

        int[] table = AirCellClassifier.table().types();
        byte[] types = new byte[(int) blocks];
        byte[] sky = new byte[(int) blocks];
        byte[] sourceById = new byte[table.length]; // 0 unknown, 1 a source, 2 not
        int[] sources = new int[64];
        int sourceCount = 0;
        LayerLightEventListener skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        byte air = (byte) AirCellClassifier.type(Blocks.AIR.defaultBlockState()).ordinal();
        boolean airSimulated = AirCellType.of(air).simulated();

        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                LevelChunk chunk = chunks[(cz - cz0) * (cx1 - cx0 + 1) + cx - cx0];
                int bx0 = Math.max(x0, cx << 4), bx1 = Math.min(x1, (cx << 4) + 15);
                int bz0 = Math.max(z0, cz << 4), bz1 = Math.min(z1, (cz << 4) + 15);
                for (int sy0 = y0 >> 4; sy0 <= y1 >> 4; sy0++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy0));
                    DataLayer light = skyLight.getDataLayerData(SectionPos.of(cx, sy0, cz));
                    int by0 = Math.max(y0, sy0 << 4), by1 = Math.min(y1, (sy0 << 4) + 15);
                    boolean empty = section.hasOnlyAir();
                    for (int y = by0; y <= by1; y++) {
                        for (int z = bz0; z <= bz1; z++) {
                            int row = ((y - y0) * sz + (z - z0)) * sx - x0;
                            for (int x = bx0; x <= bx1; x++) {
                                int i = row + x;
                                byte t;
                                if (empty) {
                                    t = air;
                                } else {
                                    BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                    int id = Block.BLOCK_STATE_REGISTRY.getId(state);
                                    t = (byte) (id >= 0 && id < table.length ? table[id] : AirCellClassifier.type(state).ordinal());
                                    if (id >= 0 && id < table.length && !state.isAir()) {
                                        if (sourceById[id] == 0) {
                                            sourceById[id] = (byte) (HeatTables.airSourceCandidate(state.getBlock()) ? 1 : 2);
                                        }
                                        if (sourceById[id] == 1) {
                                            if (sourceCount == sources.length) {
                                                sources = Arrays.copyOf(sources, sourceCount * 2);
                                            }
                                            sources[sourceCount++] = i;
                                        }
                                    }
                                }
                                types[i] = t;
                                if (empty ? airSimulated : AirCellType.of(t).simulated()) {
                                    int value = light != null ? light.get(x & 15, y & 15, z & 15)
                                            : skyLight.getLightValue(pos.set(x, y, z));
                                    sky[i] = (byte) (value >= 15 ? 1 : 0);
                                }
                            }
                        }
                    }
                }
            }
        }
        CellCountStudy.Grid grid = new CellCountStudy.Grid(x0, y0, z0, sx, sy, sz, types, sky);
        return new Result(grid, Arrays.copyOf(sources, sourceCount), y0 != min.getY() || y1 != max.getY(),
                (System.nanoTime() - start) / 1e6);
    }

    private AirSnapshot() {
    }
}
