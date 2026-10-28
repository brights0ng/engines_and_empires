// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirForcingTest {
    private static final Bounds B=new Bounds(0,0,0,5,6,5);
    private static final Cell AIR=LocalAirPreparationTest.AIR,WALL=LocalAirPreparationTest.SOLID;
    private static Heat heat(double power) { return power==0?null:new Heat(power,Double.NaN,Double.NaN,false); }
    private static class World implements LocalAirCoverage.World {
        final Thread owner=Thread.currentThread();long revision=1;int geometryReads,driverReads;boolean frozen,doorOpen,solidSource,fail;
        Heat power=heat(20);
        public long contextId() { return 1; }public long geometryRevision() { return revision; }public long materialRevision() { return 1; }
        public int minBuildY() { return 0; }public int maxBuildY() { return 6; }
        public Heat drivenHeat(int x,int y,int z) {
            assertSame(owner,Thread.currentThread());assertFalse(frozen,"Historical geometry must not read live drivers");driverReads++;
            if(fail) throw new Unavailable(Status.MISSING_GEOMETRY,"Driven chunk unloaded");return power;
        }
        public Cell sample(int x,int y,int z) {
            geometryReads++;
            if(x==2 && y==2 && z==2) return new Cell((solidSource?WALL:AIR).shape(),1,1,1,1,null,true);
            if(x==2 && y==3 && z==2) return new Cell(new Shape(doorOpen?new int[]{0,0,0,16,16,3}:new int[]{0,0,0,3,16,16},new int[0]),1,1,1,1);
            return x==0 || y==0 || z==0 || x==4 || y==5 || z==4?WALL:AIR;
        }
        public LocalAirCoverage.History freeze() {
            var past=new World();past.revision=revision;past.frozen=true;past.doorOpen=doorOpen;past.solidSource=solidSource;
            return new LocalAirCoverage.History() { public LocalAirCoverage.World world() { return past; }public void close() { } };
        }
    }
    private static LocalAirPreparation create(World w) { return new LocalAirPreparation(w,B,B.cells(),0,new LocalAirRun.Settings(20,0,0,4)); }
    private static void pump(LocalAirPreparation p,long tick) {
        p.observe(tick,1);p.advance(1000,Long.MAX_VALUE);p.observe(tick,1);var job=p.takeWork();if(job!=null) p.complete(job.run());
    }
    private static LocalAirRun.Frame through(LocalAirPreparation p,long tick) {
        for(int i=0;i<300;i++) { pump(p,tick);var f=p.field();if(f!=null && f.throughTick==tick) return f; }
        fail(p.status()+" "+p.runReason());return null;
    }
    @Test void liveChangesDuringWorkerJobsPreserveWarmthAndNeverReadDriversOnWorkers() throws Exception {
        var w=new World();var p=create(w);
        for(int i=0;i<20 && p.published()==null;i++) { p.observe(0,1);p.advance(1000,Long.MAX_VALUE);var job=p.takeWork();if(job!=null) p.complete(job.run()); }
        p.observe(0,1);var initialize=p.takeWork();assertNotNull(initialize);
        int geometry=w.geometryReads,reads=w.driverReads;
        w.power=null;p.observe(10,1);assertEquals(0,p.advance(0,Long.MAX_VALUE));assertEquals(reads,w.driverReads);
        assertEquals(0,p.advance(1,System.nanoTime()-1));assertEquals(reads,w.driverReads);
        assertEquals(1,p.advance(1,Long.MAX_VALUE));assertNull(p.field());
        var result=new AtomicReference<LocalAirPreparation.Result>();var worker=new Thread(()->result.set(initialize.run()));worker.start();worker.join();p.complete(result.get());
        assertEquals(reads+1,w.driverReads);assertNull(p.field(),"Old initialization cannot publish over a newer power observation");
        var f=through(p,20);assertEquals(10,f.sourceEnergy,1e-7);assertTrue(f.temperature(f.flow.nodeAt(2.5,2.5,2.5))>20);
        assertEquals(geometry,w.geometryReads);assertEquals(0,p.restarts());assertEquals(0,f.quality.energyBalanceError(),1e-6);p.close();
    }
    @Test void initialPowerUsesTheLatestPrimedObservationInsteadOfGeometryCaptureTime() {
        var w=new World();var p=create(w);p.advance(1000,Long.MAX_VALUE);assertEquals(0,w.driverReads);
        w.power=null;through(p,0);assertEquals(0,through(p,20).sourceEnergy);p.close();
    }
    @Test void opposingTermsKeepIndependentRoutingAndLimitsRatherThanCancelingAtBlockLevel() {
        var w=new World();w.solidSource=true;w.power=new Heat(List.of(new HeatTerm(20,Double.NaN,Double.NaN,false),new HeatTerm(-20,Double.NaN,Double.NaN,true)));
        var p=create(w);through(p,0);var f=through(p,10);
        // Six air-facing sides: +20 total, -20 per face. The raw block powers sum to zero.
        assertEquals(-50,f.sourceEnergy,1e-6);assertEquals(0,f.quality.energyBalanceError(),1e-6);p.close();
        var hot=new HeatTerm(5,80,10,false);var cold=new HeatTerm(-5,5,8,false);
        assertEquals(30,hot.limit(20));assertEquals(12,cold.limit(20));
        assertThrows(IllegalArgumentException.class,()->new Heat(Collections.nCopies(65,hot)));
    }
    @Test void doorReconstructionRetainsTheDrivenValueAtItsBarrierAndReplaysLaterObservations() {
        var w=new World();var p=create(w);through(p,0);through(p,10);
        long old=w.revision++;w.doorOpen=true;assertTrue(p.changeGeometry(2,3,2,old,13,1));
        w.power=null;var f=through(p,40);
        // The next observation occurs at tick 40; it must not retroactively turn off the source at tick 13.
        assertEquals(40,f.sourceEnergy,1e-6);assertEquals(1,f.transfers);assertEquals(0,f.quality.energyBalanceError(),1e-6);p.close();
    }
    @Test void layoutInitializationUsesBarrierPowerAndKeepsUpdatesObservedWhileProjectionIsHeld() {
        var w=new World();var donor=create(w);through(donor,0);through(donor,10);
        var target=create(w);target.awaitLayoutSeed();
        for(int i=0;i<30 && target.published()==null;i++) pump(target,10);
        assertNotNull(target.published());assertTrue(donor.holdLayout(15,1));assertTrue(target.holdLayout(15,1));
        for(int i=0;i<30 && donor.layoutCheckpoint()==null;i++) pump(donor,15);
        var seed=donor.layoutCheckpoint();assertNotNull(seed);assertEquals(15,seed.frame().sourceEnergy,1e-6);
        target.installLayoutSeed(LocalAirRun.LayoutSeed.from(List.of(seed)));target.observe(15,1);var initialization=target.takeWork();assertNotNull(initialization);
        w.power=null;target.observe(20,1);target.advance(1,Long.MAX_VALUE);target.complete(initialization.run());assertNotNull(target.layoutCheckpoint());
        target.resumeLayout();var f=through(target,30);assertEquals(5,f.sourceEnergy,1e-6);assertEquals(20,seed.frame().sourceEnergy+f.sourceEnergy,1e-6);
        donor.close();target.close();
    }
    @Test void missingLiveSourceFailsExplicitlyAndAnInvalidationCanRetry() {
        var w=new World();var p=create(w);through(p,0);w.fail=true;p.observe(10,1);p.advance(1,Long.MAX_VALUE);
        assertEquals(LocalAirPreparation.Status.FAILED,p.status());assertNull(p.field());assertTrue(p.reason().contains("unloaded"));
        w.fail=false;w.revision++;through(p,20);assertEquals(1,p.restarts());p.close();
    }
    @Test void simultaneousOpposingLimitsPreserveTheExistingStrictestNetDirectionLaw() {
        var w=new World();w.power=new Heat(List.of(new HeatTerm(20,100,Double.NaN,false),new HeatTerm(-5,0,Double.NaN,false)));
        var p=create(w);through(p,0);assertEquals(0,through(p,10).sourceEnergy,"Net heating is capped by the stricter 0 C term, as in the legacy solver");p.close();
    }
    @Test void excessiveDynamicCandidatesRejectBeforeReadingBlockEntities() {
        var w=new World() {
            @Override public int maxBuildY() { return 10; }
            @Override public Cell sample(int x,int y,int z) {
                return x>0 && y>0 && z>0 && x<9 && y<9 && z<9?new Cell(AIR.shape(),1,1,1,1,null,true):WALL;
            }
        };
        var b=new Bounds(0,0,0,10,10,10);var p=new LocalAirPreparation(w,b,b.cells(),0,new LocalAirRun.Settings(20,0,0,4));
        p.observe(0,1);p.advance(b.cells(),Long.MAX_VALUE);assertEquals(LocalAirPreparation.Status.FAILED,p.status());
        assertTrue(p.reason().contains("admission limit"));assertEquals(0,w.driverReads);assertNull(p.field());p.close();
    }
}
