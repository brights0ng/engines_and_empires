// Written by ChatGPT.
// Updated by Claude: verified an unlit placed source keeps its demand and automatic domain while it toggles.
package dev.brights0ng.meteodyne.heat.world;

import static dev.brights0ng.meteodyne.heat.world.LocalAirDemandRegistry.*;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.instrument.InstrumentContent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.*;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirDemandGameTests {
    private static Key key(Kind kind,BlockPos p) { return new Key(kind,Long.toString(p.asLong())); }
    private static boolean active(Snapshot s,Key key) { return s.entries().stream().anyMatch(e->e.active() && e.request().key().equals(key)); }
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_demand_registry")
    public static void sourcesSensorsAndTheirLifecycleAreObservedWithoutActivatingASolver(GameTestHelper helper) throws Exception {
        var level=helper.getLevel();var world=HeatWorld.of(level);world.clearLocalAirGeometry();
        var origin=helper.absolutePos(new BlockPos(2,3,2));var fire=origin;var lava=origin.offset(2,0,0);
        var temperature=origin.offset(0,0,2);var wind=origin.offset(2,0,2);
        var restore=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(var p:List.of(fire,lava,temperature,wind)) restore.put(p,level.getBlockState(p));
        try {
            level.setBlock(fire,Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true),2);
            level.setBlock(lava,Blocks.LAVA.defaultBlockState(),2);
            level.setBlock(temperature,InstrumentContent.TEMPERATURE_SENSOR.get().defaultBlockState(),2);
            level.setBlock(wind,InstrumentContent.WIND_SENSOR.get().defaultBlockState(),2);
            var snapshot=world.localAirDemands();
            helper.assertTrue(active(snapshot,key(Kind.PLACED_SOURCE,fire)),"Loaded placed source creates demand");
            helper.assertTrue(!active(snapshot,key(Kind.PLACED_SOURCE,lava)),"Natural source does not create demand");
            helper.assertTrue(active(snapshot,key(Kind.TEMPERATURE_SENSOR,temperature)) && active(snapshot,key(Kind.WIND_SENSOR,wind)),"Placed instruments create independent demand");
            var sensor= snapshot.entries().stream().filter(e->e.request().key().equals(key(Kind.TEMPERATURE_SENSOR,temperature))).findFirst().orElseThrow();
            var sample=((dev.brights0ng.meteodyne.instrument.SensorBlockEntity)level.getBlockEntity(temperature)).samplePoint();
            helper.assertTrue(sensor.request().cell().equals(new Cell((int)Math.floor(sample.x),(int)Math.floor(sample.y),(int)Math.floor(sample.z))),"Sensor demand is in front of its face rather than in the housing");
            helper.assertTrue(world.localAirField()==null && world.localAirStatus()==LocalAirPreparation.Status.CLOSED,"Registering demand does not allocate a simulation");
            level.setBlock(fire,Blocks.AIR.defaultBlockState(),2);level.setBlock(temperature,Blocks.AIR.defaultBlockState(),2);
            snapshot=world.localAirDemands();
            helper.assertTrue(!active(snapshot,key(Kind.PLACED_SOURCE,fire)) && !active(snapshot,key(Kind.TEMPERATURE_SENSOR,temperature)),"Removal releases active source and sensor owners");
            helper.assertTrue(snapshot.entries().stream().anyMatch(e->!e.active() && e.request().key().equals(key(Kind.PLACED_SOURCE,fire))),"Removed source leaves transient grace coverage");
            var adapter=new LocalAirDemands(level);adapter.sensor(wind,level.getBlockState(wind));
            adapter.unload(wind.getX()>>4,wind.getZ()>>4);
            helper.assertTrue(adapter.registry.snapshot().entries().isEmpty(),"Housing chunk unload releases sensor and grace entries even across chunk faces");
            var source=level.getServer().createCommandSourceStack().withLevel(level).withPermission(2).withSuppressedOutput();
            helper.assertTrue(level.getServer().getCommands().getDispatcher().execute("meteodyne heat air demands",source)==1,"Demand inspection command is available without a solver");
            helper.succeed();
        } finally { restore.forEach((pos,state)->level.setBlock(pos,state,2)); }
    }
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_demand_players")
    public static void playerMovementAndHeldInstrumentsShareOneOwnerAndDepartureRetainsGrace(GameTestHelper helper) {
        var level=helper.getLevel();var adapter=new LocalAirDemands(level);var player=LocalAirDiagnosticsGameTests.viewer(helper);
        var origin=helper.absolutePos(new BlockPos(3,3,3));player.setPos(origin.getX()+.2,origin.getY(),origin.getZ()+.2);
        try {
            player.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(InstrumentContent.THERMOMETER.get()));
            player.getInventory().setItem(1,new net.minecraft.world.item.ItemStack(InstrumentContent.WIND_GAUGE.get()));
            adapter.players(List.of(player));var initial=adapter.registry.snapshot();
            helper.assertTrue(initial.entries().size()==1 && initial.entries().getFirst().request().key().kind()==Kind.PLAYER,"Held instruments reuse the player's one demand owner");
            player.setPos(player.getX()+.1,player.getY(),player.getZ());adapter.players(List.of(player));
            helper.assertTrue(adapter.registry.snapshot().revision()==initial.revision(),"Movement within a sample cell causes no coverage churn");
            player.setPos(player.getX()+1,player.getY(),player.getZ());adapter.players(List.of(player));
            helper.assertTrue(adapter.registry.snapshot().anchors().size()==2,"Crossing a cell retains previous coverage");
            adapter.players(List.of());
            helper.assertTrue(adapter.registry.snapshot().entries().stream().noneMatch(Entry::active),"Disconnect/dimension departure removes the active player");
            helper.assertTrue(!adapter.registry.snapshot().entries().isEmpty(),"Departure retains a bounded grace interval");
            adapter.clear();helper.assertTrue(adapter.registry.snapshot().entries().isEmpty(),"Shutdown/disable clears demand");helper.succeed();
        } finally { adapter.clear();player.discard(); }
    }
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_unlit_source",timeoutTicks=1200)
    public static void unlitSourceKeepsDemandAndDomain(GameTestHelper helper) {
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);world.clearLocalAirGeometry();world.stopLocalAirPlan();
        var at=helper.absolutePos(new BlockPos(3072,2,3072));
        var low=new BlockPos(Math.floorDiv(at.getX(),16)*16,level.getMaxBuildHeight()-16,Math.floorDiv(at.getZ(),16)*16);
        world.setTestArea(new int[]{low.getX(),low.getY(),low.getZ(),low.getX()+15,low.getY()+15,low.getZ()+15});
        var chunk=new net.minecraft.world.level.ChunkPos(low.getX()>>4,low.getZ()>>4);boolean forced=!level.getForcedChunks().contains(chunk.toLong());
        if(forced) level.setChunkForced(chunk.x,chunk.z,true);
        var restore=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var data=level.getChunkAt(low);var saved=data.getData(HeatContent.CHUNK_DATA).serializeNBT(level.registryAccess());
        Runnable cleanup=()->{world.stopLocalAirPlan();world.clearLocalAirGeometry();world.setTestArea(null);restore.forEach((pos,state)->level.setBlock(pos,state,2));data.getData(HeatContent.CHUNK_DATA).deserializeNBT(level.registryAccess(),saved);data.setUnsaved(true);if(forced) level.setChunkForced(chunk.x,chunk.z,false);};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        for(int x=0;x<16;x++) for(int y=0;y<16;y++) for(int z=0;z<16;z++) {
            var p=low.offset(x,y,z);restore.put(p,level.getBlockState(p));
            boolean room=x>=4 && x<=11 && y>=7 && y<=9 && z>=4 && z<=11;
            level.setBlock(p,(room?Blocks.AIR:Blocks.STONE).defaultBlockState(),2);
        }
        var fire=low.offset(8,7,8);var source=key(Kind.PLACED_SOURCE,fire);
        level.setBlock(fire,Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,false),2);
        helper.assertTrue(active(world.localAirDemands(),source),"An unlit placed source still requests coverage");
        world.planLocalAir(new LocalAirPlanner.Settings(0,1,8,8));world.startAutomaticAir(new LocalAirAutomatic.Settings(2,16384,0,4));
        // Ticks since toggling started (-1 before), owner ids seen and ticks without an active demand or owner.
        long[] toggling={-1,0,0};var owners=new LinkedHashSet<Long>();
        helper.onEachTick(()->{
            LockSupport.parkNanos(50_000_000L);if(toggling[0]<0) return;
            if(toggling[0]>0 && toggling[0]%60==0 && toggling[0]<=300) {
                var state=level.getBlockState(fire);level.setBlock(fire,state.setValue(BlockStateProperties.LIT,!state.getValue(BlockStateProperties.LIT)),2);
            }
            if(!active(world.localAirDemands(),source)) toggling[1]++;
            var owner=owner(world,fire);if(owner==null) toggling[2]++;else owners.add(owner.id);
            toggling[0]++;
        });
        helper.startSequence().thenWaitUntil(()->{
            var owner=owner(world,fire);
            helper.assertTrue(owner!=null && owner.preparation.field()!=null,"An automatic owner covers the unlit source: "+world.automaticAir().rows()+" "+world.automaticAir().decisions());
        }).thenExecute(()->{ owners.add(owner(world,fire).id);toggling[0]=0; }).thenWaitUntil(()->{
            helper.assertTrue(toggling[0]>300,"Toggling for 300 ticks");
        }).thenExecute(()->{
            helper.assertTrue(toggling[1]==0,"The placed-source demand stays active while unlit: "+toggling[1]+" ticks without it");
            helper.assertTrue(toggling[2]==0 && owners.size()==1,"One automatic owner throughout: "+owners+", "+toggling[2]+" ticks without one; "+world.automaticAir().decisions());
        }).thenSucceed();
    }
    private static LocalAirAutomatic.Domain owner(HeatWorld world,BlockPos p) {
        var auto=world.automaticAir();if(auto==null) return null;
        for(var d:auto.committedDomains()) if(d.bounds.contains(p.getX()+.5,p.getY()+.5,p.getZ()+.5)) return d;
        return null;
    }
}
