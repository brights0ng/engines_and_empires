// Updated by ChatGPT: checked qualified natural temperature initialization.
// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirThermalGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_thermal_storage")
    public static void chunkSaveRoundTripsIndependentStatesMigratesLegacyAndPreservesUnknownVersions(GameTestHelper helper) {
        var provider=helper.getLevel().registryAccess();var pos=helper.absolutePos(new BlockPos(2,2,2)).asLong();
        var data=new HeatChunkData();data.put(pos,7);data.addBox(new int[]{0,0,0,3,3,3});
        var old=data.serializeNBT(provider);helper.assertTrue(!old.contains("local_air"),"Existing format remains unchanged when no local-air state exists");
        var loaded=new HeatChunkData();loaded.deserializeNBT(provider,old);helper.assertTrue(loaded.value(pos)==7 && loaded.airValue(pos)==null,"Legacy save remains readable without fabricating phase history");
        var shape=new LocalAirGeometryCapture.Shape(new int[]{7,0,0,9,16,16},new int[]{0,0,0,16,4,16});
        var material=new LocalAirThermalState.Material(shape,1,2,3,4);
        loaded.putAir(pos,new LocalAirThermalState.Saved(material,100,new double[]{11,22,33,44}));
        loaded.putAir(pos,new LocalAirThermalState.Saved(material,99,new double[]{1,2,3,4}));
        var encoded=loaded.serializeNBT(provider);var again=new HeatChunkData();again.deserializeNBT(provider,encoded);
        helper.assertTrue(again.airValue(pos).material.equals(material) && Arrays.equals(again.airValue(pos).offsets(),new double[]{11,22,33,44}),"Air pockets, wall and fluid remain independent, and an older checkpoint cannot overwrite newer warmth");
        again.clearBox(new int[]{-30000000,-2048,-30000000,30000000,2047,30000000});
        helper.assertTrue(again.airValue(pos)!=null,"Legacy cooling and box cleanup cannot erase the independent coupled field");
        helper.assertTrue(loaded.value(pos)==7 && encoded.getCompound("local_air").getInt("version")==1,"Versioned local-air state coexists with unchanged legacy data");
        again.putAir(pos,new LocalAirThermalState.Saved(material,101,new double[]{0,0,0,0}));
        helper.assertTrue(again.airValue(pos)!=null && again.airValue(pos).offsets()[0]==0,"Explicit zero prevents resurrecting an older warm estimate");
        var future=encoded.copy();future.getCompound("local_air").putInt("version",99);again.deserializeNBT(provider,future);
        boolean rejected=false;try { again.airValue(pos); } catch(IllegalStateException e) { rejected=true; }
        helper.assertTrue(rejected && again.serializeNBT(provider).getCompound("local_air").equals(future.getCompound("local_air")),"Unknown versions are retained and explicitly rejected, not erased");
        var malformed=encoded.copy();malformed.getCompound("local_air").getList("cells",Tag.TAG_COMPOUND).getCompound(0).putLongArray("offsets",new long[]{Double.doubleToLongBits(Double.NaN)});
        again.deserializeNBT(provider,malformed);rejected=false;try { again.airValue(pos); } catch(IllegalStateException e) { rejected=true; }
        helper.assertTrue(rejected,"Corrupt thermal arrays cannot enter the solver");helper.succeed();
    }

    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_thermal_natural",timeoutTicks=100)
    public static void naturalArrivalReusesSettledLegacyHeatThenRestoresMixedRoomFromChunkNBT(GameTestHelper helper) {
        var level=helper.getLevel();var world=HeatWorld.of(level);world.stopLocalAirPlan();world.setPaused(true);world.forgetRegions();
        var low=helper.absolutePos(new BlockPos(2,2,2));var source=low.offset(2,1,2);var probe=source.above();
        var b=new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),5,5,5);
        var restore=new LinkedHashMap<BlockPos,BlockState>();var chunks=new LinkedHashMap<net.minecraft.world.level.chunk.LevelChunk,CompoundTag>();
        var area=new int[]{low.getX(),low.getY(),low.getZ(),low.getX()+4,low.getY()+4,low.getZ()+4};
        world.setTestArea(area);world.setExtraAnchors(List.of(Vec3.atCenterOf(probe)));
        LocalAirPreparation first=null,second=null;
        try {
            for(int z=0;z<5;z++) for(int y=0;y<5;y++) for(int x=0;x<5;x++) {
                var at=low.offset(x,y,z);var chunk=level.getChunkAt(at);chunks.computeIfAbsent(chunk,c->c.getData(HeatContent.CHUNK_DATA).serializeNBT(level.registryAccess()));
                restore.put(at,level.getBlockState(at));level.setBlock(at,(x==0 || y==0 || z==0 || x==4 || y==4 || z==4?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
            }
            world.clearSaved(area);for(var chunk:chunks.keySet()) chunk.getData(HeatContent.CHUNK_DATA).clearAir(area);level.setBlock(source,Blocks.LAVA.defaultBlockState(),2);
            var ws=new LocalAirWorldSource(level,()->1);var capture=new LocalAirGeometryCapture(b,125,ws);capture.advance(125,Long.MAX_VALUE);var snapshot=capture.snapshot();
            var waiting=world.beginAirThermal(snapshot);int sourceCell=b.index(2,1,2);
            helper.assertTrue(snapshot.natural(sourceCell) && waiting.read(sourceCell)==null,"Natural arrival waits rather than publishing a cold field as initialized");
            world.runNow(0);var legacy=world.regions().stream().filter(r->r.published()!=null && r.published().contains(probe.getX(),probe.getY(),probe.getZ())).findFirst().orElseThrow();
            var old=legacy.published();double offset=old.offsets()[old.index(probe.getX()-old.minX(),probe.getY()-old.minY(),probe.getZ()-old.minZ())];
            helper.assertTrue(offset>.1 && legacy.naturalOnly(),"Existing thermal-only settling produced an already-hot lava cave");
            first=prepare(ws,b,world,level.getGameTime());var f=first.field();var seed=first.thermalCheckpoint();
            var reading=first.query(level.getGameTime(),probe.getX()+.5,probe.getY()+.5,probe.getZ()+.5,0).currentSample().orElseThrow();
            helper.assertTrue(reading.temperature()>20+offset && firstEstimateQualified(f) && f.acceptedSteps==0 && f.sourceEnergy==0,"Refined initial temperature improves on the legacy estimate without advancing gameplay time or counting historical source energy");
            helper.assertTrue(!f.persistentThermal,"Natural-only thermal state remains transient");world.persistAir(seed,null);
            for(var chunk:chunks.keySet()) helper.assertTrue(chunk.getData(HeatContent.CHUNK_DATA).airCells()==0,"Natural-only domain was not saved");
            // Upgrade the same fixture to a mixed room: a placed campfire provides persistent ownership.
            first.close();level.setBlock(low.offset(1,1,1),Blocks.CAMPFIRE.defaultBlockState(),2);world.runNow(0);
            first=prepare(new LocalAirWorldSource(level,()->1),b,world,level.getGameTime());seed=first.thermalCheckpoint();f=first.field();
            helper.assertTrue(f.persistentThermal,"Placed plus natural heat follows the mixed-region persistence policy");world.persistAir(seed,null);
            for(var chunk:chunks.keySet()) { var data=chunk.getData(HeatContent.CHUNK_DATA);var encoded=data.serializeNBT(level.registryAccess());var fresh=new HeatChunkData();fresh.deserializeNBT(level.registryAccess(),encoded);chunk.setData(HeatContent.CHUNK_DATA,fresh); }
            world.forgetRegions();second=prepare(new LocalAirWorldSource(level,()->1),b,world,level.getGameTime());var restored=second.field();
            helper.assertTrue(restored.restoredCells==125 && restored.sourceEnergy==0 && restored.acceptedSteps==0,"Reload restores every represented thermal cell with fresh work arrays and a fresh ledger");
            for(int state=0;state<seed.prepared().thermal().states();state++) helper.assertTrue(Math.abs(restored.temperature(state)-f.temperature(state))<1e-10,"Thermal phase survives chunk NBT round-trip");
            helper.assertTrue(restored.quality.maxDivergence()<=1e-7 && Math.abs(restored.quality.energyBalanceError())<1e-7,"Restored publication meets existing numerical acceptance");
            Meteodyne.LOGGER.info("Local-air natural initialization: legacy arrival offset {} C; restored {} cells",offset,restored.restoredCells);
            helper.succeed();
        } finally {
            if(first!=null) first.close();if(second!=null) second.close();world.forgetRegions();world.setExtraAnchors(List.of());world.setTestArea(null);
            restore.forEach((p,s)->level.setBlock(p,s,2));chunks.forEach((chunk,tag)->chunk.getData(HeatContent.CHUNK_DATA).deserializeNBT(level.registryAccess(),tag));world.setPaused(false);
        }
    }
    private static boolean firstEstimateQualified(LocalAirRun.Frame frame) { return frame.naturalInitialization!=null && frame.acceptedSteps==0 && frame.quality.maxDivergence()<=1e-7; }
    private static LocalAirPreparation prepare(LocalAirWorldSource source,LocalAirGeometryCapture.Bounds bounds,HeatWorld world,long tick) {
        var prep=new LocalAirPreparation(source,bounds,125,0,new LocalAirRun.Settings(20,0,0,4));prep.thermalInitialization(world::beginAirThermal);
        for(int i=0;i<10000 && prep.field()==null;i++) { prep.observe(tick,1);prep.advance(125,Long.MAX_VALUE);prep.observe(tick,1);var job=prep.takeWork();if(job!=null) prep.complete(job.run()); }
        if(prep.field()==null) throw new GameTestAssertException(prep.status()+" "+prep.runReason());return prep;
    }
}
