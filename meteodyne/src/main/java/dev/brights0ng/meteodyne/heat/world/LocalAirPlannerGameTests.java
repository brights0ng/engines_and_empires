// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirPlannerGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_planner",timeoutTicks=60)
    public static void commandEnablesContinuouslyUpdatedPlanningWithoutActivatingTheSolver(GameTestHelper helper) throws Exception {
        var level=helper.getLevel();var world=HeatWorld.of(level);world.clearLocalAirGeometry();world.stopLocalAirPlan();
        var source=level.getServer().createCommandSourceStack().withLevel(level).withPermission(2).withSuppressedOutput();
        var dispatcher=level.getServer().getCommands().getDispatcher();
        var fire=helper.absolutePos(new net.minecraft.core.BlockPos(2,3,2));var previous=level.getBlockState(fire);
        try {
            level.setBlock(fire,net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState(),2);
            helper.assertTrue(dispatcher.execute("meteodyne heat air plan 0 1 64 64",source)==1,"Explicit planning command enables the planner");
            helper.assertTrue(world.localAirPlan().groups().stream().flatMap(g->g.requests().stream()).anyMatch(e->
                    e.active() && e.request().key().kind()==LocalAirDemandRegistry.Kind.PLACED_SOURCE
                            && e.request().key().id().equals(Long.toString(fire.asLong()))),"Real placed source flows from the world registry into the plan");
        } catch(Exception | Error e) { world.stopLocalAirPlan();throw e; }
        finally { level.setBlock(fire,previous,2); }
        long first=world.localAirPlan().tick();
        helper.runAfterDelay(21,()->{
            try {
                helper.assertTrue(world.localAirPlan().tick()>first,"Enabled planning refreshes automatically without another command");
                helper.assertTrue(world.localAirField()==null && world.localAirStatus()==LocalAirPreparation.Status.CLOSED,"Planning never starts a coupled domain");
                helper.assertTrue(dispatcher.execute("meteodyne heat air plan",source)==1,"Plan inspection is available");
                helper.assertTrue(dispatcher.execute("meteodyne heat air plan off",source)==1 && world.localAirPlan()==null,"Off releases planner state");
                helper.succeed();
            } catch(Exception e) { throw new RuntimeException(e); }
            finally { world.stopLocalAirPlan(); }
        });
    }
}
