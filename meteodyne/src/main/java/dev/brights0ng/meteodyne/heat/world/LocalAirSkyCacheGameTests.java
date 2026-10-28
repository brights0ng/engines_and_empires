// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirSkyCacheGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_sky_index",timeoutTicks=1400)
    public static void tallColumnsShareEvidenceAcrossOwnersAndStillRejectRoofFluidAndSnowEdits(GameTestHelper helper) {
        helper.onEachTick(()->java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L));
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);world.stopLocalAirPlan();world.clearLocalAirGeometry();
        var at=helper.absolutePos(new BlockPos(3072,2,3072));
        var low=new BlockPos(Math.floorDiv(at.getX(),16)*16+8,level.getMinBuildHeight()+3,Math.floorDiv(at.getZ(),16)*16+8);
        var cp=new net.minecraft.world.level.ChunkPos(low);boolean forced=!level.getForcedChunks().contains(cp.toLong());if(forced) level.setChunkForced(cp.x,cp.z,true);
        var restore=new LinkedHashMap<BlockPos,BlockState>();
        Runnable cleanup=()->{world.clearLocalAirGeometry();restore.forEach((p,s)->level.setBlock(p,s,2));if(forced) level.setChunkForced(cp.x,cp.z,false);};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) {cleanup.run();}
            public void testFailed(GameTestInfo info,GameTestRunner runner) {cleanup.run();}
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) {cleanup.run();}
        });
        for(int x=-1;x<=3;x++) for(int z=-1;z<=3;z++) for(int y=low.getY();y<level.getMaxBuildHeight();y++) {
            var pos=new BlockPos(low.getX()+x,y,low.getZ()+z);restore.put(pos,level.getBlockState(pos));level.setBlock(pos,(y==low.getY()?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        world.skyCache().clear();var before=world.skyCache().report();
        var bounds=new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),3,3,3);
        var roof=new BlockPos(low.getX(),level.getMaxBuildHeight()-50,low.getZ());long[] scalar={0},checks={0};
        world.requestLocalAirGeometry(bounds,27,100_000);
        var sequence=helper.startSequence().thenWaitUntil(()->helper.assertTrue(world.localAirGeometry()!=null,"Cold tall-column index admits geometry: "+world.localAirReport()))
                .thenExecute(()->{
                    var cold=world.skyCache().report();scalar[0]=cold.samples();checks[0]=cold.sectionChecks();
                    helper.assertTrue(cold.samples()-before.samples()<400,"Cold tall columns avoid per-block traversal of empty sky: "+cold);
                    helper.assertTrue((long)world.localAirReport().get("coverageBulkCells")>7000,"Bulk proof accounts for the actual empty sky positions");
                    System.out.println("SKY_INDEX_COLD scalarReads="+(cold.samples()-before.samples())+" sectionChecks="+(cold.sectionChecks()-before.sectionChecks())+" operations="+(cold.queries()-before.queries())+" bulkCells="+(cold.bulkCells()-before.bulkCells())+" report="+world.localAirReport());
                    world.requestLocalAirGeometry(bounds,27,100_000);
                }).thenWaitUntil(()->helper.assertTrue(world.localAirGeometry()!=null,"New capture owner reuses the world-lifetime column index"))
                .thenExecute(()->{
                    var warm=world.skyCache().report();helper.assertTrue(warm.samples()==scalar[0] && warm.sectionChecks()==checks[0],"Warm owner uses no new scalar or section world reads: "+warm);
                    helper.assertTrue((long)world.localAirReport().get("coverageReusedCells")>7000,"New owner records shared sky evidence");
                    var epoch=new LocalAirDependencies(bounds);var source=new LocalAirWorldSource(level,epoch::get,epoch,world.skyCache());var history=source.freeze();
                    helper.assertTrue(history.world().skyCache()==null,"Historical geometry never consults the live sky index");history.close();
                    System.out.println("SKY_INDEX_WARM "+warm);
                });
        for(var state:List.of(Blocks.STONE.defaultBlockState(),Blocks.STONE_SLAB.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.SNOW.defaultBlockState())) {
            sequence.thenExecute(()->{
                level.setBlock(roof,state,2);helper.assertTrue(world.localAirGeometry()==null,"An edit above a bulk/shared proof withdraws geometry immediately");
            }).thenWaitUntil(()->helper.assertTrue(world.localAirStatus()==LocalAirPreparation.Status.REJECTED,"Edited roof/fluid/thin occupancy is rejected: "+world.localAirReport()))
                    .thenExecute(()->{
                        helper.assertTrue(world.localAirGeometry()==null && String.valueOf(world.localAirReport().get("reason")).contains(Integer.toString(roof.getY())),"Rejection identifies the real high obstruction");
                        level.setBlock(roof,Blocks.AIR.defaultBlockState(),2);
                    }).thenWaitUntil(()->helper.assertTrue(world.localAirGeometry()!=null,"Removing an obstruction reindexes and restores coverage"));
        }
        sequence.thenExecute(()->{
            checks[0]=world.skyCache().report().sectionChecks();var chunk=level.getChunkSource().getChunkNow(cp.x,cp.z);world.onChunkUnload(chunk);world.onChunkLoad(chunk);
            helper.assertTrue(world.localAirGeometry()==null,"Chunk lifecycle withdraws both cached proof and geometry");
        }).thenWaitUntil(()->helper.assertTrue(world.localAirGeometry()!=null,"Reloaded chunk receives fresh evidence"))
                .thenExecute(()->{helper.assertTrue(world.skyCache().report().sectionChecks()>checks[0],"Chunk reload invalidates section summaries");checks[0]=world.skyCache().report().sectionChecks();HeatTables.clear();helper.assertTrue(world.localAirGeometry()==null,"Material reload withdraws cached publication");})
                .thenWaitUntil(()->helper.assertTrue(world.localAirGeometry()!=null,"Material revision permits fresh indexing"))
                .thenExecute(()->helper.assertTrue(world.skyCache().report().sectionChecks()>checks[0],"Material reload cannot reuse old summaries"))
                .thenSucceed();
    }
}
