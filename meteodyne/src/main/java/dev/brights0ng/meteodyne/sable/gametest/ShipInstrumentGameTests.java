// Written by ChatGPT.
package dev.brights0ng.meteodyne.sable.gametest;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.climate.Climate;
import dev.brights0ng.meteodyne.instrument.InstrumentContent;
import dev.brights0ng.meteodyne.instrument.InstrumentReadings;
import dev.brights0ng.meteodyne.instrument.TemperatureSensorBlockEntity;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Registered only with the optional ship integration: the sensor follows the ship's actual world altitude. */
@PrefixGameTestTemplate(false)
public final class ShipInstrumentGameTests {
    @GameTest(templateNamespace = Meteodyne.MODID, template = GameTestStructures.EMPTY, batch = "instrument_ship")
    public static void mountedThermometerSamplesTheShipInWorldCoordinates(GameTestHelper helper) {
        var level = helper.getLevel();
        var container = SubLevelContainer.getContainer(level);
        var pose = new Pose3d();
        BlockPos test = helper.absolutePos(new BlockPos(3, 0, 3));
        pose.position().set(test.getX() + 0.5, level.getSeaLevel() + 80, test.getZ() + 0.5);
        var ship = container.allocateNewSubLevel(pose);
        var plot = ship.getPlot();
        try {
            plot.newEmptyChunk(plot.getCenterChunk());
            var blocks = plot.getEmbeddedLevelAccessor();
            blocks.setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
            blocks.setBlock(BlockPos.ZERO.above(), InstrumentContent.TEMPERATURE_SENSOR.get().defaultBlockState(), 3);
            ship.updateLastPose();
            BlockPos sensorPosition = plot.getCenterBlock().above();
            var sensor = (TemperatureSensorBlockEntity) level.getBlockEntity(sensorPosition);
            helper.assertTrue(sensor != null, "sensor exists in the plot");
            for (int step = 0; step < 2; step++) {
                var world = InstrumentReadings.worldPoint(level, sensor.samplePoint());
                helper.assertTrue(world != null && Math.abs(world.x - sensorPosition.getX()) > 1000,
                        "physical world position is distinct from the storage plot");
                sensor.refresh();
                double expected = Climate.sample(level, world.x, world.y, world.z).temperature();
                helper.assertTrue(Math.abs(sensor.reading() - expected) < 0.001, "sensor samples actual world altitude, step " + step);
                ship.logicalPose().position().add(0, 80, 0);
                ship.updateLastPose();
            }
        } finally {
            var gridOrigin = container.getOrigin();
            container.removeSubLevel(plot.plotPos.x - gridOrigin.x, plot.plotPos.z - gridOrigin.y, SubLevelRemovalReason.REMOVED);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = Meteodyne.MODID, template = GameTestStructures.EMPTY, batch = "instrument_ship")
    public static void mountedWindGaugeUsesPhysicalAltitudeInsteadOfPlotHeight(GameTestHelper helper) {
        var level = helper.getLevel();
        var container = SubLevelContainer.getContainer(level);
        var pose = new Pose3d();
        BlockPos test = helper.absolutePos(new BlockPos(3, 0, 3));
        pose.position().set(test.getX() + 0.5, level.getSeaLevel() + 10, test.getZ() + 0.5);
        var ship = container.allocateNewSubLevel(pose);
        var plot = ship.getPlot();
        var previous = dev.brights0ng.meteodyne.wind.WindSources.override();
        try {
            dev.brights0ng.meteodyne.wind.WindSources.setOverride(new dev.brights0ng.paradynelib.wind.WindColumn(0, 2, 12, 0));
            plot.newEmptyChunk(plot.getCenterChunk());
            var blocks = plot.getEmbeddedLevelAccessor();
            blocks.setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
            blocks.setBlock(BlockPos.ZERO.above(), InstrumentContent.WIND_SENSOR.get().defaultBlockState(), 3);
            ship.updateLastPose();
            BlockPos position = plot.getCenterBlock().above();
            var sensor = (dev.brights0ng.meteodyne.instrument.WindSensorBlockEntity) level.getBlockEntity(position);
            helper.assertTrue(sensor != null, "wind sensor exists in the plot");
            double lower = Double.NaN;
            for (int step = 0; step < 2; step++) {
                Vec3 world = InstrumentReadings.worldPoint(level, sensor.samplePoint());
                var expected = InstrumentReadings.wind(level, world, position);
                sensor.refresh();
                helper.assertTrue(expected != null && Math.abs(sensor.reading() - expected.speed()) < 0.001,
                        "ship wind agrees with physical world position, step " + step);
                helper.assertTrue(Double.isFinite(sensor.angle(1)), "ship dial has a valid wind direction");
                if (step == 0) { lower = sensor.reading(); }
                else { helper.assertTrue(Math.abs(lower - sensor.reading()) > 1, "moving upward changes the surface/aloft blend"); }
                ship.logicalPose().position().add(0, 300, 0);
                ship.updateLastPose();
            }
        } finally {
            dev.brights0ng.meteodyne.wind.WindSources.setOverride(previous);
            var origin = container.getOrigin();
            container.removeSubLevel(plot.plotPos.x - origin.x, plot.plotPos.z - origin.y, SubLevelRemovalReason.REMOVED);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = Meteodyne.MODID, template = GameTestStructures.EMPTY, batch = "instrument_ship_cover", timeoutTicks = 80)
    public static void shipWallsAndRoofsShelterMountedAndHeldInstruments(GameTestHelper helper) {
        var level = helper.getLevel();
        var container = SubLevelContainer.getContainer(level);
        var pose = new Pose3d();
        BlockPos test = helper.absolutePos(new BlockPos(3,0,3));
        pose.position().set(test.getX()+0.5,level.getSeaLevel()+80,test.getZ()+0.5);
        var ship = container.allocateNewSubLevel(pose);
        var plot = ship.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        var blocks = plot.getEmbeddedLevelAccessor();
        blocks.setBlock(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),3);
        blocks.setBlock(BlockPos.ZERO.above(),InstrumentContent.WIND_SENSOR.get().defaultBlockState(),3);
        for (int z=-1; z<=1; z++) {
            for (int x=-1; x<=1; x++) { blocks.setBlock(new BlockPos(x,3,z),Blocks.STONE.defaultBlockState(),3); }
            for (int y=1; y<=2; y++) { blocks.setBlock(new BlockPos(-1,y,z),Blocks.STONE.defaultBlockState(),3); }
        }
        ship.updateLastPose();
        helper.runAfterDelay(3, () -> {
            var previous = dev.brights0ng.meteodyne.wind.WindSources.override();
            try {
                dev.brights0ng.meteodyne.wind.WindSources.setOverride(dev.brights0ng.paradynelib.wind.WindColumn.uniform(10,0));
                var pos=plot.getCenterBlock().above();
                var sensor=(dev.brights0ng.meteodyne.instrument.WindSensorBlockEntity)level.getBlockEntity(pos);
                helper.assertTrue(sensor!=null,"mounted gauge exists in the cabin");
                sensor.refresh();
                helper.assertTrue(sensor.reading()==0,"a ship's upwind wall stops the mounted cups");
                Vec3 held=InstrumentReadings.worldPoint(level,pos.south().getCenter());
                var player=helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
                player.setPos(held.add(0,-0.9,0));
                var stack=new net.minecraft.world.item.ItemStack(InstrumentContent.WIND_GAUGE.get());
                InstrumentContent.WIND_GAUGE.get().inventoryTick(stack,level,player,0,true);
                var reading=stack.get(InstrumentContent.WIND_READING.get());
                helper.assertTrue(reading!=null && reading.calm(),"a held gauge is sheltered inside the ship too");
                for (int z=-1; z<=1; z++) {
                    for (int y=1; y<=2; y++) { blocks.setBlock(new BlockPos(-1,y,z),Blocks.AIR.defaultBlockState(),3); }
                }
                sensor.refresh();
                double roof=10*(1-dev.brights0ng.paradynelib.wind.WindConfig.params().roofWeight());
                helper.assertTrue(Math.abs(sensor.reading()-roof)<0.001,"only the ship roof remains: configured reduction");
                var under=InstrumentReadings.wind(level,InstrumentReadings.worldPoint(level,sensor.samplePoint()).add(0,-8,0));
                helper.assertTrue(under!=null && Math.abs(under.speed()-roof)<0.001,"a ship also shelters a world-space instrument below it");
                for (int z=-1; z<=1; z++) {
                    for (int x=-1; x<=1; x++) { blocks.setBlock(new BlockPos(x,3,z),Blocks.AIR.defaultBlockState(),3); }
                }
                sensor.refresh();
                helper.assertTrue(Math.abs(sensor.reading()-10)<0.001,"the open deck reads full wind without self-sheltering");
                ship.logicalPose().position().add(0,30,0);
                ship.updateLastPose();
                sensor.refresh();
                helper.assertTrue(Math.abs(sensor.reading()-10)<0.001,"moving the ship preserves the exact housing exclusion");
                helper.succeed();
            } finally {
                dev.brights0ng.meteodyne.wind.WindSources.setOverride(previous);
                var origin=container.getOrigin();
                container.removeSubLevel(plot.plotPos.x-origin.x,plot.plotPos.z-origin.y,SubLevelRemovalReason.REMOVED);
            }
        });
    }

    private ShipInstrumentGameTests() { }
}
