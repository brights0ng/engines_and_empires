package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of the portable record display's 3D model: every box it is built from, and where its screen and lamps are. The
 * data generator builds the model from {@link #boxes()}, and the renderer ({@code PrdItemRenderer}) draws the live map and
 * the lit lamp in the places named here, so the two always agree. Nothing here touches Minecraft.
 *
 * <p>Coordinates are model pixels (16 to a block), as in a block model: {@code x} to the right, {@code y} up, {@code z}
 * towards the viewer. The screen faces {@code +z} (south). Seen from the front, left to right:
 * <ul>
 *   <li>a knurled scroll wheel sticking out of the left side, near the top;</li>
 *   <li>a column of five buttons (save, load, delete, rename, clear);</li>
 *   <li>the screen in a raised bezel, with a floppy drive under it;</li>
 *   <li>the D-shaped handle on the right, its upright wrapped in stitched leather, for the right hand.</li>
 * </ul>
 * On top: the antenna at the left, and the three lamps (red, yellow, green) standing up tall on a ledge, so they can be seen
 * from behind the display as well as in front of it. The ledge sits within the body's depth: nothing hangs over the front.
 */
public final class PrdShape {

    /**
     * The textures the model uses. The metal is Create's, so the display matches Create's machines: the body is its train
     * (railway) casing, a brass-framed dark steel panel; the trim (handle, bezel, drive, buttons, wheel, ledge) is cut from its
     * brass block; the small dark parts are its industrial iron. The handle's grip is wrapped in dark oak. Only the glass and
     * the lenses are the display's own 16 x 16 pictures, drawn by {@link PrdTextures}.
     */
    public enum Tex {
        CASING("create:block/railway_casing"),
        TRIM("create:block/brass_block"),
        GRIP("minecraft:block/dark_oak_planks"),
        SCREEN(null),
        DARK("create:block/industrial_iron_block"),
        LENS_RED(null),
        LENS_YELLOW(null),
        LENS_GREEN(null);

        private final String borrowed;

        Tex(String borrowed) {
            this.borrowed = borrowed;
        }

        /** Whether this is borrowed (Create's, or the game's own), rather than one of the display's own. */
        public boolean borrowed() {
            return borrowed != null;
        }

        /** For one of the display's own: its path under this mod's {@code textures/}, without the extension. */
        public String path() {
            return "item/prd_" + name().toLowerCase(java.util.Locale.ROOT);
        }

        /** Where it is, as a model names it: {@code namespace:path}. */
        public String location() {
            return borrowed != null ? borrowed : "engines_and_empires:" + path();
        }
    }

    // Faces, as bits in the order Minecraft lists directions: down, up, north, south, west, east.
    public static final int DOWN = 1;
    public static final int UP = 1 << 1;
    public static final int NORTH = 1 << 2;
    public static final int SOUTH = 1 << 3;
    public static final int WEST = 1 << 4;
    public static final int EAST = 1 << 5;
    public static final int ALL = 63;

    /** What a box is, for the cursor: most are just the body; these can be touched. */
    public enum Part {
        BODY, GLASS, BUTTON, EJECT, WHEEL,
        /** Not touched by the cursor (it is the body, to it), but named so it can be found. */
        HANDLE
    }

    /**
     * One box of the model.
     *
     * @param texture   what every face shows
     * @param faces     which faces it has (the others are hidden inside the model, so are left out)
     * @param rotationX a turn about the X axis through the box's middle, in degrees (0, ±22.5 or ±45: what models allow)
     * @param fullUv    whether each face shows the whole texture, stretched, rather than a piece of it the box's own size
     * @param part      what it is, for the cursor
     * @param index     which one, for a button (0 is the top, {@link #BUTTONS}); 0 otherwise
     */
    public record Box(float x1, float y1, float z1, float x2, float y2, float z2, Tex texture, int faces, float rotationX,
                      boolean fullUv, Part part, int index) {

        Box(float x1, float y1, float z1, float x2, float y2, float z2, Tex texture, int faces, float rotationX, boolean fullUv) {
            this(x1, y1, z1, x2, y2, z2, texture, faces, rotationX, fullUv, Part.BODY, 0);
        }

        Box(float x1, float y1, float z1, float x2, float y2, float z2, Tex texture, int faces) {
            this(x1, y1, z1, x2, y2, z2, texture, faces, 0, false);
        }

        /** The same box, tagged as a part the cursor can touch. */
        Box as(Part newPart, int newIndex) {
            return new Box(x1, y1, z1, x2, y2, z2, texture, faces, rotationX, fullUv, newPart, newIndex);
        }

        /** The same box moved, in model pixels. */
        public Box moved(float dx, float dy, float dz) {
            return new Box(x1 + dx, y1 + dy, z1 + dz, x2 + dx, y2 + dy, z2 + dz, texture, faces, rotationX, fullUv, part, index);
        }

        /** A small part whose every face shows its whole texture, squeezed to fit: a button, a lens, the wheel's rim. */
        static Box whole(float x1, float y1, float z1, float x2, float y2, float z2, Tex texture, int faces) {
            return new Box(x1, y1, z1, x2, y2, z2, texture, faces, 0, true);
        }

        public boolean has(int face) {
            return (faces & face) != 0;
        }
    }

    // ---- the body ----

    /** The body's back and front faces. */
    public static final float BACK_Z = 6;
    public static final float FRONT_Z = 10;
    /** The body's bottom and top faces. */
    public static final float BOTTOM_Y = 2;
    public static final float TOP_Y = 15;
    /** The body's left and right sides; the handle goes on past the right one. */
    public static final float LEFT_X = -4;
    public static final float RIGHT_X = 13;

    // ---- the screen ----

    /** The glass: its corners, and its front face, sunk a little into the bezel. */
    public static final float SCREEN_X1 = 0;
    public static final float SCREEN_X2 = 10;
    public static final float SCREEN_Y1 = 5;
    public static final float SCREEN_Y2 = 13;
    public static final float SCREEN_Z = 10.25F;
    /** How far the bezel stands out from the body. */
    public static final float BEZEL_Z = 10.75F;

    /** How many of the map screen's pixels ({@link PrdDisplay}) go to one model pixel of the glass. */
    public static final float MAP_PIXELS_PER_MODEL_PIXEL = 16;

    // ---- what is drawn on the glass, in the map screen's pixels from its top left ----

    /** The glass, across and down. */
    public static final int GLASS_WIDTH = 160;
    public static final int GLASS_HEIGHT = 128;
    /** The bar of tabs (MAP, RECORDS) along the top. */
    public static final int TAB_HEIGHT = 11;
    /** The strip along the bottom: the reading under the cursor or selected, a message, or a name being typed. */
    public static final int STRIP_HEIGHT = 20;
    /** A row of the records list. */
    public static final int ROW_HEIGHT = 10;
    /** How many rows of the records list show at once. */
    public static final int ROWS = (GLASS_HEIGHT - TAB_HEIGHT - STRIP_HEIGHT) / ROW_HEIGHT;

    /** Where a point on the glass (model pixels) is, from the glass's top left, in the map screen's pixels: {x, y}. */
    public static double[] glassPixel(double x, double y) {
        return new double[]{(x - SCREEN_X1) * MAP_PIXELS_PER_MODEL_PIXEL, (SCREEN_Y2 - y) * MAP_PIXELS_PER_MODEL_PIXEL};
    }

    /** Where a point on the glass (model pixels) is on the map, from its middle ({@link PrdDisplay}'s coordinates): {x, y}. */
    public static double[] mapPixel(double x, double y) {
        return new double[]{(x - screenCentreX()) * MAP_PIXELS_PER_MODEL_PIXEL, -(y - screenCentreY()) * MAP_PIXELS_PER_MODEL_PIXEL};
    }

    /** The row of the records list at {@code y} glass pixels down, counting from the first shown; -1 if not on a row. */
    public static int rowAt(double y) {
        double below = y - TAB_HEIGHT - 1;
        if (below < 0 || below >= ROWS * ROW_HEIGHT) {
            return -1;
        }
        return (int) (below / ROW_HEIGHT);
    }

    public static float screenCentreX() {
        return (SCREEN_X1 + SCREEN_X2) / 2;
    }

    public static float screenCentreY() {
        return (SCREEN_Y1 + SCREEN_Y2) / 2;
    }

    // ---- the lamps ----

    /** The lamps' left edges, left to right: red, yellow, green (the order they have on screen). */
    public static final float[] LAMP_X = {0.8F, 3.2F, 5.6F};
    public static final float LAMP_WIDTH = 1.6F;
    /** The lenses stand on the ledge, which stands on the top of the body. They are tall, so they show over the top. */
    public static final float LEDGE_Y = TOP_Y + 0.8F;
    public static final float LAMP_TOP_Y = LEDGE_Y + 1.3F;
    /** The ledge, and the lenses on it, front to back: both within the body's own depth. */
    public static final float LEDGE_BACK_Z = 6.6F;
    public static final float LEDGE_FRONT_Z = 9.8F;
    public static final float LAMP_BACK_Z = 7.2F;
    public static final float LAMP_FRONT_Z = 9.4F;
    private static final Tex[] LENSES = {Tex.LENS_RED, Tex.LENS_YELLOW, Tex.LENS_GREEN};

    // ---- the buttons ----

    /** The buttons, top to bottom, as the sketch labels them. */
    public static final String[] BUTTONS = {"save", "load", "delete", "rename", "clear"};
    public static final float BUTTON_X1 = -3.4F;
    public static final float BUTTON_X2 = -1.5F;
    public static final float BUTTON_TOP = 13.6F;
    public static final float BUTTON_HEIGHT = 1.4F;
    public static final float BUTTON_GAP = 0.55F;
    public static final float BUTTON_Z = 10.6F;

    // ---- the handle ----

    /** The upright of the handle, which the hand closes around: its middle. */
    public static final float GRIP_X = 16;
    public static final float GRIP_Y = 8.5F;
    public static final float GRIP_Z = 8;

    /** The middle of the whole display, less its antenna: what is held in front of the eyes (body left side to handle). */
    public static final float CENTRE_X = (LEFT_X + GRIP_X + 1) / 2;
    public static final float CENTRE_Y = 8.5F;
    public static final float CENTRE_Z = 8;

    // ---- how it is carried ----

    /**
     * Carried in third person, hanging from the right fist like a toolbox: handle up, screen facing out, top edge back. The
     * hand's frame there has x out to the side, y forward and z up the arm; the turn takes the model's x (towards the handle)
     * up, and its z (out of the screen) out to the side. The translation puts the handle's upright in the fist. Degrees, and
     * model pixels, as an item model's {@code display} block takes them. The renderer undoes this to hold the display up in
     * both hands instead, so it must match what the data generator writes: both read it from here.
     */
    public static final float[] CARRY_ROTATION = {0, 90, 180};
    public static final float[] CARRY_TRANSLATION = {0, 0.2F, -2.7F};
    public static final float CARRY_SCALE = 0.4F;

    /** Every box of the model. */
    public static List<Box> boxes() {
        List<Box> boxes = new ArrayList<>();

        // The body: a slab with its four long edges cut back one pixel, as three boxes that do not overlap (overlapping faces
        // in one plane flicker).
        boxes.add(new Box(-3, BOTTOM_Y, BACK_Z, 12, TOP_Y, FRONT_Z, Tex.CASING, ALL));
        boxes.add(new Box(LEFT_X, BOTTOM_Y + 1, BACK_Z, -3, TOP_Y - 1, FRONT_Z, Tex.CASING, ALL & ~EAST));
        boxes.add(new Box(12, BOTTOM_Y + 1, BACK_Z, RIGHT_X, TOP_Y - 1, FRONT_Z, Tex.CASING, ALL & ~WEST));
        // A plate across the back.
        boxes.add(new Box(-2, 3.5F, 5.5F, 11, 13.5F, BACK_Z, Tex.TRIM, ALL & ~SOUTH));

        // The screen: the glass, and the bezel standing round it.
        boxes.add(new Box(SCREEN_X1, SCREEN_Y1, FRONT_Z, SCREEN_X2, SCREEN_Y2, SCREEN_Z, Tex.SCREEN, SOUTH, 0, true).as(Part.GLASS, 0));
        boxes.add(new Box(SCREEN_X1 - 1, SCREEN_Y2, FRONT_Z, SCREEN_X2 + 1, SCREEN_Y2 + 1, BEZEL_Z, Tex.TRIM, ALL & ~NORTH));
        boxes.add(new Box(SCREEN_X1 - 1, SCREEN_Y1 - 1, FRONT_Z, SCREEN_X2 + 1, SCREEN_Y1, BEZEL_Z, Tex.TRIM, ALL & ~NORTH));
        boxes.add(new Box(SCREEN_X1 - 1, SCREEN_Y1, FRONT_Z, SCREEN_X1, SCREEN_Y2, BEZEL_Z, Tex.TRIM, SOUTH | WEST | EAST));
        boxes.add(new Box(SCREEN_X2, SCREEN_Y1, FRONT_Z, SCREEN_X2 + 1, SCREEN_Y2, BEZEL_Z, Tex.TRIM, SOUTH | WEST | EAST));

        // The buttons, down the left of the screen.
        for (int i = 0; i < BUTTONS.length; i++) {
            boxes.add(button(i));
        }

        // The floppy drive under the screen: its housing, the dark slot, and the eject button.
        boxes.add(new Box(SCREEN_X1, 2.4F, FRONT_Z, SCREEN_X2, 3.8F, 10.5F, Tex.TRIM, ALL & ~NORTH));
        boxes.add(new Box(1, 2.9F, 10.5F, 7.5F, 3.3F, 10.55F, Tex.DARK, SOUTH | UP | DOWN));
        boxes.add(eject());

        // The scroll wheel, sticking out of the left side near the top: two square plates, one turned 45 degrees, make an
        // octagon. The turned one is a hair thinner, so where they overlap the plain one's face is in front and does not
        // flicker against it. A hub on the outside.
        boxes.add(new Box(-5.2F, 11, 6.5F, LEFT_X, 14, 9.5F, Tex.TRIM, ALL & ~EAST).as(Part.WHEEL, 0));
        boxes.add(new Box(-5.15F, 11, 6.5F, LEFT_X, 14, 9.5F, Tex.TRIM, ALL & ~EAST, 45, false).as(Part.WHEEL, 0));
        boxes.add(new Box(-5.4F, 12, 7.5F, -5.2F, 13, 8.5F, Tex.DARK, ALL & ~EAST));

        // The antenna, at the top left: a base, a rod and a knob.
        boxes.add(new Box(-2.5F, TOP_Y, 6.5F, -0.5F, TOP_Y + 1, 8.5F, Tex.TRIM, ALL & ~DOWN));
        boxes.add(new Box(-1.9F, TOP_Y + 1, 7.1F, -1.1F, TOP_Y + 7, 7.9F, Tex.DARK, ALL & ~DOWN));
        boxes.add(new Box(-2.1F, TOP_Y + 7, 6.9F, -0.9F, TOP_Y + 7.6F, 8.1F, Tex.DARK, ALL));

        // The lamps: a ledge along the top, with a lens standing on it for each lamp.
        boxes.add(new Box(0.2F, TOP_Y, LEDGE_BACK_Z, 7.8F, LEDGE_Y, LEDGE_FRONT_Z, Tex.TRIM, ALL & ~DOWN));
        for (int i = 0; i < LAMP_X.length; i++) {
            boxes.add(Box.whole(LAMP_X[i], LEDGE_Y, LAMP_BACK_Z, LAMP_X[i] + LAMP_WIDTH, LAMP_TOP_Y, LAMP_FRONT_Z, LENSES[i],
                    ALL & ~DOWN));
        }

        // The handle: two arms out of the right side, their outer corners cut back, and the upright between them. The arms
        // meet the body wholly on its side (which is cut back a pixel at top and bottom), so neither hangs off it.
        float upright = GRIP_X - 1; // the upright's inner face
        float armTop = TOP_Y - 1;       // the side's top edge
        float armBottom = BOTTOM_Y + 1; // and its bottom edge
        boxes.add(new Box(RIGHT_X, armTop - 2, 6.5F, upright, armTop, 9.5F, Tex.TRIM, ALL & ~WEST).as(Part.HANDLE, 0));
        boxes.add(new Box(RIGHT_X, armBottom, 6.5F, upright, armBottom + 2, 9.5F, Tex.TRIM, ALL & ~WEST).as(Part.HANDLE, 0));
        boxes.add(new Box(upright, armTop - 2, 6.5F, upright + 1.5F, armTop - 0.5F, 9.5F, Tex.TRIM, ALL & ~WEST).as(Part.HANDLE, 0));
        boxes.add(new Box(upright, armBottom + 0.5F, 6.5F, upright + 1.5F, armBottom + 2, 9.5F, Tex.TRIM, ALL & ~WEST).as(Part.HANDLE, 0));
        boxes.add(new Box(upright, armBottom + 2, 6.5F, upright + 2, armTop - 2, 9.5F, Tex.TRIM, ALL).as(Part.HANDLE, 0));
        // The grip: a dark oak sleeve round the upright, where the hand goes.
        boxes.add(new Box(upright - 0.3F, armBottom + 2.5F, 6.2F, upright + 2.3F, armTop - 2.5F, 9.8F, Tex.GRIP, ALL)
                .as(Part.HANDLE, 0));

        return boxes;
    }

    /**
     * The boxes baked into the display's model: all but the buttons, which are drawn apart from it so they can be pushed in
     * (see {@link #button} and {@link #eject}).
     */
    public static List<Box> bodyBoxes() {
        return boxes().stream().filter(box -> box.part() != Part.BUTTON && box.part() != Part.EJECT).toList();
    }

    /** Side button {@code i}, 0 at the top. They are all alike, one under another. */
    public static Box button(int i) {
        float top = BUTTON_TOP - i * (BUTTON_HEIGHT + BUTTON_GAP);
        return new Box(BUTTON_X1, top - BUTTON_HEIGHT, FRONT_Z, BUTTON_X2, top, BUTTON_Z, Tex.TRIM, ALL & ~NORTH).as(Part.BUTTON, i);
    }

    /** How far down each side button is from the one above it. */
    public static final float BUTTON_STEP = BUTTON_HEIGHT + BUTTON_GAP;

    /** The floppy drive's eject button. */
    public static Box eject() {
        return new Box(8, 2.8F, 10.5F, 9.3F, 3.4F, 10.8F, Tex.TRIM, ALL & ~NORTH).as(Part.EJECT, 0);
    }

    /** How far a side button goes in while pressed, in model pixels: most of the way, not all (its face is 0.6 proud). */
    public static final float PRESS_DEPTH = 0.4F;
    /** The eject button is shallower (0.3 proud of the drive), so it goes in less, and stays in sight. */
    public static final float EJECT_PRESS_DEPTH = 0.15F;

    // ---- what the cursor touches ----

    /**
     * Where a line of sight first meets the display.
     *
     * @param box  the box it meets
     * @param x    where it meets it, in model pixels
     * @param t    how far along the line that is (in units of its direction)
     */
    public record Hit(Box box, double x, double y, double z, double t) {
    }

    /**
     * The first box a line of sight from ({@code ox, oy, oz}) along ({@code dx, dy, dz}) meets, in model pixels, or null. Each
     * box is taken as it stands square (the wheel's turned plate is close enough to its square one).
     */
    public static Hit pick(double ox, double oy, double oz, double dx, double dy, double dz) {
        Hit best = null;
        for (Box box : boxes()) {
            double t = enter(box, ox, oy, oz, dx, dy, dz);
            if (t >= 0 && (best == null || t < best.t())) {
                best = new Hit(box, ox + dx * t, oy + dy * t, oz + dz * t, t);
            }
        }
        return best;
    }

    /** How far along the line it enters the box, or -1 if it misses (the slab method). */
    static double enter(Box box, double ox, double oy, double oz, double dx, double dy, double dz) {
        double near = Double.NEGATIVE_INFINITY;
        double far = Double.POSITIVE_INFINITY;
        double[] origin = {ox, oy, oz};
        double[] direction = {dx, dy, dz};
        double[] low = {box.x1(), box.y1(), box.z1()};
        double[] high = {box.x2(), box.y2(), box.z2()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1e-12) {
                if (origin[axis] < low[axis] || origin[axis] > high[axis]) {
                    return -1;
                }
                continue;
            }
            double a = (low[axis] - origin[axis]) / direction[axis];
            double b = (high[axis] - origin[axis]) / direction[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
        }
        if (near > far || far < 0) {
            return -1;
        }
        return Math.max(near, 0);
    }

    /**
     * Where a line of sight crosses the plane of the glass, {x, y} in model pixels, wherever that is (on the glass or off it:
     * a drag keeps going past the edge). Null if the line runs along the plane, or away from it.
     */
    public static double[] onGlassPlane(double ox, double oy, double oz, double dx, double dy, double dz) {
        if (Math.abs(dz) < 1e-12) {
            return null;
        }
        double t = (SCREEN_Z - oz) / dz;
        return t < 0 ? null : new double[]{ox + dx * t, oy + dy * t};
    }

    /** The lens box of lamp {@code i} (0 red, 1 yellow, 2 green). */
    public static float[] lamp(int i) {
        return new float[]{LAMP_X[i], LEDGE_Y, LAMP_BACK_Z, LAMP_X[i] + LAMP_WIDTH, LAMP_TOP_Y, LAMP_FRONT_Z};
    }

    private PrdShape() {
    }
}
