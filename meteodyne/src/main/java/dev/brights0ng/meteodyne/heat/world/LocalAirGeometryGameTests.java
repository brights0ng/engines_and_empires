// Written by ChatGPT.
// Updated by Claude: checked Bright's snow buckets (1-3 layers air, 4-7 half block, 8 full).
package dev.brights0ng.meteodyne.heat.world;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real block-state controls; capture remains disconnected from live thermal regions. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirGeometryGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_capture")
    public static void snowSlabsDoorsStairsAndFluidsCaptureWithoutInventedExterior(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(3,3,3));
        helper.assertTrue(LocalAirGeometryCapture.blockKey(pos.getX(),pos.getY(),pos.getZ())==pos.asLong(),"Worker-safe coordinate key matches actual BlockPos packing");
        var old=level.getBlockState(pos);var above=level.getBlockState(pos.above());
        try {
            level.setBlock(pos.above(),Blocks.AIR.defaultBlockState(),2);
            var snow=Blocks.SNOW.defaultBlockState();
            var states=java.util.List.of(Blocks.AIR.defaultBlockState(),snow,snow.setValue(SnowLayerBlock.LAYERS,3),
                    snow.setValue(SnowLayerBlock.LAYERS,4),snow.setValue(SnowLayerBlock.LAYERS,7),snow.setValue(SnowLayerBlock.LAYERS,8),
                    Blocks.OAK_SLAB.defaultBlockState(),Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true),
                    Blocks.OAK_DOOR.defaultBlockState(),Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN,true),
                    Blocks.OAK_STAIRS.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.LAVA.defaultBlockState());
            for(var state:states) {
                level.setBlock(pos,state,2);
                var source=new LocalAirWorldSource(level,()->1);
                var c=new LocalAirGeometryCapture(new LocalAirGeometryCapture.Bounds(pos.getX(),pos.getY(),pos.getZ(),1,1,1),1,source);
                c.advance(1,Long.MAX_VALUE);
                helper.assertTrue(c.status()==LocalAirGeometryCapture.Status.CAPTURED,"Failed capture: "+state+" "+c.reason());
                var p=c.snapshot().bakeClosedControl(ThermalGeometry.Coefficients.legacyMaterials());
                helper.assertTrue(p.flow().faces()==0,"Diagnostic outer boundary must stay closed");
                if(state.is(Blocks.SNOW)) {
                    int layers=state.getValue(SnowLayerBlock.LAYERS);double volume=layers<=3?0:layers<8?.5:1;
                    helper.assertTrue(p.flow().solidVolume(0)==volume,"Snow of "+layers+" layers occupies "+volume+" of the block, not "+p.flow().solidVolume(0));
                }
                if(!state.getFluidState().isEmpty()) helper.assertTrue(p.thermal().fluidState(0)>=0,"Missing independent fluid temperature");
                if(state.is(Blocks.WATER)) {
                    var dependencies=new LocalAirDependencies(c.snapshot().bounds);
                    var tracked=new LocalAirWorldSource(level,dependencies::get,dependencies);
                    tracked.sample(pos.getX(),pos.getY(),pos.getZ());
                    helper.assertTrue(dependencies.affectsBlock(pos.getX(),pos.getY()+1,pos.getZ()),
                            "Fluid-height context read above the domain is a dependency");
                    dependencies.blockChanged(pos.getX(),pos.getY()+1,pos.getZ());
                    helper.assertTrue(dependencies.get()==1,"External shape context invalidates the tracked source");
                    var beforeHeight=tracked.sample(pos.getX(),pos.getY(),pos.getZ()).shape();var past=tracked.freeze();
                    tracked.remember(pos.above(),level.getBlockState(pos.above()));
                    level.setBlock(pos.above(),Blocks.WATER.defaultBlockState(),2);
                    helper.assertTrue(past.world().sample(pos.getX(),pos.getY(),pos.getZ()).shape().equals(beforeHeight),
                            "Historical fluid geometry reads the old above-block context");
                    helper.assertTrue(!tracked.sample(pos.getX(),pos.getY(),pos.getZ()).shape().equals(beforeHeight),
                            "Current fluid geometry sees the newly full fluid height");
                    past.close();helper.assertTrue(!tracked.hasHistory(),"Historical read retention is released");
                    var bounded=tracked.freeze();boolean admitted=true;
                    for(int i=0;i<=256;i++) admitted=tracked.remember(pos.offset(i+1,0,0),Blocks.AIR.defaultBlockState());
                    helper.assertTrue(!admitted && bounded.world().geometryRevision()==Long.MIN_VALUE,"Excess historical context invalidates instead of dropping old states");
                    bounded.close();
                    level.setBlock(pos.above(),Blocks.AIR.defaultBlockState(),2);
                }
                if(state.is(Blocks.OAK_SLAB) && !state.getFluidState().isEmpty()) {
                    helper.assertTrue(p.flow().solidVolume(0)==.5 && p.flow().fluidVolume(0)>0,"Waterlogged slab lost a phase");
                    var captured=source.sample(pos.getX(),pos.getY(),pos.getZ());
                    helper.assertTrue(captured.fluidCapacity()==HeatTables.insulation(Blocks.WATER).capacity(),"Wrong fluid material table");
                }
            }
            level.setBlock(pos,Blocks.OAK_FENCE.defaultBlockState(),2);
            var source=new LocalAirWorldSource(level,()->1);
            var fence=new LocalAirGeometryCapture(new LocalAirGeometryCapture.Bounds(pos.getX(),pos.getY(),pos.getZ(),1,1,1),1,source);
            fence.advance(1,Long.MAX_VALUE);
            helper.assertTrue(fence.status()==LocalAirGeometryCapture.Status.UNSUPPORTED_GEOMETRY,"Overhanging collider must reject admission");
        } finally { level.setBlock(pos,old,2);level.setBlock(pos.above(),above,2); }
        helper.succeed();
    }

    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_capture")
    public static void missingChunkDoesNotLoadOrBecomeAir(GameTestHelper helper) {
        var level=helper.getLevel();int cx=1_000_000,cz=1_000_000;
        helper.assertTrue(level.getChunkSource().getChunkNow(cx,cz)==null,"Fixture requires an unloaded remote chunk");
        var c=new LocalAirGeometryCapture(new LocalAirGeometryCapture.Bounds(cx*16,level.getMinBuildHeight()+20,cz*16,1,1,1),1,new LocalAirWorldSource(level,()->1));
        c.advance(1,Long.MAX_VALUE);
        helper.assertTrue(c.status()==LocalAirGeometryCapture.Status.MISSING_GEOMETRY,"Missing chunk became empty air");
        helper.assertTrue(level.getChunkSource().getChunkNow(cx,cz)==null,"Capture loaded a chunk");
        helper.succeed();
    }

    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_capture")
    public static void roofAboveCapturedVolumeRejectsOutdoorAdmission(GameTestHelper helper) {
        var level=helper.getLevel();var pos=helper.absolutePos(new BlockPos(3,3,3));
        var old=level.getBlockState(pos);var air=level.getBlockState(pos.above());var roof=level.getBlockState(pos.above(2));
        try {
            level.setBlock(pos,Blocks.STONE.defaultBlockState(),2);
            level.setBlock(pos.above(),Blocks.AIR.defaultBlockState(),2);
            level.setBlock(pos.above(2),Blocks.STONE.defaultBlockState(),2);
            var dependencies=new LocalAirDependencies(new LocalAirGeometryCapture.Bounds(pos.getX(),pos.getY(),pos.getZ(),1,2,1));
            var source=new LocalAirWorldSource(level,dependencies::get,dependencies);
            var c=new LocalAirGeometryCapture(new LocalAirGeometryCapture.Bounds(pos.getX(),pos.getY(),pos.getZ(),1,2,1),2,source);
            c.advance(2,Long.MAX_VALUE);
            helper.assertTrue(c.status()==LocalAirGeometryCapture.Status.CAPTURED,"Capture should complete below the roof");
            var coverage=new LocalAirCoverage(LocalAirCoverage.plan(c.snapshot()),source,10_000);
            coverage.advance(10_000,Long.MAX_VALUE);
            helper.assertTrue(coverage.status()==LocalAirCoverage.Status.INSUFFICIENT_COVERAGE,"Cropped roofed passage became outdoors");
            helper.assertTrue(dependencies.affectsBlock(pos.getX(),pos.getY()+2,pos.getZ()),"Rejected sky obstruction remains a dependency");
            dependencies.blockChanged(pos.getX(),pos.getY()+2,pos.getZ());
            helper.assertTrue(dependencies.get()==1,"Removing a failed coverage obstruction permits retry");
        } finally {
            level.setBlock(pos,old,2);level.setBlock(pos.above(),air,2);level.setBlock(pos.above(2),roof,2);
        }
        helper.succeed();
    }
}
