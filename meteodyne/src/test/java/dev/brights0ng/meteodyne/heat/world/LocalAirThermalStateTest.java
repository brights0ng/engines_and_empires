// Updated by ChatGPT: qualified refined arrival accuracy and initialization lifecycle barriers.
// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirThermalStateTest {
    private static final Bounds BOX=new Bounds(0,0,0,5,5,5);
    private static final Cell PARTIAL=new Cell(new Shape(new int[]{7,0,0,9,16,16},new int[]{0,0,0,16,4,16}),1,2,3,4);
    private static class World extends LocalAirPreparationTest.World {
        @Override public Cell sample(int x,int y,int z) {
            reads++;return x==0 || y==0 || z==0 || x==4 || y==4 || z==4?LocalAirPreparationTest.SOLID:x==2 && y==2 && z==2?PARTIAL:LocalAirPreparationTest.AIR;
        }
    }
    private static Snapshot capture(World w) { var c=new LocalAirGeometryCapture(BOX,125,w);c.advance(125,Long.MAX_VALUE);return c.snapshot(); }
    private static LocalAirRun run(Snapshot s,Prepared p,LocalAirThermalState.Initial initial,long tick,double ambient) {
        return new LocalAirRun(p,s,new LocalAirRun.Settings(ambient,0,0,4),tick,1,null,new LocalAirRun.Weather(ambient,0,0),0,null,Map.of(),initial);
    }
    @Test void independentPocketsSolidAndFluidRoundTripWithNewPressureAndNoDuplicatedSourceHistory() {
        var s=capture(new World());var p=s.bakeClosedControl(dev.brights0ng.paradynelib.heat.air.ThermalGeometry.Coefficients.legacyMaterials());
        int c=BOX.index(2,2,2);int[] states=LocalAirThermalState.states(p,c);assertEquals(4,states.length);
        double[] offsets={11,22,33,44};var saved=new LocalAirThermalState.Saved(s.material(c),9,offsets);offsets[0]=900;
        var cells=new LocalAirThermalState.Value[125];Arrays.fill(cells,LocalAirThermalState.Value.ambient());cells[c]=new LocalAirThermalState.Value(saved,0,true,"SAVED_COUPLED");
        var first=run(s,p,new LocalAirThermalState.Initial(s,cells),10,20);first.complete(first.takeWork().run());var f=first.published();assertNotNull(f,first.reason());
        for(int k=0;k<states.length;k++) assertEquals(20+11*(k+1),f.temperature(states[k]),1e-12);
        var encoded=LocalAirThermalState.save(new LocalAirRun.Seed(p,s,f),c);cells[c]=new LocalAirThermalState.Value(encoded,0,true,"SAVED_COUPLED");
        var second=run(s,p,new LocalAirThermalState.Initial(s,cells),900,10);second.complete(second.takeWork().run());var next=second.published();assertNotNull(next,second.reason());
        for(int k=0;k<states.length;k++) assertEquals(10+11*(k+1),next.temperature(states[k]),1e-12);
        assertEquals(0,next.acceptedSteps);assertEquals(0,next.sourceEnergy);assertEquals(0,next.thermalSeconds);assertEquals(0,next.quality.energyBalanceError(),1e-8);
        assertTrue(next.persistentThermal);assertTrue(next.quality.maxDivergence()<=1e-7);
        var escaped=encoded.offsets();escaped[0]=999;assertEquals(11,encoded.offsets()[0]);
    }
    @Test void incompatibleMaterialOrPocketCountUsesExplicitEstimateRatherThanMixingStates() {
        var s=capture(new World());var p=s.bakeClosedControl(dev.brights0ng.paradynelib.heat.air.ThermalGeometry.Coefficients.legacyMaterials());int c=BOX.index(2,2,2);
        var values=new LocalAirThermalState.Value[125];Arrays.fill(values,LocalAirThermalState.Value.ambient());
        var wrong=new LocalAirThermalState.Material(PARTIAL.shape(),2,2,3,4);
        values[c]=new LocalAirThermalState.Value(new LocalAirThermalState.Saved(wrong,1,new double[]{30,40,50,60}),5,true,"SAVED_COUPLED");
        var temperatures=new LocalAirThermalState.Initial(s,values).temperatures(p,20);for(int state:LocalAirThermalState.states(p,c)) assertEquals(25,temperatures[state]);
        values[c]=new LocalAirThermalState.Value(new LocalAirThermalState.Saved(s.material(c),1,new double[]{30}),7,true,"SAVED_COUPLED");
        temperatures=new LocalAirThermalState.Initial(s,values).temperatures(p,20);for(int state:LocalAirThermalState.states(p,c)) assertEquals(27,temperatures[state]);
        assertThrows(IllegalArgumentException.class,()->new LocalAirThermalState.Saved(wrong,1,new double[]{Double.NaN}));
    }
    @Test void preparationWaitsForNaturalEstimateAndSlicesReadsOnOwnerThread() throws Exception {
        var w=new World();var prep=new LocalAirPreparation(w,BOX,125,0,new LocalAirRun.Settings(20,0,0,4));
        boolean[] available={false};int[] read={0};Thread owner=Thread.currentThread();
        prep.thermalInitialization(s->!available[0]?null:c->{assertSame(owner,Thread.currentThread());read[0]++;return new LocalAirThermalState.Value(null,8,false,"LEGACY_THERMAL_ESTIMATE");});
        for(int i=0;i<30;i++) {prep.observe(0,1);prep.advance(125,Long.MAX_VALUE);var job=prep.takeWork();if(job!=null) prep.complete(job.run());}
        assertNull(prep.field());assertTrue(prep.runReason().contains("natural-source"));assertEquals(0,read[0]);available[0]=true;
        assertEquals(0,prep.advance(0,Long.MAX_VALUE));assertEquals(0,prep.advance(3,System.nanoTime()-1));
        assertEquals(3,prep.advance(3,Long.MAX_VALUE));assertEquals(3,read[0]);assertNull(prep.field());
        for(int i=0;i<50;i++) { assertTrue(prep.advance(3,Long.MAX_VALUE)<=3);prep.observe(0,1);var job=prep.takeWork();if(job!=null) {var result=new java.util.concurrent.atomic.AtomicReference<LocalAirPreparation.Result>();var worker=new Thread(()->result.set(job.run()));worker.start();worker.join();prep.complete(result.get());} }
        var f=prep.field();assertNotNull(f,prep.runReason());assertEquals(125,read[0]);assertEquals(28,f.temperature(f.flow.nodeAt(1.5,1.5,1.5)));assertFalse(f.persistentThermal);assertEquals(0,f.sourceEnergy);assertEquals(0,f.acceptedSteps);
        assertEquals(125,f.estimatedCells);prep.close();
    }
    @Test void staleInitializationCannotPublishAfterWorldChanges() {
        var w=new World();var prep=new LocalAirPreparation(w,BOX,125,0,new LocalAirRun.Settings(20,0,0,4));
        prep.thermalInitialization(s->c->new LocalAirThermalState.Value(null,8,false,"ESTIMATE"));
        LocalAirPreparation.Work held=null;
        for(int i=0;i<20;i++) {prep.observe(0,1);prep.advance(125,Long.MAX_VALUE);var job=prep.takeWork();if(job!=null) {if(prep.published()!=null) {held=job;break;}prep.complete(job.run());}}
        assertNotNull(held);var result=held.run();w.revision++;prep.complete(result);assertNull(prep.field());assertEquals(2,prep.attempts());prep.close();
    }
    @Test void naturalEstimateIsComparedWithOfflineCoupledSteadyFixtureWithoutAdvancingInitializationClock() {
        var b=new Bounds(0,0,0,9,9,9);var heat=new Heat(12,80,Double.NaN,false);
        var w=new World() {
            @Override public int maxBuildY() { return 16; }
            @Override public Cell sample(int x,int y,int z) {
                if(y==0 || y<=5 && x>=2 && x<=6 && z>=2 && z<=6 && (x==2 || x==6 || z==2 || z==6 || y==5)) return LocalAirPreparationTest.SOLID;
                if(x==4 && y==1 && z==4) return new Cell(LocalAirPreparationTest.SOLID.shape(),1,1,1,1,heat,false,true);
                return LocalAirPreparationTest.AIR;
            }
        };
        var legacy=new dev.brights0ng.paradynelib.heat.air.AirGrid(9,9,9,dev.brights0ng.paradynelib.heat.air.AirParams.DEFAULT);
        for(int z=1;z<8;z++) for(int y=1;y<8;y++) for(int x=1;x<8;x++) {
            var cell=w.sample(x,y,z);if(cell.shape().equals(LocalAirPreparationTest.SOLID.shape())) legacy.setSolid(x,y,z,1,1,0);
            else legacy.setAir(x,y,z,!(x>2 && x<6 && z>2 && z<6 && y<5));
        }
        for(int[] at:new int[][]{{3,1,4},{5,1,4},{4,2,4},{4,1,3},{4,1,5}})
            legacy.sources().add(new dev.brights0ng.paradynelib.heat.air.AirGrid.Source(legacy.index(at[0],at[1],at[2]),12.0/5,60));
        legacy.settle(1500);
        float[] old=new float[729];legacy.exportOffsets(old);
        var preparation=new LocalAirPreparation(w,b,729,20000,new LocalAirRun.Settings(20,0,0,64));
        preparation.thermalInitialization(snapshot->c->new LocalAirThermalState.Value(null,old[c],false,"LEGACY_THERMAL_ESTIMATE"));
        for(int i=0;i<10000 && preparation.field()==null;i++) {preparation.observe(0,1);preparation.advance(1000,Long.MAX_VALUE);preparation.observe(0,1);var job=preparation.takeWork();if(job!=null) preparation.complete(job.run());}
        var initial=preparation.field();assertNotNull(initial,preparation.runReason());var prepared=preparation.published();var snapshot=preparation.thermalCheckpoint().snapshot();
        var control=new dev.brights0ng.paradynelib.heat.air.CoupledAirGrid(prepared.thermal(),dev.brights0ng.paradynelib.heat.air.FlowBoundary.wind(prepared.flow(),0,0,0),
                dev.brights0ng.paradynelib.heat.air.FlowSettings.DEFAULT,dev.brights0ng.paradynelib.heat.air.ThermalTransport.Settings.defaults());
        for(int st=0;st<prepared.thermal().states();st++) control.setTemperature(st,20);control.setSources(snapshot.sources(prepared.thermal(),20));assertTrue(control.initialize(0,0,0).ready());
        double[] background=new double[prepared.flow().nodes()],exterior=new double[prepared.flow().faces()];Arrays.fill(background,20);Arrays.fill(exterior,20);
        double[] lastWindow=new double[prepared.thermal().states()];
        for(int step=0;step<12000;step++) {
            if(step==11800) for(int st=0;st<lastWindow.length;st++) lastWindow[st]=control.temperature(st);
            assertTrue(control.step(.5,1,background,exterior).ready(),"Offline control must meet existing solver tolerances");
        }
        double finalDrift=0;for(int st=0;st<lastWindow.length;st++) finalDrift=Math.max(finalDrift,Math.abs(lastWindow[st]-control.temperature(st)));
        assertTrue(finalDrift<.001,"Offline thermal reference must meet the existing 0.001 C drift criterion over its final 100 seconds");
        double estimate=0,steady=0,maxError=0;int count=0;
        for(int z=3;z<=5;z++) for(int y=1;y<=4;y++) for(int x=3;x<=5;x++) {
            int cell=b.index(x,y,z);for(int n=prepared.flow().firstNode(cell);n<prepared.flow().endNode(cell);n++) {estimate+=initial.temperature(n);steady+=control.temperature(n);count++;maxError=Math.max(maxError,Math.abs(initial.temperature(n)-control.temperature(n)));}
        }
        assertNotNull(initial.naturalInitialization);assertTrue(initial.naturalInitialization.verificationDrift()<=.001);
        var timing=preparation.metrics().get(LocalAirMetrics.Stage.ESTIMATE);assertNotNull(timing);
        System.out.printf(java.util.Locale.ROOT,"Refined initialization: %s; jobs=%d total worker ms=%.6f max worker ms=%.6f%n",initial.naturalInitialization,timing.jobs(),timing.wallNanos()/1e6,timing.maxWallNanos()/1e6);
        assertTrue(maxError<.01,"Refined arrival must match the independently time-stepped coupled reference within 0.01 C in every room cell: "+maxError);
        assertTrue(estimate/count>20);assertTrue(steady/count>20);assertEquals(0,initial.acceptedSteps);assertEquals(0,initial.thermalSeconds);assertFalse(initial.persistentThermal);
        System.out.printf(java.util.Locale.ROOT,"Natural initialization characterization: arrival mean %.6f C, offline 6000 s mean %.6f C, max room cell difference %.6f C, final 100 s max thermal drift %.9f C%n",estimate/count,steady/count,maxError,finalDrift);
        preparation.close();
    }

    private static class NaturalWorld extends World {
        boolean on=true;
        @Override public Cell sample(int x,int y,int z) {
            var cell=super.sample(x,y,z);return x==2 && y==2 && z==2?new Cell(LocalAirPreparationTest.AIR.shape(),1,1,1,1,on?new Heat(12,80,Double.NaN,false):null,false,true):cell;
        }
    }
    private static LocalAirPreparation natural(NaturalWorld world) {
        var prep=new LocalAirPreparation(world,BOX,125,0,new LocalAirRun.Settings(20,0,0,4));
        prep.thermalInitialization(s->c->new LocalAirThermalState.Value(null,8,false,"LEGACY_THERMAL_ESTIMATE"));return prep;
    }
    private static void finish(LocalAirPreparation prep,long tick) {
        for(int i=0;i<10000 && prep.field()==null && prep.status()!=LocalAirPreparation.Status.FAILED;i++) {prep.observe(tick,1);prep.advance(125,Long.MAX_VALUE);prep.observe(tick,1);var work=prep.takeWork();if(work!=null) prep.complete(work.run());}
        assertNotNull(prep.field(),prep.runReason());
    }
    private static LocalAirPreparation.Work holdEstimate(LocalAirPreparation prep) {
        for(int i=0;i<40;i++) {prep.observe(0,1);prep.advance(125,Long.MAX_VALUE);var work=prep.takeWork();if(work!=null) {if(prep.runReason().contains("equilibrium")) return work;prep.complete(work.run());}}
        throw new AssertionError("Estimate job was never queued");
    }
    @Test void naturalRefinementJobsRemainSingleOwnedAndLateCompletionsCannotPublishAfterInvalidation() throws Exception {
        var world=new NaturalWorld();var prep=natural(world);var held=holdEstimate(prep);assertNull(prep.takeWork());assertNull(prep.field());assertNull(prep.published());
        int reads=world.reads;var result=new java.util.concurrent.atomic.AtomicReference<LocalAirPreparation.Result>();var worker=new Thread(()->result.set(held.run()));worker.start();worker.join();assertEquals(reads,world.reads,"Equilibrium work cannot read the world");
        world.revision++;prep.complete(result.get());assertNull(prep.field());assertEquals(1,prep.metrics().get(LocalAirMetrics.Stage.ESTIMATE).discarded());
        finish(prep,0);assertNotNull(prep.field().naturalInitialization);prep.close();
    }
    @Test void weatherAndSourceChangesDuringRefinementReplayOnlyRealGameTimeAndKeepNaturalWarmth() {
        var world=new NaturalWorld();var prep=natural(world);var held=holdEstimate(prep);
        world.on=false;assertTrue(prep.changeSource(2,2,2,10,1));assertTrue(prep.changeWeather(10,new LocalAirRun.Weather(19,.2,.1),1));
        prep.complete(held.run());finish(prep,10);var frame=prep.field();
        assertEquals(10,frame.throughTick);assertEquals(.5,frame.thermalSeconds,1e-12);assertTrue(frame.sourceRevision>0 && frame.weatherRevision>0);assertEquals(0,frame.sourceEnergy,1e-6);
        assertTrue(frame.temperature(frame.flow.nodeAt(1.5,1.5,1.5))>70);assertEquals(0,frame.quality.energyBalanceError(),1e-6);assertNotNull(frame.naturalInitialization);prep.close();
    }
    @Test void refinementPreservesSavedAndLegacyPersistentStatesIncludingExplicitZero() {
        var world=new NaturalWorld();var prep=new LocalAirPreparation(world,BOX,125,0,new LocalAirRun.Settings(20,0,0,4));
        int exact=BOX.index(1,1,1),zero=BOX.index(3,1,1),old=BOX.index(1,3,1);
        prep.thermalInitialization(s->c->c==exact || c==zero?new LocalAirThermalState.Value(new LocalAirThermalState.Saved(s.material(c),0,new double[]{c==zero?0:11}),0,true,"SAVED_COUPLED"):
                c==old?new LocalAirThermalState.Value(null,13,true,"LEGACY_SAVE"):new LocalAirThermalState.Value(null,8,false,"LEGACY_THERMAL_ESTIMATE"));
        finish(prep,0);var frame=prep.field();var flow=frame.flow;
        assertEquals(31,frame.temperature(flow.nodeAt(1.5,1.5,1.5)),1e-12);assertEquals(20,frame.temperature(flow.nodeAt(3.5,1.5,1.5)),1e-12);assertEquals(33,frame.temperature(flow.nodeAt(1.5,3.5,1.5)),1e-12);
        assertTrue(frame.temperature(flow.nodeAt(3.5,3.5,3.5))>79.99);assertEquals(2,frame.restoredCells);assertTrue(frame.persistentThermal);assertEquals(0,frame.acceptedSteps);assertEquals(0,frame.sourceEnergy);prep.close();
    }

}
