package dev.brights0ng.enginesandempires.weather.wind;

import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Measures how much of a box's top and four sides is covered by world blocks: from a small grid of points on each face,
 * a probe walks straight out (up to {@code roofReach} above, {@code sideReach} to the sides) and either meets a solid
 * block or doesn't. A face's coverage is the share of its probes that met one.
 *
 * <p>The box is a physics object's world bounds. Its own blocks live in Sable's plot, not at those world positions, so
 * they never block its probes. Probes stop in unloaded chunks and count as open there; nothing is loaded.
 */
public final class ShelterProbe {

    /** The most probes along one edge of a face. */
    private static final int MAX_PER_EDGE = 5;

    /**
     * @param out receives coverage per face, in {@link Shelter}'s order
     */
    public static void measure(ServerLevel level, double minX, double minY, double minZ, double maxX, double maxY,
                               double maxZ, int sideReach, int roofReach, double[] out) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int nx = probesAlong(maxX - minX);
        int ny = probesAlong(maxY - minY);
        int nz = probesAlong(maxZ - minZ);

        int top = (int) Math.floor(maxY);
        int hits = 0;
        for (int i = 0; i < nx; i++) {
            for (int k = 0; k < nz; k++) {
                hits += blocked(level, pos, block(minX, maxX, i, nx), top, block(minZ, maxZ, k, nz), 0, 1, 0, roofReach);
            }
        }
        out[Shelter.TOP] = hits / (double) (nx * nz);

        int north = (int) Math.floor(minZ - 1e-3);
        int south = (int) Math.floor(maxZ + 1e-3);
        int northHits = 0;
        int southHits = 0;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                int x = block(minX, maxX, i, nx);
                int y = block(minY, maxY, j, ny);
                northHits += blocked(level, pos, x, y, north, 0, 0, -1, sideReach);
                southHits += blocked(level, pos, x, y, south, 0, 0, 1, sideReach);
            }
        }
        out[Shelter.NORTH] = northHits / (double) (nx * ny);
        out[Shelter.SOUTH] = southHits / (double) (nx * ny);

        int west = (int) Math.floor(minX - 1e-3);
        int east = (int) Math.floor(maxX + 1e-3);
        int westHits = 0;
        int eastHits = 0;
        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                int z = block(minZ, maxZ, k, nz);
                int y = block(minY, maxY, j, ny);
                westHits += blocked(level, pos, west, y, z, -1, 0, 0, sideReach);
                eastHits += blocked(level, pos, east, y, z, 1, 0, 0, sideReach);
            }
        }
        out[Shelter.WEST] = westHits / (double) (nz * ny);
        out[Shelter.EAST] = eastHits / (double) (nz * ny);
    }

    /** How many probes along an edge of this length: one per 4 blocks or so, at least 2, at most 5. */
    static int probesAlong(double length) {
        return Math.max(2, Math.min(MAX_PER_EDGE, (int) Math.ceil(length / 4.0) + 1));
    }

    /** The block a probe starts in, spread evenly along an edge (centres of n equal parts). */
    private static int block(double min, double max, int index, int count) {
        return (int) Math.floor(min + (max - min) * (index + 0.5) / count);
    }

    /** 1 if the probe from (x, y, z) stepping (dx, dy, dz) meets a solid block within {@code reach} steps. */
    private static int blocked(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z,
                               int dx, int dy, int dz, int reach) {
        for (int step = 0; step < reach; step++) {
            pos.set(x + dx * step, y + dy * step, z + dz * step);
            if (level.isOutsideBuildHeight(pos) || !level.isLoaded(pos)) {
                return 0;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && VoxelNeighborhoodState.isSolid(level, pos, state)) {
                return 1;
            }
        }
        return 0;
    }

    private ShelterProbe() {
    }
}
