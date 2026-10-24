// Written by Claude.
package dev.brights0ng.empire.bonfire;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The bonfire's hitbox, following its model ({@code grand_folksy_bonfire_no_embers.geo.json}): six courses of logs
 * stacked log-cabin style around an open pit, with a low ember bed in the middle. The whole structure's boxes are cut
 * into one shape per cell of the 3x3x3 structure once, at class load; the blocks just look theirs up.
 *
 * <p>Cells holding only flames (the whole top row, and the middle of the second) get no shape: nothing to walk into
 * or aim at, but the part block still fills the cell, so nothing can be placed there.
 */
public final class BonfireShapes {

    /**
     * The structure's boxes in model pixels, as {minX, minY, minZ, maxX, maxY, maxZ}: x and z from the core block's
     * centre (the structure spans -24..24), y from its bottom. Each log is taken as its full 6x6 cross-section.
     */
    private static final double[][] BOXES = {
            // Course 1: logs along x.
            {-24, 1, -20, 24, 7, -14}, {-24, 1, 14, 24, 7, 20},
            // Course 2: logs along z.
            {14, 5.5, -24, 20, 11.5, 24}, {-20, 5.5, -24, -14, 11.5, 24},
            // Course 3.
            {-22, 10, -18, 22, 16, -12}, {-22, 10, 12, 22, 16, 18},
            // Course 4.
            {12, 14.5, -22, 18, 20.5, 22}, {-18, 14.5, -22, -12, 20.5, 22},
            // Course 5.
            {-19, 19, -15, 19, 25, -9}, {-19, 19, 9, 19, 25, 15},
            // Course 6.
            {9, 23.5, -19, 15, 29.5, 19}, {-15, 23.5, -19, -9, 29.5, 19},
            // Ember bed and kindling, squared off (the model's are irregular, 2-3 px and 5 px high).
            {-16, 0, -16, 16, 2, 16}, {-8, 0, -8, 8, 4, 8},
    };

    /**
     * Where the fire burns, in blocks: inside the top course's logs ({@value #BURN_HALF_WIDTH} each side of the core's
     * centre), from the core's bottom to the top of the structure.
     */
    private static final double BURN_HALF_WIDTH = 9.0D / 16.0D;
    private static final double BURN_HEIGHT = 3.0D;

    private static final VoxelShape CORE = slice(0, 0, 0);
    private static final VoxelShape[] PARTS = new VoxelShape[BonfirePartBlock.PART_OFFSETS.length];

    static {
        for (int i = 0; i < PARTS.length; i++) {
            BlockPos offset = BonfirePartBlock.PART_OFFSETS[i];
            PARTS[i] = slice(offset.getX(), offset.getY(), offset.getZ());
        }
    }

    /** The core block's shape. */
    public static VoxelShape core() {
        return CORE;
    }

    /** The shape of the part at {@code offset} ({@link BonfirePartBlock#OFFSET}). */
    public static VoxelShape part(int offset) {
        return offset >= 0 && offset < PARTS.length ? PARTS[offset] : Shapes.empty();
    }

    /** Whether {@code entity} touches the fire of the bonfire whose core is at {@code core}. */
    public static boolean inFire(BlockPos core, Entity entity) {
        double cx = core.getX() + 0.5D;
        double cz = core.getZ() + 0.5D;
        AABB fire = new AABB(cx - BURN_HALF_WIDTH, core.getY(), cz - BURN_HALF_WIDTH,
                cx + BURN_HALF_WIDTH, core.getY() + BURN_HEIGHT, cz + BURN_HALF_WIDTH);
        return entity.getBoundingBox().intersects(fire);
    }

    /** The structure's boxes clipped to the cell {@code (dx, dy, dz)} from the core, in that cell's own coordinates. */
    private static VoxelShape slice(int dx, int dy, int dz) {
        VoxelShape shape = Shapes.empty();
        for (double[] box : BOXES) {
            // Model pixels to blocks from the core block's min corner.
            double x0 = Math.max((box[0] + 8.0D) / 16.0D, dx);
            double y0 = Math.max(box[1] / 16.0D, dy);
            double z0 = Math.max((box[2] + 8.0D) / 16.0D, dz);
            double x1 = Math.min((box[3] + 8.0D) / 16.0D, dx + 1);
            double y1 = Math.min(box[4] / 16.0D, dy + 1);
            double z1 = Math.min((box[5] + 8.0D) / 16.0D, dz + 1);
            if (x0 < x1 && y0 < y1 && z0 < z1) {
                shape = Shapes.or(shape, Shapes.box(x0 - dx, y0 - dy, z0 - dz, x1 - dx, y1 - dy, z1 - dz));
            }
        }
        return shape.optimize();
    }

    private BonfireShapes() {
    }
}
