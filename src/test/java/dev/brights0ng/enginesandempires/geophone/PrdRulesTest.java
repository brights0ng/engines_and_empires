package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The portable record display's pure rules: what it holds, how its lamps flash, and how its map is turned. */
class PrdRulesTest {

    private static final Path LANG = Path.of("src/main/resources/assets/engines_and_empires/lang/en_us.json");

    // ---- what it holds ----

    @Test
    void itHoldsSixteenReadingsByTheLogbooksRules() {
        Logbook records = Logbook.empty(16);
        for (int i = 1; i <= 16; i++) {
            records = records.save(new ReaderReading("minecraft:overworld", i, 0, 0, true, i, 100)).logbook();
        }
        assertTrue(records.isFull());
        assertEquals(Logbook.Outcome.FULL, records.save(new ReaderReading("minecraft:overworld", 0, 0, 0, true, 99, 100)).outcome());
        assertEquals(Logbook.Outcome.REPLACED, records.save(new ReaderReading("minecraft:overworld", 1, 0, 0, true, 3, 200)).outcome(),
                "a newer reading of a deposit it has still goes on when full");
        assertEquals(Logbook.Outcome.ALREADY, records.save(new ReaderReading("minecraft:overworld", 1, 0, 0, true, 3, 100)).outcome());
        assertEquals(Logbook.Outcome.OLDER, records.save(new ReaderReading("minecraft:overworld", 1, 0, 0, true, 3, 50)).outcome());
    }

    // ---- the lamps ----

    @Test
    void eachOutcomeFlashesItsOwnLamp() {
        assertEquals(PrdSignal.Pattern.GREEN_LONG, PrdSignal.forOutcome(Logbook.Outcome.ADDED));
        assertEquals(PrdSignal.Pattern.GREEN_LONG, PrdSignal.forOutcome(Logbook.Outcome.REPLACED));
        assertEquals(PrdSignal.Pattern.YELLOW_BLINKS, PrdSignal.forOutcome(Logbook.Outcome.ALREADY));
        assertEquals(PrdSignal.Pattern.YELLOW_BLINKS, PrdSignal.forOutcome(Logbook.Outcome.OLDER));
        assertEquals(PrdSignal.Pattern.RED_BLINKS, PrdSignal.forOutcome(Logbook.Outcome.FULL));
    }

    @Test
    void aWarningBlinksThreeTimesThenGoesDark() {
        PrdSignal red = new PrdSignal(PrdSignal.Pattern.RED_BLINKS, 1000);
        int on = 0;
        int turnedOn = 0;
        PrdSignal.Light before = PrdSignal.Light.NONE;
        for (long t = 990; t < 1100; t++) {
            PrdSignal.Light light = red.lit(t);
            assertTrue(light == PrdSignal.Light.NONE || light == PrdSignal.Light.RED, "only the red lamp");
            if (light == PrdSignal.Light.RED) {
                on++;
                if (before == PrdSignal.Light.NONE) {
                    turnedOn++;
                }
            }
            before = light;
        }
        assertEquals(3, turnedOn, "three blinks");
        assertEquals(3 * PrdSignal.BLINK_TICKS, on);
        assertEquals(PrdSignal.Light.NONE, red.lit(999), "not before it starts");
        assertEquals(PrdSignal.Light.NONE, red.lit(1000 + PrdSignal.Pattern.RED_BLINKS.duration()), "dark once it is over");
    }

    @Test
    void theLongGreenPulseOutlastsTheBriefFlash() {
        PrdSignal pulse = new PrdSignal(PrdSignal.Pattern.GREEN_LONG, 0);
        PrdSignal flash = new PrdSignal(PrdSignal.Pattern.GREEN_BRIEF, 0);
        assertTrue(PrdSignal.Pattern.GREEN_LONG.duration() > PrdSignal.Pattern.GREEN_BRIEF.duration());
        for (long t = 0; t < PrdSignal.Pattern.GREEN_LONG.duration(); t++) {
            assertEquals(PrdSignal.Light.GREEN, pulse.lit(t), "one steady pulse, no gaps");
        }
        assertEquals(PrdSignal.Light.GREEN, flash.lit(0));
        assertEquals(PrdSignal.Light.NONE, flash.lit(PrdSignal.Pattern.GREEN_BRIEF.duration()));
    }

    // ---- the map ----

    @Test
    void upOnTheMapIsTheWayThePlayerWasFacing() {
        LoggerDisplay.View view = new LoggerDisplay.View(100, 200, 6); // 16 blocks a metre, 5 pixels a block
        assertEquals(5.0, PrdDisplay.pixelsPerBlock(view), 1e-9);
        // Facing south (yaw 0): south is up, west is right.
        double[] south = PrdDisplay.toScreen(view, 0, 100, 210);
        assertEquals(0, south[0], 1e-9);
        assertEquals(-50, south[1], 1e-9);
        double[] west = PrdDisplay.toScreen(view, 0, 90, 200);
        assertEquals(50, west[0], 1e-9);
        // Facing north (yaw 180): north is up, east is right.
        double[] north = PrdDisplay.toScreen(view, 180, 100, 190);
        assertEquals(0, north[0], 1e-9);
        assertEquals(-50, north[1], 1e-9);
        double[] east = PrdDisplay.toScreen(view, 180, 110, 200);
        assertEquals(50, east[0], 1e-9);
        assertEquals(0, east[1], 1e-9);
    }

    @Test
    void theMapsPositionsGoThereAndBackAtAnyAngle() {
        LoggerDisplay.View view = new LoggerDisplay.View(-3000, 750, 2);
        for (double yaw : new double[]{0, 37.5, 90, -135, 200}) {
            double[] screen = PrdDisplay.toScreen(view, yaw, -2950, 800);
            double[] back = PrdDisplay.toWorld(view, yaw, screen[0], screen[1]);
            assertEquals(-2950, back[0], 1e-6);
            assertEquals(800, back[1], 1e-6);
        }
    }

    @Test
    void theSameZoomCoversTheSameGroundAsTheLoggersMap() {
        LoggerDisplay.View home = LoggerDisplay.home(0, 0);
        assertEquals(2048.0, PrdDisplay.MAP_SIZE / PrdDisplay.pixelsPerBlock(home), 1e-9,
                "at zoom 0 the map is two of the logger's metres across");
    }

    @Test
    void hoveringCallsOutTheNearestReadingInThisDimension() {
        LoggerDisplay.View view = new LoggerDisplay.View(0, 0, 6);
        List<ReaderReading> readings = List.of(
                new ReaderReading("minecraft:overworld", 5, 0, 5, true, 1, 1),
                new ReaderReading("minecraft:overworld", 9, 0, 5, true, 2, 1),
                new ReaderReading("minecraft:the_nether", 5, 0, 5, true, 3, 1));
        double[] first = PrdDisplay.toScreen(view, 45, 5.5, 5.5);
        assertEquals(0, PrdDisplay.hovered(view, 45, first[0], first[1], readings, "minecraft:overworld"));
        assertEquals(2, PrdDisplay.hovered(view, 45, first[0], first[1], readings, "minecraft:the_nether"));
        assertEquals(-1, PrdDisplay.hovered(view, 45, first[0] + 30, first[1], readings, "minecraft:overworld"));
        assertFalse(PrdDisplay.onMap(PrdDisplay.MAP_SIZE, 0), "off the map");
    }

    // ---- its look and words ----

    @Test
    void everyTextureOfItsOwnIsDrawnAndTheMetalIsCreates() {
        Map<String, BufferedImage> textures = PrdTextures.all();
        for (PrdShape.Tex tex : PrdShape.Tex.values()) {
            if (tex.borrowed()) {
                assertTrue(tex.location().startsWith("create:block/") || tex.location().startsWith("minecraft:block/"),
                        tex + " is borrowed from Create or the game");
                assertFalse(textures.containsKey(tex.path()), tex + " is Create's, so not drawn here");
                continue;
            }
            BufferedImage image = textures.get(tex.path());
            assertTrue(image != null, "no texture for " + tex);
            assertEquals(16, image.getWidth(), tex.name());
            assertEquals(16, image.getHeight(), tex.name());
        }
    }

    /** The lenses are drawn dark (the renderer lights them), each in its own colour: red, yellow, green, left to right. */
    @Test
    void theLensesAreDarkAndEachItsOwnColour() {
        Map<String, BufferedImage> textures = PrdTextures.all();
        int red = textures.get(PrdShape.Tex.LENS_RED.path()).getRGB(8, 8);
        int yellow = textures.get(PrdShape.Tex.LENS_YELLOW.path()).getRGB(8, 8);
        int green = textures.get(PrdShape.Tex.LENS_GREEN.path()).getRGB(8, 8);
        assertTrue(channel(red, 16) > 2 * channel(red, 8), "the red lens is red");
        assertTrue(channel(green, 8) > 2 * channel(green, 16), "the green lens is green");
        assertTrue(channel(yellow, 16) > 2 * channel(yellow, 0) && channel(yellow, 8) > 2 * channel(yellow, 0), "the yellow lens is yellow");
        for (int lens : new int[]{red, yellow, green}) {
            assertTrue(brightness(lens) < 300, "a lens is drawn dark");
        }
        assertTrue(PrdShape.LAMP_X[0] < PrdShape.LAMP_X[1] && PrdShape.LAMP_X[1] < PrdShape.LAMP_X[2], "left to right");
    }

    private static int channel(int argb, int shift) {
        return (argb >> shift) & 0xFF;
    }

    /** Seen from the front: the handle on the right, for the right hand, and the buttons on the left of the screen. */
    @Test
    void theHandleIsOnTheRightAndTheButtonsOnTheLeft() {
        List<PrdShape.Box> boxes = PrdShape.boxes();
        List<PrdShape.Box> handle = boxes.stream().filter(b -> b.part() == PrdShape.Part.HANDLE).toList();
        assertTrue(handle.stream().allMatch(b -> b.x1() >= PrdShape.RIGHT_X), "the handle is past the body's right side");
        assertTrue(handle.stream().anyMatch(b -> b.x1() < PrdShape.GRIP_X && b.x2() > PrdShape.GRIP_X
                && b.y1() < PrdShape.GRIP_Y && b.y2() > PrdShape.GRIP_Y), "the grip point is in the upright");
        // Where the arms meet the body, they meet it wholly: the body's side runs from a pixel above its bottom to a pixel
        // below its top (its edges are cut back), and every arm touching it stays within that.
        for (PrdShape.Box arm : handle) {
            if (arm.x1() == PrdShape.RIGHT_X) {
                assertTrue(arm.y1() >= PrdShape.BOTTOM_Y + 1 && arm.y2() <= PrdShape.TOP_Y - 1, arm + " hangs off the body");
                assertTrue(arm.z1() >= PrdShape.BACK_Z && arm.z2() <= PrdShape.FRONT_Z, arm + " hangs off the body");
            }
        }
        assertTrue(handle.stream().anyMatch(b -> b.texture() == PrdShape.Tex.GRIP && b.x1() < PrdShape.GRIP_X
                && b.x2() > PrdShape.GRIP_X && b.y1() < PrdShape.GRIP_Y && b.y2() > PrdShape.GRIP_Y), "a dark oak grip round the upright");
        long buttons = boxes.stream().filter(b -> b.part() == PrdShape.Part.BUTTON && b.x2() <= PrdShape.SCREEN_X1).count();
        assertEquals(PrdShape.BUTTONS.length, buttons, "save, load, delete, rename, clear, left of the screen");
    }

    /** The lamps stand up on top of the body, on a ledge that sits on it: neither hangs over the front or the back. */
    @Test
    void theLampsStandOnTopWithinTheBody() {
        for (int i = 0; i < 3; i++) {
            float[] lamp = PrdShape.lamp(i);
            assertTrue(lamp[1] >= PrdShape.TOP_Y, "lamp " + i + " is above the body");
            assertTrue(lamp[4] - PrdShape.TOP_Y >= 2, "lamp " + i + " stands up high enough to see over the top");
            assertTrue(lamp[2] >= PrdShape.LEDGE_BACK_Z && lamp[5] <= PrdShape.LEDGE_FRONT_Z, "lamp " + i + " is on its ledge");
        }
        assertTrue(PrdShape.LEDGE_BACK_Z >= PrdShape.BACK_Z && PrdShape.LEDGE_FRONT_Z <= PrdShape.FRONT_Z,
                "the ledge sits on the body, not hanging over it");
    }

    /** An item model's boxes must lie within -16..32 pixels, and may only be turned by 0, 22.5 or 45 degrees. */
    @Test
    void everyBoxIsOneAModelCanHold() {
        for (PrdShape.Box box : PrdShape.boxes()) {
            for (float v : new float[]{box.x1(), box.y1(), box.z1(), box.x2(), box.y2(), box.z2()}) {
                assertTrue(v >= -16 && v <= 32, box + " is outside what a model can hold");
            }
            assertTrue(box.x1() < box.x2() && box.y1() < box.y2() && box.z1() < box.z2(), box + " is inside out");
            assertTrue(List.of(0F, 22.5F, 45F, -22.5F, -45F).contains(box.rotationX()), box + " is turned by a disallowed angle");
            assertTrue(box.faces() != 0, box + " has no faces");
        }
    }

    /** The glass shows the map screen's pixels at a fixed scale: the full width of the screen's map, and most of its height. */
    @Test
    void theMapFitsTheGlass() {
        float width = (PrdShape.SCREEN_X2 - PrdShape.SCREEN_X1) * PrdShape.MAP_PIXELS_PER_MODEL_PIXEL;
        float height = (PrdShape.SCREEN_Y2 - PrdShape.SCREEN_Y1) * PrdShape.MAP_PIXELS_PER_MODEL_PIXEL;
        assertEquals(PrdDisplay.MAP_SIZE, width, 1e-3);
        assertTrue(height <= PrdDisplay.MAP_SIZE && height >= PrdDisplay.MAP_SIZE * 0.75F);
        assertEquals(PrdShape.GLASS_WIDTH, width, 1e-3);
        assertEquals(PrdShape.GLASS_HEIGHT, height, 1e-3);
    }

    // ---- the cursor ----

    /** A line of sight straight at the display, from in front of it, at model pixel (x, y). */
    private static PrdShape.Hit lookAt(double x, double y) {
        return PrdShape.pick(x, y, 40, 0, 0, -1);
    }

    @Test
    void lookingStraightAtTheGlassTouchesItWhereLooked() {
        PrdShape.Hit hit = lookAt(PrdShape.screenCentreX() + 1, PrdShape.screenCentreY() - 2);
        assertTrue(hit != null && hit.box().part() == PrdShape.Part.GLASS, "the middle of the glass is glass");
        assertEquals(PrdShape.SCREEN_Z, hit.z(), 1e-6, "it is touched on its face");
        double[] map = PrdShape.mapPixel(hit.x(), hit.y());
        assertEquals(16, map[0], 1e-6, "one model pixel right is 16 map pixels right");
        assertEquals(32, map[1], 1e-6, "two model pixels down is 32 map pixels down");
        double[] glass = PrdShape.glassPixel(PrdShape.SCREEN_X1, PrdShape.SCREEN_Y2);
        assertEquals(0, glass[0], 1e-6);
        assertEquals(0, glass[1], 1e-6, "the glass's top left is its origin");
    }

    @Test
    void eachButtonIsFoundUnderTheCursor() {
        for (int i = 0; i < PrdShape.BUTTONS.length; i++) {
            PrdShape.Box button = PrdShape.button(i);
            PrdShape.Hit hit = lookAt((button.x1() + button.x2()) / 2, (button.y1() + button.y2()) / 2);
            assertTrue(hit != null && hit.box().part() == PrdShape.Part.BUTTON && hit.box().index() == i, "button " + i);
            assertEquals(PrdShape.BUTTON_Z, hit.z(), 1e-6, "a button is touched on its face, in front of the body");
        }
        PrdShape.Box eject = PrdShape.eject();
        PrdShape.Hit hit = lookAt((eject.x1() + eject.x2()) / 2, (eject.y1() + eject.y2()) / 2);
        assertTrue(hit != null && hit.box().part() == PrdShape.Part.EJECT, "the eject button");
    }

    @Test
    void missingTheDisplayTouchesNothingAndTheBezelIsNotGlass() {
        assertTrue(lookAt(40, 40) == null, "far off to the side");
        assertTrue(PrdShape.pick(5, 9, 40, 0, 0, 1) == null, "looking away from it");
        PrdShape.Hit bezel = lookAt(PrdShape.screenCentreX(), PrdShape.SCREEN_Y2 + 0.5);
        assertTrue(bezel != null && bezel.box().part() == PrdShape.Part.BODY, "the bezel above the glass");
        double[] plane = PrdShape.onGlassPlane(30, 30, 40, 0, 0, -1);
        assertTrue(plane != null && plane[0] == 30 && plane[1] == 30, "a drag keeps going past the glass's edge");
    }

    @Test
    void theButtonsAreDrawnApartFromTheBody() {
        assertTrue(PrdShape.bodyBoxes().stream().noneMatch(b -> b.part() == PrdShape.Part.BUTTON || b.part() == PrdShape.Part.EJECT));
        assertEquals(PrdShape.boxes().size() - PrdShape.BUTTONS.length - 1, PrdShape.bodyBoxes().size());
        PrdShape.Box second = PrdShape.button(1);
        assertEquals(PrdShape.button(0).y1() - PrdShape.BUTTON_STEP, second.y1(), 1e-5, "each button one step below the last");
    }

    /** Pushed in, a button still shows: it never goes further in than it stands out. */
    @Test
    void pressedButtonsStayInSight() {
        assertTrue(PrdShape.PRESS_DEPTH < PrdShape.BUTTON_Z - PrdShape.FRONT_Z, "a side button");
        PrdShape.Box eject = PrdShape.eject();
        assertTrue(PrdShape.EJECT_PRESS_DEPTH < eject.z2() - eject.z1(), "the eject button");
    }

    /** The north marker: at the edge of what shows, due north of the arrow, wherever the arrow is. */
    @Test
    void theNorthMarkerSitsAtTheEdgeDueNorthOfTheArrow() {
        double[] fromMiddle = PrdDisplay.edgeAlong(0, 0, 0, -1, -75, -48, 75, 39);
        assertEquals(0, fromMiddle[0], 1e-9);
        assertEquals(-48, fromMiddle[1], 1e-9, "straight up to the top edge");
        double[] dragged = PrdDisplay.edgeAlong(20, 10, 0, -1, -75, -48, 75, 39);
        assertEquals(20, dragged[0], 1e-9, "still due north of the arrow, which was dragged right");
        assertEquals(-48, dragged[1], 1e-9);
        double[] turned = PrdDisplay.edgeAlong(0, 0, 1, -1, -75, -48, 75, 39);
        assertEquals(48, turned[0], 1e-9, "north-east: meets the top edge first");
        assertEquals(-48, turned[1], 1e-9);
        double[] offBelow = PrdDisplay.edgeAlong(0, 100, 0, -1, -75, -48, 75, 39);
        assertEquals(-48, offBelow[1], 1e-9, "the arrow off the bottom: the line still crosses, out at the top");
        double[] away = PrdDisplay.edgeAlong(0, 100, 0, 1, -75, -48, 75, 39);
        assertEquals(39, away[1], 1e-9, "north pointing away from the map: on the edge that way from the middle");
    }

    @Test
    void theRecordsListHasItsRowsBetweenTheTabsAndTheStrip() {
        assertEquals(-1, PrdShape.rowAt(PrdShape.TAB_HEIGHT - 1), "on the tabs");
        assertEquals(0, PrdShape.rowAt(PrdShape.TAB_HEIGHT + 2));
        assertEquals(PrdShape.ROWS - 1, PrdShape.rowAt(PrdShape.TAB_HEIGHT + 1 + PrdShape.ROWS * PrdShape.ROW_HEIGHT - 1));
        assertEquals(-1, PrdShape.rowAt(PrdShape.GLASS_HEIGHT - 1), "on the strip");
        assertTrue(PrdShape.TAB_HEIGHT + 1 + PrdShape.ROWS * PrdShape.ROW_HEIGHT <= PrdShape.GLASS_HEIGHT - PrdShape.STRIP_HEIGHT,
                "the rows stop above the strip");
    }

    private static int brightness(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }

    @Test
    void everyPieceOfTextItCanShowIsWritten() throws IOException {
        String lang = Files.readString(LANG);
        for (String key : List.of(
                "item.engines_and_empires.portable_record_display",
                "container.engines_and_empires.portable_record_display",
                "gui.engines_and_empires.portable_record_display.short",
                "gui.engines_and_empires.portable_record_display.records",
                "message.engines_and_empires.portable_record_display.full",
                "message.engines_and_empires.smart_logger.uploaded",
                "message.engines_and_empires.smart_logger.sent",
                "message.engines_and_empires.smart_logger.display_has",
                "engines_and_empires.smart_logger.docked",
                "gui.engines_and_empires.portable_record_display.select_hint",
                "gui.engines_and_empires.portable_record_display.records_hint",
                "gui.engines_and_empires.portable_record_display.tab_map",
                "gui.engines_and_empires.portable_record_display.tab_records",
                "gui.engines_and_empires.portable_record_display.empty",
                "gui.engines_and_empires.portable_record_display.no_disk",
                "gui.engines_and_empires.portable_record_display.select_first",
                "gui.engines_and_empires.portable_record_display.confirm_delete",
                "gui.engines_and_empires.portable_record_display.name_prompt",
                "gui.engines_and_empires.portable_record_display.name_help",
                "key.engines_and_empires.prd_look",
                "key.categories.engines_and_empires",
                "gui.engines_and_empires.portable_record_display.button.save",
                "gui.engines_and_empires.portable_record_display.button.load",
                "gui.engines_and_empires.portable_record_display.button.delete",
                "gui.engines_and_empires.portable_record_display.button.rename",
                "gui.engines_and_empires.portable_record_display.button.clear",
                "gui.engines_and_empires.portable_record_display.button.eject")) {
            assertTrue(lang.contains("\"" + key + "\""), "no text for " + key);
        }
        assertFalse(lang.contains("smart_logger.not_yet"), "nothing on the logger is unfinished any more");
    }
}
