package dev.brights0ng.meteodyne.sable.client;

import org.joml.Vector3d;

import dev.brights0ng.meteodyne.ships.Ships;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;

/** A ship's pose as the client draws it this frame (the heat view's regions aboard). Client only, Sable only. */
public final class ShipRenderPoses {

    private ShipRenderPoses() {
    }

    /** The pose of the ship whose plot holds chunk ({@code cx}, {@code cz}), or null if none does. */
    public static Ships.Pose of(Level level, int cx, int cz) {
        SubLevel s = Sable.HELPER.getContaining(level, cx, cz);
        if (s == null) {
            return null;
        }
        Pose3dc pose = s instanceof ClientSubLevel c ? c.renderPose() : s.logicalPose();
        return new Ships.Pose() {
            @Override
            public Vector3d transformPosition(Vector3d point) {
                return pose.transformPosition(point);
            }

            @Override
            public Vector3d transformNormal(Vector3d direction) {
                return pose.transformNormal(direction);
            }
        };
    }
}
