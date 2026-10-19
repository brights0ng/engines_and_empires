// Written by ChatGPT. Mount proportions reference the existing altitude sensor; geometry below was written here.
package dev.brights0ng.meteodyne.instrument;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** One geometry source for generated housings, collision/picking, and the animated thermometer face. Units: model pixels. */
public final class SensorShape {
    public static final int FACE_LEFT = 11, FACE_RIGHT = 21, FACE_TOP = 1, FACE_BOTTOM = 31;
    public static final int TUBE_LEFT = 14, TUBE_RIGHT = 16, TUBE_TOP = 6, TUBE_BOTTOM = 24;
    public static final int BULB_LEFT = 13, BULB_RIGHT = 17, BULB_TOP = 24, BULB_BOTTOM = 28;
    public record Box(double x0, double y0, double z0, double x1, double y1, double z1) { }

    public static Box head(AttachFace face) {
        return switch (face) {
            case FLOOR -> new Box(5, 3, 4, 11, 16, 6);
            case WALL -> new Box(5, 1, 8, 11, 14, 10);
            case CEILING -> new Box(5, 0, 4, 11, 13, 6);
        };
    }

    public static List<Box> boxes(AttachFace face) {
        List<Box> boxes = new ArrayList<>();
        switch (face) {
            case FLOOR -> {
                boxes.add(new Box(0, 0, 0, 16, 2, 16));
                boxes.add(new Box(1, 2, 6, 15, 10, 10));
            }
            case WALL -> {
                boxes.add(new Box(0, 0, 14, 16, 16, 16));
                boxes.add(new Box(1, 6, 10, 15, 10, 14));
            }
            case CEILING -> {
                boxes.add(new Box(0, 14, 0, 16, 16, 16));
                boxes.add(new Box(1, 6, 6, 15, 14, 10));
            }
        }
        boxes.add(head(face));
        return List.copyOf(boxes);
    }

    public static VoxelShape shape(AttachFace face, Direction facing) {
        return shape(boxes(face), facing);
    }

    public static VoxelShape shape(List<Box> boxes, Direction facing) {
        VoxelShape result = Shapes.empty();
        int turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
        for (Box original : boxes) {
            Box box = original;
            for (int i = 0; i < turns; i++) {
                box = new Box(16 - box.z1, box.y0, box.x0, 16 - box.z0, box.y1, box.x1);
            }
            result = Shapes.or(result, Block.box(box.x0, box.y0, box.z0, box.x1, box.y1, box.z1));
        }
        return result.optimize();
    }

    /** Texture coordinates on a north-facing head, shared with its animated fluid. */
    public static float faceX(Box head, double pixel) {
        return (float) ((head.x1 - (pixel - FACE_LEFT) / (FACE_RIGHT - FACE_LEFT) * (head.x1 - head.x0)) / 16);
    }

    public static float faceY(Box head, double pixel) {
        return (float) ((head.y1 - (pixel - FACE_TOP) / (FACE_BOTTOM - FACE_TOP) * (head.y1 - head.y0)) / 16);
    }

    private SensorShape() { }
}
