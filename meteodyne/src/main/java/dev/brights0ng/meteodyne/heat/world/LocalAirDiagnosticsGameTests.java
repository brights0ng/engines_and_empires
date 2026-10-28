// Written by ChatGPT.
// Updated by ChatGPT: verify the public consumer query lifecycle.
// Updated by ChatGPT: checked view ownership and age protocol metadata.
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
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_view_codec")
    public static void persistentViewPayloadRoundTripsAndBoundsItsSamples(GameTestHelper helper) {
        var sample=new LocalAirViewNetwork.Point(-10,65,20,12.5f,1,-2,3);
        var payload=new LocalAirViewNetwork.Payload("minecraft:overworld",true,1234,true,17,20,java.util.List.of(sample));
        var buffer=io.netty.buffer.Unpooled.buffer();
        try {
            LocalAirViewNetwork.Payload.CODEC.encode(buffer,payload);
            helper.assertTrue(payload.equals(LocalAirViewNetwork.Payload.CODEC.decode(buffer)),"Domain ownership, history policy, actual age, positions, temperature offsets and full velocity survive the codec");
            buffer.clear();net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(buffer,"minecraft:overworld");
            buffer.writeBoolean(true);buffer.writeLong(99);buffer.writeBoolean(false);buffer.writeLong(0);buffer.writeInt(40);buffer.writeInt(LocalAirViewNetwork.MAX_POINTS+1);boolean rejected=false;
            try { LocalAirViewNetwork.Payload.CODEC.decode(buffer); } catch(IllegalArgumentException expected) { rejected=true; }
            helper.assertTrue(rejected,"Decoder rejects oversized sample arrays before allocating");
            buffer.clear();
            var full=new LocalAirViewNetwork.Payload("minecraft:overworld",true,java.util.Collections.nCopies(LocalAirViewNetwork.MAX_POINTS,sample));
            LocalAirViewNetwork.Payload.CODEC.encode(buffer,full);
            helper.assertTrue(buffer.readableBytes()<1_048_576,"Largest admitted view fits one clientbound payload");
            helper.assertTrue(full.equals(LocalAirViewNetwork.Payload.CODEC.decode(buffer)),"Full-domain payload round trips at the maximum sample count");
        } finally { buffer.release(); }
        helper.succeed();
    }
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_capture_budget")
    public static void idleThermalCaptureDoesNotLeaveLocalAirAtEightCellsPerTick(GameTestHelper helper) {
        var level=helper.getLevel();var world=HeatWorld.of(level);
        var low=helper.absolutePos(new BlockPos(2,3,2));
        var restore=new java.util.LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        world.clearLocalAirGeometry();
        try {
            for(int z=0;z<8;z++) for(int y=0;y<2;y++) for(int x=0;x<8;x++) {
                var pos=low.offset(x,y,z);restore.put(pos,level.getBlockState(pos));
                level.setBlock(pos,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),2);
            }
            world.requestLocalAirGeometry(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),8,2,8),128,0);
            helper.assertTrue(world.prepareAllSnapshots(256,System.nanoTime()-1)==0,"Expired shared deadline prevents capture");
            int used=world.prepareAllSnapshots(256,Long.MAX_VALUE);
            helper.assertTrue(used<=256,"Both preparation paths retain the combined cell cap");
            helper.assertTrue(((Number)world.localAirReport().get("captureReadCells")).longValue()==128,"Local air captures the 128-cell fixture in one admitted slice, rather than eight cells");
            helper.succeed();
        } finally { world.clearLocalAirGeometry();restore.forEach((pos,state)->level.setBlock(pos,state,2)); }
    }
    /** Inventory/permission fixture without a network login or third-party datapack-sync handlers. */
    static net.minecraft.server.level.ServerPlayer viewer(GameTestHelper helper) {
        return new net.minecraft.server.level.ServerPlayer(helper.getLevel().getServer(),helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"air-trial"),
                net.minecraft.server.level.ClientInformation.createDefault()) {
            @Override public boolean hasPermissions(int permission) { return permission<=2; }
            @Override public void displayClientMessage(net.minecraft.network.chat.Component message,boolean actionBar) { }
        };
    }
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_diagnostics")
    public static void commandsRequireOperatorAndBoundExplicitRequests(GameTestHelper helper) throws Exception {
        var world=HeatWorld.of(helper.getLevel());var dispatcher=helper.getLevel().getServer().getCommands().getDispatcher();
        var source=helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel()).withPermission(2).withSuppressedOutput();
        world.clearLocalAirGeometry();
        try {
            var missing=world.queryLocalAir(0,0,0,0);
            helper.assertTrue(missing.reason()==LocalAirReading.Reason.NO_REQUEST && missing.sample().isEmpty(),"No request gives no invented reading");
            boolean denied=false;
            try { dispatcher.execute("meteodyne heat air status",source.withPermission(0)); }
            catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected) { denied=true; }
            helper.assertTrue(denied,"Non-operators cannot use diagnostic controls");
            helper.assertTrue(dispatcher.execute("meteodyne heat air status",source)==1,"Status works without a field");
            helper.assertTrue(dispatcher.execute("meteodyne heat air sample",source)==1,"Missing sample reports unavailability");
            var viewer=LocalAirDiagnosticsGameTests.viewer(helper);

            try {

                var stack=new net.minecraft.world.item.ItemStack(dev.brights0ng.meteodyne.instrument.InstrumentContent.THERMOMETER.get());
                viewer.getInventory().setItem(0,stack);
                helper.assertTrue(LocalAirView.enable(viewer,40),"Explicit trial can wait for a domain");
                var commands=helper.getLevel().getServer().getCommands().getDispatcher();
                helper.assertTrue(LocalAirView.spacing(viewer)==1,"View defaults to one marker per cell");
                helper.assertTrue(commands.execute("meteodyne heat air view scale 2",viewer.createCommandSourceStack().withPermission(2).withSuppressedOutput())==1
                        && LocalAirView.spacing(viewer)==2,"Scale command changes active sampling spacing");
                helper.assertTrue(commands.execute("meteodyne heat air view scale",viewer.createCommandSourceStack().withPermission(2).withSuppressedOutput())==1,"Scale command reports spacing");
                stack.set(dev.brights0ng.meteodyne.instrument.InstrumentContent.READING.get(),new dev.brights0ng.meteodyne.instrument.InstrumentReading(helper.getLevel().dimension().location().toString(),helper.getLevel().getGameTime(),99));
                stack.getItem().inventoryTick(stack,helper.getLevel(),viewer,0,true);
                helper.assertTrue(!stack.has(dev.brights0ng.meteodyne.instrument.InstrumentContent.READING.get()),"Missing coupled field immediately clears a previous thermometer reading");
                helper.assertTrue(world.localAirField()==null && world.localAirStatus()==LocalAirPreparation.Status.CLOSED,"View does not create demand or a field");
            } finally { LocalAirView.disable(viewer);viewer.discard(); }
            var p=helper.absolutePos(new BlockPos(3,3,3));String point=p.getX()+" "+p.getY()+" "+p.getZ();
            helper.assertTrue(dispatcher.execute("meteodyne heat air start "+point+" "+point+" 20 0 0 1 0 2",source)==1,"Explicit diagnostic starts");
            helper.assertTrue(world.localAirStatus()==LocalAirPreparation.Status.CAPTURING,"Request is asynchronous");
            var oldState=helper.getLevel().getBlockState(p);
            var newState=(oldState.is(net.minecraft.world.level.block.Blocks.STONE)?net.minecraft.world.level.block.Blocks.DIRT:net.minecraft.world.level.block.Blocks.STONE).defaultBlockState();
            try {
                helper.getLevel().setBlock(p,newState,2);
                var report=world.localAirReport();
                var events=(LocalAirDependencies.Diagnostics)report.get("invalidations");
                var event=events.recent().getLast();
                helper.assertTrue(event.cause().equals("BLOCK") && event.x()==p.getX() && event.y()==p.getY() && event.z()==p.getZ()
                        && event.before().equals(oldState.toString()) && event.after().equals(newState.toString()),"Real block hook reports exact invalidation states and position");
                helper.assertTrue(((Number)report.get("restarts")).longValue()>=1,"Report distinguishes an actual cold restart");
                helper.assertTrue(dispatcher.execute("meteodyne heat air status",source)==1,"Status renders restart diagnostics");
            } finally { helper.getLevel().setBlock(p,oldState,2); }
            var pending=world.queryLocalAir(p.getX()+0.5,p.getY()+0.5,p.getZ()+0.5,10);
            helper.assertTrue(pending.status()==LocalAirReading.Status.PENDING && pending.sample().isEmpty(),"Consumer query does not synchronously prepare a field");
            helper.assertTrue(world.localAirStatus()==LocalAirPreparation.Status.CAPTURING,"Query preserves asynchronous preparation");
            helper.assertTrue(world.queryLocalAir(p.getX()+1,p.getY(),p.getZ(),10).reason()==LocalAirReading.Reason.OUTSIDE_DOMAIN,"Upper domain edge is exclusive");
            helper.assertTrue(dispatcher.execute("meteodyne heat air start 0 0 0 32768 0 0 20 0 0 32768 0 2",source)==0,"Oversized bounds reject admission");
            helper.assertTrue(world.localAirStatus()==LocalAirPreparation.Status.CAPTURING,"Rejected request preserves the active request");
            helper.assertTrue(dispatcher.execute("meteodyne heat air live "+point+" "+point+" 1 0 2",source)==1,"Live diagnostic command starts");
            helper.assertTrue("LIVE_CENTER".equals(world.localAirReport().get("forcingMode")),"Live mode is reported explicitly");
            helper.assertTrue(dispatcher.execute("meteodyne heat air stop",source)==1,"Explicit stop works");
            helper.assertTrue(world.localAirField()==null && world.localAirStatus()==LocalAirPreparation.Status.CLOSED,"Stop withholds all fields");
            helper.assertTrue(world.queryLocalAir(p.getX(),p.getY(),p.getZ(),10).reason()==LocalAirReading.Reason.NO_REQUEST,"Stopped request cannot supply consumer samples");
            boolean overflow=false;
            try { LocalAirCommand.bounds(new BlockPos(Integer.MIN_VALUE,0,0),new BlockPos(Integer.MAX_VALUE,0,0)); }
            catch(ArithmeticException expected) { overflow=true; }
            helper.assertTrue(overflow,"Coordinate overflow is rejected");helper.succeed();
        } finally { world.clearLocalAirGeometry(); }
    }
}
