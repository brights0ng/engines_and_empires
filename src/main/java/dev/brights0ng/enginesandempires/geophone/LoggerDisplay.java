package dev.brights0ng.enginesandempires.geophone;

import java.util.List;

/**
 * The smart logger's top: where the map screen and the button bar are, how the map is drawn onto the screen, and how it
 * zooms. Shared by the block (to tell what a click hit) and the renderer (to draw it), so the two can never disagree.
 *
 * <h2>Where things are</h2>
 * Positions on the top are given in metres (blocks) from the logger's front-left corner, as seen by someone standing at its
 * front: {@code u} across to the right, {@code v} away from them, both from 0 to 2. The screen fills the left
 * {@link #SCREEN_WIDTH}; the raised button bar fills the rest, {@link #BAR_WIDTH} wide. Its front half has the two buttons,
 * one behind the other; its back half is the dock a portable record display sits in.
 *
 * <h2>The map</h2>
 * The map is a true top-down view, lined up with the world whichever way the logger faces: north on the screen is north.
 * The world point shown at the middle of the screen, and how many blocks one metre of screen covers, make a {@link View}.
 * At zoom 0 a metre of screen covers {@link #BASE_BLOCKS_PER_METRE} blocks; every zoom level halves that, down to
 * {@link #MAX_ZOOM}.
 *
 * <p>Nothing here touches Minecraft.
 */
public final class LoggerDisplay {

    /** The whole top, across and deep, in metres. */
    public static final double WIDTH = 2.0;
    public static final double DEPTH = 2.0;

    /** The raised bar the buttons sit on, along the right-hand edge. */
    public static final double BAR_WIDTH = 0.5;

    /** The map screen: everything left of the bar. */
    public static final double SCREEN_WIDTH = WIDTH - BAR_WIDTH;

    /** The frame around the screen, inside which nothing is drawn. */
    public static final double BEZEL = 1.0 / 16;

    /** The middle of the screen, which shows the view's centre. */
    public static final double SCREEN_CENTRE_U = SCREEN_WIDTH / 2;
    public static final double SCREEN_CENTRE_V = DEPTH / 2;

    /** Heights within the block: the screen's glass, the rim around it, and the top of the button bar. */
    public static final double SCREEN_Y = 12.0 / 16;
    public static final double RIM_TOP = 13.0 / 16;
    public static final double BAR_TOP = 1.0;

    /** A button, a small bronze push button on the bar: this wide across the bar, this long along it, this tall. */
    public static final double BUTTON_ACROSS = 4.0 / 16;
    public static final double BUTTON_ALONG = 3.0 / 16;
    public static final double BUTTON_HEIGHT = 1.0 / 16;
    /** How tall it stands while pressed: it sinks half a pixel. */
    public static final double BUTTON_PRESSED_HEIGHT = 0.5 / 16;

    /** A reading's dot on the map is this wide, in metres. */
    public static final double MARKER_SIZE = 0.75 / 16;

    /** How close to a reading's dot the crosshair must be for the goggles to call it out, in metres of screen. */
    public static final double HOVER_RADIUS = 2.5 / 16;

    /** How long a button stays down once pressed, in ticks: as long as a stone button. */
    public static final int BUTTON_PRESS_TICKS = 20;

    /** The bar is marked out in quarters of its length: one for each button, and the back two for the dock. */
    public static final int BAR_SLOTS = 4;

    /** The dock for a portable record display: the back half of the bar, from here to the back edge. */
    public static final double DOCK_FROM_V = DEPTH / 2;

    /** The middle of the dock, along the bar. */
    public static final double DOCK_CENTRE_V = (DOCK_FROM_V + DEPTH) / 2;

    /** How far in the map can zoom, and how many blocks a metre of screen covers at zoom 0. */
    public static final int MAX_ZOOM = 6;
    public static final double BASE_BLOCKS_PER_METRE = 1024.0;

    /** The buttons on the bar, from front to back. */
    public enum Button {
        /** Turns the display on and off. */
        POWER,
        /** Opens the screen for loading readings to and from a wind-up reader. */
        READER;

        /** Where along the bar its middle is. */
        public double centreV() {
            return (ordinal() + 0.5) * DEPTH / BAR_SLOTS;
        }

        /** Where across the top its middle is: the middle of the bar. */
        public static double centreU() {
            return SCREEN_WIDTH + BAR_WIDTH / 2;
        }
    }

    /**
     * Which way the logger faces, as two unit vectors in the world's X and Z: to the right across its top, and forwards
     * (away from someone standing at its front).
     */
    public record Frame(int rightX, int rightZ, int forwardX, int forwardZ) {

        /** Where a world point (X, Z) is on the top, as {u, v}, given the world position of the top's middle. */
        public double[] local(double middleX, double middleZ, double worldX, double worldZ) {
            double dx = worldX - middleX;
            double dz = worldZ - middleZ;
            return new double[]{WIDTH / 2 + dx * rightX + dz * rightZ, DEPTH / 2 + dx * forwardX + dz * forwardZ};
        }

        /** Where a point {u, v} on the top is in the world, as {X, Z}, given the world position of the top's middle. */
        public double[] world(double middleX, double middleZ, double u, double v) {
            double across = u - WIDTH / 2;
            double along = v - DEPTH / 2;
            return new double[]{middleX + across * rightX + along * forwardX, middleZ + across * rightZ + along * forwardZ};
        }
    }

    /** What the map shows: the world point at the middle of the screen, and how far in it is zoomed. */
    public record View(double centreX, double centreZ, int zoom) {

        public View {
            zoom = Math.max(0, Math.min(MAX_ZOOM, zoom));
        }

        /** How many blocks one metre of screen covers. */
        public double blocksPerMetre() {
            return BASE_BLOCKS_PER_METRE / (1 << zoom);
        }
    }

    /** The view a new logger starts with: centred on the logger, zoomed all the way out. */
    public static View home(double loggerX, double loggerZ) {
        return new View(loggerX, loggerZ, 0);
    }

    /** Zooming in on a point: it moves to the middle of the screen, one level closer (or no closer, once at the limit). */
    public static View zoomIn(View view, double worldX, double worldZ) {
        return new View(worldX, worldZ, view.zoom() + 1);
    }

    /** Zooming out one level about the same middle. Back at zoom 0, the view goes home to the logger. */
    public static View zoomOut(View view, double loggerX, double loggerZ) {
        int zoom = view.zoom() - 1;
        return zoom <= 0 ? home(loggerX, loggerZ) : new View(view.centreX(), view.centreZ(), zoom);
    }

    /** How far from its centre, in blocks, a view's centre may ever be put: the world's border and a little more. */
    public static final double MAX_CENTRE = 30_000_000;

    /**
     * Zooming by {@code steps} levels (in if positive, out if negative) about the world point (X, Z), which stays where it is
     * on the screen: the point under the pointer, as scrolling does on any map. No further than the limits either way.
     */
    public static View zoomAbout(View view, double worldX, double worldZ, int steps) {
        int zoom = Math.max(0, Math.min(MAX_ZOOM, view.zoom() + steps));
        if (zoom == view.zoom()) {
            return view;
        }
        double keep = (double) (1 << view.zoom()) / (1 << zoom); // the new blocks-per-metre over the old
        return new View(worldX + (view.centreX() - worldX) * keep, worldZ + (view.centreZ() - worldZ) * keep, zoom);
    }

    /**
     * Dragging: the world point that was grabbed ({@code grabX}, {@code grabZ}) is moved to where the pointer now is, which,
     * with the view as it stands, shows the world point ({@code nowX}, {@code nowZ}). The zoom stays.
     */
    public static View drag(View view, double grabX, double grabZ, double nowX, double nowZ) {
        return new View(view.centreX() + grabX - nowX, view.centreZ() + grabZ - nowZ, view.zoom());
    }

    /** The same zoom, centred on (X, Z) again: back on the logger, or the player, after being dragged away. */
    public static View recentre(View view, double x, double z) {
        return new View(x, z, view.zoom());
    }

    /** Whether a view is fit to keep: finite, and not absurdly far out. The server checks what a client sends against this. */
    public static boolean sane(View view) {
        return Double.isFinite(view.centreX()) && Double.isFinite(view.centreZ())
                && Math.abs(view.centreX()) <= MAX_CENTRE && Math.abs(view.centreZ()) <= MAX_CENTRE;
    }

    /** The world point {X, Z} shown at a point {u, v} of the screen. */
    public static double[] toWorld(View view, Frame frame, double u, double v) {
        double across = u - SCREEN_CENTRE_U;
        double along = v - SCREEN_CENTRE_V;
        double scale = view.blocksPerMetre();
        return new double[]{
                view.centreX() + scale * (across * frame.rightX() + along * frame.forwardX()),
                view.centreZ() + scale * (across * frame.rightZ() + along * frame.forwardZ())};
    }

    /** Where on the top {u, v} a world point (X, Z) is drawn. It may be off the screen: see {@link #onScreen}. */
    public static double[] toScreen(View view, Frame frame, double worldX, double worldZ) {
        double scale = view.blocksPerMetre();
        double dx = (worldX - view.centreX()) / scale;
        double dz = (worldZ - view.centreZ()) / scale;
        return new double[]{
                SCREEN_CENTRE_U + dx * frame.rightX() + dz * frame.rightZ(),
                SCREEN_CENTRE_V + dx * frame.forwardX() + dz * frame.forwardZ()};
    }

    /** Whether a point on the top is on the visible part of the screen, inside its bezel. */
    public static boolean onScreen(double u, double v) {
        return u >= BEZEL && u <= SCREEN_WIDTH - BEZEL && v >= BEZEL && v <= DEPTH - BEZEL;
    }

    /** Whether a point on the top is on the screen at all, bezel included: what a click there counts as. */
    public static boolean inScreenArea(double u, double v) {
        return u >= 0 && u < SCREEN_WIDTH && v >= 0 && v <= DEPTH;
    }

    /** Whether a point on the top is on the bar at all. */
    private static boolean onBar(double u, double v) {
        return u >= SCREEN_WIDTH && u <= WIDTH && v >= 0 && v <= DEPTH;
    }

    /** The button whose part of the bar a point on the top is in, or null if it is not on a button's part of the bar. */
    public static Button buttonAt(double u, double v) {
        if (!onBar(u, v)) {
            return null;
        }
        int index = (int) Math.floor(v / (DEPTH / BAR_SLOTS));
        Button[] buttons = Button.values();
        return index >= 0 && index < buttons.length ? buttons[index] : null;
    }

    /** Whether a point on the top is on the dock. */
    public static boolean dockAt(double u, double v) {
        return onBar(u, v) && v >= DOCK_FROM_V;
    }

    /**
     * How far off a reading of this confidence could be, in blocks, for drawing the blurred square around it: the top of its
     * band, see {@link ReaderAccuracy}. A rough reading has no upper bound, so it is drawn at twice the approximate limit.
     */
    public static double blurBlocks(ReaderAccuracy.Confidence confidence) {
        return switch (confidence) {
            case PRECISE -> ReaderAccuracy.PRECISE_MAX_BLOCKS;
            case APPROXIMATE -> ReaderAccuracy.APPROXIMATE_MAX_BLOCKS;
            case ROUGH, UNKNOWN -> 2 * ReaderAccuracy.APPROXIMATE_MAX_BLOCKS;
        };
    }

    /** How far apart the grid lines are, in blocks: a power of two, about a quarter of a metre of screen apart. */
    public static int gridSpacing(View view) {
        double wanted = view.blocksPerMetre() / 4;
        int spacing = 1;
        while (spacing < wanted) {
            spacing <<= 1;
        }
        return spacing;
    }

    /**
     * Which reading's dot a point {u, v} on the screen is over: the nearest within {@link #HOVER_RADIUS} of it, among the
     * readings in this dimension that are on the screen. Its index in the list, or -1 if none is.
     */
    public static int hovered(View view, Frame frame, double u, double v, List<ReaderReading> readings, String dimension) {
        if (!onScreen(u, v)) {
            return -1;
        }
        int best = -1;
        double bestDistance = HOVER_RADIUS * HOVER_RADIUS;
        for (int i = 0; i < readings.size(); i++) {
            ReaderReading reading = readings.get(i);
            if (!reading.dimension().equals(dimension)) {
                continue;
            }
            double[] at = toScreen(view, frame, reading.x() + 0.5, reading.z() + 0.5);
            if (!onScreen(at[0], at[1])) {
                continue;
            }
            double du = at[0] - u;
            double dv = at[1] - v;
            double distance = du * du + dv * dv;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private LoggerDisplay() {
    }
}
