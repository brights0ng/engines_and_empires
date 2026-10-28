// Updated by ChatGPT: isolated persistent fixture attachments.
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
public final class LocalAirSparseGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_sparse",timeoutTicks=800)
    public static void liveLShapedGroupHeatsAcrossBothSeamsWithoutRepresentingTheMissingTile(GameTestHelper helper) throws Exception {
        // Scheduling allowance for the unpaced server; this is not a performance measurement.
        helper.onEachTick(()->LockSupport.parkNanos(50_000_000L));
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);world.clearLocalAirGeometry();world.stopLocalAirPlan();
        var at=helper.absolutePos(new BlockPos(1536,2,1536));
        var low=new BlockPos(Math.floorDiv(at.getX(),16)*16,level.getMaxBuildHeight()-16,Math.floorDiv(at.getZ(),16)*16);
        world.setTestArea(new int[]{low.getX(),low.getY(),low.getZ(),low.getX()+31,low.getY()+15,low.getZ()+31});
        var tiles=List.of(new int[]{0,0},new int[]{1,0},new int[]{0,1});
        var savedData=new LinkedHashMap<net.minecraft.world.level.chunk.LevelChunk,net.minecraft.nbt.CompoundTag>();
        var restore=new LinkedHashMap<BlockPos,BlockState>();var forced=new ArrayList<net.minecraft.world.level.ChunkPos>();
        for(var t:tiles) {
            var chunk=new net.minecraft.world.level.ChunkPos((low.getX()>>4)+t[0],(low.getZ()>>4)+t[1]);
            if(!level.getForcedChunks().contains(chunk.toLong())) { forced.add(chunk);level.setChunkForced(chunk.x,chunk.z,true); }
        }
        Runnable cleanup=()->{world.stopLocalAirPlan();world.clearLocalAirGeometry();world.setTestArea(null);restore.forEach((p,s)->level.setBlock(p,s,2));savedData.forEach((chunk,tag)->{chunk.getData(HeatContent.CHUNK_DATA).deserializeNBT(level.registryAccess(),tag);chunk.setUnsaved(true);});forced.forEach(c->level.setChunkForced(c.x,c.z,false));};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        for(var t:tiles) for(int z=t[1]*16;z<t[1]*16+16;z++) for(int y=0;y<16;y++) for(int x=t[0]*16;x<t[0]*16+16;x++) {
            var p=low.offset(x,y,z);var chunk=level.getChunkAt(p);savedData.computeIfAbsent(chunk,c->c.getData(HeatContent.CHUNK_DATA).serializeNBT(level.registryAccess()));restore.put(p,level.getBlockState(p));
            boolean corridor=y>=7 && y<=9 && (x>=7 && x<=24 && z>=7 && z<=9 || x>=7 && x<=9 && z>=7 && z<=24);
            level.setBlock(p,(corridor?Blocks.AIR:Blocks.STONE).defaultBlockState(),2);
        }
        var fires=List.of(low.offset(8,7,8),low.offset(24,7,8),low.offset(8,7,24));
        for(var p:fires) level.setBlock(p,Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true),2);
        var source=level.getServer().createCommandSourceStack().withLevel(level).withPermission(2).withSuppressedOutput();
        var dispatcher=level.getServer().getCommands().getDispatcher();
        helper.assertTrue(dispatcher.execute("meteodyne heat air plan 0 1 64 64",source)==1,"Planner configured");
        helper.assertTrue(dispatcher.execute("meteodyne heat air auto 1 12288 0 4",source)==1,"Only the three real tiles fit the cell allowance");
        helper.startSequence().thenWaitUntil(()->{
            var auto=world.automaticAir();helper.assertTrue(auto!=null && auto.committedDomains().size()==1,"One connected sparse owner");
            var d=auto.committedDomains().getFirst();var frame=d.preparation.field();
            helper.assertTrue(frame!=null && frame.acceptedSteps>=4,"Sparse owner progresses through real capture, bake and coupled jobs: "+auto.rows()+" "+auto.decisions());
        }).thenExecute(()->{
            var auto=world.automaticAir();var d=auto.committedDomains().getFirst();var frame=d.preparation.field();
            helper.assertTrue(auto.cells()==12288 && d.bounds.sparse()!=null && frame.flow.cells()==12288,"No state slots for the omitted fourth tile");
            helper.assertTrue(frame.flow.components()==1 && frame.sourceEnergy>0,"Both tile seams belong to one heated air component");
            helper.assertTrue(frame.quality.maxDivergence()<=1e-7,"Sparse publication meets the unchanged pressure limit");
            for(var p:fires) helper.assertTrue(world.queryAutomaticLocalAir(p.getX()+.5,p.getY()+1.5,p.getZ()+.5,40).currentSample().isPresent(),"Each arm supplies a current reading");
            helper.assertTrue(frame.flow.index(24,8,24)==-1,"Missing tile has no geometry index");
            helper.assertTrue(world.queryAutomaticLocalAir(low.getX()+24.5,low.getY()+8.5,low.getZ()+24.5,40).reason()==LocalAirReading.Reason.OUTSIDE_DOMAIN,"Missing tile cannot return an invented reading");
            helper.assertTrue(world.localAirField()==null,"Automatic fields retain their separate diagnostic API");
        }).thenSucceed();
    }
}
