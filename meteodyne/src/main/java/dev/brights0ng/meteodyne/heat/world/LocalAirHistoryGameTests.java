// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

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
public final class LocalAirHistoryGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_history",timeoutTicks=2400)
    public static void rapidDoorAndCampfireEditsRetainChronologicalWarmth(GameTestHelper helper) {
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);
        var base=helper.absolutePos(new BlockPos(2,2,2));var low=new BlockPos(base.getX(),level.getMaxBuildHeight()-10,base.getZ());
        var restore=new java.util.LinkedHashMap<BlockPos,BlockState>();
        Runnable cleanup=()->{world.clearLocalAirGeometry();restore.forEach((pos,state)->level.setBlock(pos,state,2));};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        for(int z=0;z<5;z++) for(int y=0;y<5;y++) for(int x=0;x<5;x++) {
            var pos=low.offset(x,y,z);restore.put(pos,level.getBlockState(pos));
            level.setBlock(pos,(x==0 || x==4 || y==0 || y==4 || z==0 || z==4 || x==2?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        var door=low.offset(2,1,2);var closed=Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
        java.util.function.Consumer<Boolean> setDoor=open->{
            level.setBlock(door,closed.setValue(BlockStateProperties.OPEN,open),2);
            level.setBlock(door.above(),closed.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER).setValue(BlockStateProperties.OPEN,open),2);
        };
        setDoor.accept(false);
        var fire=low.offset(1,1,2);var lit=Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true);level.setBlock(fire,lit,2);
        world.requestLocalAirSimulation(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),5,5,5),125,0,new LocalAirRun.Settings(20,0,0,2));
        long[] edit={-1};int[] event={0};LocalAirRun.Frame[] before={null};
        helper.onEachTick(()->{
            // Cooperative worker scheduling only; this fixture does not measure real-time rebuild latency.
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            var frame=world.localAirField();
            if(edit[0]<0) {
                if(frame==null || frame.acceptedSteps<20) return;
                before[0]=frame;edit[0]=level.getGameTime();setDoor.accept(true);
                helper.assertTrue(world.localAirField()==null,"Opening immediately withholds the old field");return;
            }
            long elapsed=level.getGameTime()-edit[0];
            if(event[0]==0 && elapsed>=2) {
                level.setBlock(fire,lit.setValue(BlockStateProperties.LIT,false),2);event[0]++;
                helper.assertTrue(world.localAirField()==null,"Source-off during capture withholds the old field");
            } else if(event[0]==1 && elapsed>=4) {setDoor.accept(false);event[0]++;}
            else if(event[0]==2 && elapsed>=6) {level.setBlock(fire,lit,2);event[0]++;}
            else if(event[0]==3 && elapsed>=8) {setDoor.accept(true);event[0]++;}
            if(event[0]<4 || frame==null || frame.throughTick<edit[0]+38) return;
            helper.assertTrue(frame.transfers==3 && frame.sourceRevision==2,"All three topologies and both source edges are retained: "+world.localAirReport());
            helper.assertTrue(frame.acceptedSteps>before[0].acceptedSteps && frame.sourceEnergy>=before[0].sourceEnergy,"Progress and cumulative heat never cold-start");
            helper.assertTrue(frame.temperature(frame.flow.nodeAt(1.5,1.5,2.5))>20,"Final room retains warmth");
            helper.assertTrue(frame.flow.components()==1,"Final opened door connects both rooms");
            helper.assertTrue(Math.abs(frame.quality.energyBalanceError())<1e-5 && frame.quality.maxDivergence()<1e-7,"Retained history stays conserved and projected");
            helper.succeed();
        });
    }
}
