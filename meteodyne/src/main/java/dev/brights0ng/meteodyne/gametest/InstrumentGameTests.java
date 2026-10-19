// Written by ChatGPT.
package dev.brights0ng.meteodyne.gametest;

import java.util.List;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.climate.MeteodyneTemperature;
import dev.brights0ng.meteodyne.heat.world.HeatWorld;
import dev.brights0ng.meteodyne.instrument.*;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Instrument integration: real world sampling, item serialization, redstone propagation, calibration, and room heat. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class InstrumentGameTests {
    private static final String TEMPLATE = GameTestStructures.EMPTY;
    private static final BlockPos SENSOR = new BlockPos(3, 2, 3);

    private static TemperatureSensorBlockEntity sensor(GameTestHelper helper) {
        helper.setBlock(SENSOR.below(), Blocks.STONE);
        helper.setBlock(SENSOR, InstrumentContent.TEMPERATURE_SENSOR.get());
        var sensor = (TemperatureSensorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(SENSOR));
        sensor.refresh();
        return sensor;
    }

    @GameTest(template = TEMPLATE, batch = "instrument_reequip")
    public static void readingUpdatesDoNotReequipOrInterruptMining(GameTestHelper helper) {
        var thermometer = InstrumentContent.THERMOMETER.get();
        ItemStack before = new ItemStack(thermometer);
        InstrumentReading oldReading = new InstrumentReading("minecraft:overworld", 100, 10);
        InstrumentReading newReading = new InstrumentReading("minecraft:overworld", 120, 11);
        before.set(InstrumentContent.READING.get(), oldReading);
        ItemStack after = before.copy();
        after.set(InstrumentContent.READING.get(), newReading);
        helper.assertTrue(!thermometer.shouldCauseReequipAnimation(before, after, false), "measurement sync leaves the held item raised");
        helper.assertTrue(!thermometer.shouldCauseBlockBreakReset(before, after), "measurement sync does not restart mining");
        helper.assertTrue(oldReading.equals(before.get(InstrumentContent.READING.get()))
                && newReading.equals(after.get(InstrumentContent.READING.get())), "animation comparison never mutates either live stack");
        after.remove(InstrumentContent.READING.get());
        helper.assertTrue(!thermometer.shouldCauseReequipAnimation(before, after, false), "losing the reading does not lower the item either");
        helper.assertTrue(thermometer.shouldCauseReequipAnimation(before, after, true), "switching hotbar slots still reequips");
        after.set(DataComponents.CUSTOM_NAME, Component.literal("Cabin thermometer"));
        helper.assertTrue(thermometer.shouldCauseReequipAnimation(before, after, false), "renaming still counts as a real stack change");
        helper.assertTrue(thermometer.shouldCauseBlockBreakReset(before, after), "other component changes still reset mining");
        helper.assertTrue(thermometer.shouldCauseReequipAnimation(before, new ItemStack(net.minecraft.world.item.Items.CLOCK), false),
                "switching item types still reequips");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "instrument_sampling")
    public static void heldAndMountedThermometersReadTheSameAir(GameTestHelper helper) {
        var sensor = sensor(helper);
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 at = sensor.samplePoint();
        player.setPos(at.add(0, -0.9, 0));
        ItemStack stack = new ItemStack(InstrumentContent.THERMOMETER.get());
        InstrumentContent.THERMOMETER.get().inventoryTick(stack, helper.getLevel(), player, 0, true);
        InstrumentReading reading = stack.get(InstrumentContent.READING.get());
        double expected = MeteodyneTemperature.INSTANCE.air(helper.getLevel(), BlockPos.containing(at))
                + MeteodyneTemperature.INSTANCE.room(helper.getLevel(), at);
        helper.assertTrue(reading != null && Math.abs(reading.value() - expected) < 0.001, "held reading agrees with local air and room");
        helper.assertTrue(Math.abs(sensor.reading() - expected) < 0.001, "mounted reading agrees with the same location");
        helper.assertTrue(reading.usable(helper.getLevel().dimension().location().toString(), helper.getLevel().getGameTime()), "fresh server reading");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "instrument_serialization")
    public static void readingsSurviveSavingAndNetworkSynchronization(GameTestHelper helper) {
        InstrumentReading reading = new InstrumentReading("minecraft:overworld", 123456789, -12.5F);
        var json = InstrumentReadingCodecs.CODEC.encodeStart(JsonOps.INSTANCE, reading).getOrThrow();
        helper.assertTrue(reading.equals(InstrumentReadingCodecs.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow()), "reading codec round trip");
        var buffer = Unpooled.buffer();
        try {
            InstrumentReadingCodecs.STREAM_CODEC.encode(buffer, reading);
            helper.assertTrue(reading.equals(InstrumentReadingCodecs.STREAM_CODEC.decode(buffer)), "reading network round trip");
            helper.assertTrue(buffer.readableBytes() == 0, "network decoder consumed exactly the reading");
        } finally { buffer.release(); }
        ItemStack stack = new ItemStack(InstrumentContent.THERMOMETER.get());
        stack.set(InstrumentContent.READING.get(), reading);
        var saved = stack.save(helper.getLevel().registryAccess());
        ItemStack restored = ItemStack.parse(helper.getLevel().registryAccess(), saved).orElseThrow();
        helper.assertTrue(reading.equals(restored.get(InstrumentContent.READING.get())), "the actual item's component persists too");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "instrument_configuration")
    public static void calibrationPersistsAndRejectsInvalidSettings(GameTestHelper helper) {
        var sensor = sensor(helper);
        helper.assertTrue(sensor.configure(300, -100), "reversed endpoints accepted");
        var saved = sensor.saveWithFullMetadata(helper.getLevel().registryAccess());
        var restored = (TemperatureSensorBlockEntity) BlockEntity.loadStatic(sensor.getBlockPos(), sensor.getBlockState(), saved, helper.getLevel().registryAccess());
        helper.assertTrue(restored != null && restored.range().equals(new SensorRange(30, -10)), "reversed calibration persists");
        helper.assertTrue(!sensor.configure(50, 50), "equal endpoints rejected");
        helper.assertTrue(!sensor.configure(-1001, 100), "out-of-range endpoints rejected");
        helper.assertTrue(sensor.range().equals(new SensorRange(30, -10)), "invalid requests preserve the current setting");
        helper.assertTrue(!Double.isFinite(restored.reading()), "a disk load never reuses an old temperature");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "instrument_redstone", timeoutTicks = 80)
    public static void outputReachesDustAndUpdatesWhenTheRangeChanges(GameTestHelper helper) {
        var sensor = sensor(helper);
        BlockPos dust = SENSOR.east();
        helper.setBlock(dust.below(), Blocks.STONE);
        helper.setBlock(dust, Blocks.REDSTONE_WIRE);
        int temperature = (int) Math.round(sensor.reading() * 10);
        helper.assertTrue(sensor.configure(temperature - 20, temperature - 10), "hot range configured");
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(helper.getBlockState(dust).getValue(RedStoneWireBlock.POWER) == 15, "full output reaches adjacent dust");
            sensor.configure(temperature + 20, temperature + 10);
            helper.assertTrue(sensor.getBlockState().getValue(TemperatureSensorBlock.POWER) == 15, "inverted cold range is full output too");
            sensor.configure(temperature + 10, temperature + 20);
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(helper.getBlockState(dust).getValue(RedStoneWireBlock.POWER) == 0, "recalibration removes power from dust");
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, batch = "instrument_mounting")
    public static void allMountsHaveTheirOwnMatchingPickingShape(GameTestHelper helper) {
        var block = InstrumentContent.TEMPERATURE_SENSOR.get();
        for (AttachFace face : AttachFace.values()) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                var state = block.defaultBlockState().setValue(TemperatureSensorBlock.FACE, face).setValue(TemperatureSensorBlock.FACING, direction);
                var shape = state.getShape(helper.getLevel(), helper.absolutePos(SENSOR));
                helper.assertTrue(!shape.isEmpty() && shape.bounds().minX >= 0 && shape.bounds().maxX <= 1
                        && shape.bounds().minY >= 0 && shape.bounds().maxY <= 1 && shape.bounds().minZ >= 0 && shape.bounds().maxZ <= 1,
                        "picking stays inside its block for " + face + " / " + direction);
                helper.assertTrue(shape.equals(SensorShape.shape(face, direction)) || shape.toAabbs().equals(SensorShape.shape(face, direction).toAabbs()),
                        "picking comes from the same boxes as the generated model");
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "instrument_dimensions")
    public static void unsupportedDimensionsGiveNoReadingOrPower(GameTestHelper helper) {
        var nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null && !Double.isFinite(InstrumentReadings.temperature(nether, new Vec3(0, 100, 0))), "no invented Nether temperature");
        helper.assertTrue(new SensorRange(30, 0).signal(Double.NaN) == 0, "unavailable inverted sensor has zero output");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "instrument_heated_room", timeoutTicks = 200)
    public static void aThermometerReadsRoomAirWithoutRadiantHeat(GameTestHelper helper) {
        HeatWorld heat = HeatWorld.of(helper.getLevel());
        BlockPos a = helper.absolutePos(BlockPos.ZERO), b = helper.absolutePos(new BlockPos(6, 6, 6));
        int[] area = {a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()};
        heat.setPaused(true);
        heat.setTestArea(area);
        heat.setExtraAnchors(List.of());
        heat.clearSaved(area);
        heat.forgetRegions();
        try {
            for (int x = 0; x <= 6; x++) {
                for (int y = 0; y <= 4; y++) {
                    for (int z = 0; z <= 6; z++) {
                        boolean wall = x == 0 || x == 6 || y == 0 || y == 4 || z == 0 || z == 6;
                        helper.setBlock(new BlockPos(x, y, z), wall ? Blocks.STONE : Blocks.AIR);
                    }
                }
            }
            helper.setBlock(new BlockPos(3, 1, 2), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
            var sensor = sensor(helper);
            heat.runNow(600);
            sensor.refresh();
            Vec3 at = sensor.samplePoint();
            double outside = MeteodyneTemperature.INSTANCE.air(helper.getLevel(), BlockPos.containing(at));
            double room = MeteodyneTemperature.INSTANCE.room(helper.getLevel(), at);
            helper.assertTrue(room > 1, "campfire warms room air: " + room + "; regions: " + heat.regions().size());
            helper.assertTrue(Math.abs(sensor.reading() - outside - room) < 0.001, "thermometer includes exactly room air, without radiant/personal effects");
        } finally {
            heat.forgetRegions();
            heat.clearSaved(area);
            heat.setTestArea(null);
            heat.setExtraAnchors(List.of());
            heat.setPaused(false);
        }
        helper.succeed();
    }

    private InstrumentGameTests() { }
}
