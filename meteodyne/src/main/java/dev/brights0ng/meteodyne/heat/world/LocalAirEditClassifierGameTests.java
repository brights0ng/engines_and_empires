// Written by Claude.
// Updated by Claude: checked read-input comparison on real block states.
// Updated by Claude: checked snow layers classify by bucket in a prepared domain.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import java.util.concurrent.locks.LockSupport;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.*;

/** Live-hook edit classification: state-only edits leave the owner alone, heat-only edits take the source path. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirEditClassifierGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_edit_classifier",timeoutTicks=2400)
    public static void stateOnlyEditsAreIgnoredCampfireTakesTheSourcePathAndShapesStillInvalidate(GameTestHelper helper) {
        // Let the shared asynchronous lane run on the unpaced headless server; no timing claim.
        helper.onEachTick(()->LockSupport.parkNanos(1_000_000L));
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);
        var low=helper.absolutePos(new BlockPos(1,1,1));var restore=new LinkedHashMap<BlockPos,BlockState>();
        Runnable cleanup=()->{world.clearLocalAirGeometry();restore.forEach((p,s)->level.setBlock(p,s,2));};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        var fence=low.offset(-1,2,3);restore.put(fence,level.getBlockState(fence));
        for(int z=0;z<6;z++) for(int y=0;y<6;y++) for(int x=0;x<6;x++) {
            var p=low.offset(x,y,z);restore.put(p,level.getBlockState(p));
            level.setBlock(p,(x==0 || y==0 || z==0 || x==5 || y==5 || z==5?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        var wheat=low.offset(1,2,1);var leaves=low.offset(4,1,4);var wire=low.offset(4,1,1);var fire=low.offset(2,1,2);var wall=low.offset(0,2,3);
        // Farmland's open top sliver must not touch the domain's bottom face.
        level.setBlock(wheat.below(),Blocks.FARMLAND.defaultBlockState(),2);
        level.setBlock(wheat,Blocks.WHEAT.defaultBlockState(),2);
        level.setBlock(leaves,Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,true),2);
        level.setBlock(wire,Blocks.REDSTONE_WIRE.defaultBlockState(),2);
        level.setBlock(fire,Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true),2);
        // Out-of-cell fence collision cannot be captured, so the fence stands just outside the glass wall cell.
        level.setBlock(wall,Blocks.GLASS.defaultBlockState(),2);
        level.setBlock(fence,Blocks.OAK_FENCE.defaultBlockState(),2);
        world.requestLocalAirSimulation(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),6,6,6),216,0,new LocalAirRun.Settings(20,0,0,4));
        long[] restarts={0},stages={0},ignored={0},sources={0},geometry={0};long[] steps={0};
        helper.startSequence()
                .thenWaitUntil(()->helper.assertTrue(world.localAirField()!=null && world.localAirField().acceptedSteps>=10,"Fixture publishes on the shared worker lane: "+world.localAirStatus()+" "+world.localAirRunReason()+" "+world.localAirReport().get("restartCauses")+" "+world.localAirReport().get("editOutcomes")))
                .thenExecute(()->{
                    restarts[0]=(long)world.localAirReport().get("restarts");stages[0]=(long)world.localAirReport().get("topologyStages");
                    ignored[0]=outcome(world,"IGNORED");
                    level.setBlock(wheat,level.getBlockState(wheat).setValue(BlockStateProperties.AGE_7,3),2);
                    level.setBlock(leaves,level.getBlockState(leaves).setValue(BlockStateProperties.DISTANCE,3),2);
                    level.setBlock(wire,level.getBlockState(wire).setValue(BlockStateProperties.POWER,9),2);
                    helper.assertTrue(world.localAirField()!=null,"State-only edits keep the published field");
                    helper.assertTrue(outcome(world,"IGNORED")-ignored[0]>=3,"Crop, leaf and redstone edits are ignored: "+world.localAirReport().get("editOutcomes"));
                })
                .thenIdle(20)
                .thenExecute(()->{
                    helper.assertTrue((long)world.localAirReport().get("restarts")==restarts[0],"Ignored edits never restart: "+world.localAirReport().get("restartCauses"));
                    helper.assertTrue((long)world.localAirReport().get("topologyStages")==stages[0],"Ignored edits never start a topology stage");
                    helper.assertTrue(world.localAirField()!=null,"The field keeps publishing after ignored edits");
                    sources[0]=outcome(world,"SOURCE");steps[0]=world.localAirField().acceptedSteps;
                    level.setBlock(fire,level.getBlockState(fire).setValue(BlockStateProperties.LIT,false),2);
                    helper.assertTrue(outcome(world,"SOURCE")==sources[0]+1,"A campfire LIT toggle takes the source path: "+world.localAirReport().get("editOutcomes"));
                })
                .thenWaitUntil(()->helper.assertTrue(world.localAirField()!=null && world.localAirField().acceptedSteps>steps[0],"The field keeps publishing after the source edit"))
                .thenExecute(()->{
                    helper.assertTrue((long)world.localAirReport().get("restarts")==restarts[0],"A source edit never restarts: "+world.localAirReport().get("restartCauses"));
                    geometry[0]=outcome(world,"GEOMETRY");
                    level.setBlock(wall,Blocks.STONE.defaultBlockState(),2);
                    helper.assertTrue(outcome(world,"GEOMETRY")>geometry[0],"A stone placed next to the fence is a geometry edit: "+world.localAirReport().get("editOutcomes"));
                }).thenSucceed();
    }
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_edit_classifier")
    public static void readInputsCompareConvertedShapeAndFluidState(GameTestHelper helper) {
        var wire=Blocks.REDSTONE_WIRE.defaultBlockState();var leaves=Blocks.OAK_LEAVES.defaultBlockState();
        var water=Blocks.WATER.defaultBlockState();
        helper.assertTrue(LocalAirWorldSource.readInputsSame(wire.setValue(BlockStateProperties.POWER,0),wire.setValue(BlockStateProperties.POWER,15)),"Wire power keeps shape and fluid");
        helper.assertTrue(LocalAirWorldSource.readInputsSame(leaves.setValue(BlockStateProperties.DISTANCE,1),leaves.setValue(BlockStateProperties.DISTANCE,2)),"Leaf distance keeps shape and fluid");
        helper.assertTrue(LocalAirWorldSource.readInputsSame(Blocks.FURNACE.defaultBlockState(),Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.LIT,true)),"Furnace lit keeps shape");
        helper.assertTrue(LocalAirWorldSource.readInputsSame(Blocks.STONE.defaultBlockState(),Blocks.DIRT.defaultBlockState()),"Different blocks with equal converted shapes compare equal");
        helper.assertFalse(LocalAirWorldSource.readInputsSame(Blocks.STONE.defaultBlockState(),Blocks.AIR.defaultBlockState()),"Solid to air changes the shape");
        helper.assertFalse(LocalAirWorldSource.readInputsSame(Blocks.STONE_SLAB.defaultBlockState(),Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,net.minecraft.world.level.block.state.properties.SlabType.TOP)),"Slab half changes the shape");
        helper.assertFalse(LocalAirWorldSource.readInputsSame(water,water.setValue(BlockStateProperties.LEVEL,1)),"Fluid amount changes the fluid state");
        helper.assertFalse(LocalAirWorldSource.readInputsSame(Blocks.AIR.defaultBlockState(),water),"Air to water changes the fluid state");
        helper.assertFalse(LocalAirWorldSource.readInputsSame(Blocks.MOVING_PISTON.defaultBlockState(),Blocks.MOVING_PISTON.defaultBlockState()),"Dynamic shapes never compare equal");
        helper.assertFalse(LocalAirWorldSource.readInputsSame(null,wire),"An unknown before state never compares equal");
        var snow=Blocks.SNOW.defaultBlockState();
        helper.assertTrue(LocalAirWorldSource.readInputsSame(snow,snow.setValue(SnowLayerBlock.LAYERS,3)) && LocalAirWorldSource.readInputsSame(snow,Blocks.AIR.defaultBlockState()),"Thin snow reads as air");
        helper.assertFalse(LocalAirWorldSource.readInputsSame(snow.setValue(SnowLayerBlock.LAYERS,3),snow.setValue(SnowLayerBlock.LAYERS,4)),"Four layers become a half block");
        helper.assertTrue(LocalAirWorldSource.readInputsSame(snow.setValue(SnowLayerBlock.LAYERS,5),Blocks.STONE_SLAB.defaultBlockState()),"Half-block snow reads like a bottom slab");
        helper.assertTrue(LocalAirWorldSource.readInputsSame(snow.setValue(SnowLayerBlock.LAYERS,4),snow.setValue(SnowLayerBlock.LAYERS,7)),"Four to seven layers share one bucket");
        helper.assertFalse(LocalAirWorldSource.readInputsSame(snow.setValue(SnowLayerBlock.LAYERS,7),snow.setValue(SnowLayerBlock.LAYERS,8)),"Eight layers fill the block");
        helper.succeed();
    }
    // Its own batch: the manual domain is one per world, and the fixture above holds it.
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_snow_buckets",timeoutTicks=2400)
    public static void snowLayersClassifyByBucketInAPreparedDomain(GameTestHelper helper) {
        helper.onEachTick(()->LockSupport.parkNanos(1_000_000L));
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);
        var low=helper.absolutePos(new BlockPos(1,1,1));var restore=new LinkedHashMap<BlockPos,BlockState>();
        Runnable cleanup=()->{world.clearLocalAirGeometry();restore.forEach((p,s)->level.setBlock(p,s,2));};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        for(int z=0;z<5;z++) for(int y=0;y<5;y++) for(int x=0;x<5;x++) {
            var p=low.offset(x,y,z);restore.put(p,level.getBlockState(p));
            level.setBlock(p,(x==0 || y==0 || z==0 || x==4 || y==4 || z==4?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        var snow=low.offset(2,1,2);
        java.util.function.IntConsumer layers=n->level.setBlock(snow,Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,n),2);
        layers.accept(1);
        world.requestLocalAirSimulation(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),5,5,5),125,0,new LocalAirRun.Settings(20,0,0,4));
        long[] restarts={0},ignored={0},changed={0};
        helper.startSequence()
                .thenWaitUntil(()->helper.assertTrue(world.localAirField()!=null,"Fixture publishes: "+world.localAirStatus()+" "+world.localAirRunReason()))
                .thenExecute(()->{
                    restarts[0]=(long)world.localAirReport().get("restarts");ignored[0]=outcome(world,"IGNORED");
                    layers.accept(2);layers.accept(3);
                    helper.assertTrue(outcome(world,"IGNORED")-ignored[0]==2,"Layers 1->2->3 stay air: "+world.localAirReport().get("editOutcomes"));
                    helper.assertTrue(world.localAirField()!=null,"Thin snow keeps the published field");
                    changed[0]=outcome(world,"RECAPTURE")+outcome(world,"GEOMETRY");
                    layers.accept(4);
                    helper.assertTrue(outcome(world,"RECAPTURE")+outcome(world,"GEOMETRY")==changed[0]+1,"Layers 3->4 become a half block: "+world.localAirReport().get("editOutcomes"));
                })
                .thenWaitUntil(()->helper.assertTrue(world.localAirField()!=null,"The half-block snow republishes: "+world.localAirRunReason()))
                .thenIdle(5)
                .thenExecute(()->{
                    ignored[0]=outcome(world,"IGNORED");
                    for(int n=5;n<=7;n++) layers.accept(n);
                    helper.assertTrue(outcome(world,"IGNORED")-ignored[0]==3,"Layers 4->7 share the half-block bucket: "+world.localAirReport().get("editOutcomes"));
                    helper.assertTrue(world.localAirField()!=null,"Same-bucket snow keeps the published field");
                    var geometry=world.localAirGeometry();
                    helper.assertTrue(geometry.flow().solidVolume(geometry.flow().index(2,1,2))==.5,"The snow cell holds a half block");
                    helper.assertTrue((long)world.localAirReport().get("restarts")==restarts[0],"Snow never restarts the owner: "+world.localAirReport().get("restartCauses"));
                    helper.assertTrue(String.valueOf(world.localAirReport().get("geometryEdits")).contains("minecraft:snow->minecraft:snow[layers]"),"The 3->4 edit is counted by block pair: "+world.localAirReport().get("geometryEdits"));
                }).thenSucceed();
    }
    @SuppressWarnings("unchecked")
    private static long outcome(HeatWorld world,String name) {
        return ((Map<String,Long>)world.localAirReport().get("editOutcomes")).getOrDefault(name,0L);
    }
}
