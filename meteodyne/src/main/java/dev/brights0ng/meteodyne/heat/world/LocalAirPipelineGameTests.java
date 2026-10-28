// Updated by Claude: edited the domain to a different material, since a same-material swap is now ignored.
// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real shared-pool dispatch and world invalidation; isolated batch owns the one diagnostic request. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirPipelineGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_pipeline",timeoutTicks=400)
    public static void sharedWorkersRetainUnrelatedEditsAndRebuildDependencies(GameTestHelper helper) {
        // GameTestServer advances ticks without the usual 50 ms wait. Give asynchronous workers
        // the same cooperative scheduling allowance as the coupled fixture; this is no TPS benchmark.
        helper.onEachTick(()->java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L));
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);
        var pos=helper.absolutePos(new BlockPos(3,3,3));var outside=pos.above(20);
        var old=level.getBlockState(pos);var oldOutside=level.getBlockState(outside);
        Runnable cleanup=()->{ world.clearLocalAirGeometry();level.setBlock(pos,old,2);level.setBlock(outside,oldOutside,2); };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        level.setBlock(pos,Blocks.STONE.defaultBlockState(),2);
        world.requestLocalAirGeometry(new LocalAirGeometryCapture.Bounds(pos.getX(),pos.getY(),pos.getZ(),1,1,1),1,0);
        helper.startSequence()
                .thenWaitUntil(()->helper.assertTrue(world.localAirGeometry()!=null,"Shared pool publishes prepared geometry"))
                .thenExecute(()->{
                    var changed=oldOutside.is(Blocks.STONE)?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState();
                    level.setBlock(outside,changed,2);
                    var prepared=world.localAirGeometry();
                    helper.assertTrue(prepared!=null,"Unread column above a closed solid domain preserves geometry");
                    // Explicitly exercise the chunk hooks without unloading the fixture's actual chunk.
                    var distant=new net.minecraft.world.level.ChunkPos((pos.getX()>>4)+100,(pos.getZ()>>4)+100);
                    var distantChunk=new net.minecraft.world.level.chunk.LevelChunk(level,distant);
                    world.onChunkLoad(distantChunk);world.onChunkUnload(distantChunk);
                    helper.assertTrue(world.localAirGeometry()==prepared,"Unrelated chunk hooks retain the exact publication");
                    level.setBlock(pos,Blocks.OAK_PLANKS.defaultBlockState(),2);
                    helper.assertTrue(world.localAirGeometry()==null,"Domain edit invalidates before the next tick");
                })
                .thenWaitUntil(()->helper.assertTrue(world.localAirGeometry()!=null,"Invalidated request rebuilds through shared pool"))
                .thenExecute(()->{
                    var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
                    world.onChunkUnload(chunk);
                    helper.assertTrue(world.localAirGeometry()==null,"Chunk unload hook invalidates immediately");
                    world.onChunkLoad(chunk);
                })
                .thenWaitUntil(()->helper.assertTrue(world.localAirGeometry()!=null,"Chunk hooks allow current geometry to rebuild"))
                .thenExecute(()->{
                    HeatTables.clear();
                    helper.assertTrue(world.localAirGeometry()==null,"Material reload invalidates before planning");
                })
                .thenWaitUntil(()->helper.assertTrue(world.localAirGeometry()!=null,"Material reload rebuilds through shared pool"))
                .thenExecute(()->{
                    world.clearLocalAirGeometry();
                    helper.assertTrue(world.localAirStatus()==LocalAirPreparation.Status.CLOSED && world.localAirGeometry()==null,"Explicit clear retires the diagnostic domain");
                })
                .thenSucceed();
    }
}
