// Updated by ChatGPT: verified static block handoffs through the actual automatic world hook.
// Updated by ChatGPT: verified automatic shutdown persistence and isolated chunk attachments.
// Written by ChatGPT.
// Updated by ChatGPT: verified automatic view movement, source selection and saved-state reentry.
// Updated by ChatGPT: verified real partial-cut rejection, bounded expansion and viewer recovery.
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
public final class LocalAirAutomaticGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_coverage_recovery",timeoutTicks=1000)
    public static void rejectedAutomaticCutExpandsAndTheSameViewerReceivesTheAdmittedField(GameTestHelper helper) {
        helper.onEachTick(()->LockSupport.parkNanos(50_000_000L));
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);world.clearLocalAirGeometry();world.stopLocalAirPlan();
        var at=helper.absolutePos(new BlockPos(2048,2,2048));
        var low=new BlockPos(Math.floorDiv(at.getX(),16)*16,level.getMaxBuildHeight()-16,Math.floorDiv(at.getZ(),16)*16);
        world.setTestArea(new int[]{low.getX(),low.getY(),low.getZ(),low.getX()+31,low.getY()+15,low.getZ()+15});
        var forced=new ArrayList<net.minecraft.world.level.ChunkPos>();var restore=new LinkedHashMap<BlockPos,BlockState>();
        var savedData=new LinkedHashMap<net.minecraft.world.level.chunk.LevelChunk,net.minecraft.nbt.CompoundTag>();
        for(int x=0;x<2;x++) {
            var chunk=new net.minecraft.world.level.ChunkPos((low.getX()>>4)+x,low.getZ()>>4);
            if(!level.getForcedChunks().contains(chunk.toLong())) { forced.add(chunk);level.setChunkForced(chunk.x,chunk.z,true); }
        }
        var viewer=LocalAirDiagnosticsGameTests.viewer(helper);viewer.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(dev.brights0ng.meteodyne.instrument.InstrumentContent.THERMOMETER.get()));
        Runnable cleanup=()->{LocalAirView.disable(viewer);viewer.discard();world.stopLocalAirPlan();world.clearLocalAirGeometry();world.setTestArea(null);restore.forEach((p,s)->level.setBlock(p,s,2));savedData.forEach((chunk,tag)->{chunk.getData(HeatContent.CHUNK_DATA).deserializeNBT(level.registryAccess(),tag);chunk.setUnsaved(true);});forced.forEach(c->level.setChunkForced(c.x,c.z,false));};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        for(int x=0;x<32;x++) for(int y=0;y<16;y++) for(int z=0;z<16;z++) {
            var p=low.offset(x,y,z);var chunk=level.getChunkAt(p);savedData.computeIfAbsent(chunk,c->c.getData(HeatContent.CHUNK_DATA).serializeNBT(level.registryAccess()));restore.put(p,level.getBlockState(p));
            boolean room=x>=7 && x<=23 && y>=7 && y<=9 && z>=7 && z<=9;
            level.setBlock(p,(room?Blocks.AIR:Blocks.STONE).defaultBlockState(),2);
        }
        var fire=low.offset(8,7,8);var cut=low.offset(15,8,8);
        level.setBlock(fire,Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true),2);
        level.setBlock(cut,Blocks.STONE_SLAB.defaultBlockState(),2);
        viewer.setPos(fire.getX()+.5,fire.getY()+.6,fire.getZ()+.5);LocalAirView.enable(viewer,40,LocalAirView.Mode.AUTOMATIC);
        world.planLocalAir(new LocalAirPlanner.Settings(0,1,8,8));world.startAutomaticAir(new LocalAirAutomatic.Settings(2,16384,0,4));
        var rejected=world.automaticAir().committedDomains().getFirst();
        verifyMissingView(helper,viewer);
        helper.startSequence().thenWaitUntil(()->{
            var decisions=world.automaticAir().decisions();
            helper.assertTrue(decisions.stream().anyMatch(d->d.state().equals("COVERAGE_EXPANDED") && d.reason().contains("maxX") && d.reason().contains(Integer.toString(cut.getX()))),"Coverage diagnostic retains the slab position and face after recovery: "+decisions);
        }).thenWaitUntil(()->{
            var auto=world.automaticAir();helper.assertTrue(auto.committedDomains().size()==1 && auto.cells()==8192,"One expanded two-tile owner stays within limits: "+auto.rows()+" "+auto.decisions());
            helper.assertTrue(auto.committedDomains().getFirst()!=rejected,"Rejected owner is replaced without waiting for a transfer seed");
            verifyView(helper,world,viewer,fire);
        }).thenExecute(()->{
            helper.assertTrue(rejected.preparation.status()==LocalAirPreparation.Status.CLOSED,"Rejected owner released");
            var dispatcher=level.getServer().getCommands().getDispatcher();
            var messages=new ArrayList<String>();
            var output=new net.minecraft.commands.CommandSource() {
                public void sendSystemMessage(net.minecraft.network.chat.Component message) { messages.add(message.getString()); }
                public boolean acceptsSuccess() { return true; }
                public boolean acceptsFailure() { return true; }
                public boolean shouldInformAdmins() { return false; }
            };
            helper.assertTrue(execute(dispatcher,"meteodyne heat air status",viewer.createCommandSourceStack().withPermission(2).withSource(output))==1,"Ordinary status works for the selected automatic trial");
            helper.assertTrue(messages.stream().anyMatch(s->s.startsWith("Automatic trial:")) && messages.stream().noneMatch(s->s.startsWith("Forcing:")),"Status reports the automatic runtime instead of a closed manual field: "+messages);
            messages.clear();helper.assertTrue(LocalAirView.enable(viewer,40),"Explicit manual source remains selectable");
            helper.assertTrue(execute(dispatcher,"meteodyne heat air status",viewer.createCommandSourceStack().withPermission(2).withSource(output))==1,"Manual status remains selectable alongside automatic runtime");
            helper.assertTrue(messages.stream().anyMatch(s->s.startsWith("Forcing:")) && messages.stream().noneMatch(s->s.startsWith("Automatic trial:")),"An explicit manual view selects manual status even without a manual request: "+messages);
        }).thenSucceed();
    }
    private static LocalAirViewNetwork.Payload verifyView(GameTestHelper helper,HeatWorld world,net.minecraft.server.level.ServerPlayer viewer,BlockPos fire) {
        viewer.setPos(fire.getX()+.5,fire.getY()+.6,fire.getZ()+.5);
        var expected=world.queryAutomaticLocalAir(fire.getX()+.5,fire.getY()+1.5,fire.getZ()+.5,40);
        helper.assertTrue(expected.currentSample().isPresent(),"Automatic body point is current at "+fire+": "+expected+"; tick "+viewer.level().getGameTime());
        var actual=LocalAirView.reading(viewer);helper.assertTrue(actual.equals(expected),"Trial and public automatic query select the same owner and sample");
        var stack=viewer.getInventory().getItem(0);stack.getItem().inventoryTick(stack,viewer.level(),viewer,0,true);
        var reading=stack.get(dev.brights0ng.meteodyne.instrument.InstrumentContent.READING.get());var sample=actual.currentSample().orElseThrow();
        helper.assertTrue(reading!=null && reading.value()==(float)sample.temperature() && reading.time()==sample.throughTick(),"Held thermometer retains automatic temperature and actual simulation timestamp");
        var data=LocalAirView.presentation(viewer);var payload=LocalAirView.payload(viewer);
        helper.assertTrue(payload.domainId()==data.domainId() && !payload.points().isEmpty(),"View uses the same committed owner");
        for(var point:payload.points()) helper.assertTrue(data.frame().bounds.contains(point.x()+.5,point.y()+.5,point.z()+.5),"One packet stays within the selected owner");
        return payload;
    }
    private static void verifyMissingView(GameTestHelper helper,net.minecraft.server.level.ServerPlayer viewer) {
        var reading=LocalAirView.reading(viewer);helper.assertTrue(reading.currentSample().isEmpty(),"Missing automatic coverage has no invented temperature");
        var stack=viewer.getInventory().getItem(0);stack.getItem().inventoryTick(stack,viewer.level(),viewer,0,true);
        helper.assertTrue(!stack.has(dev.brights0ng.meteodyne.instrument.InstrumentContent.READING.get()),"Pending/stale/unavailable coverage clears the held trial reading");
        helper.assertTrue(LocalAirView.payload(viewer).points().isEmpty(),"Missing coverage has no current arrows");
    }
    private static int execute(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher,String command,net.minecraft.commands.CommandSourceStack source) {
        try { return dispatcher.execute(command,source); }
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new GameTestAssertException(e.getMessage()); }
    }
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_automatic",timeoutTicks=1600)
    public static void livePlannerGroupsCaptureShareWorkersPublishAndRetainVerifiedSourceChanges(GameTestHelper helper) throws Exception {
        // Scheduling allowance for an unpaced GameTestServer, not a live performance measurement.
        helper.onEachTick(()->LockSupport.parkNanos(50_000_000L));
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);world.clearLocalAirGeometry();world.stopLocalAirPlan();
        // Separate chunk tickets as well as blocks from the runner's own fixture area.
        var at=helper.absolutePos(new BlockPos(1024,2,1024));var low=new BlockPos(Math.floorDiv(at.getX(),16)*16,level.getMaxBuildHeight()-16,Math.floorDiv(at.getZ(),16)*16);
        world.setTestArea(new int[]{low.getX(),low.getY(),low.getZ(),low.getX()+79,low.getY()+15,low.getZ()+15});
        var savedData=new LinkedHashMap<net.minecraft.world.level.chunk.LevelChunk,net.minecraft.nbt.CompoundTag>();
        var restore=new LinkedHashMap<BlockPos,BlockState>();var fires=List.of(low.offset(8,7,8),low.offset(24,7,8),low.offset(72,7,8));
        var forced=new ArrayList<net.minecraft.world.level.ChunkPos>();
        for(int x=0;x<5;x++) if(x<3 || x==4) {
            var chunk=new net.minecraft.world.level.ChunkPos((low.getX()>>4)+x,low.getZ()>>4);
            if(!level.getForcedChunks().contains(chunk.toLong())) { forced.add(chunk);level.setChunkForced(chunk.x,chunk.z,true); }
        }
        var viewer=LocalAirDiagnosticsGameTests.viewer(helper);viewer.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(dev.brights0ng.meteodyne.instrument.InstrumentContent.THERMOMETER.get()));
        Runnable cleanup=()->{LocalAirView.disable(viewer);viewer.discard();world.stopLocalAirPlan();world.clearLocalAirGeometry();world.setTestArea(null);restore.forEach((p,s)->level.setBlock(p,s,2));savedData.forEach((chunk,tag)->{chunk.getData(HeatContent.CHUNK_DATA).deserializeNBT(level.registryAccess(),tag);chunk.setUnsaved(true);});forced.forEach(c->level.setChunkForced(c.x,c.z,false));};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        for(int x=0;x<80;x++) if(x<48 || x>=64) for(int y=0;y<16;y++) for(int z=0;z<16;z++) {
            var p=low.offset(x,y,z);var chunk=level.getChunkAt(p);savedData.computeIfAbsent(chunk,c->c.getData(HeatContent.CHUNK_DATA).serializeNBT(level.registryAccess()));restore.put(p,level.getBlockState(p));
            boolean room=(x>=4 && x<=27 || x>=36 && x<=43 || x>=68 && x<=75) && y>=7 && y<=9 && z>=7 && z<=9;
            level.setBlock(p,(room?Blocks.AIR:Blocks.STONE).defaultBlockState(),2);
        }
        for(var p:fires) level.setBlock(p,Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true),2);
        var source=level.getServer().createCommandSourceStack().withLevel(level).withPermission(2).withSuppressedOutput();
        var dispatcher=level.getServer().getCommands().getDispatcher();
        helper.assertTrue(dispatcher.execute("meteodyne heat air plan 0 1 64 64",source)==1,"Plan trial configured");
        helper.assertTrue(dispatcher.execute("meteodyne heat air auto 2 32768 0 4",source)==1,"Automatic runtime enabled under explicit shared limits");
        helper.assertTrue(world.localAirField()==null,"Automatic domains do not replace the manual diagnostic");
        viewer.setPos(fires.getFirst().getX()+.5,fires.getFirst().getY()+.6,fires.getFirst().getZ()+.5);
        helper.assertTrue(execute(dispatcher,"meteodyne heat air view auto 40",viewer.createCommandSourceStack().withPermission(2).withSuppressedOutput())==1,"Player explicitly selects the automatic view source");
        helper.assertTrue(LocalAirView.mode(viewer)==LocalAirView.Mode.AUTOMATIC,"Source choice is retained per player");verifyMissingView(helper,viewer);
        var cache=new LocalAirViewCache();long[] savedViewOwner={-1};
        long[] owner={-1},revision={-1};var added=low.offset(40,7,8);
        var edit=low.offset(12,8,8);LocalAirAutomatic.Domain[] editOwner={null};LocalAirRun.Frame[] editBefore={null};long[] editRestarts={-1};
        helper.startSequence()
                .thenWaitUntil(()->{
                    var auto=world.automaticAir();helper.assertTrue(auto!=null && auto.domains().size()==2,"Two separated groups have two owners: "+(auto==null?"off":auto.rows()+" "+auto.decisions()));
                    for(var d:auto.domains()) helper.assertTrue(d.preparation.field()!=null && d.preparation.field().acceptedSteps>=2,"Both owners progress through the shared worker lane: "+auto.rows()+"; physical worker "+world.localAirReport().get("physicalJobInFlight"));
                })
                .thenExecute(()->{
                    var firstView=verifyView(helper,world,viewer,fires.getFirst());cache.accept(firstView.cacheFrame(),level.getGameTime());
                    var otherView=verifyView(helper,world,viewer,fires.getLast());helper.assertTrue(otherView.domainId()!=firstView.domainId(),"Moving between separated owners switches the visual field");cache.accept(otherView.cacheFrame(),level.getGameTime());
                    viewer.setPos(low.getX()+56.5,low.getY()+7.6,low.getZ()+8.5);verifyMissingView(helper,viewer);
                    var gap=LocalAirView.payload(viewer);helper.assertTrue(!gap.retainHistory(),"Leaving coverage clears markers from the previous area");cache.accept(gap.cacheFrame(),level.getGameTime());helper.assertTrue(cache.points().isEmpty(),"Client cache removes previous-area geometry");
                    helper.assertTrue(LocalAirView.enable(viewer,40),"Manual source remains selectable alongside automatic runtime");
                    helper.assertTrue(LocalAirView.reading(viewer).reason()==LocalAirReading.Reason.NO_REQUEST,"Manual view does not silently substitute an automatic field");
                    helper.assertTrue(LocalAirView.enable(viewer,40,LocalAirView.Mode.AUTOMATIC),"Automatic trial can resume without reconstructing fields");
                    cache.accept(verifyView(helper,world,viewer,fires.getFirst()).cacheFrame(),level.getGameTime());
                    helper.assertTrue(LocalAirView.scale(viewer,3),"Automatic lattice spacing is adjustable");
                    for(var point:LocalAirView.payload(viewer).points()) helper.assertTrue(Math.floorMod(point.x(),3)==0 && Math.floorMod(point.y(),3)==0 && Math.floorMod(point.z(),3)==0,"Automatic marker positions are anchored to world coordinates");
                    LocalAirView.scale(viewer,1);
                    editOwner[0]=world.automaticAir().domains().stream().filter(d->d.bounds.nx()==32).findFirst().orElseThrow();
                    editBefore[0]=editOwner[0].preparation.field();editRestarts[0]=editOwner[0].preparation.restarts();
                    level.setBlock(edit,Blocks.STONE_SLAB.defaultBlockState(),2);
                    helper.assertTrue(editOwner[0].preparation.field()==null,"Block placement immediately withdraws incompatible airflow");
                    helper.assertTrue(world.queryAutomaticLocalAir(edit.getX()+.5,edit.getY()+1.5,edit.getZ()+.5,40).currentSample().isEmpty(),"Query cannot expose the old field during block capture");
                    verifyMissingView(helper,viewer);cache.accept(LocalAirView.payload(viewer).cacheFrame(),level.getGameTime());helper.assertTrue(!cache.current(level.getGameTime()),"Invalidated owner has no current visual flow");
                })
                .thenWaitUntil(()->{
                    var frame=editOwner[0].preparation.field();
                    helper.assertTrue(frame!=null && frame.transfers==editBefore[0].transfers+1,"Ordinary slab placement retains the automatic owner through a topology handoff");
                })
                .thenExecute(()->{
                    var frame=editOwner[0].preparation.field();
                    helper.assertTrue(frame.initialEnergy==editBefore[0].initialEnergy && frame.sourceEnergy>=editBefore[0].sourceEnergy && frame.acceptedSteps>=editBefore[0].acceptedSteps,"Block edit retains lifetime heat and time accounting");
                    helper.assertTrue(Math.abs(frame.quality.energyBalanceError())<1e-6 && editOwner[0].preparation.restarts()==editRestarts[0],"Ordinary block edit preserves the energy ledger without a cold restart");
                    editBefore[0]=frame;
                    level.setBlock(edit,Blocks.OAK_SLAB.defaultBlockState(),2);
                    helper.assertTrue(editOwner[0].preparation.field()==null,"A different material also withdraws the old field");
                })
                .thenWaitUntil(()->{
                    var frame=editOwner[0].preparation.field();
                    helper.assertTrue(frame!=null && frame.transfers==editBefore[0].transfers+1 && frame.initialEnergy==editBefore[0].initialEnergy,"Material replacement keeps unrelated stored warmth and the initial energy baseline");
                    helper.assertTrue(Math.abs(frame.quality.energyBalanceError())<1e-6 && frame.quality.maxDivergence()<=1e-7,"Replacement field is balanced and freshly projected");
                })
                .thenExecute(()->{
                    var auto=world.automaticAir();helper.assertTrue(auto.cells()==12288,"No empty bridge between separated groups");
                    for(var p:List.of(fires.getFirst(),fires.getLast())) {
                        var reading=world.queryAutomaticLocalAir(p.getX()+.5,p.getY()+1.5,p.getZ()+.5,40);
                        helper.assertTrue(reading.currentSample().isPresent(),"Automatic point query returns a current field");
                    }
                    var first=auto.domains().stream().filter(d->d.bounds.nx()==32).findFirst().orElseThrow();var frame=first.preparation.field();
                    helper.assertTrue(frame.flow.components()==1 && frame.sourceEnergy>0,"The two touching tiles share one connected heated solve");
                    helper.assertTrue(frame.quality.maxDivergence()<=1e-7,"Published field meets the existing pressure limit");
                    owner[0]=first.id;revision[0]=frame.sourceRevision;
                    level.setBlock(fires.getFirst(),level.getBlockState(fires.getFirst()).setValue(BlockStateProperties.LIT,false),2);
                    helper.assertTrue(execute(dispatcher,"meteodyne heat air auto",source)==1,"Per-owner runtime diagnostics are available");
                })
                .thenWaitUntil(()->{
                    var d=world.automaticAir().domains().stream().filter(v->v.id==owner[0]).findFirst().orElseThrow();var frame=d.preparation.field();
                    helper.assertTrue(frame!=null && frame.sourceRevision>revision[0],"Verified source change retains the automatic owner and refreshes forcing");
                })
                .thenExecute(()->{
                    level.setBlock(fires.getFirst(),level.getBlockState(fires.getFirst()).setValue(BlockStateProperties.LIT,true),2);
                    level.setBlock(added,Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true),2);
                })
                .thenWaitUntil(()->helper.assertTrue(world.queryAutomaticLocalAir(added.getX()+.5,added.getY()+1.5,added.getZ()+.5,40).status()==LocalAirReading.Status.PENDING,"Newly requested coverage waits for an atomic replacement"))
                .thenExecute(()->{
                    viewer.setPos(added.getX()+.5,added.getY()+.6,added.getZ()+.5);verifyMissingView(helper,viewer);
                    helper.assertTrue(!LocalAirView.payload(viewer).retainHistory(),"Uncommitted target cannot supply old-domain markers");
                })
                .thenWaitUntil(()->{
                    var auto=world.automaticAir();helper.assertTrue(auto.transfers()>=1,"Growth commits a retained layout: "+auto.transferState()+" "+auto.decisions());
                    helper.assertTrue(auto.committedDomains().stream().anyMatch(d->d.bounds.nx()==48 && d.preparation.field()!=null),"Expanded owner publishes after projection and chronological catch-up");
                })
                .thenExecute(()->{
                    var auto=world.automaticAir();helper.assertTrue(auto.lastTransfer().donors()==1 && auto.lastTransfer().targets()==1,"Distant owner does not rebuild for local growth");
                    var grown=auto.committedDomains().stream().filter(d->d.bounds.nx()==48).findFirst().orElseThrow();
                    helper.assertTrue(grown.preparation.captureReusedCells()>=8000 && grown.preparation.captureReadCells()<4200,"Growth reuses donor geometry instead of recapturing every overlapping block");
                    helper.assertTrue(grown.preparation.thermalReusedCells()>=8192 && grown.preparation.thermalReadCells()==4096,"Growth reads saved/legacy thermal inputs only for newly represented cells");
                    helper.assertTrue(grown.preparation.startup().firstFieldMs()!=null,"Automatic startup records the first publication separately from worker time");
                    System.out.println("LIVE_GROWTH_REUSE reads="+grown.preparation.captureReadCells()+" reused="+grown.preparation.captureReusedCells()+" thermalReads="+grown.preparation.thermalReadCells()+" thermalReused="+grown.preparation.thermalReusedCells()+" startup="+grown.preparation.startup());
                    helper.assertTrue(Math.abs(auto.lastTransfer().error())<1e-6 && auto.lastTransfer().incomingEnergy()!=0,"Added capacity is explicitly balanced");
                    var reading=world.queryAutomaticLocalAir(fires.getFirst().getX()+.5,fires.getFirst().getY()+1.5,fires.getFirst().getZ()+.5,40);
                    helper.assertTrue(reading.currentSample().isPresent(),"Retained room is readable after expansion");
                    verifyView(helper,world,viewer,added);
                    level.setBlock(added,level.getBlockState(added).setValue(BlockStateProperties.LIT,false),2);
                })
                .thenWaitUntil(()->{
                    var auto=world.automaticAir();helper.assertTrue(auto.transfers()>=2 && auto.committedDomains().stream().anyMatch(d->d.bounds.nx()==32 && d.preparation.field()!=null),"Expired demand shrinks through a retained transfer: "+auto.transferState()+" "+auto.decisions());
                })
                .thenExecute(()->{
                    var transfer=world.automaticAir().lastTransfer();helper.assertTrue(transfer.outgoingEnergy()!=0 && Math.abs(transfer.error())<1e-6,"Removed capacity leaves through the layout energy ledger");
                    verifyMissingView(helper,viewer);helper.assertTrue(!LocalAirView.payload(viewer).retainHistory(),"Shrinking coverage clears removed-owner markers");
                })
                .thenWaitUntil(()->savedViewOwner[0]=verifyView(helper,world,viewer,fires.getLast()).domainId())
                .thenExecute(()->{
                    var command=String.format(Locale.ROOT,"meteodyne heat air auto sample 40 %.1f %.1f %.1f",fires.getLast().getX()+.5,fires.getLast().getY()+1.5,fires.getLast().getZ()+.5);
                    helper.assertTrue(execute(dispatcher,command,source)==1,"Command samples automatic fields");
                    helper.assertTrue(execute(dispatcher,"meteodyne heat air auto off",source)==1,"Explicit off releases automatic owners");
                    var savedProbe=fires.getLast().above();var chunk=level.getChunkAt(savedProbe);
                    helper.assertTrue(chunk.getData(HeatContent.CHUNK_DATA).airValue(savedProbe.asLong())!=null,"Automatic shutdown saves accepted mixed/placed thermal state through the real chunk attachment");
                    helper.assertTrue(world.queryAutomaticLocalAir(low.getX()+8.5,low.getY()+8.5,low.getZ()+8.5,40).reason()==LocalAirReading.Reason.NO_REQUEST,"Stopped runtime cannot publish an old field");
                    verifyMissingView(helper,viewer);helper.assertTrue(LocalAirView.active(viewer),"A stopped runtime leaves an explicitly waiting automatic view, without weather fallback");
                    var encoded=chunk.getData(HeatContent.CHUNK_DATA).serializeNBT(level.registryAccess());chunk.getData(HeatContent.CHUNK_DATA).deserializeNBT(level.registryAccess(),encoded);
                    helper.assertTrue(execute(dispatcher,"meteodyne heat air auto 2 32768 0 4",source)==1,"Runtime can restart from round-tripped chunk state");verifyMissingView(helper,viewer);
                })
                .thenWaitUntil(()->{
                    var payload=verifyView(helper,world,viewer,fires.getLast());var data=LocalAirView.presentation(viewer);
                    helper.assertTrue(payload.domainId()!=savedViewOwner[0] && data.frame().restoredCells>0,"New owner reads restored warmth instead of retaining the retired field");
                    helper.assertTrue(data.reading().currentSample().orElseThrow().temperature()>data.frame().weather.ambient(),"Saved warm air is visible to the held thermometer after restart");
                })
                .thenExecute(()->{
                    LocalAirView.disable(viewer);helper.assertTrue(!LocalAirView.active(viewer) && !viewer.getInventory().getItem(0).has(dev.brights0ng.meteodyne.instrument.InstrumentContent.READING.get()),"Opt-out clears trial item state");
                }).thenSucceed();
    }
}
