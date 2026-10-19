// Written by ChatGPT.
package dev.brights0ng.meteodyne.gametest;

import com.mojang.serialization.JsonOps;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.instrument.*;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.wind.WindColumn;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class WindInstrumentGameTests {
    private static final String TEMPLATE = GameTestStructures.EMPTY;
    private static WindSensorBlockEntity sensor(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3,80,3);
        helper.setBlock(pos.below(), Blocks.STONE);
        helper.setBlock(pos, InstrumentContent.WIND_SENSOR.get());
        return (WindSensorBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(pos));
    }
    @GameTest(template=TEMPLATE, batch="wind_instruments")
    public static void heldAndMountedGaugeReadTheSameWindAndUpdateDirectionAtConstantSpeed(GameTestHelper helper) {
        WindColumn previous = WindSources.override();
        try {
            WindSources.setOverride(WindColumn.uniform(3,4));
            var sensor = sensor(helper);
            sensor.refresh();
            var player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setPos(sensor.samplePoint().add(1,-0.9,1));
            var gauge = InstrumentContent.WIND_GAUGE.get();
            var stack = new ItemStack(gauge);
            gauge.inventoryTick(stack,helper.getLevel(),player,0,true);
            var reading=stack.get(InstrumentContent.WIND_READING.get());
            helper.assertTrue(reading!=null && reading.x()==3 && reading.z()==4, "held vector preserves travel direction");
            helper.assertTrue(Math.abs(sensor.reading()-5)<0.001, "mounted speed equals held speed: " + sensor.reading());
            helper.assertTrue(sensor.configure(0,100), "speed endpoints accepted");
            helper.assertTrue(sensor.getBlockState().getValue(SensorBlock.POWER)==8, "5 m/s maps halfway to redstone 15");
            double old=sensor.angle(1);
            WindSources.setOverride(WindColumn.uniform(-3,4));
            sensor.refresh();
            helper.assertTrue(Math.abs(WindReading.wrap(sensor.angle(1)-old))>1, "turning wind updates even at constant speed");
            WindSources.setOverride(WindColumn.CALM);
            sensor.refresh();
            helper.assertTrue(sensor.reading()==0 && !Double.isFinite(sensor.angle(1)), "calm has a real zero speed and no invented direction");
            helper.assertTrue(sensor.getBlockState().getValue(SensorBlock.POWER)==0, "calm removes power");
        } finally {
            WindSources.setOverride(previous);
            helper.setBlock(new BlockPos(3,80,3),Blocks.AIR);
            helper.setBlock(new BlockPos(3,79,3),Blocks.AIR);
        }
        helper.succeed();
    }
    @GameTest(template=TEMPLATE, batch="wind_instruments")
    public static void windCalibrationAndMeasurementSerializationPreserveUnitsAndFreshness(GameTestHelper helper) {
        var sensor=sensor(helper);
        helper.assertTrue(sensor.configure(300,0), "inverted wind calibration accepted");
        helper.assertTrue(!sensor.configure(-1,100) && !sensor.configure(0,1001) && !sensor.configure(10,10), "negative, excessive and equal endpoints rejected");
        var saved=sensor.saveWithFullMetadata(helper.getLevel().registryAccess());
        var restored=(WindSensorBlockEntity)BlockEntity.loadStatic(sensor.getBlockPos(),sensor.getBlockState(),saved,helper.getLevel().registryAccess());
        helper.assertTrue(restored!=null && restored.range().equals(new SensorRange(30,0)), "wind calibration survives saving");
        helper.assertTrue(!Double.isFinite(restored.reading()), "disk load cannot resurrect stale wind");
        var reading=new WindReading("minecraft:overworld",123456789,3,-4);
        var json=WindReadingCodecs.CODEC.encodeStart(JsonOps.INSTANCE,reading).getOrThrow();
        helper.assertTrue(reading.equals(WindReadingCodecs.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow()), "wind codec roundtrip");
        var buffer=Unpooled.buffer();
        try {
            WindReadingCodecs.STREAM_CODEC.encode(buffer,reading);
            helper.assertTrue(reading.equals(WindReadingCodecs.STREAM_CODEC.decode(buffer)) && buffer.readableBytes()==0,"network roundtrip");
        } finally { buffer.release(); }
        var stack=new ItemStack(InstrumentContent.WIND_GAUGE.get());
        stack.set(InstrumentContent.WIND_READING.get(),reading);
        var item=ItemStack.parse(helper.getLevel().registryAccess(),stack.save(helper.getLevel().registryAccess())).orElseThrow();
        helper.assertTrue(reading.equals(item.get(InstrumentContent.WIND_READING.get())), "item component persists");
        helper.setBlock(new BlockPos(3,80,3),Blocks.AIR);
        helper.setBlock(new BlockPos(3,79,3),Blocks.AIR);
        helper.succeed();
    }
    @GameTest(template=TEMPLATE, batch="wind_instruments")
    public static void windUpdatesDoNotBobAndUnsupportedDimensionsStayUnavailable(GameTestHelper helper) {
        var gauge=InstrumentContent.WIND_GAUGE.get();
        var before=new ItemStack(gauge);
        before.set(InstrumentContent.WIND_READING.get(),new WindReading("minecraft:overworld",100,1,2));
        var after=before.copy();
        after.set(InstrumentContent.WIND_READING.get(),new WindReading("minecraft:overworld",120,3,4));
        helper.assertTrue(!gauge.shouldCauseReequipAnimation(before,after,false) && !gauge.shouldCauseBlockBreakReset(before,after),"wind sync keeps item raised and mining uninterrupted");
        after.remove(InstrumentContent.WIND_READING.get());
        helper.assertTrue(!gauge.shouldCauseReequipAnimation(before,after,false),"lost reading does not bob");
        helper.assertTrue(gauge.shouldCauseReequipAnimation(before,after,true),"real slot change still reequips");
        after.set(DataComponents.CUSTOM_NAME,Component.literal("Bridge wind gauge"));
        helper.assertTrue(gauge.shouldCauseReequipAnimation(before,after,false),"other component changes still reequips");
        var nether=helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether!=null && InstrumentReadings.wind(nether,new Vec3(0,100,0))==null,"no invented Nether wind");
        helper.succeed();
    }
    @GameTest(template=TEMPLATE, batch="wind_instruments")
    public static void windMountsIncludeTheRotorClearanceInPicking(GameTestHelper helper) {
        var block=InstrumentContent.WIND_SENSOR.get();
        for (AttachFace face:AttachFace.values()) {
            for (Direction facing:Direction.Plane.HORIZONTAL) {
                var state=block.defaultBlockState().setValue(SensorBlock.FACE,face).setValue(SensorBlock.FACING,facing);
                var actual=state.getShape(helper.getLevel(),helper.absolutePos(new BlockPos(3,80,3)));
                var expected=WindSensorShape.shape(face,facing);
                helper.assertTrue(actual.toAabbs().equals(expected.toAabbs()),"model and rotor picking share geometry");
                helper.assertTrue(actual.bounds().minY>=0 && actual.bounds().maxY<=1,"rotor stays inside each mount");
            }
        }
        helper.succeed();
    }
    @GameTest(template=TEMPLATE, batch="wind_instrument_cover")
    public static void gaugesAndSensorsShareDirectionalWallsRoofCoverAndRecovery(GameTestHelper helper) {
        WindColumn previous = WindSources.override();
        BlockPos pos = new BlockPos(3,80,3);
        try {
            WindSources.setOverride(WindColumn.uniform(10,0));
            var sensor = sensor(helper);
            sensor.refresh();
            helper.assertTrue(Math.abs(sensor.reading()-10)<0.001, "a sensor does not shelter behind its own housing");
            helper.setBlock(pos.above(2), Blocks.STONE);
            sensor.refresh();
            double roof = 10 * (1-dev.brights0ng.paradynelib.wind.WindConfig.params().roofWeight());
            helper.assertTrue(Math.abs(sensor.reading()-roof)<0.001, "a roof reduces speed by the existing configured weight");
            var player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setPos(sensor.samplePoint().add(0,-0.9,0));
            var gauge = InstrumentContent.WIND_GAUGE.get();
            // Remove the housing before putting the mock player at exactly the same measuring point.
            helper.setBlock(pos, Blocks.AIR);
            var stack = new ItemStack(gauge);
            gauge.inventoryTick(stack,helper.getLevel(),player,0,true);
            var reading=stack.get(InstrumentContent.WIND_READING.get());
            helper.assertTrue(reading!=null && Math.abs(reading.speed()-roof)<0.001, "held cups respond to the same roof as mounted cups");
            helper.setBlock(pos.above(2), Blocks.AIR);
            sensor=sensor(helper);
            helper.setBlock(pos.west(), Blocks.STONE);
            sensor.refresh();
            helper.assertTrue(sensor.reading()==0 && !Double.isFinite(sensor.angle(1)), "upwind cover stops the cups and hides the direction");
            helper.assertTrue(sensor.getBlockState().getValue(SensorBlock.POWER)==0,"shelter also removes redstone output");
            helper.setBlock(pos,Blocks.AIR);
            stack.remove(InstrumentContent.WIND_READING.get());
            gauge.inventoryTick(stack,helper.getLevel(),player,0,true);
            reading=stack.get(InstrumentContent.WIND_READING.get());
            helper.assertTrue(reading!=null && reading.calm(),"sheltered held gauge has a valid calm reading, not unavailable weather");
            sensor=sensor(helper);
            helper.setBlock(pos.west(), Blocks.AIR);
            helper.setBlock(pos.east(), Blocks.STONE);
            sensor.refresh();
            helper.assertTrue(Math.abs(sensor.reading()-10)<0.001,"downwind walls do not cut the wind");
            helper.setBlock(pos.east(), Blocks.AIR);
            sensor.refresh();
            helper.assertTrue(Math.abs(sensor.reading()-10)<0.001,"uncovering restores wind on the next sample");
            WindSources.setOverride(WindColumn.uniform(10,10));
            helper.setBlock(pos.west(), Blocks.STONE);
            sensor.refresh();
            helper.assertTrue(Math.abs(sensor.reading()-Math.hypot(5,5))<0.001,"one of two upwind faces gives proportional shelter");
        } finally {
            WindSources.setOverride(previous);
            helper.setBlock(pos.west(),Blocks.AIR);
            helper.setBlock(pos.east(),Blocks.AIR);
            helper.setBlock(pos.above(2),Blocks.AIR);
            helper.setBlock(pos,Blocks.AIR);
            helper.setBlock(pos.below(),Blocks.AIR);
        }
        helper.succeed();
    }
    @GameTest(template=TEMPLATE, batch="wind_instrument_mount_cover")
    public static void everyMountExcludesOnlyItsOwnHousing(GameTestHelper helper) {
        WindColumn previous=WindSources.override();
        BlockPos pos=new BlockPos(3,80,3);
        try {
            WindSources.setOverride(WindColumn.uniform(10,0));
            for (AttachFace face:AttachFace.values()) {
                for (Direction facing:Direction.Plane.HORIZONTAL) {
                    helper.setBlock(pos,Blocks.AIR);
                    helper.setBlock(pos,InstrumentContent.WIND_SENSOR.get().defaultBlockState().setValue(SensorBlock.FACE,face).setValue(SensorBlock.FACING,facing));
                    var sensor=(WindSensorBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(pos));
                    sensor.refresh();
                    helper.assertTrue(Math.abs(sensor.reading()-10)<0.001,"own housing excluded: "+face+"/"+facing);
                    helper.setBlock(pos.west(),Blocks.GLASS);
                    sensor.refresh();
                    helper.assertTrue(sensor.reading()==0,"an adjacent collidable block still shelters: "+face+"/"+facing);
                    helper.setBlock(pos.west(),Blocks.AIR);
                }
            }
        } finally {
            WindSources.setOverride(previous);
            helper.setBlock(pos.west(),Blocks.AIR);
            helper.setBlock(pos,Blocks.AIR);
            helper.setBlock(pos.below(),Blocks.AIR);
        }
        helper.succeed();
    }
    private WindInstrumentGameTests() { }
}
