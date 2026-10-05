package dev.brights0ng.enginesandempires.gametest;

import com.mojang.serialization.JsonOps;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.Logbook;
import dev.brights0ng.enginesandempires.geophone.LogbookCodecs;
import dev.brights0ng.enginesandempires.geophone.LogbookEntry;
import dev.brights0ng.enginesandempires.geophone.LogbookMenu;
import dev.brights0ng.enginesandempires.geophone.LoggerDisplay;
import dev.brights0ng.enginesandempires.geophone.PrdItem;
import dev.brights0ng.enginesandempires.geophone.PrdMenu;
import dev.brights0ng.enginesandempires.geophone.PrdSignal;
import dev.brights0ng.enginesandempires.geophone.ReaderReading;
import dev.brights0ng.enginesandempires.geophone.ReadingBoardMenu;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import dev.brights0ng.enginesandempires.geophone.SmartLoggerBlock;
import dev.brights0ng.enginesandempires.geophone.SmartLoggerBlockEntity;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-world tests for the portable record display: that it keeps sixteen readings through saving and the network, that its
 * records list saves from a reader and flashes its lamps, and that the smart logger's dock takes its readings in, sends the
 * logger's out on a double click, gives it back, and drops it when broken. Also that the logbook still works after its
 * screen and menu were folded into the shared board.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class PrdGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final BlockPos MASTER = new BlockPos(2, 1, 4);
    private static final String OVERWORLD = "minecraft:overworld";

    // ---- keeping readings ----

    @GameTest(template = SCRATCH)
    public static void sixteenReadingsSurviveSavingAndTheNetwork(GameTestHelper helper) {
        Logbook full = Logbook.empty(PrdItem.CAPACITY);
        for (int i = 1; i <= PrdItem.CAPACITY; i++) {
            full = full.save(reading(i, i * 10, 0, 100)).logbook();
        }
        Logbook saved = PrdItem.RECORDS_CODEC.parse(JsonOps.INSTANCE,
                PrdItem.RECORDS_CODEC.encodeStart(JsonOps.INSTANCE, full).getOrThrow()).getOrThrow();
        if (!saved.equals(full) || saved.capacity() != PrdItem.CAPACITY) {
            helper.fail("the display's readings did not load back the same: " + saved.size() + " of " + saved.capacity());
            return;
        }
        ByteBuf buffer = Unpooled.buffer();
        PrdItem.RECORDS_STREAM.encode(buffer, full);
        Logbook sent = PrdItem.RECORDS_STREAM.decode(buffer);
        if (!sent.equals(full)) {
            helper.fail("the display's readings did not survive the network");
            return;
        }
        Logbook book = LogbookCodecs.LOGBOOK.parse(JsonOps.INSTANCE,
                LogbookCodecs.LOGBOOK.encodeStart(JsonOps.INSTANCE, Logbook.EMPTY).getOrThrow()).getOrThrow();
        if (book.capacity() != Logbook.MAX_ENTRIES) {
            helper.fail("a logbook no longer loads with room for " + Logbook.MAX_ENTRIES);
            return;
        }
        helper.succeed();
    }

    // ---- the records list ----

    @GameTest(template = SCRATCH)
    public static void theRecordsListSavesFromAReaderAndFlashesTheLamps(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, new ItemStack(SeismicContent.PORTABLE_RECORD_DISPLAY.get()));
        PrdMenu menu = new PrdMenu(1, player.getInventory(), 0);
        ItemStack reader = new ItemStack(SeismicContent.WINDUP_READER.get());
        reader.set(SeismicContent.READER_READING.get(), reading(1, 0, 0, 100));
        menu.slots.get(0).set(reader);

        menu.act(player, ReadingBoardMenu.SAVE, 0, "");
        ItemStack display = player.getInventory().getItem(0);
        if (PrdItem.records(display).size() != 1 || lamp(helper, display) != PrdSignal.Light.GREEN) {
            helper.fail("saving a new reading should put it on and light green");
            return;
        }
        menu.act(player, ReadingBoardMenu.SAVE, 0, "");
        if (PrdItem.records(display).size() != 1 || lamp(helper, display) != PrdSignal.Light.YELLOW) {
            helper.fail("saving it again should change nothing and blink yellow");
            return;
        }
        Logbook full = PrdItem.records(display);
        for (int i = 2; !full.isFull(); i++) {
            full = full.save(reading(i, 0, 0, 100)).logbook();
        }
        PrdItem.setRecords(display, full);
        reader.set(SeismicContent.READER_READING.get(), reading(999, 0, 0, 100));
        menu.act(player, ReadingBoardMenu.SAVE, 0, "");
        if (PrdItem.records(display).size() != PrdItem.CAPACITY || lamp(helper, display) != PrdSignal.Light.RED) {
            helper.fail("a new deposit on a full display should be refused, blinking red");
            return;
        }
        helper.succeed();
    }

    /** The logbook's board after the refactor: save, rename and delete, each by entry number. */
    @GameTest(template = SCRATCH)
    public static void theLogbookStillSavesRenamesAndDeletes(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, new ItemStack(SeismicContent.LOGBOOK.get()));
        LogbookMenu menu = new LogbookMenu(1, player.getInventory(), 0);
        ItemStack reader = new ItemStack(SeismicContent.WINDUP_READER.get());
        reader.set(SeismicContent.READER_READING.get(), reading(7, 0, 0, 100));
        menu.slots.get(0).set(reader);

        menu.act(player, ReadingBoardMenu.SAVE, 0, "");
        Logbook book = menu.records();
        if (book.size() != 1 || book.capacity() != Logbook.MAX_ENTRIES) {
            helper.fail("the logbook did not save the reading");
            return;
        }
        int number = book.get(0).number();
        menu.act(player, ReadingBoardMenu.RENAME, number, "  Quarry  ");
        if (!menu.records().get(0).name().equals("Quarry")) {
            helper.fail("the rename was not tidied and kept: '" + menu.records().get(0).name() + "'");
            return;
        }
        menu.act(player, ReadingBoardMenu.DELETE, number, "");
        if (menu.records().size() != 0) {
            helper.fail("the delete did not remove the entry");
            return;
        }
        helper.succeed();
    }

    /**
     * The display worked by hand, with no menu open: renaming and deleting a reading by its number, only on a display the
     * player is holding.
     */
    @GameTest(template = SCRATCH)
    public static void workingTheDisplayByHandRenamesAndDeletes(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().selected = 0;
        ItemStack display = new ItemStack(SeismicContent.PORTABLE_RECORD_DISPLAY.get());
        PrdItem.setRecords(display, Logbook.empty(PrdItem.CAPACITY).save(reading(1, 0, 0, 100)).logbook()
                .save(reading(2, 5, 5, 100)).logbook());
        player.getInventory().setItem(0, display);
        ItemStack spare = display.copy();
        player.getInventory().setItem(5, spare);
        int first = PrdItem.records(display).get(0).number();
        int second = PrdItem.records(display).get(1).number();

        if (!PrdItem.act(player, 0, PrdItem.RENAME, first, "  Ridge  ") || !PrdItem.records(display).get(0).name().equals("Ridge")) {
            helper.fail("renaming the held display's reading did not tidy and keep the name");
            return;
        }
        if (PrdItem.act(player, 5, PrdItem.DELETE, first, "") || PrdItem.records(spare).size() != 2) {
            helper.fail("a display in a slot the player is not holding was changed");
            return;
        }
        if (PrdItem.act(player, 0, PrdItem.DELETE, 999, "")) {
            helper.fail("deleting a reading that is not there claimed to change something");
            return;
        }
        if (!PrdItem.act(player, 0, PrdItem.DELETE, first, "") || PrdItem.records(display).size() != 1
                || PrdItem.records(display).get(0).number() != second) {
            helper.fail("deleting by number removed the wrong reading, or none");
            return;
        }
        helper.succeed();
    }

    // ---- the smart logger's dock ----

    @GameTest(template = SCRATCH)
    public static void dockingLoadsNewAndNewerReadingsIntoTheLogger(GameTestHelper helper) {
        SmartLoggerBlockEntity logger = build(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        // The logger has deposits 1 and 2; the display has a newer 1, an older 2, and a new 3 with a name.
        keep(logger, reading(1, 0, 0, 100), reading(2, 0, 0, 300));
        ItemStack display = new ItemStack(SeismicContent.PORTABLE_RECORD_DISPLAY.get());
        Logbook onDisplay = Logbook.empty(PrdItem.CAPACITY).save(reading(1, 5, 5, 200)).logbook()
                .save(reading(2, 5, 5, 100)).logbook();
        Logbook.Saved third = onDisplay.save(reading(3, 5, 5, 100));
        PrdItem.setRecords(display, third.logbook().rename(third.entry().number(), "Ridge"));
        player.setItemInHand(InteractionHand.MAIN_HAND, display);

        clickDock(logger, player);
        if (logger.docked().isEmpty() || !player.getMainHandItem().isEmpty()) {
            helper.fail("the display did not go into the dock");
            return;
        }
        Logbook records = logger.records();
        if (records.size() != 3) {
            helper.fail("expected 3 readings on the logger, have " + records.size());
            return;
        }
        if (find(records, 1).reading().takenAt() != 200) {
            helper.fail("the older reading on the logger was not updated from the display");
            return;
        }
        if (find(records, 2).reading().takenAt() != 300) {
            helper.fail("the display's older reading replaced the logger's newer one");
            return;
        }
        if (!find(records, 3).name().equals("Ridge")) {
            helper.fail("a new reading lost the name it had on the display");
            return;
        }
        if (lamp(helper, logger.docked()) != PrdSignal.Light.GREEN) {
            helper.fail("the display's green lamp did not flash as it was loaded");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void aSelectedReadingIsSentToTheDockedDisplay(GameTestHelper helper) {
        SmartLoggerBlockEntity logger = build(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 middle = logger.middle();
        ReaderReading near = reading(1, (int) middle.x + 300, (int) middle.z, 100);
        ReaderReading far = reading(2, (int) middle.x - 300, (int) middle.z, 100);
        keep(logger, near, far);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SeismicContent.PORTABLE_RECORD_DISPLAY.get()));
        clickDock(logger, player);
        click(logger, player, LoggerDisplay.Button.centreU(), LoggerDisplay.Button.POWER.centreV());

        // A right-click on a dot, on the server, does nothing by itself: selecting is the client's.
        clickReading(logger, player, near);
        if (!PrdItem.records(logger.docked()).entries().isEmpty() || logger.view().zoom() != 0) {
            helper.fail("a right-click on a dot sent it, or zoomed");
            return;
        }
        int nearNumber = find(logger.records(), 1).number();
        int farNumber = find(logger.records(), 2).number();
        // The client asks to send it (a selected reading clicked again): green.
        logger.sendToDisplay(player, nearNumber);
        if (PrdItem.records(logger.docked()).size() != 1 || lamp(helper, logger.docked()) != PrdSignal.Light.GREEN) {
            helper.fail("sending did not put the reading on and light green");
            return;
        }
        // Again: the display already has it, so yellow.
        logger.sendToDisplay(player, nearNumber);
        if (PrdItem.records(logger.docked()).size() != 1 || lamp(helper, logger.docked()) != PrdSignal.Light.YELLOW) {
            helper.fail("sending a reading the display already has should blink yellow");
            return;
        }
        // Once full, a new deposit is refused: red.
        Logbook full = PrdItem.records(logger.docked());
        for (int i = 10; !full.isFull(); i++) {
            full = full.save(reading(i, 0, 0, 100)).logbook();
        }
        PrdItem.setRecords(logger.docked(), full);
        logger.sendToDisplay(player, farNumber);
        if (lamp(helper, logger.docked()) != PrdSignal.Light.RED) {
            helper.fail("sending to a full display should blink red");
            return;
        }
        // Nothing is sent once the display is taken out.
        clickDock(logger, player);
        if (logger.sendToDisplay(player, farNumber) != null) {
            helper.fail("something was sent with no display docked");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void anEmptyHandTakesTheDisplayBackAndBreakingDropsIt(GameTestHelper helper) {
        SmartLoggerBlockEntity logger = build(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SeismicContent.PORTABLE_RECORD_DISPLAY.get()));
        clickDock(logger, player);
        clickDock(logger, player);
        if (!logger.docked().isEmpty() || !(player.getMainHandItem().getItem() instanceof PrdItem)) {
            helper.fail("an empty hand did not take the display back");
            return;
        }
        clickDock(logger, player);
        helper.getLevel().destroyBlock(helper.absolutePos(SmartLoggerBlock.partPos(MASTER, Direction.SOUTH,
                SmartLoggerBlock.Part.BACK_RIGHT)), true);
        helper.runAfterDelay(2, () -> {
            AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(8);
            int dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area,
                    item -> item.getItem().getItem() instanceof PrdItem).size();
            if (dropped != 1) {
                helper.fail("expected the docked display to drop once, dropped " + dropped);
                return;
            }
            helper.succeed();
        });
    }

    // ---- helpers ----

    private static ReaderReading reading(long deposit, int x, int z, long takenAt) {
        return new ReaderReading(OVERWORLD, x, 40, z, true, deposit, takenAt);
    }

    private static PrdSignal.Light lamp(GameTestHelper helper, ItemStack display) {
        return PrdItem.flashing(display, helper.getLevel().getGameTime());
    }

    private static LogbookEntry find(Logbook records, long deposit) {
        for (LogbookEntry entry : records.entries()) {
            if (entry.reading().deposit() == deposit) {
                return entry;
            }
        }
        throw new IllegalStateException("no reading of deposit " + deposit);
    }

    /** Puts readings straight onto the logger, as if heard. */
    private static void keep(SmartLoggerBlockEntity logger, ReaderReading... readings) {
        Logbook records = logger.records();
        for (ReaderReading reading : readings) {
            records = records.save(reading).logbook();
        }
        logger.setRecords(records);
    }

    private static SmartLoggerBlockEntity build(GameTestHelper helper) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(MASTER, SeismicContent.SMART_LOGGER.get().defaultBlockState()
                .setValue(SmartLoggerBlock.HORIZONTAL_FACING, Direction.SOUTH));
        SmartLoggerBlock.placeParts(helper.getLevel(), helper.absolutePos(MASTER), Direction.SOUTH);
        if (!(helper.getBlockEntity(MASTER) instanceof SmartLoggerBlockEntity logger)) {
            throw new IllegalStateException("no smart logger block entity was created");
        }
        return logger;
    }

    private static void click(SmartLoggerBlockEntity logger, Player player, double u, double v) {
        Vec3 middle = logger.middle();
        double[] at = logger.frame().world(middle.x, middle.z, u, v);
        logger.use(player, new BlockHitResult(new Vec3(at[0], logger.getBlockPos().getY() + 1, at[1]), Direction.UP,
                logger.getBlockPos(), false));
    }

    private static void clickDock(SmartLoggerBlockEntity logger, Player player) {
        click(logger, player, LoggerDisplay.Button.centreU(), LoggerDisplay.DOCK_CENTRE_V);
    }

    /** A right-click right on a reading's dot on the logger's map. */
    private static void clickReading(SmartLoggerBlockEntity logger, Player player, ReaderReading reading) {
        double[] at = LoggerDisplay.toScreen(logger.view(), logger.frame(), reading.x() + 0.5, reading.z() + 0.5);
        click(logger, player, at[0], at[1]);
    }

    private PrdGameTests() {
    }
}
