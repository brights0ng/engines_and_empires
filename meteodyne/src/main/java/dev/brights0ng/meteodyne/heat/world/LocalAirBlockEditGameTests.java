// Written by ChatGPT.
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

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirBlockEditGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_block_edits",timeoutTicks=2400)
    public static void trapdoorAndBlockReplacementRetainWarmthThroughTheLiveWorldHook(GameTestHelper helper) {
        // Let the shared asynchronous lane run on the unpaced headless server; no timing claim.
        helper.onEachTick(()->LockSupport.parkNanos(1_000_000L));
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);
        var low=helper.absolutePos(new BlockPos(2,2,2));var restore=new LinkedHashMap<BlockPos,BlockState>();
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
        level.setBlock(low.offset(1,1,2),Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true),2);
        var edit=low.offset(2,2,2);
        level.setBlock(edit,Blocks.OAK_TRAPDOOR.defaultBlockState(),2);
        world.requestLocalAirSimulation(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),5,5,5),125,0,new LocalAirRun.Settings(20,0,0,4));
        LocalAirRun.Frame[] before={null};
        helper.startSequence()
                .thenWaitUntil(()->helper.assertTrue(world.localAirField()!=null && world.localAirField().acceptedSteps>=10,"Fixture warms on the shared worker lane"))
                .thenExecute(()->{
                    before[0]=world.localAirField();
                    level.setBlock(edit,level.getBlockState(edit).setValue(BlockStateProperties.OPEN,true),2);
                    helper.assertTrue(world.localAirField()==null,"Trapdoor edit immediately withdraws the old airflow");
                })
                .thenWaitUntil(()->helper.assertTrue(world.localAirField()!=null && world.localAirField().transfers==before[0].transfers+1,"A trapdoor OPEN edit uses a retained handoff"))
                .thenExecute(()->{
                    verify(helper,before[0],world.localAirField());
                    before[0]=world.localAirField();
                    level.setBlock(edit,Blocks.COPPER_BLOCK.defaultBlockState(),2);
                    helper.assertTrue(world.localAirField()==null,"Material replacement withdraws the old airflow");
                })
                .thenWaitUntil(()->helper.assertTrue(world.localAirField()!=null && world.localAirField().transfers==before[0].transfers+1,"A different full material also uses a retained handoff"))
                .thenExecute(()->{
                    verify(helper,before[0],world.localAirField());
                    var geometry=world.localAirGeometry();var thermal=geometry.thermal();
                    helper.assertTrue(thermal.materialState(geometry.flow().index(2,2,2))>=0 && geometry.flow().nodeAt(2.5,2.5,2.5)<0,"Replacement owns solid storage and cannot remain an old air pocket");
                    helper.assertTrue(world.queryLocalAir(edit.getX()+.5,edit.getY()+.5,edit.getZ()+.5,40).reason()==LocalAirReading.Reason.OCCUPIED,"Geometry-aware query reports the new occupied cell");
                }).thenSucceed();
    }
    private static void verify(GameTestHelper helper,LocalAirRun.Frame before,LocalAirRun.Frame after) {
        helper.assertTrue(after.initialEnergy==before.initialEnergy && after.sourceEnergy>=before.sourceEnergy && after.acceptedSteps>=before.acceptedSteps,"Heat, time and the original energy baseline survive the edit");
        helper.assertTrue(Math.abs(after.quality.energyBalanceError())<1e-6 && after.quality.maxDivergence()<=1e-7,"Replacement is balanced and pressure-qualified");
        helper.assertTrue(after.temperature(after.flow.nodeAt(3.5,2.5,2.5))>20,"Unchanged room air retains warmth");
    }
}
