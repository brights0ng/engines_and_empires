// Written by ChatGPT; reuses the shared mount geometry and Bright's proportional metal panels.
package dev.brights0ng.meteodyne.instrument;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shared geometry for the wind housing, rotor clearance, picking and renderer. Units are model pixels. */
public final class WindSensorShape {
    public static SensorShape.Box head(AttachFace face) {
        SensorShape.Box original = SensorShape.head(face);
        double bottom = face == AttachFace.FLOOR ? 3 : 4;
        return new SensorShape.Box(5, bottom, original.z0(), 11, bottom + 6, original.z1());
    }
    public static double rotorY(AttachFace face) { return face == AttachFace.CEILING ? 2 : 13; }
    public static List<SensorShape.Box> boxes(AttachFace face) {
        var boxes = new ArrayList<>(SensorShape.boxes(face));
        boxes.removeLast();
        boxes.add(head(face));
        boxes.add(new SensorShape.Box(7, Math.min(8, rotorY(face)), 7, 9, Math.max(8, rotorY(face)), 9));
        return List.copyOf(boxes);
    }
    public static VoxelShape shape(AttachFace face, Direction facing) {
        var boxes = new ArrayList<>(boxes(face));
        double y = rotorY(face);
        boxes.add(new SensorShape.Box(1.75, y - 1, 1.75, 14.25, y + 2, 14.25));
        return SensorShape.shape(boxes, facing);
    }
    /** Four open cup bowls and their cross arms, shared by the block renderer and inventory model. */
    public static List<SensorShape.Box> rotorBoxes(AttachFace face) {
        double y = rotorY(face);
        var boxes = new ArrayList<SensorShape.Box>();
        boxes.add(new SensorShape.Box(3, y, 7.75, 13, y + 0.5, 8.25));
        boxes.add(new SensorShape.Box(7.75, y, 3, 8.25, y + 0.5, 13));
        for (int[] centre : new int[][]{{3,8},{13,8},{8,3},{8,13}}) {
            double x = centre[0], z = centre[1];
            boxes.add(new SensorShape.Box(x - 1, y, z - 1, x + 1, y + 0.5, z + 1));
            boxes.add(new SensorShape.Box(x - 1, y, z - 1, x - 0.5, y + 2, z + 1));
            boxes.add(new SensorShape.Box(x + 0.5, y, z - 1, x + 1, y + 2, z + 1));
            boxes.add(new SensorShape.Box(x - 0.5, y, z - 1, x + 0.5, y + 2, z - 0.5));
            boxes.add(new SensorShape.Box(x - 0.5, y, z + 0.5, x + 0.5, y + 2, z + 1));
        }
        return List.copyOf(boxes);
    }
    private WindSensorShape() { }
}
