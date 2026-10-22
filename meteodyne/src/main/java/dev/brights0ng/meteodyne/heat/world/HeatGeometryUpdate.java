// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.BitSet;
import dev.brights0ng.paradynelib.heat.air.AirGrid;
import dev.brights0ng.paradynelib.wind.WindParams;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Main-thread, resumable snapshot preparation. Only changed columns/lines are sampled after block edits.
 * Arrays are copied on the first actual write, so a worker's previous snapshot is never mutated. Dirty bits
 * can be requeued between slices; completion requires draining them, including lines dirtied by changed kinds.
 * The legacy thermal geometry is retained: physical revisions can change without changing its coarse arrays.
 */
final class HeatGeometryUpdate {
    private final HeatRegion region;
    private final WindParams wind;
    private final HeatRegion.Geometry original;
    final boolean full;
    private final BitSet cells = new BitSet(), westEast = new BitSet(), northSouth = new BitSet(), roofs = new BitSet();
    private byte[] kinds, open, cover;
    private float[] conductances, capacities;
    private boolean ownKinds, ownOpen, ownCover, ownConductances, ownCapacities;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final boolean[] line;
    long nanos, coverNanos, blockReads, cellColumns, xRows, zRows, roofColumns;

    HeatGeometryUpdate(HeatRegion region, WindParams wind, boolean full) {
        this.region = region;
        this.wind = wind;
        this.original = region.geometry;
        this.full = full || original == null;
        line = new boolean[Math.max(region.ny, Math.max(region.nx, region.nz) + 2 * wind.sideReach())];
        if (this.full) {
            int n = region.nx * region.ny * region.nz;
            kinds = new byte[n]; open = new byte[n]; cover = new byte[n];
            conductances = new float[n]; capacities = new float[n];
            ownKinds = ownOpen = ownCover = ownConductances = ownCapacities = true;
            cells.set(0, region.nx * region.nz);
            westEast.set(0, region.ny * region.nz);
            northSouth.set(0, region.nx * region.ny);
            roofs.set(0, region.nx * region.nz);
        } else {
            kinds = original.kinds(); open = original.openFaces(); cover = original.cover();
            conductances = original.conductances(); capacities = original.capacities();
        }
    }

    /** A column refresh also reconciles its heightmap classification. Neighbor columns cover shape dependencies. */
    void dirty(int wx, int wy, int wz, boolean physical) {
        int x = wx - region.box[0], y = wy - region.box[1], z = wz - region.box[2];
        markColumn(x, z, physical);
        if (!physical) { return; }
        markColumn(x - 1, z, true); markColumn(x + 1, z, true);
        markColumn(x, z - 1, true); markColumn(x, z + 1, true);
        for (int yy = Math.max(0, y - 1); yy <= Math.min(region.ny - 1, y + 1); yy++) {
            for (int zz = z - 1; zz <= z + 1; zz++) {
                if (zz >= 0 && zz < region.nz && x >= -wind.sideReach() - 1 && x <= region.nx + wind.sideReach()) {
                    westEast.set(zz * region.ny + yy);
                }
            }
            for (int xx = x - 1; xx <= x + 1; xx++) {
                if (xx >= 0 && xx < region.nx && z >= -wind.sideReach() - 1 && z <= region.nz + wind.sideReach()) {
                    northSouth.set(yy * region.nx + xx);
                }
            }
        }
    }

    private void markColumn(int x, int z, boolean shelter) {
        if (x >= 0 && x < region.nx && z >= 0 && z < region.nz) {
            cells.set(z * region.nx + x);
            if (shelter) { roofs.set(z * region.nx + x); }
        }
    }

    boolean pending() {
        return !cells.isEmpty() || !westEast.isEmpty() || !northSouth.isEmpty() || !roofs.isEmpty();
    }

    int advance(ServerLevel level, int allowance, long deadline) {
        int used = 0;
        while (used < allowance && System.nanoTime() < deadline) {
            boolean coverUnit = cells.isEmpty();
            long start = coverUnit ? System.nanoTime() : 0;
            int i;
            if ((i = cells.nextSetBit(0)) >= 0) {
                cells.clear(i); sampleCells(level, i % region.nx, i / region.nx); cellColumns++;
            } else if ((i = westEast.nextSetBit(0)) >= 0) {
                westEast.clear(i); sampleX(level, i % region.ny, i / region.ny); xRows++;
            } else if ((i = northSouth.nextSetBit(0)) >= 0) {
                northSouth.clear(i); sampleZ(level, i % region.nx, i / region.nx); zRows++;
            } else if ((i = roofs.nextSetBit(0)) >= 0) {
                roofs.clear(i); sampleRoof(level, i % region.nx, i / region.nx); roofColumns++;
            } else { break; }
            if (coverUnit) { coverNanos += System.nanoTime() - start; }
            used++;
        }
        return used;
    }

    HeatRegion.Geometry finish() {
        if (pending()) { throw new IllegalStateException("Incomplete thermal geometry snapshot"); }
        if (!full && !ownKinds && !ownOpen && !ownCover && !ownConductances && !ownCapacities) { return original; }
        return new HeatRegion.Geometry(kinds, conductances, capacities, open, cover);
    }

    private void sampleCells(ServerLevel level, int x, int z) {
        int wx = region.box[0] + x, wz = region.box[2] + z;
        LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        int top = chunk == null ? 0 : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, wx & 15, wz & 15);
        for (int y = 0; y < region.ny; y++) {
            int i = region.index(x, y, z);
            byte kind = AirGrid.BOUNDARY;
            float conductance = 0, capacity = 0;
            byte faces = 0;
            if (chunk != null && x > 0 && z > 0 && y > 0 && x < region.nx - 1 && z < region.nz - 1 && y < region.ny - 1) {
                blockReads++;
                var cell = HeatTables.cell(chunk.getBlockState(pos.set(wx, region.box[1] + y, wz)));
                kind = cell.kind();
                if (kind == AirGrid.AIR) {
                    kind = region.box[1] + y > top && !region.roofed ? AirGrid.EXPOSED : AirGrid.AIR;
                } else {
                    conductance = cell.conductance(); capacity = cell.capacity(); faces = (byte) cell.openFaces();
                }
            }
            if (kinds[i] != kind) {
                if (!ownKinds) { kinds = kinds.clone(); ownKinds = true; }
                kinds[i] = kind;
                westEast.set(z * region.ny + y); northSouth.set(y * region.nx + x); roofs.set(z * region.nx + x);
            }
            if (conductances[i] != conductance) {
                if (!ownConductances) { conductances = conductances.clone(); ownConductances = true; }
                conductances[i] = conductance;
            }
            if (capacities[i] != capacity) {
                if (!ownCapacities) { capacities = capacities.clone(); ownCapacities = true; }
                capacities[i] = capacity;
            }
            if (open[i] != faces) {
                if (!ownOpen) { open = open.clone(); ownOpen = true; }
                open[i] = faces;
            }
        }
    }

    private boolean blocker(ServerLevel level, int wx, int wy, int wz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        if (chunk == null) { return false; }
        blockReads++;
        BlockState state = chunk.getBlockState(pos.set(wx, wy, wz));
        if (state.isAir() || !state.getFluidState().isEmpty()) { return false; }
        var cell = HeatTables.cell(state);
        return cell.kind() == AirGrid.SOLID && cell.openFaces() != AirGrid.ALL_FACES;
    }

    private void sampleX(ServerLevel level, int y, int z) {
        int reach = wind.sideReach(), size = region.nx + 2 * reach;
        for (int x = 0; x < size; x++) {
            line[x] = blocker(level, region.box[0] - reach + x, region.box[1] + y, region.box[2] + z);
        }
        int last = Integer.MIN_VALUE / 2;
        for (int x = 0; x < size; x++) {
            if (line[x]) { last = x; }
            if (x >= reach && x < reach + region.nx) {
                cover(region.index(x - reach, y, z), HeatRegion.COVER_WEST,
                        !line[x] && x - last <= reach ? HeatRegion.COVER_WEST : 0);
            }
        }
        last = Integer.MAX_VALUE / 2;
        for (int x = size - 1; x >= 0; x--) {
            if (line[x]) { last = x; }
            if (x >= reach && x < reach + region.nx) {
                cover(region.index(x - reach, y, z), HeatRegion.COVER_EAST,
                        !line[x] && last - x <= reach ? HeatRegion.COVER_EAST : 0);
            }
        }
    }

    private void sampleZ(ServerLevel level, int x, int y) {
        int reach = wind.sideReach(), size = region.nz + 2 * reach;
        for (int z = 0; z < size; z++) {
            line[z] = blocker(level, region.box[0] + x, region.box[1] + y, region.box[2] - reach + z);
        }
        int last = Integer.MIN_VALUE / 2;
        for (int z = 0; z < size; z++) {
            if (line[z]) { last = z; }
            if (z >= reach && z < reach + region.nz) {
                cover(region.index(x, y, z - reach), HeatRegion.COVER_NORTH,
                        !line[z] && z - last <= reach ? HeatRegion.COVER_NORTH : 0);
            }
        }
        last = Integer.MAX_VALUE / 2;
        for (int z = size - 1; z >= 0; z--) {
            if (line[z]) { last = z; }
            if (z >= reach && z < reach + region.nz) {
                cover(region.index(x, y, z - reach), HeatRegion.COVER_SOUTH,
                        !line[z] && last - z <= reach ? HeatRegion.COVER_SOUTH : 0);
            }
        }
    }

    private void sampleRoof(ServerLevel level, int x, int z) {
        int wx = region.box[0] + x, wz = region.box[2] + z;
        LevelChunk chunk = level.getChunkSource().getChunkNow(wx >> 4, wz >> 4);
        int highest = chunk == null ? 0 : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, wx & 15, wz & 15);
        int last = Integer.MAX_VALUE / 2;
        for (int y = region.ny - 1; y >= 0; y--) {
            boolean solid = blocker(level, wx, region.box[1] + y, wz);
            if (solid) { last = y; }
            boolean sheltered = !solid && (last - y <= wind.roofReach()
                    || (last > region.ny && highest > region.box[4] && highest - (region.box[1] + y) <= wind.roofReach()));
            cover(region.index(x, y, z), HeatRegion.COVER_TOP, sheltered ? HeatRegion.COVER_TOP : 0);
        }
    }

    private void cover(int i, int mask, int bits) {
        byte value = (byte) ((kinds[i] == AirGrid.AIR || kinds[i] == AirGrid.EXPOSED) ? (cover[i] & ~mask) | bits : 0);
        if (cover[i] != value) {
            if (!ownCover) { cover = cover.clone(); ownCover = true; }
            cover[i] = value;
        }
    }
}
