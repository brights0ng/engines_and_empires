package dev.brights0ng.enginesandempires.geophone.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import dev.brights0ng.enginesandempires.geophone.Logbook;
import dev.brights0ng.enginesandempires.geophone.LogbookEntry;
import dev.brights0ng.enginesandempires.geophone.LoggerDisplay;
import dev.brights0ng.enginesandempires.geophone.PrdActionPayload;
import dev.brights0ng.enginesandempires.geophone.PrdDisplay;
import dev.brights0ng.enginesandempires.geophone.PrdItem;
import dev.brights0ng.enginesandempires.geophone.PrdShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Working the portable record display by hand, on this client: everything about it while it is held up, from the moment
 * it is right-clicked until it is put down. The display itself is the interface. The cursor moves freely over it, pushes
 * its buttons and touches its glass; {@link PrdDeviceScreen} only catches the mouse and keys and passes them here.
 *
 * <p>The glass has two pages, picked by tabs along its top: the map (the smart logger's controls, done on the glass) and
 * the records list. A strip along the bottom shows the reading under the cursor or the one selected, a message, or a name
 * being typed. One reading at a time can be selected, on either page, and the side buttons act on it:
 * <ul>
 *   <li>DELETE: press once to ask, again within {@link #CONFIRM_MS} to delete it.</li>
 *   <li>RENAME: type its new name on the keyboard; Enter keeps it, Esc leaves it be.</li>
 *   <li>SAVE, LOAD, CLEAR and the eject button: for the floppy drive, which is not built yet, so they only say "NO DISK".</li>
 * </ul>
 *
 * <p>How the cursor finds what it is over: every frame, {@link PrdPoses} tells this where it has just drawn the display
 * ({@link #capture}: model pixels to the screen). Run backwards from the cursor, that gives a line of sight in model pixels,
 * which {@link PrdShape#pick} follows to the first part it meets.
 *
 * <p>It is put down by Esc, the inventory key, a right-click off the display, or anything that takes it out of the hand.
 * Holding {@link PrdClient#LOOK} (Tab) lets go of the cursor to look around, and picks it up again on release; see
 * {@link PrdClient}.
 */
public final class PrdSession {

    public enum Page {
        MAP, RECORDS
    }

    /** How long a first press of DELETE waits for the second. */
    static final long CONFIRM_MS = 3000;
    /** How long a message stays on the strip. */
    static final long NOTICE_MS = 2000;
    /** However quick the click, a button shows pushed in for at least this long. */
    static final long PRESS_MS = 120;
    /** The eject button, counted after the five side buttons. */
    static final int EJECT = PrdShape.BUTTONS.length;

    private static boolean active;
    private static boolean looking;
    private static InteractionHand hand = InteractionHand.MAIN_HAND;
    private static Page page = Page.MAP;

    // The map.
    private static int zoom;
    private static double[] centre;
    private static int selected = -1;
    private static boolean dragging;
    private static double grabX;
    private static double grabZ;

    // The records list.
    private static int scroll;

    // Typing a name, deleting, messages, buttons, hover.
    private static StringBuilder typing;
    private static int typingNumber = -1;
    private static int armedDelete = -1;
    private static long armedUntil;
    private static Component notice;
    private static long noticeUntil;
    private static int held = -1;
    private static final long[] pressedAt = new long[EJECT + 1];
    private static int hovered = -1;

    // Where the display was drawn: model pixels to the screen, and when.
    private static final Matrix4f toScreen = new Matrix4f();
    private static long capturedAt;

    // ---- starting and stopping ----

    /** Raises the display in this hand and starts working it. */
    public static void start(InteractionHand inHand) {
        active = true;
        looking = false;
        hand = inHand;
        page = Page.MAP;
        centre = null;
        selected = -1;
        dragging = false;
        typing = null;
        armedDelete = -1;
        notice = null;
        held = -1;
        hovered = -1;
        Minecraft.getInstance().setScreen(new PrdDeviceScreen());
    }

    /** Puts it down. */
    public static void end() {
        if (!active) {
            return;
        }
        active = false;
        looking = false;
        typing = null;
        dragging = false;
        held = -1;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof PrdDeviceScreen) {
            minecraft.setScreen(null);
        }
    }

    public static boolean active() {
        return active;
    }

    public static boolean looking() {
        return looking;
    }

    static void setLooking(boolean now) {
        looking = now;
    }

    public static InteractionHand hand() {
        return hand;
    }

    /**
     * The heading the map is turned to: the way the player faces now, so up on the map is always straight ahead. With the
     * cursor out the view does not turn, so this only changes while looking around (Tab), when the whole map, north and
     * all, turns under the arrow.
     */
    public static float yaw() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? 0 : player.getYRot();
    }

    public static Page page() {
        return page;
    }

    public static int zoom() {
        return zoom;
    }

    public static int selected() {
        return selected;
    }

    /** The reading the cursor is over, by number, or -1. */
    public static int hovered() {
        return hovered;
    }

    public static int scroll() {
        return scroll;
    }

    public static boolean typing() {
        return typing != null;
    }

    public static String typed() {
        return typing == null ? "" : typing.toString();
    }

    /** The message on the strip, if one is showing. */
    public static Component notice() {
        return notice != null && System.currentTimeMillis() < noticeUntil ? notice : null;
    }

    /** How far button {@code i} is pushed in, in model pixels (0 to 4 the side buttons, then the eject button). */
    public static float pressDepth(int i) {
        if (held != i && System.currentTimeMillis() - pressedAt[i] >= PRESS_MS) {
            return 0;
        }
        return i == EJECT ? PrdShape.EJECT_PRESS_DEPTH : PrdShape.PRESS_DEPTH;
    }

    /** The display being worked. */
    public static ItemStack display() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
    }

    /** Whether this is the display being worked. */
    public static boolean shows(ItemStack stack) {
        if (!active) {
            return false;
        }
        ItemStack held = display();
        return held == stack || ItemStack.isSameItemSameComponents(held, stack);
    }

    public static Logbook records() {
        return PrdItem.records(display());
    }

    /** What the map shows now: centred on the player, following them, or wherever it was dragged. */
    public static LoggerDisplay.View view() {
        if (centre != null) {
            return new LoggerDisplay.View(centre[0], centre[1], zoom);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? new LoggerDisplay.View(0, 0, zoom) : new LoggerDisplay.View(player.getX(), player.getZ(), zoom);
    }

    /** Whether the map is following the player (not dragged away). */
    public static boolean following() {
        return centre == null;
    }

    private static String dimension() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? "" : minecraft.level.dimension().location().toString();
    }

    /** Called every tick while working it: lets go of a selection or a name whose reading has gone. */
    static void tick() {
        Logbook records = records();
        if (selected >= 0 && records.indexOfNumber(selected) < 0) {
            selected = -1;
        }
        if (typing != null && records.indexOfNumber(typingNumber) < 0) {
            typing = null;
        }
        scroll = Mth.clamp(scroll, 0, Math.max(0, records.size() - PrdShape.ROWS));
    }

    // ---- where the cursor is ----

    /** Where {@link PrdPoses} has just drawn the display: model pixels to the screen's clip space. */
    static void capture(Matrix4f modelPixelsToClip) {
        toScreen.set(modelPixelsToClip);
        capturedAt = System.nanoTime();
    }

    /**
     * The line of sight under the cursor, in model pixels: {origin x, y, z, direction x, y, z}; null if the display was not
     * drawn just now (in third person, say), so there is nothing to touch.
     */
    static double[] ray(double mouseX, double mouseY, double width, double height) {
        if (System.nanoTime() - capturedAt > 200_000_000L) {
            return null;
        }
        float x = (float) (2 * mouseX / width - 1);
        float y = (float) (1 - 2 * mouseY / height);
        Matrix4f back = new Matrix4f(toScreen).invert();
        Vector3f near = back.transformProject(new Vector3f(x, y, -1));
        Vector3f far = back.transformProject(new Vector3f(x, y, 1));
        return new double[]{near.x, near.y, near.z, far.x - near.x, far.y - near.y, far.z - near.z};
    }

    private static PrdShape.Hit pick(double[] ray) {
        return ray == null ? null : PrdShape.pick(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5]);
    }

    /** Works out what the cursor is over, each frame, for the rings and the strip. */
    static void hover(double[] ray) {
        hovered = -1;
        PrdShape.Hit hit = pick(ray);
        if (hit == null || hit.box().part() != PrdShape.Part.GLASS || dragging) {
            return;
        }
        double[] glass = PrdShape.glassPixel(hit.x(), hit.y());
        if (!inContent(glass[1])) {
            return;
        }
        Logbook records = records();
        if (page == Page.MAP) {
            double[] map = PrdShape.mapPixel(hit.x(), hit.y());
            int index = PrdDisplay.hovered(view(), yaw(), map[0], map[1],
                    records.entries().stream().map(LogbookEntry::reading).toList(), dimension());
            hovered = index < 0 ? -1 : records.get(index).number();
        } else {
            int row = PrdShape.rowAt(glass[1]);
            hovered = row >= 0 && records.has(scroll + row) ? records.get(scroll + row).number() : -1;
        }
    }

    /** Between the tabs and the strip. */
    private static boolean inContent(double glassY) {
        return glassY >= PrdShape.TAB_HEIGHT && glassY < PrdShape.GLASS_HEIGHT - PrdShape.STRIP_HEIGHT;
    }

    // ---- the mouse ----

    /** A click: {@code button} 0 is the left button, 1 the right. */
    static void click(double[] ray, int button, boolean shift) {
        PrdShape.Hit hit = pick(ray);
        if (hit == null) {
            if (button == 1) {
                end(); // a right-click off the display puts it down
            }
            return;
        }
        switch (hit.box().part()) {
            case GLASS -> touchGlass(hit, button, shift);
            case BUTTON -> press(hit.box().index());
            case EJECT -> press(EJECT);
            default -> {
            }
        }
    }

    static void release(int button) {
        if (button == 0) {
            dragging = false;
        }
        held = -1;
    }

    static void drag(double[] ray) {
        if (!dragging || ray == null) {
            return;
        }
        double[] at = PrdShape.onGlassPlane(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5]);
        if (at == null) {
            return;
        }
        double[] map = PrdShape.mapPixel(at[0], at[1]);
        LoggerDisplay.View view = view();
        double[] now = PrdDisplay.toWorld(view, yaw(), map[0], map[1]);
        LoggerDisplay.View moved = LoggerDisplay.drag(view, grabX, grabZ, now[0], now[1]);
        zoom = moved.zoom();
        centre = new double[]{moved.centreX(), moved.centreZ()};
    }

    /** The mouse wheel, over the glass or the knurled wheel: zooms the map, or scrolls the list. */
    static void scrolled(double[] ray, double amount) {
        PrdShape.Hit hit = pick(ray);
        if (hit == null || amount == 0) {
            return;
        }
        PrdShape.Part part = hit.box().part();
        if (part != PrdShape.Part.GLASS && part != PrdShape.Part.WHEEL) {
            return;
        }
        int steps = amount > 0 ? 1 : -1;
        if (page == Page.RECORDS) {
            int before = scroll;
            scroll = Mth.clamp(scroll - steps, 0, Math.max(0, records().size() - PrdShape.ROWS));
            if (scroll != before) {
                sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.6F, 0.25F);
            }
            return;
        }
        int before = zoom;
        if (centre == null || part != PrdShape.Part.GLASS) {
            // Following the player (or turning the wheel, not pointing at the map): zoom about the middle.
            zoom = Mth.clamp(zoom + steps, 0, LoggerDisplay.MAX_ZOOM);
        } else {
            double[] map = PrdShape.mapPixel(hit.x(), hit.y());
            LoggerDisplay.View view = view();
            double[] under = PrdDisplay.toWorld(view, yaw(), map[0], map[1]);
            LoggerDisplay.View zoomed = LoggerDisplay.zoomAbout(view, under[0], under[1], steps);
            zoom = zoomed.zoom();
            centre = new double[]{zoomed.centreX(), zoomed.centreZ()};
            if (dragging) {
                double[] grabbed = PrdDisplay.toWorld(zoomed, yaw(), map[0], map[1]);
                grabX = grabbed[0];
                grabZ = grabbed[1];
            }
        }
        if (zoom != before) {
            sound(SoundEvents.COMPARATOR_CLICK, zoom > before ? 1.1F : 0.8F, 1);
        }
    }

    private static void touchGlass(PrdShape.Hit hit, int button, boolean shift) {
        double[] glass = PrdShape.glassPixel(hit.x(), hit.y());
        if (glass[1] < PrdShape.TAB_HEIGHT) {
            Page tab = PrdGlass.tabAt(glass[0]);
            if (tab != null && tab != page) {
                page = tab;
                sound(SoundEvents.UI_BUTTON_CLICK.value(), 1.4F, 0.4F);
            }
            return;
        }
        if (!inContent(glass[1])) {
            return;
        }
        Logbook records = records();
        if (page == Page.RECORDS) {
            int row = PrdShape.rowAt(glass[1]);
            if (row >= 0 && records.has(scroll + row)) {
                int number = records.get(scroll + row).number();
                select(selected == number ? -1 : number);
            }
            return;
        }
        double[] map = PrdShape.mapPixel(hit.x(), hit.y());
        LoggerDisplay.View view = view();
        if (button == 0) {
            double[] grabbed = PrdDisplay.toWorld(view, yaw(), map[0], map[1]);
            dragging = true;
            grabX = grabbed[0];
            grabZ = grabbed[1];
        } else if (button == 1) {
            if (shift) {
                centre = null; // back on the player, following them again
                sound(SoundEvents.COMPARATOR_CLICK, 0.8F, 1);
                return;
            }
            int index = PrdDisplay.hovered(view, yaw(), map[0], map[1],
                    records.entries().stream().map(LogbookEntry::reading).toList(), dimension());
            int number = index < 0 ? -1 : records.get(index).number();
            select(number < 0 || selected == number ? -1 : number);
        }
    }

    private static void select(int number) {
        if (number == selected) {
            return;
        }
        selected = number;
        armedDelete = -1;
        typing = null;
        sound(SoundEvents.COMPARATOR_CLICK, number >= 0 ? 1.3F : 0.7F, 1);
    }

    // ---- the buttons ----

    private static void press(int button) {
        held = button;
        pressedAt[button] = System.currentTimeMillis();
        sound(SoundEvents.STONE_BUTTON_CLICK_ON, 1.8F, 0.5F);
        if (button != 2) {
            armedDelete = -1;
        }
        switch (button) {
            case 2 -> delete();
            case 3 -> rename();
            default -> tell(Component.translatable("gui.engines_and_empires.portable_record_display.no_disk"));
        }
    }

    private static void delete() {
        Logbook records = records();
        int row = selected < 0 ? -1 : records.indexOfNumber(selected);
        if (row < 0) {
            tell(Component.translatable("gui.engines_and_empires.portable_record_display.select_first"));
            return;
        }
        long now = System.currentTimeMillis();
        if (armedDelete == selected && now < armedUntil) {
            send(PrdItem.DELETE, selected, "");
            armedDelete = -1;
            selected = -1;
            notice = null;
            return;
        }
        armedDelete = selected;
        armedUntil = now + CONFIRM_MS;
        notice = Component.translatable("gui.engines_and_empires.portable_record_display.confirm_delete",
                PrdGlass.name(records.get(row)));
        noticeUntil = armedUntil;
    }

    private static void rename() {
        Logbook records = records();
        int row = selected < 0 ? -1 : records.indexOfNumber(selected);
        if (row < 0) {
            tell(Component.translatable("gui.engines_and_empires.portable_record_display.select_first"));
            return;
        }
        LogbookEntry entry = records.get(row);
        typing = new StringBuilder(entry.hasName() ? entry.name() : "");
        typingNumber = entry.number();
        notice = null;
    }

    // ---- typing a name ----

    static void typed(char c) {
        if (typing == null || !StringUtil.isAllowedChatCharacter(c)
                || typing.codePointCount(0, typing.length()) >= Logbook.MAX_NAME_LENGTH) {
            return;
        }
        typing.append(c);
    }

    static void backspace() {
        if (typing != null && !typing.isEmpty()) {
            typing.setLength(typing.offsetByCodePoints(typing.length(), -1));
        }
    }

    static void confirmName() {
        if (typing != null) {
            send(PrdItem.RENAME, typingNumber, typing.toString());
            typing = null;
        }
    }

    static void cancelName() {
        typing = null;
    }

    // ---- small things ----

    private static void send(int action, int number, String name) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            PacketDistributor.sendToServer(new PrdActionPayload(PrdItem.slotOf(player, hand), action, number, name));
        }
    }

    private static void tell(Component message) {
        notice = message;
        noticeUntil = System.currentTimeMillis() + NOTICE_MS;
    }

    private static void sound(net.minecraft.sounds.SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private PrdSession() {
    }
}
