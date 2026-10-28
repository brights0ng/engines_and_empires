// Written by Claude.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import java.util.concurrent.locks.LockSupport;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
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
    @SuppressWarnings("unchecked")
    private static long outcome(HeatWorld world,String name) {
        return ((Map<String,Long>)world.localAirReport().get("editOutcomes")).getOrDefault(name,0L);
    }
}
