// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.LinkedHashMap;
import java.util.concurrent.locks.LockSupport;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.heat.air.AirParams;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** World heat-table source capture and coupled stepping on the shared diagnostic pipeline. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirCoupledGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_coupled",timeoutTicks=2400)
    public static void campfireColdStartWarmsAndInvalidationWithholdsTheField(GameTestHelper helper) {
        // GameTestServer has no normal tick wait. Yield briefly so this asynchronous integration test
        // cannot spin through retained physical history while the shared worker is waiting to be scheduled.
        // This is a test scheduling allowance, not a 20-TPS performance measurement.
        helper.onEachTick(()->LockSupport.parkNanos(1_000_000L));
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);
        var low=helper.absolutePos(new BlockPos(2,2,2));var restore=new LinkedHashMap<BlockPos,BlockState>();
        boolean[] capturing={false};
        LocalAirGeometryCapture.Prepared[] retainedGeometry={null};
        LocalAirRun.Frame[] beforeEdit={null};
        LocalAirGeometryCapture.Prepared[] completedGeometry={null};LocalAirRun.Frame[] completed={null};
        Runnable cleanup=()->{ if(capturing[0]) world.benchmark().stop();world.clearLocalAirGeometry();restore.forEach((pos,state)->level.setBlock(pos,state,2)); };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        for(int z=0;z<5;z++) for(int y=0;y<5;y++) for(int x=0;x<5;x++) {
            var pos=low.offset(x,y,z);restore.put(pos,level.getBlockState(pos));
            level.setBlock(pos,(x==0 || y==0 || z==0 || x==4 || y==4 || z==4?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        var fire=low.offset(2,1,2);var state=Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true);
        level.setBlock(fire,state,2);
        var variant=HeatTables.power(state);var heat=new LocalAirWorldSource(level,()->1).sample(fire.getX(),fire.getY(),fire.getZ()).heat();
        helper.assertTrue(heat!=null && heat.power()==AirParams.DEFAULT.gridPower(variant.power(),variant.radiant()),"Captured source excludes existing radiant share");
        helper.assertTrue(heat.limit(20)==20+HeatTables.offsetLimit(state,variant,20),"Captured absolute/offset limit matches the existing table contract");
        capturing[0]=world.benchmark().start(level.getServer().createCommandSourceStack().withLevel(level).withPosition(low.getCenter()),60,0);
        helper.assertTrue(capturing[0],"Diagnostic fixture owns a bounded server-side timing capture");
        world.requestLocalAirSimulation(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),5,5,5),125,0,new LocalAirRun.Settings(20,0,0,2));
        helper.startSequence()
                .thenWaitUntil(()->helper.assertTrue(world.localAirField()!=null && world.localAirField().acceptedSteps>=20,"Shared pool advances at least 20 coupled steps"))
                .thenExecute(()->{
                    var geometry=world.localAirGeometry();var field=world.localAirField();
                    int node=geometry.flow().nodeAt(2.5,1.5,2.5);
                    helper.assertTrue(node>=0 && field.temperature(node)>20 && field.sourceEnergy>0,"Captured campfire warms represented air");
                    helper.assertTrue(field.exteriorEnergy==0,"Closed room has no invented weather heat flux");
                    var distant=low.offset(0,20,0);restore.put(distant,level.getBlockState(distant));
                    level.setBlock(distant,(level.getBlockState(distant).is(Blocks.STONE)?Blocks.AIR:Blocks.STONE).defaultBlockState(),2);
                    helper.assertTrue(world.localAirGeometry()==geometry && world.localAirField()==field,
                            "Unrelated edit preserves the exact warm field, progress and geometry immediately");
                    helper.assertTrue(world.benchmark().stop(),"Timing capture completes without blocking stepping");capturing[0]=false;
                    try {
                        var report=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(world.benchmark().lastReport().resolve("summary.json"))).getAsJsonObject();
                        helper.assertTrue(report.getAsJsonObject("localAirAtEnd").get("fieldAvailable").getAsBoolean(),"Report records field availability");
                        helper.assertTrue(report.getAsJsonObject("metrics").has("local_air_step_wall_ms"),"Report includes measured coupled worker steps");
                        helper.assertTrue(report.getAsJsonObject("localAirAtEnd").has("quality"),"Report records actual solver quality");
                    } catch(java.io.IOException error) { helper.fail("Cannot read diagnostic timing capture: "+error); }
                    retainedGeometry[0]=geometry;beforeEdit[0]=field;
                    level.setBlock(fire,state.setValue(BlockStateProperties.LIT,false),2);
                    helper.assertTrue(world.localAirField()==null,"A source edit withholds the previous field immediately");
                    helper.assertTrue(world.localAirGeometry()==retainedGeometry[0],"The source edit itself must not invalidate geometry");
                })
                .thenWaitUntil(()->{
                    var geometry=world.localAirGeometry();var field=world.localAirField();
                    helper.assertTrue(field!=null && field.acceptedSteps>0 && (field.sourceRevision==1 || geometry!=retainedGeometry[0]),
                            "Source edit produces a current refresh or a conservative externally invalidated rebuild");
                    completed[0]=field;completedGeometry[0]=geometry;
                })
                .thenExecute(()->{
                    var field=completed[0];
                    if(completedGeometry[0]==retainedGeometry[0]) {
                        int node=retainedGeometry[0].flow().nodeAt(2.5,1.5,2.5);
                        helper.assertTrue(field.sourceRevision==1 && field.acceptedSteps>=beforeEdit[0].acceptedSteps && field.temperature(node)>20,"Source refresh retains the warmed field and elapsed progress");
                        helper.assertTrue(field.sourceEnergy>=beforeEdit[0].sourceEnergy,"Source refresh retains cumulative energy including pre-edit catch-up");
                        helper.assertTrue(Math.abs(field.energy-beforeEdit[0].energy-(field.sourceEnergy-beforeEdit[0].sourceEnergy))<1e-5,"Source refresh preserves the same closed-room energy ledger");
                    } else {
                        // Relevant chunk/block changes from other fixtures are intentionally outside the
                        // source-only continuity contract. Their rebuilt room must have no unlit heat input.
                        helper.assertTrue(field.sourceRevision==0 && field.sourceEnergy==0,
                                "External invalidation cold-starts with no unlit source energy: sourceRevision="+field.sourceRevision
                                        +", sourceEnergy="+field.sourceEnergy+", geometry="+field.geometryRevision
                                        +", material="+field.materialRevision+", beforeGeometry="+beforeEdit[0].geometryRevision
                                        +", beforeMaterial="+beforeEdit[0].materialRevision);
                    }
                })
                .thenSucceed();
    }
}
