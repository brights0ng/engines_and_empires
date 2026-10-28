// Written by Claude.
package dev.brights0ng.meteodyne.heat.world;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonParser;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** One station churned by the real BUSY schedule, plus a short capture that writes the local-air timeline. */
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
                    helper.succeed();
                } catch(java.io.IOException error) { throw new IllegalStateException(error); }
                finally { cleanup.run(); }
            });
        } catch(RuntimeException error) { cleanup.run();throw error; }
    }
    private LocalAirSoakGameTests() { }
}
