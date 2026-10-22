// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.LinkedHashMap;
import java.util.concurrent.locks.LockSupport;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirCoverageReuseGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_coverage_reuse",timeoutTicks=2400)
    public static void reusedSkyProofsRemainProtectedByRoofChunkAndMaterialInvalidation(GameTestHelper h) {
        h.onEachTick(()->LockSupport.parkNanos(1_000_000L));
        var level=h.getLevel();var world=HeatWorld.of(level);world.setPaused(false);
        var origin=h.absolutePos(new BlockPos(2,2,2));var low=new BlockPos(origin.getX(),level.getMaxBuildHeight()-7,origin.getZ());
        var restore=new LinkedHashMap<BlockPos,BlockState>();
        Runnable cleanup=()->{world.clearLocalAirGeometry();restore.forEach((p,s)->level.setBlock(p,s,2));};
        h.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) {cleanup.run();}
            public void testFailed(GameTestInfo info,GameTestRunner runner) {cleanup.run();}
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) {cleanup.run();}
        });
        for(int z=0;z<5;z++)for(int y=0;y<4;y++)for(int x=0;x<5;x++) {
            var p=low.offset(x,y,z);restore.put(p,level.getBlockState(p));level.setBlock(p,(y==0?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        var door=low.offset(2,1,2);var state=Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
        java.util.function.Consumer<Boolean> setDoor=open->{
            var next=state.setValue(BlockStateProperties.OPEN,open);level.setBlock(door,next,2);
            level.setBlock(door.above(),next.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER),2);
        };
        setDoor.accept(false);
        world.requestLocalAirSimulation(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),5,4,5),100,2000,new LocalAirRun.Settings(20,0.5,0,2));
        long[] reads={0};
        Runnable retained=()->{
            var f=world.localAirField();h.assertTrue(f!=null && f.transfers==1,"Door transfer publishes with retained heat/flow");
            h.assertTrue((long)world.localAirReport().get("coverageReadCells")==reads[0],"Door edit performs no fresh sky reads");
            h.assertTrue((long)world.localAirReport().get("coverageReusedCells")>0,"Door edit records reused sky samples");
        };
        h.startSequence()
                .thenWaitUntil(()->h.assertTrue(world.localAirField()!=null,"Initial open-sky field publishes"))
                .thenExecute(()->{reads[0]=(long)world.localAirReport().get("coverageReadCells");h.assertTrue(reads[0]>0,"Initial field has actual sky evidence");setDoor.accept(true);})
                .thenWaitUntil(retained)
                .thenExecute(()->{
                    var chunk=level.getChunkSource().getChunkNow(low.getX()>>4,low.getZ()>>4);world.onChunkUnload(chunk);
                    h.assertTrue(world.localAirField()==null,"Relevant chunk hook immediately withholds a field with reused proofs");world.onChunkLoad(chunk);
                })
                .thenWaitUntil(()->h.assertTrue(world.localAirField()!=null && world.localAirField().transfers==0,"Chunk invalidation cold-starts with fresh evidence"))
                .thenExecute(()->{h.assertTrue((long)world.localAirReport().get("coverageReadCells")>reads[0],"Chunk invalidation resamples sky");reads[0]=(long)world.localAirReport().get("coverageReadCells");setDoor.accept(false);})
                .thenWaitUntil(retained)
                .thenExecute(()->{HeatTables.clear();h.assertTrue(world.localAirField()==null,"Material reload immediately withholds reused proofs");})
                .thenWaitUntil(()->h.assertTrue(world.localAirField()!=null && world.localAirField().transfers==0,"Material reload captures fresh evidence"))
                .thenExecute(()->{h.assertTrue((long)world.localAirReport().get("coverageReadCells")>reads[0],"Material reload resamples sky");reads[0]=(long)world.localAirReport().get("coverageReadCells");setDoor.accept(true);})
                .thenWaitUntil(retained)
                .thenExecute(()->{
                    var roof=low.offset(0,5,2);restore.put(roof,level.getBlockState(roof));level.setBlock(roof,Blocks.STONE.defaultBlockState(),2);
                    h.assertTrue(world.localAirField()==null,"Above-domain edit in a reused proof column invalidates immediately");
                })
                .thenWaitUntil(()->h.assertTrue(world.localAirStatus()==LocalAirPreparation.Status.REJECTED,"New roof obstructs the fresh proof and rejects outdoor admission"))
                .thenExecute(()->h.assertTrue(world.localAirField()==null && (long)world.localAirReport().get("coverageReadCells")>reads[0],"Obstruction cannot republish the cached outdoor field"))
                .thenSucceed();
    }
}
