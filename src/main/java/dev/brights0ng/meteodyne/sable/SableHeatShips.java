package dev.brights0ng.meteodyne.sable;

import java.util.List;

import org.joml.Vector3d;

import dev.brights0ng.meteodyne.heat.world.HeatShips;
import dev.brights0ng.meteodyne.heat.world.HeatWorld;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.sable.ships.ShipCover;
import dev.brights0ng.paradynelib.wind.Shelter;
import dev.brights0ng.paradynelib.wind.ShelterProbe;
import dev.brights0ng.paradynelib.wind.WindBlend;
import dev.brights0ng.paradynelib.wind.WindColumn;
import dev.brights0ng.paradynelib.wind.WindConfig;
import dev.brights0ng.paradynelib.wind.WindParams;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@link HeatShips} answered by Sable: a ship is its sub-level. Registered by {@link MeteodyneSable} only when Sable is
 * loaded.
 */
final class SableHeatShips implements HeatShips {

    static final SableHeatShips INSTANCE = new SableHeatShips();

    private SableHeatShips() {
    }

    @Override
    public void addCentres(ServerLevel level, List<Vec3> out) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                if (!ship.isRemoved()) {
                    BoundingBox3dc b = ship.boundingBox();
                    out.add(new Vec3((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2, (b.minZ() + b.maxZ()) / 2));
                }
            }
        }
    }

    @Override
    public void addLoadedChunks(ServerLevel level, LongSet out) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                if (ship.isRemoved()) {
                    continue;
                }
                for (var holder : ship.getPlot().getLoadedChunks()) {
                    out.add(holder.getPos().toLong());
                }
            }
        }
    }

    @Override
    public Object containing(Level level, int cx, int cz) {
        if (!ShipCover.inPlot(level, new ChunkPos(cx, cz))) {
            return null;
        }
        SubLevel ship = Sable.HELPER.getContaining(level, cx, cz);
        return ship == null || ship.isRemoved() ? null : ship;
    }

    @Override
    public Object shipAt(Level level, BlockPos pos) {
        SubLevel ship = ShipCover.shipAt(level, pos);
        return ship == null || ship.isRemoved() ? null : ship;
    }

    @Override
    public int[] clipToPlot(int[] box, Object handle) {
        SubLevel ship = (SubLevel) handle;
        ChunkPos min = ship.getPlot().getChunkMin();
        ChunkPos max = ship.getPlot().getChunkMax();
        int[] b = box.clone();
        b[0] = Math.max(b[0], min.getMinBlockX());
        b[2] = Math.max(b[2], min.getMinBlockZ());
        b[3] = Math.min(b[3], max.getMaxBlockX());
        b[5] = Math.min(b[5], max.getMaxBlockZ());
        return b[3] - b[0] >= 2 && b[5] - b[2] >= 2 ? b : null;
    }

    @Override
    public AABB worldBounds(Object handle) {
        BoundingBox3dc b = ((SubLevel) handle).boundingBox();
        return new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    @Override
    public HeatWorld.ShipAir shipAir(ServerLevel level, Object handle, List<Vec3> people, double near) {
        SubLevel ship = (SubLevel) handle;
        WindParams wp = WindConfig.params();
        BoundingBox3ic pb = ship.getPlot().getBoundingBox();
        Pose3dc pose = ship.logicalPose();
        Vector3d plotCentre = pb.volume() > 0
                ? new Vector3d((pb.minX() + pb.maxX() + 1) / 2.0, (pb.minY() + pb.maxY() + 1) / 2.0,
                (pb.minZ() + pb.maxZ() + 1) / 2.0)
                : new Vector3d(ship.getPlot().getCenterBlock().getX(), ship.getPlot().getCenterBlock().getY(),
                ship.getPlot().getCenterBlock().getZ());
        Vector3d world = pose.transformPosition(plotCentre, new Vector3d());

        // The wind at its height, against its own motion
        double wx = 0;
        double wz = 0;
        WindColumn column = WindSources.column(level, world.x, world.z);
        if (column != null) {
            BlockPos here = BlockPos.containing(world.x, world.y, world.z);
            double ground = level.hasChunkAt(here)
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, here.getX(), here.getZ())
                    : level.getSeaLevel();
            double[] w = column.at(WindBlend.aloftShare(world.y, ground, wp.surfaceLayer(), wp.aloftHeight()));
            wx = w[0];
            wz = w[1];
        }
        Vector3d velocity = Sable.HELPER.getVelocity(level, plotCentre, new Vector3d());
        double rx = wx - velocity.x;
        double rz = wz - velocity.z;

        // World blocks around it as a whole (a hangar, a dock wall)
        BoundingBox3dc wb = ship.boundingBox();
        double[] coverage = new double[Shelter.FACES];
        ShelterProbe.measure(level, wb.minX(), wb.minY(), wb.minZ(), wb.maxX(), wb.maxY(), wb.maxZ(),
                wp.sideReach(), wp.roofReach(), coverage);
        boolean roofed = coverage[Shelter.TOP] >= 0.5;
        double speed = Math.hypot(rx, rz);
        if (speed > 1e-6) {
            double exposure = Shelter.exposure(coverage, rx / speed, rz / speed, wp.roofWeight());
            rx *= exposure;
            rz *= exposure;
        }
        Vector3d local = pose.transformNormalInverse(new Vector3d(rx, 0, rz), new Vector3d());

        boolean isNear = false;
        for (Vec3 a : people) {
            if (a.x >= wb.minX() - near && a.x <= wb.maxX() + near && a.y >= wb.minY() - near
                    && a.y <= wb.maxY() + near && a.z >= wb.minZ() - near && a.z <= wb.maxZ() + near) {
                isNear = true;
                break;
            }
        }
        return new HeatWorld.ShipAir(new Vec3(world.x, world.y, world.z), local.x, local.z, roofed, isNear);
    }
}
