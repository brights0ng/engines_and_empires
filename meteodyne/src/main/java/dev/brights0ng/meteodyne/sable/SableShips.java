// Extended by ChatGPT: forward exact sensor-block exclusions to the shared ship cover reader.
package dev.brights0ng.meteodyne.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import dev.brights0ng.meteodyne.sable.client.ShipRenderPoses;
import dev.brights0ng.meteodyne.ships.Ships;
import dev.brights0ng.paradynelib.sable.ships.ShipCover;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * {@link Ships} answered by Sable, through the library's {@link ShipCover}. Registered by {@link MeteodyneSable} only
 * when Sable is loaded.
 */
final class SableShips implements Ships {

    static final SableShips INSTANCE = new SableShips();

    private SableShips() {
    }

    @Override
    public boolean inPlot(Level level, ChunkPos cp) {
        return ShipCover.inPlot(level, cp);
    }

    @Override
    public boolean inPlot(Level level, BlockPos pos) {
        return ShipCover.inPlot(level, pos);
    }

    @Override
    public Vec3 worldCentre(Level level, BlockPos pos) {
        return ShipCover.worldCentre(level, pos);
    }

    @Override
    public boolean covered(Level level, double x, double y, double z) {
        return ShipCover.covered(level, x, y, z);
    }

    @Override
    public Vec3 plotPoint(Level level, double x, double y, double z) {
        return ShipCover.plotPoint(level, x, y, z);
    }

    @Override
    public BlockPos plotAt(Level level, double x, double y, double z) {
        return ShipCover.plotAt(level, x, y, z);
    }

    @Override
    public boolean near(Level level, double x, double y, double z, double radius) {
        return ShipCover.near(level, x, y, z, radius);
    }

    @Override
    public int rayObstruction(Level level, double x, double y, double z, double dx, double dy, double dz, double from,
                              double reach, ToIntFunction<BlockState> classify, BlockPos ignored) {
        return ShipCover.rayObstruction(level, x, y, z, dx, dy, dz, from, reach, classify, ignored);
    }

    @Override
    public int rayObstruction(Level level, double x, double y, double z, double dx, double dy, double dz, double from,
                              double reach, ToIntFunction<BlockState> classify) {
        return ShipCover.rayObstruction(level, x, y, z, dx, dy, dz, from, reach, classify);
    }

    @Override
    public Cover area(Level level, double minX, double minZ, double maxX, double maxZ) {
        return new Area(level, ShipCover.area(level, minX, minZ, maxX, maxZ));
    }

    @Override
    public List<Vec3> strikePointsIn(Level level, double minX, double minY, double minZ, double maxX, double maxY,
                                     double maxZ, double x, double z) {
        return strikePoints(level, ShipCover.shipsIn(level, minX, minY, minZ, maxX, maxY, maxZ), x, z);
    }

    @Override
    public Vec3 inShipFrame(Entity entity) {
        SubLevel ship = Sable.HELPER.getTrackingSubLevel(entity);
        return ship == null ? null : ship.logicalPose().transformPositionInverse(entity.position());
    }

    @Override
    public Pose renderPose(Level level, int cx, int cz) {
        return ShipRenderPoses.of(level, cx, cz);
    }

    /** Where a strike from (x, z) lands on each ship in turn, skipping the ships it would miss. */
    private static List<Vec3> strikePoints(Level level, List<SubLevel> ships, double x, double z) {
        List<Vec3> out = new ArrayList<>(ships.size());
        for (SubLevel ship : ships) {
            Vec3 p = ShipCover.strikePoint(level, ship, x, z);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** {@link ShipCover.Area} as a {@link Cover}. */
    private record Area(Level level, ShipCover.Area area) implements Cover {

        @Override
        public boolean isEmpty() {
            return area.isEmpty();
        }

        @Override
        public double top(int x, int z) {
            return area.top(x, z);
        }

        @Override
        public double topAbove(double x, double z, double above) {
            return area.topAbove(x, z, above);
        }

        @Override
        public List<Vec3> strikePoints(double x, double z) {
            return SableShips.strikePoints(level, area.ships(), x, z);
        }
    }
}
