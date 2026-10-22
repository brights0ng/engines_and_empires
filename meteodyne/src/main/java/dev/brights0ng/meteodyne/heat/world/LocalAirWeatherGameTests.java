// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.LinkedHashMap;
import java.util.concurrent.locks.LockSupport;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.wind.WindColumn;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirWeatherGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_weather",timeoutTicks=2400)
    public static void liveProviderSnapshotsAndGustsRetainThePreparedRoom(GameTestHelper helper) {
        helper.onEachTick(()->LockSupport.parkNanos(1_000_000L));
        var level=helper.getLevel();var world=HeatWorld.of(level);world.setPaused(false);
        var low=helper.absolutePos(new BlockPos(2,2,2));var restore=new LinkedHashMap<BlockPos,BlockState>();
        var previousWind=WindSources.override();
        Runnable cleanup=()->{world.clearLocalAirGeometry();WindSources.setOverride(previousWind);restore.forEach((p,s)->level.setBlock(p,s,2));};
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        for(int z=0;z<5;z++) for(int y=0;y<5;y++) for(int x=0;x<5;x++) {
            var p=low.offset(x,y,z);restore.put(p,level.getBlockState(p));
            level.setBlock(p,(x==0 || x==4 || y==0 || y==4 || z==0 || z==4?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        var bounds=new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),5,5,5);
        WindSources.setOverride(WindColumn.uniform(0.5,-0.25));
        var expected=LocalAirWeatherSource.sample(level,bounds);
        world.requestLiveLocalAirSimulation(bounds,125,0,2);
        helper.assertTrue(expected.equals(world.localAirReport().get("sampledWeather")),"Live request samples the existing climate and surface-wind providers");
        LocalAirGeometryCapture.Prepared[] geometry={null};LocalAirRun.Frame[] retained={null};
        helper.startSequence()
                .thenWaitUntil(()->helper.assertTrue(world.localAirField()!=null && world.localAirField().acceptedSteps>=5,"Live field advances"))
                .thenExecute(()->{
                    retained[0]=world.localAirField();geometry[0]=world.localAirGeometry();
                    helper.assertTrue(retained[0].weather.windX()==0.5 && retained[0].weather.windZ()==-0.25,"Initial provider wind reaches worker snapshots");
                    WindSources.setOverride(WindColumn.uniform(-0.75,0.5));
                })
                .thenWaitUntil(()->{
                    var f=world.localAirField();helper.assertTrue(f!=null && f.weather.windX()==-0.75 && f.weather.windZ()==0.5,"Gust reversal reaches a current published frame");
                    helper.assertTrue(world.localAirGeometry()==geometry[0],"Weather does not recapture geometry");
                    helper.assertTrue(f.acceptedSteps>=retained[0].acceptedSteps && f.initialEnergy==retained[0].initialEnergy,"Gust retains progress and initial energy");
                    helper.assertTrue(f.exteriorEnergy==0 && Math.abs(f.quality.energyBalanceError())<1e-6,"Closed room has no invented exterior heat");
                    helper.assertTrue(f.weatherRevision>retained[0].weatherRevision && f.transfers==0,"Gust is a chronological weather event");
                })
                .thenSucceed();
    }
}
