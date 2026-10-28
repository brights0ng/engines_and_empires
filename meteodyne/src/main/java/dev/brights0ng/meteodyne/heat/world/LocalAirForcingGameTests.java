// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.heat.air.AirParams;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LocalAirForcingGameTests {
    @GameTest(template=GameTestStructures.EMPTY,batch="local_air_forcing",timeoutTicks=1000)
    public static void liveBlockEntityPowerSharesWarmThenCoolWithoutRecapturingGeometry(GameTestHelper helper) {
        var level=helper.getLevel();var low=helper.absolutePos(new BlockPos(2,2,2));
        var at=low.offset(2,2,2);var restore=new LinkedHashMap<BlockPos,BlockState>();
        var hot=Meteodyne.id("test_air_hot");var cold=Meteodyne.id("test_air_cold");
        HeatDrivers.register(hot,(l,p,s,be)->{ if(!l.getServer().isSameThread()) throw new AssertionError("Driver read on worker");return ((BarrelBlockEntity)be).getItem(0).getCount()/4.0; });
        HeatDrivers.register(cold,(l,p,s,be)->((BarrelBlockEntity)be).getItem(1).getCount()/4.0);
        var variants=List.of(new HeatPowerData.Variant(Map.of(),2,.25,false,false,500,1000,hot),new HeatPowerData.Variant(Map.of(),-1,0,false,false,500,-200,cold));
        var source=new LocalAirWorldSource(level,()->1,null,s->s.is(Blocks.BARREL)?variants:HeatTables.driven(s));
        var bounds=new LocalAirGeometryCapture.Bounds(low.getX(),low.getY(),low.getZ(),5,5,5);
        var preparation=new LocalAirPreparation(source,bounds,125,0,new LocalAirRun.Settings(20,0,0,4));
        Runnable cleanup=()->{ preparation.close();restore.forEach((p,s)->level.setBlock(p,s,2));HeatDrivers.register(hot,(l,p,s,be)->0);HeatDrivers.register(cold,(l,p,s,be)->0); };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) { }
            public void testPassed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info,GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo,GameTestInfo newInfo,GameTestRunner runner) { cleanup.run(); }
        });
        for(int z=0;z<5;z++) for(int y=0;y<5;y++) for(int x=0;x<5;x++) {
            var p=low.offset(x,y,z);restore.put(p,level.getBlockState(p));level.setBlock(p,(x==0 || y==0 || z==0 || x==4 || y==4 || z==4?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        level.setBlock(at,Blocks.BARREL.defaultBlockState(),2);var be=(BarrelBlockEntity)level.getBlockEntity(at);
        be.setItem(0,new ItemStack(Items.COAL,2));be.setItem(1,new ItemStack(Items.SNOWBALL,1));
        var captured=source.sample(at.getX(),at.getY(),at.getZ());helper.assertTrue(captured.driven() && captured.heat()==null,"Geometry capture records a candidate without freezing live driver power");
        var terms=source.drivenHeat(at.getX(),at.getY(),at.getZ()).terms();
        helper.assertTrue(terms.size()==2 && terms.getFirst().power()==AirParams.DEFAULT.gridPower(1,.25) && terms.getLast().power()==AirParams.DEFAULT.gridPower(-.25,0),"Independent fractional heat/cold shares exclude radiant power");
        helper.assertTrue(terms.getFirst().limit(20)==520 && terms.getLast().limit(20)==-200,"Each driver retains its existing absolute/offset limit");
        // The legacy net-power thermostat takes the strictest limit from all terms. Exercise both
        // operating modes separately; simultaneous opposing limits are qualified in the unit control.
        be.setItem(1,ItemStack.EMPTY);
        helper.onEachTick(()->{
            preparation.observe(level.getGameTime(),1);preparation.advance(256,System.nanoTime()+2_000_000);preparation.observe(level.getGameTime(),1);
            var job=preparation.takeWork();if(job!=null) preparation.complete(job.run());
        });
        LocalAirRun.Frame[] warm={null};LocalAirGeometryCapture.Prepared[] geometry={null};long[] changed={-1};
        helper.startSequence().thenWaitUntil(()->{
            var frame=preparation.field();helper.assertTrue(frame!=null && frame.acceptedSteps>=4 && frame.sourceEnergy>0,"Fractional drivers warm the real captured room: "+preparation.status()+" "+preparation.runReason());
        }).thenExecute(()->{
            warm[0]=preparation.field();geometry[0]=preparation.published();changed[0]=level.getGameTime();be.setItem(0,ItemStack.EMPTY);be.setItem(1,new ItemStack(Items.SNOWBALL,1));
        }).thenWaitUntil(()->{
            var frame=preparation.field();helper.assertTrue(frame!=null && frame.sourceRevision>warm[0].sourceRevision && frame.throughTick>=changed[0]+100 && frame.sourceEnergy<warm[0].sourceEnergy,"Switching from heating to cooling updates the retained field without a block-state edit");
        }).thenExecute(()->{
            var frame=preparation.field();helper.assertTrue(preparation.published()==geometry[0] && preparation.restarts()==0 && preparation.captureReadCells()==125,"Power-only updates retain geometry and stored material/air heat");
            helper.assertTrue(frame.initialEnergy==warm[0].initialEnergy && Math.abs(frame.quality.energyBalanceError())<1e-6 && frame.quality.maxDivergence()<=1e-7,"Driven updates retain the energy ledger and numerical quality");
            helper.assertTrue(preparation.drivenReadCells()>1 && preparation.drivenSourceCells()==1,"Driver work is observable separately from geometry reads");
        }).thenSucceed();
    }
}
