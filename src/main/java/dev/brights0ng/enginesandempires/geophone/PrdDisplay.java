package dev.brights0ng.enginesandempires.geophone;

import java.util.List;

/**
 * The portable record display's map: how world positions land on it, and back. Shared by the screen that draws it and the
 * tests, so the two can never disagree.
 *
 * <p>The map is square, {@link #MAP_SIZE} pixels across, and turned so that "up" on it is the way the player was facing
 * when they opened it (their yaw, frozen for as long as it is open). Positions on it are given in pixels from its middle:
 * {@code x} to the right, {@code y} down, as on any screen.
 *
 * <p>It zooms exactly like the smart logger's map, with the same {@link LoggerDisplay.View}: a "metre" of the logger's
 * screen is {@link #PIXELS_PER_METRE} pixels here, so the same zoom level covers the same ground on both.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class PrdDisplay {

    /** The map's width and height, in pixels. */
    public static final int MAP_SIZE = 160;

    /** How many pixels one metre of the smart logger's screen is here. At zoom 0 the map is 2048 blocks across. */
    public static final double PIXELS_PER_METRE = 80.0;

    /** How close to a reading's dot the mouse must be to call it out, in pixels. */
    public static final double HOVER_PIXELS = 4.0;

    /** How many pixels one block is at this view's zoom. */
    public static double pixelsPerBlock(LoggerDisplay.View view) {
        return PIXELS_PER_METRE / view.blocksPerMetre();
    }

    /**
     * The world point (X, Z) at {x, y} pixels from the map's middle, for a map turned to face {@code yaw} (Minecraft's
     * yaw, in degrees: 0 is south, 90 west, 180 north, -90 east).
     */
    public static double[] toWorld(LoggerDisplay.View view, double yaw, double x, double y) {
        double k = pixelsPerBlock(view);
        double[] right = right(yaw);
        double[] forward = forward(yaw);
        double across = x / k;
        double ahead = -y / k;
        return new double[]{
                view.centreX() + across * right[0] + ahead * forward[0],
                view.centreZ() + across * right[1] + ahead * forward[1]};
    }

    /** Where the world point (X, Z) is drawn, as {x, y} pixels from the map's middle. It may be off the map: see {@link #onMap}. */
    public static double[] toScreen(LoggerDisplay.View view, double yaw, double worldX, double worldZ) {
        double k = pixelsPerBlock(view);
        double[] right = right(yaw);
        double[] forward = forward(yaw);
        double dx = worldX - view.centreX();
        double dz = worldZ - view.centreZ();
        return new double[]{(dx * right[0] + dz * right[1]) * k, -(dx * forward[0] + dz * forward[1]) * k};
    }

    /** A direction in the world (X, Z), as a direction on the map {x, y}: no scaling, just turned. */
    public static double[] turn(double yaw, double worldX, double worldZ) {
        double[] right = right(yaw);
        double[] forward = forward(yaw);
        return new double[]{worldX * right[0] + worldZ * right[1], -(worldX * forward[0] + worldZ * forward[1])};
    }

    /** The way someone facing {@code yaw} is looking, as a unit (X, Z). */
    public static double[] forward(double yaw) {
        double radians = Math.toRadians(yaw);
        return new double[]{-Math.sin(radians), Math.cos(radians)};
    }

    /** To the right of someone facing {@code yaw}, as a unit (X, Z). */
    public static double[] right(double yaw) {
        double radians = Math.toRadians(yaw);
        return new double[]{-Math.cos(radians), -Math.sin(radians)};
    }

    /** Whether a point {x, y} pixels from the middle is on the map. */
    public static boolean onMap(double x, double y) {
        double half = MAP_SIZE / 2.0;
        return x >= -half && x <= half && y >= -half && y <= half;
    }

    /**
     * Where the north marker goes: as far along the line from {@code (fromX, fromY)} (the player's arrow) in the direction
     * {@code (dx, dy)} (north) as it stays inside the box {@code x1..x2, y1..y2}, so it sits at the edge of what shows, due
     * north of the arrow, however the map is zoomed or dragged. If that line never crosses the box (the arrow is off the map,
     * and north points away from it), it goes on the box's edge the same way from the box's middle instead. As {x, y}.
     */
    public static double[] edgeAlong(double fromX, double fromY, double dx, double dy, double x1, double y1, double x2, double y2) {
        double[] exit = exit(fromX, fromY, dx, dy, x1, y1, x2, y2);
        return exit != null ? exit : exit((x1 + x2) / 2, (y1 + y2) / 2, dx, dy, x1, y1, x2, y2);
    }

    /** The last point of the ray inside the box, or null if the ray never is inside it. */
    private static double[] exit(double fromX, double fromY, double dx, double dy, double x1, double y1, double x2, double y2) {
        double near = Double.NEGATIVE_INFINITY;
        double far = Double.POSITIVE_INFINITY;
        double[] from = {fromX, fromY};
        double[] d = {dx, dy};
        double[] low = {x1, y1};
        double[] high = {x2, y2};
        for (int axis = 0; axis < 2; axis++) {
            if (Math.abs(d[axis]) < 1e-12) {
                if (from[axis] < low[axis] || from[axis] > high[axis]) {
                    return null;
                }
                continue;
            }
            double a = (low[axis] - from[axis]) / d[axis];
            double b = (high[axis] - from[axis]) / d[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
        }
        if (near > far || far < 0 || Double.isInfinite(far)) {
            return null;
        }
        return new double[]{fromX + dx * far, fromY + dy * far};
    }

    /**
     * Which reading's dot the point {x, y} is over: the nearest within {@link #HOVER_PIXELS} of it, among the readings in
     * this dimension that are on the map. Its index in the list, or -1 if none is.
     */
    public static int hovered(LoggerDisplay.View view, double yaw, double x, double y, List<ReaderReading> readings,
                              String dimension) {
        if (!onMap(x, y)) {
            return -1;
        }
        int best = -1;
        double bestDistance = HOVER_PIXELS * HOVER_PIXELS;
        for (int i = 0; i < readings.size(); i++) {
            ReaderReading reading = readings.get(i);
            if (!reading.dimension().equals(dimension)) {
                continue;
            }
            double[] at = toScreen(view, yaw, reading.x() + 0.5, reading.z() + 0.5);
            if (!onMap(at[0], at[1])) {
                continue;
            }
            double dx = at[0] - x;
            double dy = at[1] - y;
            double distance = dx * dx + dy * dy;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private PrdDisplay() {
    }
}
