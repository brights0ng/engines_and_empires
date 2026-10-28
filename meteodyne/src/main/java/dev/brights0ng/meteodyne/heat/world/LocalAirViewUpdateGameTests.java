// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import java.util.function.Consumer;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.instrument.InstrumentContent;
import dev.brights0ng.meteodyne.wind.WindSources;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.wind.WindColumn;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirViewUpdateGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_view_updates",timeoutTicks=1200)
    public static void alignedLiveWeatherStillDeliversCurrentPacketsAndGeometryEditsWithdrawThem(GameTestHelper helper) {
        var trial=new Trial(helper);trial.start();
    }
    private static final class Trial {
        final GameTestHelper helper;
        final net.minecraft.server.level.ServerLevel level;
        final HeatWorld world;
        final net.minecraft.server.level.ServerPlayer viewer;
        final Map<BlockPos,BlockState> restore=new LinkedHashMap<>();
        final WindColumn oldWind=WindSources.override();
        final LocalAirViewCache cache=new LocalAirViewCache();
        final Consumer<ServerTickEvent.Post> listener=this::post;
        BlockPos low;
        boolean closed,started;
        Throwable error;
        long lastField=-10;
        int fields,offPhase,weatherGaps,beforeEdit;
        Trial(GameTestHelper helper) {
            this.helper=helper;level=helper.getLevel();world=HeatWorld.of(level);viewer=LocalAirDiagnosticsGameTests.viewer(helper);
        }
        void start() {
            world.setPaused(false);world.stopLocalAirPlan();world.clearLocalAirGeometry();
            var at=helper.absolutePos(new BlockPos(2,2,2));low=new BlockPos(at.getX(),level.getMaxBuildHeight()-7,at.getZ());
            world.setTestArea(new int[]{low.getX(),low.getY(),low.getZ(),low.getX()+4,low.getY()+4,low.getZ()+4});
            helper.testInfo.addListener(new GameTestListener() {
                public void testStructureLoaded(GameTestInfo info) { }
                public void testPassed(GameTestInfo info,GameTestRunner runner) {cleanup();}
                public void testFailed(GameTestInfo info,GameTestRunner runner) {cleanup();}
                public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) {cleanup();}
            });
            for(int x=0;x<5;x++) for(int y=0;y<5;y++) for(int z=0;z<5;z++) {
                var p=low.offset(x,y,z);restore.put(p,level.getBlockState(p));level.setBlock(p,(x==0 || x==4 || y==0 || y==4 || z==0 || z==4?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
            }
            viewer.setPos(low.getX()+1.5,low.getY()+1.1,low.getZ()+1.5);
            viewer.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(InstrumentContent.THERMOMETER.get()));
            helper.assertTrue(LocalAirView.enable(viewer,40),"Manual view explicitly enabled");
            NeoForge.EVENT_BUS.addListener(listener);
            helper.onEachTick(()->{
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                if(error!=null) throw new GameTestAssertException("View update callback: "+error);
            });
            helper.startSequence().thenWaitUntil(()->helper.assertTrue(fields>=6 && offPhase>=6 && weatherGaps>=6,"Current packets reach the view between aligned weather gaps: fields="+fields+", offPhase="+offPhase+", gaps="+weatherGaps))
                    .thenExecute(()->{
                        beforeEdit=fields;level.setBlock(low.offset(3,2,2),Blocks.STONE.defaultBlockState(),2);
                        helper.assertTrue(world.localAirField()==null,"Geometry edit withholds the field immediately");
                        poll();helper.assertTrue(!cache.current(level.getGameTime()),"Client data has no current arrows after invalidation");
                        helper.assertTrue(!viewer.getInventory().getItem(0).has(InstrumentContent.READING.get()),"Trial thermometer also withdraws invalid geometry");
                    }).thenWaitUntil(()->helper.assertTrue(fields>=beforeEdit+2,"Rebuilt field resumes through the same publication schedule"))
                    .thenSucceed();
        }
        void post(ServerTickEvent.Post event) {
            if(closed || error!=null || event.getServer()!=level.getServer()) return;
            try {
                long now=level.getGameTime();
                if(!started) {
                    if(now%10!=0) return;
                    WindSources.setOverride(WindColumn.uniform(.5,0));
                    world.requestLiveLocalAirSimulation(new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),5,5,5),125,0,4);started=true;
                }
                if(now%10==0 && world.localAirStatus()==LocalAirPreparation.Status.PREPARED && world.localAirField()==null) weatherGaps++;
                poll();
                // Set the next input before HeatWorld's next aligned refresh, after this tick's presentation.
                if(now%10==9) WindSources.setOverride(WindColumn.uniform((now/10)%2==0?.6:.4,0));
            } catch(Throwable failure) {error=failure;}
        }
        void poll() {
            long now=level.getGameTime();var update=LocalAirView.update(viewer);var data=LocalAirView.presentation(viewer);
            var stack=viewer.getInventory().getItem(0);stack.getItem().inventoryTick(stack,level,viewer,0,true);
            if(update.payload()==null) return;
            cache.accept(update.payload().cacheFrame(),now);
            if(!update.payload().points().isEmpty()) {
                helper.assertTrue(now-lastField>=10,"Full point packets retain the ten-tick rate limit");lastField=now;fields++;if(now%10!=0) offPhase++;
                helper.assertTrue(data.reading().currentSample().isPresent() && cache.current(now),"Packet has an actually current guarded field");
                var sample=data.reading().currentSample().orElseThrow();var reading=stack.get(InstrumentContent.READING.get());
                helper.assertTrue(reading!=null && reading.time()==sample.throughTick() && reading.value()==(float)sample.temperature(),"Held thermometer matches the published field and timestamp");
                helper.assertTrue(update.caption()!=null && update.caption().getString().contains(" C | v="),"Current caption replaces pending text");
            } else helper.assertTrue(!cache.current(now),"Metadata-only withdrawal has no current arrows");
        }
        void cleanup() {
            if(closed) return;closed=true;NeoForge.EVENT_BUS.unregister(listener);LocalAirView.disable(viewer);viewer.discard();world.clearLocalAirGeometry();world.setTestArea(null);
            WindSources.setOverride(oldWind);restore.forEach((p,s)->level.setBlock(p,s,2));
        }
    }
}
