package dev.brights0ng.enginesandempires.gametest;

import com.mojang.serialization.JsonOps;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.LoggerDisplay;
import dev.brights0ng.enginesandempires.geophone.ReaderCodecs;
import dev.brights0ng.enginesandempires.geophone.ReaderReading;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import dev.brights0ng.enginesandempires.geophone.SmartLoggerBlock;
import dev.brights0ng.enginesandempires.geophone.SmartLoggerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-world tests for the smart logger: that the four quarters go up and come down together, that readings are only taken
 * down while it turns, and that its buttons, map and redstone do what they should.
 *
 * <p>Its speed is set on the block entity directly: the tests are about what it does with rotation, not about Create's
 * networks.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class SmartLoggerGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    /** The master, facing south: the desk runs east (right) and north (away) from it. */
    private static final BlockPos MASTER = new BlockPos(2, 1, 4);

    @GameTest(template = SCRATCH)
    public static void allFourQuartersGoUpTogether(GameTestHelper helper) {
        build(helper);
        for (SmartLoggerBlock.Part part : SmartLoggerBlock.Part.values()) {
            BlockPos at = relative(helper, part);
            helper.assertBlockProperty(at, SmartLoggerBlock.PART, part);
            if (!(helper.getBlockEntity(at) instanceof SmartLoggerBlockEntity be) || be.isMaster() != (part == SmartLoggerBlock.Part.FRONT_LEFT)) {
                helper.fail("wrong block entity at " + part);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void breakingAnyQuarterTakesDownTheDeskAndDropsItOnce(GameTestHelper helper) {
        build(helper);
        helper.getLevel().destroyBlock(helper.absolutePos(relative(helper, SmartLoggerBlock.Part.BACK_RIGHT)), true);
        helper.runAfterDelay(2, () -> {
            for (SmartLoggerBlock.Part part : SmartLoggerBlock.Part.values()) {
                helper.assertBlockNotPresent(SeismicContent.SMART_LOGGER.get(), relative(helper, part));
            }
            AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(8);
            int dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area,
                    item -> item.getItem().is(SeismicContent.SMART_LOGGER.get().asItem())).stream()
                    .mapToInt(item -> item.getItem().getCount()).sum();
            if (dropped != 1) {
                helper.fail("expected the desk to drop once, dropped " + dropped);
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = SCRATCH)
    public static void readingsAreOnlyTakenDownWhileTurning(GameTestHelper helper) {
        SmartLoggerBlockEntity logger = build(helper);
        // Let the kinetic network settle first, so it does not reset the speed set below.
        helper.runAfterDelay(5, () -> {
            long now = helper.getLevel().getGameTime();
            logger.setSpeed(0);
            logger.receive(now, reading(11));
            helper.runAfterDelay(2, () -> {
                if (logger.records().size() != 0) {
                    helper.fail("a reading was kept while not turning");
                    return;
                }
                logger.setSpeed(64);
                logger.receive(helper.getLevel().getGameTime() + 3, reading(12));
                helper.runAfterDelay(1, () -> {
                    if (logger.records().size() != 0) {
                        helper.fail("a reading was kept before it was due");
                        return;
                    }
                    helper.runAfterDelay(4, () -> {
                        if (logger.records().size() != 1 || logger.records().get(0).reading().deposit() != 12) {
                            helper.fail("expected the one reading to be kept, have " + logger.records().size());
                            return;
                        }
                        helper.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = SCRATCH)
    public static void powerButtonTurnsTheDisplayOnAndTheMapZooms(GameTestHelper helper) {
        SmartLoggerBlockEntity logger = build(helper);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        if (logger.displayOn()) {
            helper.fail("the display should start off");
            return;
        }
        // The map cannot be zoomed while off.
        LoggerDisplay.View home = logger.view();
        if (logger.setView(player, LoggerDisplay.zoomAbout(home, home.centreX() + 40, home.centreZ(), 1)) || logger.view().zoom() != 0) {
            helper.fail("zoomed while the display was off");
            return;
        }
        click(logger, player, LoggerDisplay.Button.centreU(), LoggerDisplay.Button.POWER.centreV());
        if (!logger.displayOn()) {
            helper.fail("the power button did not turn the display on");
            return;
        }
        // Pressed again straight away, a stone button is still down, so nothing happens.
        click(logger, player, LoggerDisplay.Button.centreU(), LoggerDisplay.Button.POWER.centreV());
        if (!logger.displayOn()) {
            helper.fail("a button that was still down was pressed again");
            return;
        }
        // A right-click on the map changes nothing on the server: its clicks are worked out on the client.
        click(logger, player, 0.3, 1.5);
        if (!logger.view().equals(home)) {
            helper.fail("a right-click on the map changed the view on the server");
            return;
        }
        // Zooming, dragging and recentring, as a client would ask for them.
        if (!logger.setView(player, LoggerDisplay.zoomAbout(home, home.centreX() + 40, home.centreZ(), 1)) || logger.view().zoom() != 1) {
            helper.fail("a zoom was refused with the display on");
            return;
        }
        logger.setView(player, LoggerDisplay.drag(logger.view(), 0, 0, 100, 0));
        if (Math.abs(logger.view().centreX() - (home.centreX() + 20 - 100)) > 1e-6) {
            helper.fail("the drag did not move the map: " + logger.view());
            return;
        }
        logger.setView(player, LoggerDisplay.recentre(logger.view(), logger.middle().x, logger.middle().z));
        if (logger.view().centreX() != logger.middle().x || logger.view().zoom() != 1) {
            helper.fail("recentring did not put the logger back in the middle at the same zoom");
            return;
        }
        if (logger.setView(player, new LoggerDisplay.View(Double.NaN, 0, 2))) {
            helper.fail("a nonsense view was taken");
            return;
        }
        helper.runAfterDelay(LoggerDisplay.BUTTON_PRESS_TICKS + 2, () -> {
            player.setShiftKeyDown(false);
            click(logger, player, LoggerDisplay.Button.centreU(), LoggerDisplay.Button.POWER.centreV());
            if (logger.displayOn()) {
                helper.fail("the power button did not turn the display off once it had come back up");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = SCRATCH)
    public static void redstoneAtAnyQuarterTurnsTheDisplayOn(GameTestHelper helper) {
        SmartLoggerBlockEntity logger = build(helper);
        helper.runAfterDelay(2, () -> {
            helper.setBlock(relative(helper, SmartLoggerBlock.Part.BACK_RIGHT).east(), Blocks.REDSTONE_BLOCK);
            helper.runAfterDelay(2, () -> {
                if (!logger.displayOn()) {
                    helper.fail("redstone at the back-right quarter did not turn the display on");
                    return;
                }
                helper.setBlock(relative(helper, SmartLoggerBlock.Part.BACK_RIGHT).east(), Blocks.AIR);
                helper.runAfterDelay(2, () -> {
                    if (logger.displayOn()) {
                        helper.fail("the display stayed on without redstone or the switch");
                        return;
                    }
                    helper.succeed();
                });
            });
        });
    }

    /** A reading's ore survives being saved (on a reader, in a logbook or logger), and one saved before loads without. */
    @GameTest(template = SCRATCH)
    public static void theOreIsSavedWithAReading(GameTestHelper helper) {
        ReaderReading reading = new ReaderReading("minecraft:overworld", 1, 2, 3, true, 9, 50,
                dev.brights0ng.enginesandempires.geophone.ReaderAccuracy.Confidence.PRECISE, "iron");
        var json = ReaderCodecs.READING.encodeStart(JsonOps.INSTANCE, reading).getOrThrow();
        if (!reading.equals(ReaderCodecs.READING.parse(JsonOps.INSTANCE, json).getOrThrow())) {
            helper.fail("the reading did not come back the same");
            return;
        }
        json.getAsJsonObject().remove("ore");
        if (ReaderCodecs.READING.parse(JsonOps.INSTANCE, json).getOrThrow().knowsOre()) {
            helper.fail("an old reading with no ore loaded with one");
            return;
        }
        io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        ReaderCodecs.READING_STREAM.encode(buffer, reading);
        if (!reading.equals(ReaderCodecs.READING_STREAM.decode(buffer))) {
            helper.fail("the reading did not survive the network");
            return;
        }
        helper.succeed();
    }

    // ---- helpers ----

    /** A stone floor, and the desk on it, facing south. */
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

    /** Where a quarter is, relative to the test (which is never rotated, so relative directions are the world's). */
    private static BlockPos relative(GameTestHelper helper, SmartLoggerBlock.Part part) {
        return SmartLoggerBlock.partPos(MASTER, Direction.SOUTH, part);
    }

    /** A click on the top of the desk at {u, v}, as a player standing at it would make. */
    private static void click(SmartLoggerBlockEntity logger, Player player, double u, double v) {
        Vec3 middle = logger.middle();
        double[] at = logger.frame().world(middle.x, middle.z, u, v);
        LoggerDisplay.Button button = LoggerDisplay.buttonAt(u, v);
        double y = logger.getBlockPos().getY() + (button != null ? LoggerDisplay.BAR_TOP + LoggerDisplay.BUTTON_HEIGHT
                : LoggerDisplay.SCREEN_Y);
        logger.use(player, new BlockHitResult(new Vec3(at[0], y, at[1]), Direction.UP, logger.getBlockPos(), false));
    }

    private static ReaderReading reading(long deposit) {
        return new ReaderReading("minecraft:overworld", 100, 40, 100, true, deposit, 1);
    }

    private SmartLoggerGameTests() {
    }
}
