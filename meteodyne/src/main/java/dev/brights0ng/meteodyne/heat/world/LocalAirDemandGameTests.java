// Written by ChatGPT.
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
}
