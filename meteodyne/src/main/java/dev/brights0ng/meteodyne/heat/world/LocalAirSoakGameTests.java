// Written by Claude.
// Updated by Claude: reproduced the live busy soak across a full automatic capture and checked the new counters reach summary.json.
package dev.brights0ng.meteodyne.heat.world;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonParser;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** One station churned by the real BUSY schedule, plus a short capture that writes the local-air timeline;
 * and the live soak sequence end to end, which every automatic owner must survive to a published frame. */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirSoakGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_soak",timeoutTicks=300)
    public static void busyScheduleChurnsOneStationAndCaptureWritesTimeline(GameTestHelper helper) {
        var level=helper.getLevel();var world=HeatWorld.of(level);
        var origin=helper.absolutePos(new BlockPos(3,1,3));
        int h=level.getHeight(Heightmap.Types.WORLD_SURFACE,origin.getX(),origin.getZ());
        // The station footprint (12 x 5 x 14) is larger than the 7-block structure; its own batch keeps neighbors away and every block is restored.
        Map<BlockPos,BlockState> saved=new HashMap<>();
        for(int x=-8;x<=3;x++) for(int y=-1;y<=3;y++) for(int z=-6;z<=7;z++) {
            var pos=new BlockPos(origin.getX()+x,h+y,origin.getZ()+z);saved.put(pos,level.getBlockState(pos));
        }
        Runnable cleanup=()->{
            LocalAirSoak.stop(level);world.benchmark().stop();
            var order=new ArrayList<>(saved.keySet());order.sort((a,b)->Integer.compare(b.getY(),a.getY()));
            for(var pos:order) level.setBlock(pos,saved.get(pos),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        };
        try {
            LocalAirSoak.build(level,origin,1,false);
            var station=LocalAirSoak.stations().getFirst();
            List<BlockPos> furnace=List.of(station.furnace());
            Map<BlockPos,BlockState> initial=new HashMap<>();
            for(var group:List.of(furnace,station.wheat(),station.leaves(),station.wires())) for(var pos:group) initial.put(pos,level.getBlockState(pos));
            var previous=world.benchmark().lastReport();
            LocalAirSoak.start(level,LocalAirSoak.Mode.BUSY,false);
            var source=level.getServer().createCommandSourceStack().withLevel(level);
            helper.assertTrue(world.benchmark().start(source,5,0),"capture starts during busy soak");
            boolean[] changed=new boolean[4];
            helper.onEachTick(()->{
                int i=0;
                for(var group:List.of(furnace,station.wheat(),station.leaves(),station.wires())) {
                    for(var pos:group) if(level.getBlockState(pos)!=initial.get(pos)) changed[i]=true;
                    i++;
                }
            });
            helper.runAtTickTime(220,()->{
                try {
                    helper.assertTrue(LocalAirSoak.count(LocalAirSoak.Action.FURNACE)>=1 && changed[0],"furnace toggled");
                    helper.assertTrue(LocalAirSoak.count(LocalAirSoak.Action.CROP)>=10 && changed[1],"crops aged: "+LocalAirSoak.count(LocalAirSoak.Action.CROP));
                    helper.assertTrue(LocalAirSoak.count(LocalAirSoak.Action.LEAF)>=5 && changed[2],"leaves flipped: "+LocalAirSoak.count(LocalAirSoak.Action.LEAF));
                    helper.assertTrue(LocalAirSoak.count(LocalAirSoak.Action.REDSTONE)>=20 && changed[3],"wires pulsed: "+LocalAirSoak.count(LocalAirSoak.Action.REDSTONE));
                    helper.assertTrue(LocalAirSoak.count(LocalAirSoak.Action.CHUNK)>=2,"chunk action counted even without churn");
                    var path=world.benchmark().lastReport();
                    helper.assertTrue(path!=null && !path.equals(previous),"the 5-second capture finished on its own");
                    var timeline=Files.readAllLines(path.resolve("local_air_timeline.csv"));
                    helper.assertTrue(!timeline.isEmpty() && (timeline.getFirst()+"\n").equals(HeatBenchmark.TIMELINE_HEADER),"timeline header written");
                    var json=JsonParser.parseString(Files.readString(path.resolve("summary.json"))).getAsJsonObject();
                    helper.assertTrue(json.has("localAirAvailability"),"availability summary present");
                    helper.assertTrue(json.has("soak") && json.getAsJsonObject("soak").get("mode").getAsString().equals("BUSY"),"active soak reported");
                    var atEnd=json.getAsJsonObject("localAirAtEnd");
                    helper.assertTrue(atEnd.has("revisionSites") && atEnd.has("rereadCells") && atEnd.has("requeuedColumns"),"revision sites and re-read counters reported");
                    helper.succeed();
                } catch(java.io.IOException error) { throw new IllegalStateException(error); }
                finally { cleanup.run(); }
            });
        } catch(RuntimeException error) { cleanup.run();throw error; }
    }
    /** The live sequence: air plan 4 4 32 8; air auto 4 131072 1000000 4; soak build; soak start busy. Random ticks stay on. */
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_soak_capture",timeoutTicks=2400)
    public static void soakBusyCaptureCompletes(GameTestHelper helper) {
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);world.clearLocalAirGeometry();world.stopLocalAirPlan();
        var at=helper.absolutePos(new BlockPos(3072,0,3072));
        // Station footprints (x -8..3, z -6..7) sit inside their 16-block tiles: a farmland row on a domain face
        // is a partial boundary cut that rejects coverage, which is a different failure from the live one.
        var origin=new BlockPos(Math.floorDiv(at.getX(),16)*16+12,0,Math.floorDiv(at.getZ(),16)*16+8);
        int lastX=origin.getX()+LocalAirSoak.SPACING*(LocalAirSoak.STATIONS-1);
        // Every chunk a station, its planned domain or the domain's coverage context can reach stays loaded.
        var forced=new ArrayList<ChunkPos>();var savedData=new LinkedHashMap<LevelChunk,net.minecraft.nbt.CompoundTag>();
        for(int cx=(origin.getX()>>4)-3;cx<=(lastX>>4)+3;cx++) for(int cz=(origin.getZ()>>4)-3;cz<=(origin.getZ()>>4)+3;cz++) {
            if(!level.getForcedChunks().contains(ChunkPos.asLong(cx,cz))) { forced.add(new ChunkPos(cx,cz));level.setChunkForced(cx,cz,true); }
            var chunk=level.getChunk(cx,cz);savedData.put(chunk,chunk.getData(HeatContent.CHUNK_DATA).serializeNBT(level.registryAccess()));
        }
        Map<BlockPos,BlockState> saved=new HashMap<>();
        for(int i=0;i<LocalAirSoak.STATIONS;i++) {
            var station=origin.offset(LocalAirSoak.SPACING*i,0,0);int h=level.getHeight(Heightmap.Types.WORLD_SURFACE,station.getX(),station.getZ());
            for(int x=-8;x<=3;x++) for(int y=-1;y<=3;y++) for(int z=-6;z<=7;z++) {
                var pos=new BlockPos(station.getX()+x,h+y,station.getZ()+z);saved.put(pos,level.getBlockState(pos));
            }
        }
        var randomTicks=level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING);int oldRandomTicks=randomTicks.get();
        if(oldRandomTicks<3) randomTicks.set(3,level.getServer());
        var area=new AABB(origin.getX()-32,level.getMinBuildHeight(),origin.getZ()-32,lastX+32,level.getMaxBuildHeight(),origin.getZ()+32);
        world.setTestArea(new int[]{(int)area.minX,(int)area.minY,(int)area.minZ,(int)area.maxX,(int)area.maxY-1,(int)area.maxZ});
        boolean[] cleaned={false};
        Runnable cleanup=()->{
            if(cleaned[0]) return;cleaned[0]=true;
            LocalAirSoak.stop(level);world.stopLocalAirPlan();world.clearLocalAirGeometry();world.setTestArea(null);
            var order=new ArrayList<>(saved.keySet());order.sort((a,b)->Integer.compare(b.getY(),a.getY()));
            for(var pos:order) level.setBlock(pos,saved.get(pos),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            for(var item:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,area)) item.discard();
            savedData.forEach((chunk,tag)->{chunk.getData(HeatContent.CHUNK_DATA).deserializeNBT(level.registryAccess(),tag);chunk.setUnsaved(true);});
            forced.forEach(c->level.setChunkForced(c.x,c.z,false));
            randomTicks.set(oldRandomTicks,level.getServer());
        };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        world.planLocalAir(new LocalAirPlanner.Settings(4,4,32,8));
        world.startAutomaticAir(new LocalAirAutomatic.Settings(4,131072,1_000_000,4));
        LocalAirSoak.build(level,origin,LocalAirSoak.STATIONS,true); // the code path of /meteodyne heat air soak build
        LocalAirSoak.start(level,LocalAirSoak.Mode.BUSY,true);      // ... and of soak start busy
        helper.onEachTick(()->{
            // A live tick's length, so worker jobs and the soak schedule keep their live ratio.
            LockSupport.parkNanos(50_000_000L);
            var auto=world.automaticAir();
            if(auto!=null) for(var d:auto.committedDomains()) if(d.preparation.restarts()>2) helper.fail("Domain "+d.id+" restarted "+d.preparation.restarts()+" times: "+owners(auto));
        });
        helper.startSequence().thenWaitUntil(()->{
            var auto=world.automaticAir();var committed=auto.committedDomains();
            helper.assertTrue(!committed.isEmpty(),"The soak stations get automatic owners: "+auto.decisions());
            // The run has published a frame once it is READY. field() also needs that frame to be current with the
            // 10-tick weather samples, which a loaded shared job lane need not reach within this timeout.
            for(var d:committed) helper.assertTrue(d.preparation.runStatus()==LocalAirRun.Status.READY,"Every committed owner publishes a frame: "+owners(auto));
        }).thenSucceed();
    }
    private static String owners(LocalAirAutomatic auto) {
        var out=new StringBuilder();
        for(var d:auto.domains()) out.append("\n  domain ").append(d.id).append(' ').append(d.bounds).append(" recent invalidations ").append(d.capture.dependencies().diagnostics().recent());
        for(var row:auto.rows()) out.append("\n  domain ").append(row.id()).append(' ').append(row.role()).append(' ').append(row.preparation())
                .append(" frameTick ").append(row.frameTick()).append(" restarts ").append(row.restarts()).append(" restartCauses ").append(row.restartCauses())
                .append(" revisionSites ").append(row.revisionSites()).append(" editOutcomes ").append(row.editOutcomes()).append(" (").append(row.progress()).append(")\n    run ").append(row.run()).append(' ').append(row.reason()).append(" startup ").append(row.startup());
        return out.append("\n  decisions ").append(auto.decisions()).toString();
    }
    private LocalAirSoakGameTests() { }
}
