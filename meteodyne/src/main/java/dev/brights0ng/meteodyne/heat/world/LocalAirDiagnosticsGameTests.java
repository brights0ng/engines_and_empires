// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirDiagnosticsGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_diagnostics")
    public static void commandsRequireOperatorAndBoundExplicitRequests(GameTestHelper helper) throws Exception {
        var world=HeatWorld.of(helper.getLevel());var dispatcher=helper.getLevel().getServer().getCommands().getDispatcher();
        var source=helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel()).withPermission(2).withSuppressedOutput();
        world.clearLocalAirGeometry();
        try {
            boolean denied=false;
            try { dispatcher.execute("meteodyne heat air status",source.withPermission(0)); }
            catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected) { denied=true; }
            helper.assertTrue(denied,"Non-operators cannot use diagnostic controls");
            helper.assertTrue(dispatcher.execute("meteodyne heat air status",source)==1,"Status works without a field");
            helper.assertTrue(dispatcher.execute("meteodyne heat air sample",source)==1,"Missing sample reports unavailability");
            var p=helper.absolutePos(new BlockPos(3,3,3));String point=p.getX()+" "+p.getY()+" "+p.getZ();
            helper.assertTrue(dispatcher.execute("meteodyne heat air start "+point+" "+point+" 20 0 0 1 0 2",source)==1,"Explicit diagnostic starts");
            helper.assertTrue(world.localAirStatus()==LocalAirPreparation.Status.CAPTURING,"Request is asynchronous");
            helper.assertTrue(dispatcher.execute("meteodyne heat air start 0 0 0 32768 0 0 20 0 0 32768 0 2",source)==0,"Oversized bounds reject admission");
            helper.assertTrue(world.localAirStatus()==LocalAirPreparation.Status.CAPTURING,"Rejected request preserves the active request");
            helper.assertTrue(dispatcher.execute("meteodyne heat air live "+point+" "+point+" 1 0 2",source)==1,"Live diagnostic command starts");
            helper.assertTrue("LIVE_CENTER".equals(world.localAirReport().get("forcingMode")),"Live mode is reported explicitly");
            helper.assertTrue(dispatcher.execute("meteodyne heat air stop",source)==1,"Explicit stop works");
            helper.assertTrue(world.localAirField()==null && world.localAirStatus()==LocalAirPreparation.Status.CLOSED,"Stop withholds all fields");
            boolean overflow=false;
            try { LocalAirCommand.bounds(new BlockPos(Integer.MIN_VALUE,0,0),new BlockPos(Integer.MAX_VALUE,0,0)); }
            catch(ArithmeticException expected) { overflow=true; }
            helper.assertTrue(overflow,"Coordinate overflow is rejected");helper.succeed();
        } finally { world.clearLocalAirGeometry(); }
    }
}
