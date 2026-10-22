// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.nio.file.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import dev.brights0ng.meteodyne.Meteodyne;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Opt-in extension of the ordinary coupled test; headless pacing is explicit, not a server MSPT measurement. */
@GameTestHolder(Meteodyne.MODID)
public final class LocalAirSceneGameTests {
    @GameTestGenerator
    public static List<TestFunction> scene() {
        return Boolean.getBoolean("meteodyne.airScene")?List.of(new TestFunction("local_air_scene","localairscene.room",
                "meteodyne_air_scene:gametest/empty",2400,0,true,LocalAirSceneGameTests::run)):List.of();
    }
    static void run(GameTestHelper helper) {
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);
        var origin=helper.absolutePos(new BlockPos(2,2,2));
        // High, loaded air keeps the explicit sky-evidence phase bounded without changing the admission rules.
        var low=new BlockPos(origin.getX(),level.getMaxBuildHeight()-14,origin.getZ());
        var restore=new LinkedHashMap<BlockPos,BlockState>();
        var output=Path.of(System.getProperty("meteodyne.airSceneOutput"));
        boolean[] capturing={false};long[] next={System.nanoTime()},phaseStart={level.getGameTime()},maxAge={0},firstField={-1};
        int[] phase={0};long[] targetSteps={20},retainedAttempts={0};
        long[] lastReadCells={0},lastReusedCells={0};
        LocalAirGeometryCapture.Prepared[] retained={null};
        LocalAirRun.Frame[] sourceOff={null};
        LocalAirRun.Frame[] doorBefore={null},doorFirst={null};
        Runnable cleanup=()->{if(capturing[0]) world.benchmark().stop();world.clearLocalAirGeometry();restore.forEach((pos,state)->level.setBlock(pos,state,2));};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        for(int z=0;z<12;z++) for(int y=0;y<7;y++) for(int x=0;x<12;x++) {
            var pos=low.offset(x,y,z);restore.put(pos,level.getBlockState(pos));
            boolean wall=x==0 || x==11 || y==0 || y==6 || z==0 || z==11 || x==6;
            level.setBlock(pos,(wall?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        var door=low.offset(6,1,5);var doorState=Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
        level.setBlock(door,doorState,2);
        level.setBlock(door.above(),doorState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER),2);
        var fire=low.offset(2,1,2);var lit=Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true);
        level.setBlock(fire,lit,2);
        try {
            Files.createDirectories(output);
            Files.writeString(output.resolve("phases.csv"),"phase,cells,nodes,steps,elapsed_ticks,first_field_ticks,max_age_ticks,source_energy,exterior_energy,energy_error,max_divergence,geometry_transfers,geometry_exchange_energy,capture_read_cells,capture_reused_cells\n");
        } catch(IOException error) { helper.fail("Cannot create scene evidence: "+error); }
        Runnable start=()->{
            phaseStart[0]=level.getGameTime();maxAge[0]=0;firstField[0]=-1;
            capturing[0]=world.benchmark().start(level.getServer().createCommandSourceStack().withLevel(level).withPosition(low.getCenter()),60,0);
            helper.assertTrue(capturing[0],"Scene phase owns a timing capture");
        };
        start.run();
        world.requestLocalAirSimulation(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),12,7,12),1008,0,new LocalAirRun.Settings(20,0,0,2));
        helper.onEachTick(()->{
            // GameTestServer normally skips tick waits. Supply one 50 ms deadline here for this opt-in fixture only.
            next[0]+=50_000_000L;long delay=next[0]-System.nanoTime();
            if(delay>0) LockSupport.parkNanos(delay);else next[0]=System.nanoTime();
            var frame=world.localAirField();
            if(frame!=null) {
                if((phase[0]==1 || phase[0]==3) && doorFirst[0]==null && frame.transfers==doorBefore[0].transfers+1) doorFirst[0]=frame;
                if(phase[0]==2 && sourceOff[0]==null && frame.sourceRevision==1) sourceOff[0]=frame;
                if(firstField[0]<0) firstField[0]=level.getGameTime()-phaseStart[0];
                maxAge[0]=Math.max(maxAge[0],LocalAirDiagnostics.age(frame,level.getGameTime()));
            }
        });
        Runnable ready=()->helper.assertTrue(world.localAirField()!=null && world.localAirField().acceptedSteps>=targetSteps[0],
                "Larger scene progresses: "+world.localAirStatus()+" / "+world.localAirRunStatus()+" / "+world.localAirRunReason());
        Runnable record=()->{
            var frame=world.localAirField();
            long readCells=(long)world.localAirReport().get("captureReadCells"),reusedCells=(long)world.localAirReport().get("captureReusedCells");
            if(phase[0]==1 || phase[0]==3) {
                helper.assertTrue(readCells-lastReadCells[0]<=3 && reusedCells-lastReusedCells[0]>=1005,
                        "Door recapture samples only changed/context-dependent cells: reads="+(readCells-lastReadCells[0]));
            }
            lastReadCells[0]=readCells;lastReusedCells[0]=reusedCells;
            helper.assertTrue(frame.flow.components()==(phase[0]==0 || phase[0]==3?2:1),"Closed door separates the rooms; opening connects them");
            helper.assertTrue(Double.isFinite(frame.energy) && Math.abs(frame.quality.energyBalanceError())<1e-5,"Finite conserved larger-scene energy");
            helper.assertTrue(frame.quality.maxDivergence()<1e-7,"Larger-scene divergence remains bounded");
            helper.assertTrue(phase[0]==4?frame.sourceEnergy==0:frame.sourceEnergy>0,"Source ledger follows the current fire state without resetting history");
            if(phase[0]==1 || phase[0]==3) {
                var before=doorBefore[0];var first=doorFirst[0];
                helper.assertTrue(first!=null && frame.transfers==before.transfers+1,"Door edit transfers once, coalescing both halves at the same tick");
                helper.assertTrue(first.acceptedSteps>=before.acceptedSteps && first.sourceEnergy>=before.sourceEnergy,"Door transfer retains progress and cumulative source history");
                int node=frame.flow.nodeAt(2.5,1.5,2.5);
                helper.assertTrue(first.temperature(node)>20,"Door transfer retains room warmth instead of a uniform ambient reset");
                helper.assertTrue(Math.abs(frame.energy-before.energy-(frame.sourceEnergy-before.sourceEnergy)
                        -(frame.exteriorEnergy-before.exteriorEnergy)-(frame.geometryEnergy-before.geometryEnergy))<1e-5,"Door edit energy exchange is explicitly accounted");
            }
            if(phase[0]==2) {
                helper.assertTrue(sourceOff[0]!=null && frame.sourceRevision==1,"Source refresh produces a current retained field");
                helper.assertTrue(world.localAirGeometry()==retained[0] && world.localAirReport().get("attempts").equals(retainedAttempts[0]),"Source refresh retains geometry without a preparation restart");
                helper.assertTrue(frame.sourceEnergy==sourceOff[0].sourceEnergy,"No additional source heat after the source-off boundary");
                int node=frame.flow.nodeAt(2.5,1.5,2.5);
                helper.assertTrue(sourceOff[0].temperature(node)>20 && frame.temperature(node)<sourceOff[0].temperature(node),"Retained warm air cools after extinguishing");
            }
            if(phase[0]==4) {
                double speed=0;
                for(int node=0;node<frame.flow.nodes();node++) speed=Math.max(speed,Math.abs(frame.velocity(node,0)));
                helper.assertTrue(speed>0.1,"Admitted open scene carries the explicit wind");
            }
            helper.assertTrue(world.benchmark().stop(),"Scene capture finishes");capturing[0]=false;
            try {
                Files.copy(world.benchmark().lastReport().resolve("summary.json"),output.resolve("phase-"+phase[0]+".json"),StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(output.resolve("phases.csv"),phase[0]+",1008,"+frame.flow.nodes()+","+frame.acceptedSteps+","+(level.getGameTime()-phaseStart[0])+","+firstField[0]+","+maxAge[0]+","+frame.sourceEnergy+","+frame.exteriorEnergy+","+frame.quality.energyBalanceError()+","+frame.quality.maxDivergence()+","+frame.transfers+","+frame.geometryEnergy+","+readCells+","+reusedCells+"\n",StandardOpenOption.APPEND);
            } catch(IOException error) { helper.fail("Cannot save scene evidence: "+error); }
        };
        helper.startSequence().thenWaitUntil(ready).thenExecute(()->{
            record.run();doorBefore[0]=world.localAirField();targetSteps[0]=doorBefore[0].acceptedSteps+20;phase[0]=1;
            level.setBlock(door,doorState.setValue(BlockStateProperties.OPEN,true),2);
            level.setBlock(door.above(),doorState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER).setValue(BlockStateProperties.OPEN,true),2);
            helper.assertTrue(world.localAirField()==null,"Door edit immediately withholds stale geometry");start.run();
        }).thenWaitUntil(ready).thenExecute(()->{
            record.run();phase[0]=2;retained[0]=world.localAirGeometry();retainedAttempts[0]=(long)world.localAirReport().get("attempts");
            targetSteps[0]=world.localAirField().acceptedSteps+20;
            level.setBlock(fire,lit.setValue(BlockStateProperties.LIT,false),2);
            helper.assertTrue(world.localAirField()==null,"Source edit immediately withholds stale heating");start.run();
        }).thenWaitUntil(ready).thenExecute(()->{
            record.run();doorBefore[0]=world.localAirField();doorFirst[0]=null;
            targetSteps[0]=doorBefore[0].acceptedSteps+20;phase[0]=3;
            level.setBlock(door,doorState,2);
            level.setBlock(door.above(),doorState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER),2);
            helper.assertTrue(world.localAirField()==null,"Closing the door withholds the previous connected field");start.run();
        }).thenWaitUntil(ready).thenExecute(()->{
            record.run();phase[0]=4;targetSteps[0]=20;
            for(int z=0;z<12;z++) for(int x=0;x<12;x++) level.setBlock(low.offset(x,6,z),Blocks.AIR.defaultBlockState(),2);
            for(int z=1;z<11;z++) for(int y=1;y<6;y++) {
                level.setBlock(low.offset(0,y,z),Blocks.AIR.defaultBlockState(),2);
                level.setBlock(low.offset(11,y,z),Blocks.AIR.defaultBlockState(),2);
            }
            helper.assertTrue(world.localAirField()==null,"Exterior edits withhold the closed-domain field");
            start.run();
            world.requestLocalAirSimulation(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),12,7,12),1008,10000,new LocalAirRun.Settings(20,2,0,2));
        }).thenWaitUntil(ready).thenExecute(record).thenSucceed();
    }
}
