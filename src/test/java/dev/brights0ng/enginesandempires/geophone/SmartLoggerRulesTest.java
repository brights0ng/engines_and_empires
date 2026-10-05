package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The smart logger's pure rules: how far it listens, what it keeps, and how its map and buttons are laid out. */
class SmartLoggerRulesTest {

    // ---- listening ----

    @Test
    void itOnlyListensWhileTurningAndFurtherTheFasterItTurns() {
        assertEquals(0.0, LoggerListening.radius(0), 0.0);
        assertEquals(12.0, LoggerListening.radius(0.0001F), 0.01);
        assertEquals(30.0, LoggerListening.radius(128), 1e-9);
        assertEquals(48.0, LoggerListening.radius(256), 1e-9);
        assertEquals(48.0, LoggerListening.radius(512), 1e-9, "no further than its maximum");
        assertEquals(LoggerListening.radius(64), LoggerListening.radius(-64), 0.0, "either direction counts the same");
        assertTrue(LoggerListening.radius(32) < LoggerListening.radius(96));
    }

    @Test
    void itHoldsTwoHundredAndFiftySixReadings() {
        assertEquals(256, LoggerListening.CAPACITY);
        Logbook records = Logbook.empty(LoggerListening.CAPACITY);
        for (int i = 1; i <= LoggerListening.CAPACITY; i++) {
            Logbook.Saved saved = records.save(new ReaderReading("minecraft:overworld", i, 0, 0, true, i, 100));
            assertEquals(Logbook.Outcome.ADDED, saved.outcome());
            records = saved.logbook();
        }
        assertTrue(records.isFull());
        assertEquals(Logbook.Outcome.FULL, records.save(new ReaderReading("minecraft:overworld", 0, 0, 0, true, 999, 100)).outcome(),
                "a new deposit is refused once full");
        Logbook.Saved newer = records.save(new ReaderReading("minecraft:overworld", 5, 5, 5, true, 7, 200));
        assertEquals(Logbook.Outcome.REPLACED, newer.outcome(), "a newer reading of a deposit it has still replaces the old one");
        assertEquals(LoggerListening.CAPACITY, newer.logbook().size());
    }

    @Test
    void aLogbookItemStillHoldsTwelve() {
        assertEquals(Logbook.MAX_ENTRIES, Logbook.EMPTY.capacity());
        assertEquals(Logbook.MAX_ENTRIES, new Logbook(List.of(), 1).capacity());
    }

    // ---- hearing every deposit ----

    private static SeismicWave.Echoer deposit(long id, double x, double z) {
        return new SeismicWave.Echoer(id, "iron", new WaveModel.OreCells(new double[]{x}, new double[]{0.5}, new double[]{z}));
    }

    @Test
    void itKeepsEveryDepositThreeGeophonesNearItHeardNearestFirst() {
        List<SeismicWave.Receiver> geophones = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            geophones.add(new SeismicWave.Receiver(i, 1.5 * i, 0, 0, GeophoneTier.ANDESITE));
        }
        List<SeismicWave.Echoer> echoers = List.of(deposit(5, 60.5, 0.5), deposit(6, 20.5, 0.5), deposit(7, 0.5, 90.5));
        List<SeismicWave.Pulse> pulses = new ArrayList<>();
        for (int g = 1; g <= 4; g++) {
            pulses.add(new SeismicWave.Pulse(g, "iron", 10 + g, 20 + g, 5));
            pulses.add(new SeismicWave.Pulse(g, "iron", 30 + g, 40 + g, 6));
        }
        // Deposit 7 is heard by only two geophones: not enough.
        pulses.add(new SeismicWave.Pulse(1, "iron", 50, 60, 7));
        pulses.add(new SeismicWave.Pulse(2, "iron", 51, 61, 7));

        ReaderRecorder.Reader logger = new ReaderRecorder.Reader(1, 0, 0, 0);
        List<ReaderRecorder.Recording> all = ReaderRecorder.chooseAll(logger, 12, echoers, geophones, pulses, 16);
        assertEquals(List.of(6L, 5L), all.stream().map(ReaderRecorder.Recording::deposit).toList());
        assertEquals(ReaderRecorder.choose(logger, echoers, geophones, pulses, 16), all.get(0),
                "a reader takes the first of what the logger takes");

        // A small listening radius only reaches the first geophones.
        assertTrue(ReaderRecorder.chooseAll(logger, 3.1, echoers, geophones, pulses, 16).isEmpty());
    }

    // ---- the display ----

    /** Facing south: right is east (+X), forward is north (-Z). */
    private static final LoggerDisplay.Frame SOUTH = new LoggerDisplay.Frame(1, 0, 0, -1);
    /** Facing east: right is north (-Z), forward is west (-X). */
    private static final LoggerDisplay.Frame EAST = new LoggerDisplay.Frame(0, -1, -1, 0);

    @Test
    void theTopsCoordinatesGoThereAndBack() {
        for (LoggerDisplay.Frame frame : List.of(SOUTH, EAST)) {
            double[] world = frame.world(100, 200, 0.3, 1.7);
            double[] local = frame.local(100, 200, world[0], world[1]);
            assertEquals(0.3, local[0], 1e-9);
            assertEquals(1.7, local[1], 1e-9);
        }
        // Facing south, the front-left corner is to the south-west of the middle.
        double[] corner = SOUTH.world(0, 0, 0, 0);
        assertEquals(-1, corner[0], 1e-9);
        assertEquals(1, corner[1], 1e-9);
    }

    @Test
    void theMapIsLinedUpWithTheWorldWhicheverWayTheLoggerFaces() {
        LoggerDisplay.View view = new LoggerDisplay.View(1000, -500, 3);
        for (LoggerDisplay.Frame frame : List.of(SOUTH, EAST)) {
            double[] middle = LoggerDisplay.toWorld(view, frame, LoggerDisplay.SCREEN_CENTRE_U, LoggerDisplay.SCREEN_CENTRE_V);
            assertEquals(1000, middle[0], 1e-9);
            assertEquals(-500, middle[1], 1e-9);
            double[] screen = LoggerDisplay.toScreen(view, frame, 1010, -520);
            double[] back = LoggerDisplay.toWorld(view, frame, screen[0], screen[1]);
            assertEquals(1010, back[0], 1e-9);
            assertEquals(-520, back[1], 1e-9);
        }
        // Facing south, further along the screen (away from the viewer) is further north.
        double[] further = LoggerDisplay.toWorld(view, SOUTH, LoggerDisplay.SCREEN_CENTRE_U, LoggerDisplay.SCREEN_CENTRE_V + 0.5);
        assertTrue(further[1] < -500);
    }

    @Test
    void zoomingInCentresOnTheClickAndZoomingOutGoesHome() {
        LoggerDisplay.View home = LoggerDisplay.home(10, 20);
        assertEquals(1024.0, home.blocksPerMetre(), 0.0);
        LoggerDisplay.View in = LoggerDisplay.zoomIn(home, 300, -40);
        assertEquals(1, in.zoom());
        assertEquals(300, in.centreX(), 0.0);
        assertEquals(512.0, in.blocksPerMetre(), 0.0);
        LoggerDisplay.View deep = in;
        for (int i = 0; i < 20; i++) {
            deep = LoggerDisplay.zoomIn(deep, 300, -40);
        }
        assertEquals(LoggerDisplay.MAX_ZOOM, deep.zoom(), "no further than the limit");
        LoggerDisplay.View out = LoggerDisplay.zoomOut(deep, 10, 20);
        assertEquals(LoggerDisplay.MAX_ZOOM - 1, out.zoom());
        assertEquals(300, out.centreX(), 0.0, "zooming out keeps the middle");
        assertEquals(home, LoggerDisplay.zoomOut(in, 10, 20), "back at zoom 0 it is centred on the logger again");
    }

    @Test
    void theTwoButtonsSitOnTheFrontHalfOfTheBarAndTheDockOnTheBack() {
        assertNull(LoggerDisplay.buttonAt(1.0, 1.0), "the screen is not a button");
        assertEquals(LoggerDisplay.Button.POWER, LoggerDisplay.buttonAt(1.75, 0.1));
        assertEquals(LoggerDisplay.Button.READER, LoggerDisplay.buttonAt(1.75, 0.6));
        assertEquals(2, LoggerDisplay.Button.values().length);
        assertNull(LoggerDisplay.buttonAt(1.6, 1.3), "the back half of the bar is the dock, not a button");
        assertNull(LoggerDisplay.buttonAt(1.9, 2.0));
        assertTrue(LoggerDisplay.dockAt(1.6, 1.3));
        assertTrue(LoggerDisplay.dockAt(1.9, 2.0));
        assertFalse(LoggerDisplay.dockAt(1.75, 0.6), "the buttons are not the dock");
        assertFalse(LoggerDisplay.dockAt(1.0, 1.5), "the screen is not the dock");
        assertEquals(0.25, LoggerDisplay.Button.POWER.centreV(), 1e-9);
        assertEquals(0.75, LoggerDisplay.Button.READER.centreV(), 1e-9, "the buttons have not moved");
        assertEquals(1.5, LoggerDisplay.DOCK_CENTRE_V, 1e-9);
        assertEquals(1.75, LoggerDisplay.Button.centreU(), 1e-9);
        assertTrue(LoggerDisplay.inScreenArea(0.1, 0.1));
        assertFalse(LoggerDisplay.inScreenArea(1.6, 0.1));
        assertEquals(0.5, LoggerDisplay.BAR_WIDTH, 0.0);
    }

    @Test
    void theGridIsAboutAQuarterMetreApart() {
        assertEquals(256, LoggerDisplay.gridSpacing(LoggerDisplay.home(0, 0)));
        assertEquals(4, LoggerDisplay.gridSpacing(new LoggerDisplay.View(0, 0, 6)));
    }

    @Test
    void scrollingZoomsAboutThePointUnderThePointer() {
        LoggerDisplay.View view = new LoggerDisplay.View(100, 200, 2);
        double[] pointer = LoggerDisplay.toScreen(view, SOUTH, 180, 150);
        LoggerDisplay.View in = LoggerDisplay.zoomAbout(view, 180, 150, 1);
        assertEquals(3, in.zoom());
        double[] after = LoggerDisplay.toWorld(in, SOUTH, pointer[0], pointer[1]);
        assertEquals(180, after[0], 1e-9, "the spot under the pointer stays under it");
        assertEquals(150, after[1], 1e-9);
        LoggerDisplay.View out = LoggerDisplay.zoomAbout(in, 180, 150, -1);
        assertEquals(view, out, "zooming back out undoes it");
        assertEquals(view.zoom() - 2, LoggerDisplay.zoomAbout(view, 0, 0, -5).zoom(), "no further out than zoom 0");
        LoggerDisplay.View max = new LoggerDisplay.View(1, 2, LoggerDisplay.MAX_ZOOM);
        assertEquals(max, LoggerDisplay.zoomAbout(max, 500, 500, 1), "no further in than the limit, and nothing moves");
    }

    @Test
    void draggingMovesTheGrabbedSpotToThePointer() {
        LoggerDisplay.View view = new LoggerDisplay.View(0, 0, 4);
        double[] grab = LoggerDisplay.toWorld(view, EAST, 0.5, 0.5);
        double[] now = LoggerDisplay.toWorld(view, EAST, 1.0, 1.2);
        LoggerDisplay.View dragged = LoggerDisplay.drag(view, grab[0], grab[1], now[0], now[1]);
        double[] under = LoggerDisplay.toWorld(dragged, EAST, 1.0, 1.2);
        assertEquals(grab[0], under[0], 1e-9);
        assertEquals(grab[1], under[1], 1e-9);
        assertEquals(4, dragged.zoom());
        assertEquals(new LoggerDisplay.View(7, 8, 4), LoggerDisplay.recentre(dragged, 7, 8), "recentring keeps the zoom");
        assertFalse(LoggerDisplay.sane(new LoggerDisplay.View(Double.NaN, 0, 0)));
        assertFalse(LoggerDisplay.sane(new LoggerDisplay.View(0, 4e7, 0)));
        assertTrue(LoggerDisplay.sane(dragged));
    }

    // ---- ores and hovering ----

    @Test
    void readingsCarryTheirOreFromTheDepositThatWasHeard() {
        List<SeismicWave.Receiver> geophones = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            geophones.add(new SeismicWave.Receiver(i, 1.5 * i, 0, 0, GeophoneTier.BRASS));
        }
        SeismicWave.Echoer zinc = new SeismicWave.Echoer(5, "zinc",
                new WaveModel.OreCells(new double[]{40.5}, new double[]{0.5}, new double[]{0.5}));
        List<SeismicWave.Pulse> pulses = List.of(new SeismicWave.Pulse(1, "zinc", 10, 20, 5),
                new SeismicWave.Pulse(2, "zinc", 11, 21, 5), new SeismicWave.Pulse(3, "zinc", 12, 22, 5));
        List<ReaderRecorder.Recording> all = ReaderRecorder.chooseAll(new ReaderRecorder.Reader(1, 0, 0, 0), 12, List.of(zinc),
                geophones, pulses, 16);
        assertEquals("zinc", all.get(0).ore());
    }

    @Test
    void aReadingKeepsItsOre() {
        ReaderReading reading = new ReaderReading("minecraft:overworld", 1, 2, 3, true, 9, 50, ReaderAccuracy.Confidence.PRECISE, "iron");
        ReaderReading old = new ReaderReading("minecraft:overworld", 1, 2, 3, true, 9, 50, ReaderAccuracy.Confidence.PRECISE);
        assertFalse(old.knowsOre());
        assertEquals("iron", old.withOre("iron").ore());
        assertEquals("iron", reading.withDeposit(4).ore(), "a reading keeps its ore when its deposit is worked out");
    }

    @Test
    void oresAreMarkedInTheBrassGeophonesColours() {
        assertEquals(0xFFE8998D, ReadingOres.colour("iron"));
        assertEquals(ReadingOres.UNKNOWN_COLOUR, ReadingOres.colour(""));
    }

    @Test
    void theGogglesCallOutTheReadingNearestTheCrosshair() {
        LoggerDisplay.View view = new LoggerDisplay.View(0, 0, 6); // 16 blocks to a metre of screen
        List<ReaderReading> readings = List.of(
                new ReaderReading("minecraft:overworld", 1, 0, 1, true, 1, 1),
                new ReaderReading("minecraft:overworld", 4, 0, 1, true, 2, 1),
                new ReaderReading("minecraft:the_nether", 1, 0, 1, true, 3, 1));
        double[] first = LoggerDisplay.toScreen(view, SOUTH, 1.5, 1.5);
        assertEquals(0, LoggerDisplay.hovered(view, SOUTH, first[0], first[1], readings, "minecraft:overworld"));
        double[] second = LoggerDisplay.toScreen(view, SOUTH, 4.2, 1.5);
        assertEquals(1, LoggerDisplay.hovered(view, SOUTH, second[0], second[1], readings, "minecraft:overworld"));
        double[] nowhere = LoggerDisplay.toScreen(view, SOUTH, 20, 20);
        assertEquals(-1, LoggerDisplay.hovered(view, SOUTH, nowhere[0], nowhere[1], readings, "minecraft:overworld"));
        assertEquals(2, LoggerDisplay.hovered(view, SOUTH, first[0], first[1], readings, "minecraft:the_nether"),
                "only readings in the logger's own dimension are on its map");
    }
}
