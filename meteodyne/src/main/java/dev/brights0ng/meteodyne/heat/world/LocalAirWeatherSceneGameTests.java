// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import com.google.gson.GsonBuilder;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.wind.WindColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;

/** Opt-in, isolated 20-TPS live-provider trial. No production controls or weather overrides are added. */
@GameTestHolder(Meteodyne.MODID)
public final class LocalAirWeatherSceneGameTests {
    @GameTestGenerator
    public static List<TestFunction> scene() {
        return Boolean.getBoolean("meteodyne.airWeatherScene")?List.of(new TestFunction("local_air_weather_scene","localairweatherscene.building",
                "meteodyne_air_scene:gametest/empty",2400,0,true,h->new Trial(h).start())):List.of();
    }
    private static final class Trial {
        final GameTestHelper h;
        final net.minecraft.server.level.ServerLevel level;
        final HeatWorld world;
        final BlockPos low,door,fire;
        final BlockState doorState=Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST);
        final BlockState lit=Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT,true);
        final Map<BlockPos,BlockState> restore=new LinkedHashMap<>();
        final WindColumn oldWind=WindSources.override();
        final long oldDay,startTick,dayBase;
        final Path output=Path.of(System.getProperty("meteodyne.airSceneOutput")).resolve("weather");
        final List<Map<String,Object>> phases=new ArrayList<>();
        final StringBuilder trace=new StringBuilder("phase,tick,available,frame_tick,age_ticks,weather_revision,ambient,wind_x,wind_z,source_revision,transfers,steps,probe_c,probe_vx,energy,source_energy,exterior_energy,geometry_energy,energy_error,max_divergence\n");
        int phase;
        long phaseTick,firstField=-1,available,unavailable,maxAge,nextTick,lastWall,maxTickNanos,lateTicks;
        long acceptedAtStart;
        double initialEnergy=Double.NaN,sourceOffEnergy=Double.NaN,firstOffTemperature=Double.NaN;
        boolean capturing;
        LocalAirRun.Frame first;
        Trial(GameTestHelper helper) {
            h=helper;level=h.getLevel();world=HeatWorld.of(level);
            var origin=h.absolutePos(new BlockPos(2,2,2));low=new BlockPos(origin.getX(),level.getMaxBuildHeight()-14,origin.getZ());
            door=low.offset(6,1,5);fire=low.offset(2,1,2);
            oldDay=level.getDayTime();startTick=level.getGameTime();dayBase=Math.floorDiv(oldDay,24000)*24000;
        }
        void start() {
            world.setPaused(false);
            h.testInfo.addListener(new GameTestListener() {
                public void testStructureLoaded(GameTestInfo info) { }
                public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup(); }
                public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup(); }
                public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup(); }
            });
            for(int z=0;z<12;z++) for(int y=0;y<7;y++) for(int x=0;x<12;x++) {
                var p=low.offset(x,y,z);restore.put(p,level.getBlockState(p));
                // Open roof and east/west sides; partition and north/south walls remain physical obstacles.
                level.setBlock(p,(y==0 || z==0 || z==11 || x==6?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
            }
            setDoor(false);level.setBlock(fire,lit,2);setWeather();
            try { Files.createDirectories(output); } catch(IOException error) { throw new IllegalStateException(error); }
            capturing=world.benchmark().start(level.getServer().createCommandSourceStack().withLevel(level).withPosition(low.getCenter()),300,0);
            h.assertTrue(capturing,"Trial owns the heat-pipeline timing capture");
            world.requestLiveLocalAirSimulation(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),12,7,12),1008,10000,2);
            phaseTick=startTick;nextTick=lastWall=System.nanoTime();h.onEachTick(this::tick);
        }
        void setDoor(boolean open) {
            var state=doorState.setValue(BlockStateProperties.OPEN,open);
            level.setBlock(door,state,2);level.setBlock(door.above(),state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER),2);
        }
        void setWeather() {
            long elapsed=level.getGameTime()-phaseTick;
            level.setDayTime(dayBase+(phase==1?9000:0));
            WindSources.setOverride(switch(phase) {
                case 0 -> WindColumn.uniform(0.5,0);
                case 1 -> WindColumn.uniform(2,0);
                case 2 -> WindColumn.uniform(-2,0);
                case 3,5 -> (elapsed/10)%2==0?WindColumn.uniform(1,0.5):WindColumn.uniform(-1,-0.5);
                default -> WindColumn.CALM;
            });
        }
        void tick() {
            nextTick+=50_000_000L;long delay=nextTick-System.nanoTime();
            if(delay>0) LockSupport.parkNanos(delay);else {lateTicks++;nextTick=System.nanoTime();}
            long wall=System.nanoTime();maxTickNanos=Math.max(maxTickNanos,wall-lastWall);lastWall=wall;
            setWeather();long tick=level.getGameTime(),elapsed=tick-phaseTick;
            if(phase==3) {
                if(elapsed==30) setDoor(true);
                if(elapsed==60) setDoor(false);
                if(elapsed==80) level.setBlock(fire,lit,2);
                if(elapsed==100) level.setBlock(fire,lit.setValue(BlockStateProperties.LIT,false),2);
            }
            var f=world.localAirField();trace.append(phase).append(',').append(tick).append(',').append(f!=null);
            if(f==null) { unavailable++;trace.append(",,,,,,,,,,,,,,,,,\n");return; }
            long age=LocalAirDiagnostics.age(f,tick);available++;maxAge=Math.max(maxAge,age);
            if(firstField<0) {firstField=elapsed;first=f;}
            if(Double.isNaN(initialEnergy)) initialEnergy=f.initialEnergy;
            h.assertTrue(f.initialEnergy==initialEnergy,"All weather/edit phases retain the original energy reference");
            int node=f.flow.nodeAt(2.5,1.5,2.5);
            double temperature=f.temperature(node);
            if(phase==2 && f.sourceRevision==1 && Double.isNaN(sourceOffEnergy)) {sourceOffEnergy=f.sourceEnergy;firstOffTemperature=temperature;}
            trace.append(',').append(f.throughTick).append(',').append(age).append(',').append(f.weatherRevision).append(',').append(f.weather.ambient())
                    .append(',').append(f.weather.windX()).append(',').append(f.weather.windZ()).append(',').append(f.sourceRevision).append(',').append(f.transfers)
                    .append(',').append(f.acceptedSteps).append(',').append(temperature).append(',').append(f.velocity(node,0)).append(',').append(f.energy)
                    .append(',').append(f.sourceEnergy).append(',').append(f.exteriorEnergy).append(',').append(f.geometryEnergy)
                    .append(',').append(f.quality.energyBalanceError()).append(',').append(f.quality.maxDivergence()).append('\n');
            boolean ready=phase==0?f.acceptedSteps>=20:f.throughTick>=phaseTick+200;
            if(!ready) return;
            h.assertTrue(Double.isFinite(f.energy) && Math.abs(f.quality.energyBalanceError())<1e-5,"Trial energy remains finite and accounted");
            h.assertTrue(f.quality.maxDivergence()<1e-7,"Trial projection remains bounded");
            h.assertTrue(f.acceptedSteps>acceptedAtStart,"Every phase makes physical progress");
            if(phase==0) h.assertTrue(temperature>f.weather.ambient(),"Campfire warms the open building before edits");
            if(phase==1) {
                h.assertTrue(f.transfers==1 && f.sourceRevision==0,"Warm gust and opening preserve history");
                h.assertTrue(f.weather.ambient()>((Number)phases.get(0).get("ambient")).doubleValue()+1,"Existing climate provider supplies a warmer afternoon");
            }
            if(phase==2) {
                h.assertTrue(f.transfers==2 && f.sourceRevision==1 && f.sourceEnergy==sourceOffEnergy,"Closing and extinguishing retain the exact source-off ledger");
                h.assertTrue(f.weather.ambient()<((Number)phases.get(1).get("ambient")).doubleValue()-1,"Existing climate provider supplies a colder dawn");
                h.assertTrue(temperature<firstOffTemperature,"Retained room air cools after extinguishing under the cold reversal");
            }
            if(phase>=3) h.assertTrue(f.transfers==4 && f.sourceRevision==3,"Frequent gusts and intermediate door/source events preserve all revisions");
            if(phase>0) {
                var firstReport=(Map<?,?>)phases.get(0).get("report");
                h.assertTrue(world.localAirReport().get("coverageReadCells").equals(firstReport.get("coverageReadCells")),
                        "Interior door/source/weather events reuse all unchanged sky proofs");
            }
            int west=f.flow.nodeAt(0.5,3.5,5.5),east=f.flow.nodeAt(11.5,3.5,5.5);
            if(phase==1) h.assertTrue(f.velocity(west,0)>0.1,"Warm gust ventilates the west opening");
            if(phase==2) h.assertTrue(f.velocity(east,0)<-0.1,"Reversed cold gust ventilates the east opening");
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("phase",phase);row.put("elapsedTicks",elapsed);row.put("firstFieldTicks",firstField);row.put("availableTicks",available);row.put("unavailableTicks",unavailable);row.put("maxAgeTicks",maxAge);
            row.put("frameTick",f.throughTick);row.put("ambient",f.weather.ambient());row.put("weather",f.weather);row.put("weatherRevision",f.weatherRevision);row.put("probeC",temperature);row.put("probeVx",f.velocity(node,0));
            row.put("westC",f.temperature(west));row.put("westVx",f.velocity(west,0));row.put("eastC",f.temperature(east));row.put("eastVx",f.velocity(east,0));
            row.put("energy",f.energy);row.put("sourceEnergy",f.sourceEnergy);row.put("exteriorEnergy",f.exteriorEnergy);row.put("geometryEnergy",f.geometryEnergy);row.put("quality",f.quality);row.put("report",world.localAirReport());phases.add(row);
            if(phase==5) {finish();return;}
            acceptedAtStart=f.acceptedSteps;phase++;phaseTick=tick;firstField=-1;first=null;available=unavailable=maxAge=0;
            setWeather();
            if(phase==1) setDoor(true);
            if(phase==2) {setDoor(false);level.setBlock(fire,lit.setValue(BlockStateProperties.LIT,false),2);}
        }
        void finish() {
            h.assertTrue(world.benchmark().stop(),"Trial timing capture completes");capturing=false;
            Map<String,Object> result=new LinkedHashMap<>();result.put("writtenBy","ChatGPT");result.put("cells",1008);result.put("origin",List.of(low.getX(),low.getY(),low.getZ()));
            result.put("elapsedTicks",level.getGameTime()-startTick);result.put("maxTickMs",maxTickNanos/1e6);result.put("lateTickDeadlines",lateTicks);result.put("phases",phases);
            try {
                Files.writeString(output.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
                Files.writeString(output.resolve("trace.csv"),trace.toString());
                Files.copy(world.benchmark().lastReport().resolve("summary.json"),output.resolve("pipeline.json"),StandardCopyOption.REPLACE_EXISTING);
            } catch(IOException error) { throw new IllegalStateException(error); }
            h.succeed();
        }
        void cleanup() {
            if(capturing) {world.benchmark().stop();capturing=false;}
            world.clearLocalAirGeometry();WindSources.setOverride(oldWind);level.setDayTime(oldDay+level.getGameTime()-startTick);
            restore.forEach((p,s)->level.setBlock(p,s,2));
        }
    }
}
