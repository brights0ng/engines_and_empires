// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;
import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;

class LocalAirRunTest {
    private static final Bounds B=new Bounds(0,0,0,5,6,3);
    private static final Cell AIR=new Cell(new Shape(new int[0],new int[0]),1,1,2,2);
    private static final Cell WALL=new Cell(new Shape(new int[]{0,0,0,16,16,16},new int[0]),1,1,2,2);
    private static final Cell WATER=new Cell(new Shape(new int[0],new int[]{0,0,0,16,16,16}),1,1,2,2);
    private static Cell heated(Cell cell,double power,boolean perFace) { return new Cell(cell.shape(),1,1,2,2,new Heat(power,Double.NaN,Double.NaN,perFace)); }
    private static class World implements LocalAirCoverage.World {
        long revision=1;
        boolean open,wet;
        boolean door,doorOpen;
        Cell source=heated(AIR,20,false);
        int sx=2,sy=1,sz=1;
        public long contextId() { return 81; }
        public long geometryRevision() { return revision; }
        public long materialRevision() { return 1; }
        public int minBuildY() { return 0; }
        public int maxBuildY() { return 8; }
        public LocalAirCoverage.History freeze() {
            var frozen=new World();frozen.revision=revision;frozen.open=open;frozen.wet=wet;
            frozen.door=door;frozen.doorOpen=doorOpen;frozen.source=source;frozen.sx=sx;frozen.sy=sy;frozen.sz=sz;
            return new LocalAirCoverage.History() {
                public LocalAirCoverage.World world() { return frozen; }
                public void close() { }
            };
        }
        public Cell sample(int x,int y,int z) {
            if(door && x==2 && y==3 && z==1) return new Cell(new Shape(doorOpen?new int[]{0,0,0,16,16,3}:new int[]{0,0,0,3,16,16},new int[0]),1,1,2,2);
            if(x==sx && y==sy && z==sz) return source;
            if(wet && x==1 && y==1 && z==1) return WATER;
            boolean boundary=x==0 || y==0 || z==0 || x==4 || y==5 || z==2;
            return y==0 || boundary && !open?WALL:AIR;
        }
    }
    private record Fixture(World world,Snapshot snapshot,Prepared prepared) {
        LocalAirRun run(double ambient,double wind,int cap) { return new LocalAirRun(prepared,snapshot,new LocalAirRun.Settings(ambient,wind,0,cap),0,1); }
    }
    private static Fixture fixture(World w) {
        var capture=new LocalAirGeometryCapture(B,B.cells(),w);capture.advance(B.cells(),Long.MAX_VALUE);
        var s=capture.snapshot();var coverage=new LocalAirCoverage(LocalAirCoverage.plan(s),w,10_000);coverage.advance(10_000,Long.MAX_VALUE);
        assertEquals(LocalAirCoverage.Status.ADMITTED,coverage.status(),coverage.reason());
        return new Fixture(w,s,coverage.certificate().bake(s,ThermalGeometry.Coefficients.legacyMaterials()));
    }
    private static void initialize(LocalAirRun run) { var work=run.takeWork();assertNotNull(work);run.complete(work.run());assertEquals(LocalAirRun.Status.READY,run.status(),run.reason()); }
    private static void drain(LocalAirRun run) {
        for(int jobs=0;jobs<1000;jobs++) { var job=run.takeWork();if(job==null) return;run.complete(job.run()); }
        fail("Unbounded diagnostic work");
    }
    private static LocalAirPreparation preparation(World w,int cap) {
        var p=new LocalAirPreparation(w,B,B.cells(),10_000,new LocalAirRun.Settings(20,0,0,cap));
        for(int i=0;i<200 && p.field()==null;i++) { p.advance(1000,Long.MAX_VALUE);p.observe(0,1);var work=p.takeWork();if(work!=null) p.complete(work.run()); }
        assertNotNull(p.field(),p.runReason());return p;
    }
    private static void drive(LocalAirPreparation p,long tick,double scale,boolean firstField) {
        for(int i=0;i<1000;i++) {
            p.advance(1000,Long.MAX_VALUE);p.observe(tick,scale);var work=p.takeWork();if(work!=null) p.complete(work.run());
            var field=p.field();if(field!=null && (firstField || field.throughTick==tick)) return;
        }
        fail("Preparation did not progress: "+p.status()+" / "+p.runStatus()+" / "+p.runReason());
    }
    @Test void geometryBarrierFinishesAnInflightPacketAndItsShortTailAtTheExactEditTick() {
        var f=fixture(new World());var run=f.run(20,0,1);initialize(run);run.observe(20,1);var old=run.takeWork();
        assertTrue(run.seal(23,1));assertNull(run.sealedSeed());run.complete(old.run());drain(run);
        var seed=run.sealedSeed();assertNotNull(seed);assertEquals(23,seed.frame().throughTick);
        assertEquals(23,seed.frame().sourceEnergy,1e-8);assertEquals(3,seed.frame().acceptedSteps);
        assertNull(run.takeWork());assertThrows(IllegalStateException.class,()->run.observe(24,1));
        assertFalse(run.changeSource(23,f.prepared.flow().index(2,1,1),null,1));
    }
    @Test void topologyTransferPreservesTheLedgerAndReplaysPreparationTimeWithItsResponseHistory() {
        var w=new World();w.door=true;var p=preparation(w,1);drive(p,100,1,false);var before=p.field();
        p.observe(120,1);var old=p.takeWork();assertNotNull(old);long revision=w.revision++;
        w.doorOpen=true;assertTrue(p.changeGeometry(2,3,1,revision,123,1));assertNull(p.field());
        p.complete(old.run());assertNull(p.field());p.observe(130,2);drive(p,200,2,true);
        var mapped=p.field();assertEquals(123,mapped.throughTick);assertEquals(1,mapped.transfers);
        assertEquals(123,mapped.sourceEnergy,1e-7);assertEquals(13,mapped.acceptedSteps);
        assertTrue(mapped.temperature(mapped.flow.nodeAt(2.5,1.5,1.5))>20);
        assertEquals(before.initialEnergy,mapped.initialEnergy);assertEquals(0,before.transfers);
        drive(p,200,2,false);var after=p.field();assertEquals(13.5,after.thermalSeconds,1e-12);
        assertEquals(270,after.sourceEnergy,1e-6);assertTrue(Math.abs(after.quality.energyBalanceError())<1e-6);
        assertEquals(after.energy,after.initialEnergy+after.sourceEnergy+after.exteriorEnergy+after.geometryEnergy,1e-6);
        assertTrue(after.quality.maxDivergence()<1e-7);
    }
    @Test void sameTickGeometryEditsCoalesceAndStalePlanningCannotPublish() {
        var w=new World();w.door=true;var p=preparation(w,2);drive(p,100,1,false);
        long revision=w.revision++;w.doorOpen=true;assertTrue(p.changeGeometry(2,3,1,revision,100,1));
        p.advance(1000,Long.MAX_VALUE);var plan=p.takeWork();assertNotNull(plan);
        revision=w.revision++;assertTrue(p.changeGeometry(2,3,1,revision,100,1));p.complete(plan.run());assertNull(p.field());
        drive(p,120,1,true);assertEquals(1,p.field().transfers);assertEquals(100,p.field().throughTick);
        assertEquals(w.revision,p.field().geometryRevision);
    }
    @Test void unknownRevisionDuringTransferCancelsTheSeedAndCannotReviveOldWork() {
        var w=new World();w.door=true;var p=preparation(w,1);drive(p,100,1,false);p.observe(110,1);var old=p.takeWork();
        long revision=w.revision++;w.doorOpen=true;assertTrue(p.changeGeometry(2,3,1,revision,113,1));
        w.revision++;assertNull(p.field());p.complete(old.run());assertNull(p.field());drive(p,150,1,true);
        assertEquals(0,p.field().transfers);assertEquals(0,p.field().sourceEnergy);assertEquals(150,p.field().throughTick);
    }
    @Test void laterTickTopologyEditRetainsBothIntervalsAndCloseCannotPublishABarrier() {
        var w=new World();w.door=true;var p=preparation(w,1);drive(p,100,1,false);
        long revision=w.revision++;assertTrue(p.changeGeometry(2,3,1,revision,103,1));var tail=p.takeWork();assertNotNull(tail);
        revision=w.revision++;assertTrue(p.changeGeometry(2,3,1,revision,104,1));assertNull(p.field());p.complete(tail.run());
        drive(p,150,1,true);assertEquals(2,p.field().transfers);assertEquals(104,p.field().throughTick);
        p.observe(160,1);var work=p.takeWork();revision=w.revision++;assertTrue(p.changeGeometry(2,3,1,revision,163,1));
        p.close();p.complete(work.run());assertNull(p.field());assertNull(p.takeWork());
    }
    @Test void preparationTimeBeyondRetainedHistoryFailsWithoutDiscardingPhysicalTime() {
        var w=new World();w.door=true;var p=preparation(w,1);drive(p,100,1,false);
        long revision=w.revision++;assertTrue(p.changeGeometry(2,3,1,revision,100,1));p.observe(2501,1);
        assertEquals(LocalAirPreparation.Status.FAILED,p.status());assertNull(p.field());assertTrue(p.reason().contains("chronological"));
    }
    @Test void sourceRevisionAndCumulativeHeatSurviveATopologyTransfer() {
        var w=new World();w.door=true;var p=preparation(w,2);drive(p,100,1,false);w.source=AIR;
        assertTrue(p.changeSource(2,1,1,100,1));drive(p,100,1,false);var off=p.field();
        long revision=w.revision++;w.doorOpen=true;assertTrue(p.changeGeometry(2,3,1,revision,100,1));drive(p,200,1,false);
        assertEquals(1,p.field().sourceRevision);assertEquals(off.sourceEnergy,p.field().sourceEnergy);assertEquals(1,p.field().transfers);
        w.source=heated(AIR,20,false);assertTrue(p.changeSource(2,1,1,200,1));drive(p,220,1,false);
        assertEquals(2,p.field().sourceRevision);assertEquals(off.sourceEnergy+20,p.field().sourceEnergy,1e-6);
    }
    @Test void sourceRefreshRetainsEveryTemperatureFluxAndTheEnergyLedgerThenCools() {
        var f=fixture(new World());var run=f.run(20,0,4);initialize(run);run.observe(400,1);drain(run);
        var hot=run.published();int cell=f.prepared.flow().index(2,1,1);
        assertTrue(run.changeSource(400,cell,null,1));assertNull(run.published());drain(run);
        var refreshed=run.published();assertNotNull(refreshed,run.reason());assertEquals(1,refreshed.sourceRevision);
        assertEquals(hot.energy,refreshed.energy);assertEquals(hot.sourceEnergy,refreshed.sourceEnergy);
        assertEquals(hot.acceptedSteps,refreshed.acceptedSteps);
        for(int i=0;i<f.prepared.thermal().states();i++) assertEquals(hot.temperature(i),refreshed.temperature(i));
        for(int i=0;i<f.prepared.flow().faces();i++) assertEquals(hot.flux(i),refreshed.flux(i));
        run.observe(800,1);drain(run);var cooled=run.published();assertNotNull(cooled,run.reason());
        assertEquals(hot.sourceEnergy,cooled.sourceEnergy);assertEquals(hot.energy,cooled.energy,1e-6);
        assertTrue(cooled.temperature(f.prepared.flow().nodeAt(2.5,1.5,1.5))<hot.temperature(f.prepared.flow().nodeAt(2.5,1.5,1.5)));
        assertEquals(0,hot.sourceRevision);assertTrue(Math.abs(cooled.quality.energyBalanceError())<1e-6);
    }
    @Test void chronologicalSourceEdgesSplitPhysicalStepsAndRemainIdenticalAcrossJobCaps() {
        LocalAirRun.Frame expected=null;
        for(int cap:new int[]{1,2,64}) {
            var f=fixture(new World());var run=f.run(20,0,cap);initialize(run);int cell=f.prepared.flow().index(2,1,1);
            assertTrue(run.changeSource(7,cell,null,1));
            assertTrue(run.changeSource(17,cell,new Heat(10,Double.NaN,Double.NaN,false),1));
            assertTrue(run.changeSource(27,cell,null,1));run.observe(40,1);drain(run);
            var frame=run.published();assertNotNull(frame,run.reason());assertEquals(40,frame.throughTick);
            assertEquals(12,frame.sourceEnergy,1e-8);assertEquals(3,frame.sourceRevision);
            if(expected!=null) {
                assertEquals(expected.energy,frame.energy);
                for(int i=0;i<f.prepared.thermal().states();i++) assertEquals(expected.temperature(i),frame.temperature(i));
            }
            expected=frame;
        }
    }
    @Test void preEditWorkerCompletionCannotPublishOldSourceStateButItsChronologicalProgressIsRetained() {
        var f=fixture(new World());var run=f.run(20,0,1);initialize(run);run.observe(20,1);var old=run.takeWork();
        assertTrue(run.changeSource(20,f.prepared.flow().index(2,1,1),null,1));run.complete(old.run());
        assertNull(run.published());drain(run);var refreshed=run.published();assertNotNull(refreshed,run.reason());
        assertEquals(20,refreshed.throughTick);assertEquals(20,refreshed.sourceEnergy,1e-8);
        run.observe(40,1);drain(run);assertEquals(20,run.published().sourceEnergy,1e-8);
    }
    @Test void sourceChangesHonorResponseHistoryAndHandleMultipleChangesAtTheSameTick() {
        var f=fixture(new World());var run=f.run(20,0,1);initialize(run);run.observe(0,.5);
        int cell=f.prepared.flow().index(2,1,1);
        assertTrue(run.changeSource(7,cell,null,2));
        assertTrue(run.changeSource(7,cell,new Heat(10,Double.NaN,Double.NaN,false),2));
        assertTrue(run.changeSource(17,cell,null,1));run.observe(30,1);drain(run);
        assertEquals(3.5+10,run.published().sourceEnergy,1e-8);assertEquals(3,run.published().sourceRevision);
        assertFalse(run.changeSource(29,cell,null,1));
    }
    @Test void sourceHistoryAdmissionAndCloseCannotLoseOrReviveWork() {
        var f=fixture(new World());var run=f.run(20,0,1);initialize(run);
        assertFalse(run.changeSource(0,0,new Heat(10,Double.NaN,Double.NaN,false),1));
        for(int i=0;i<256;i++) assertTrue(run.changeSource(0,f.prepared.flow().index(2,1,1),null,1));
        assertFalse(run.changeSource(0,f.prepared.flow().index(2,1,1),null,1));
        var work=run.takeWork();run.close();run.complete(work.run());assertNull(run.published());assertNull(run.takeWork());
    }
    @Test void preparedSourceRefreshKeepsTheExactBakedGeometryAndPreparationGeneration() {
        var w=new World();var p=new LocalAirPreparation(w,B,B.cells(),10_000,new LocalAirRun.Settings(20,0,0,4));
        for(int i=0;i<100 && p.status()!=LocalAirPreparation.Status.PREPARED;i++) { p.advance(1000,Long.MAX_VALUE);var job=p.takeWork();if(job!=null) p.complete(job.run()); }
        p.observe(0,1);p.complete(p.takeWork().run());p.observe(20,1);p.complete(p.takeWork().run());
        var geometry=p.published();long generation=p.attempts();var hot=p.field();w.source=AIR;
        assertTrue(p.changeSource(2,1,1,20,1));assertNull(p.field());assertSame(geometry,p.published());
        p.complete(p.takeWork().run());assertNotNull(p.field());assertEquals(hot.energy,p.field().energy);assertEquals(generation,p.attempts());
        w.source=WALL;assertFalse(p.changeSource(2,1,1,20,1));
        w.revision++;assertNull(p.field());assertEquals(generation+1,p.attempts());
    }
    @Test void initializesEveryAirSolidAndFluidStateAtExplicitAmbientWithZeroElapsedHeat() {
        var w=new World();w.wet=true;var f=fixture(w);var run=f.run(17,0,2);assertNull(run.published());initialize(run);
        var frame=run.published();double capacity=0;
        for(int i=0;i<f.prepared.thermal().states();i++) { assertEquals(17,frame.temperature(i));capacity+=f.prepared.thermal().capacity(i); }
        assertEquals(17*capacity,frame.energy,1e-9);assertEquals(0,frame.sourceEnergy);assertEquals(0,frame.acceptedSteps);assertEquals(0,frame.throughTick);
        assertTrue(f.prepared.thermal().fluidState(f.prepared.flow().index(1,1,1))>=0);
    }
    @Test void workCapYieldsWithoutDroppingPendingPhysicalTime() {
        var f=fixture(new World());var run=f.run(20,0,2);initialize(run);run.observe(50,1);
        var job=run.takeWork();assertNull(run.takeWork());run.complete(job.run());
        assertEquals(2,run.published().acceptedSteps);assertEquals(20,run.published().throughTick);
        drain(run);assertEquals(5,run.published().acceptedSteps);assertEquals(50,run.published().throughTick);assertEquals(2.5,run.published().thermalSeconds);
        run.observe(59,1);assertNull(run.takeWork());
    }
    @Test void rateTransitionsRemainChronologicalAcrossWorkerYields() {
        var f=fixture(new World());LocalAirRun.Frame expected=null;
        for(int cap:new int[]{1,2,64}) {
            var run=f.run(20,0,cap);initialize(run);run.observe(0,.5);run.observe(7,2);run.observe(41,1);run.observe(100,1);drain(run);
            var actual=run.published();assertNotNull(actual,run.reason());assertEquals(100,actual.throughTick);
            assertEquals(.35*.5+1.7*2+2.95,actual.thermalSeconds,1e-12);
            if(expected==null) expected=actual;
            else {
                assertEquals(expected.energy,actual.energy);
                for(int i=0;i<f.prepared.thermal().states();i++) assertEquals(expected.temperature(i),actual.temperature(i));
                for(int face=0;face<f.prepared.flow().faces();face++) assertEquals(expected.flux(face),actual.flux(face));
            }
        }
    }
    @Test void pendingIntervalKeepsItsEndWhileNewTimeArrivesAndFinishesAShortTail() {
        var f=fixture(new World());var run=f.run(20,0,1);initialize(run);run.observe(27,1);var work=run.takeWork();
        run.observe(33,2);run.complete(work.run());drain(run);
        assertEquals(27,run.published().throughTick);assertEquals(3,run.published().acceptedSteps);assertNull(run.takeWork());
        run.observe(37,2);drain(run);assertEquals(37,run.published().throughTick);
        assertEquals(27/20.0+6/20.0+4/20.0*2,run.published().thermalSeconds,1e-12);
    }
    @Test void capturedHeatingAndCoolingDriveTemperatureAndBuoyantCirculation() {
        for(double sign:new double[]{1,-1}) {
            var w=new World();w.source=heated(AIR,20*sign,false);var f=fixture(w);var run=f.run(20,0,4);initialize(run);
            run.observe(400,1);drain(run);var frame=run.published();assertNotNull(frame,run.reason());
            assertTrue(frame.sourceEnergy*sign>0);double maxVelocity=0;
            for(int node=0;node<f.prepared.flow().nodes();node++) maxVelocity=Math.max(maxVelocity,Math.abs(frame.velocity(node,1)));
            assertTrue(maxVelocity>1e-5,"Source must couple into momentum");
            assertTrue((frame.temperature(f.prepared.flow().nodeAt(2.5,1.5,1.5))-20)*sign>0);
            assertEquals(frame.energy-runEnergy(f,20),frame.sourceEnergy,1e-6);
        }
    }
    private static double runEnergy(Fixture f,double ambient) { double sum=0;for(int i=0;i<f.prepared.thermal().states();i++) sum+=f.prepared.thermal().capacity(i)*ambient;return sum; }
    @Test void uniformWindIsPhysicalAndIndependentOfThermalResponseScale() {
        var w=new World();w.open=true;w.source=AIR;var f=fixture(w);double[] expected=null;
        for(double scale:new double[]{.1,1,10}) {
            var run=f.run(20,2,4);initialize(run);run.observe(0,scale);run.observe(20,scale);drain(run);var frame=run.published();assertNotNull(frame,run.reason());
            var flux=new double[f.prepared.flow().faces()];for(int i=0;i<flux.length;i++) flux[i]=frame.flux(i);
            assertTrue(Arrays.stream(flux).anyMatch(v->Math.abs(v)>.01));
            if(expected==null) expected=flux;else assertArrayEquals(expected,flux,1e-8);
            for(int node=0;node<f.prepared.flow().nodes();node++) assertEquals(20,frame.temperature(node),1e-8);
        }
    }
    @Test void limitsUseStrictestAbsoluteAndAmbientRelativeBoundForBothSigns() {
        assertEquals(30,new Heat(20,35,10,false).limit(20));assertEquals(25,new Heat(20,25,10,false).limit(20));
        assertEquals(15,new Heat(-20,15,10,false).limit(20));assertEquals(10,new Heat(-20,5,10,false).limit(20));
        assertTrue(Double.isNaN(new Heat(20,Double.NaN,Double.NaN,false).limit(20)));
    }
    @Test void sourceSnapshotIsOwnedAndPerFaceRoutingRetainsItsMeaning() {
        for(boolean perFace:new boolean[]{false,true}) {
            var w=new World();w.source=heated(WALL,20,perFace);w.sy=2;var f=fixture(w);w.source=AIR;
            var sources=f.snapshot.sources(f.prepared.thermal(),20);double power=Arrays.stream(sources).mapToDouble(s->s.power()).sum();
            // z neighbors are the represented walls; the four x/y faces open into interior air.
            assertEquals(perFace?80:20,power,1e-12);assertEquals(4,sources.length);
        }
    }
    @Test void boundarySourceFailsInitializationWithoutPublishingAField() {
        var w=new World();w.sx=0;w.source=heated(WALL,20,false);var f=fixture(w);var run=f.run(20,0,4);
        var work=run.takeWork();run.complete(work.run());assertEquals(LocalAirRun.Status.FAILED,run.status());assertNull(run.published());assertTrue(run.reason().contains("boundary"));
    }
    @Test void rejectedCoupledStepWithholdsPublicationInsteadOfConsumingPendingTime() {
        var f=fixture(new World());var run=f.run(3000,0,4);initialize(run);var previous=run.published();run.observe(10,1);drain(run);
        assertEquals(LocalAirRun.Status.FAILED,run.status());assertNull(run.published());assertEquals(0,previous.acceptedSteps);assertTrue(run.reason().contains("rejected"));
    }
    @Test void excessiveBacklogIsReportedAndDelayedCompletionCannotReviveIt() {
        var f=fixture(new World());var run=f.run(20,0,4);initialize(run);run.observe(10,1);var work=run.takeWork();
        run.observe(2401,1);run.complete(work.run());assertEquals(LocalAirRun.Status.LAG_LIMIT,run.status());assertNull(run.published());assertNull(run.takeWork());
    }
    @Test void workerOwnershipSingleExecutionAndHistoricalFramesAreEnforced() throws Exception {
        var f=fixture(new World());var run=f.run(20,0,4);initialize(run);var previous=run.published();run.observe(10,1);var work=run.takeWork();
        var result=new AtomicReference<LocalAirRun.Result>();var error=new AtomicReference<Throwable>();
        var thread=new Thread(()->{ try {
            result.set(work.run());assertThrows(IllegalStateException.class,work::run);
            assertThrows(IllegalStateException.class,run::published);assertThrows(IllegalStateException.class,()->run.complete(result.get()));
        } catch(Throwable t) { error.set(t); } });thread.start();thread.join();if(error.get()!=null) throw new AssertionError(error.get());
        run.complete(result.get());assertEquals(0,previous.acceptedSteps);assertEquals(0,previous.sourceEnergy);assertEquals(1,run.published().acceptedSteps);
        run.close();assertNull(run.published());assertNull(run.takeWork());
    }
    @Test void preparationInvalidationDiscardsInFlightCoupledResultsAndColdStartsAgain() {
        var w=new World();var p=new LocalAirPreparation(w,B,B.cells(),10_000,new LocalAirRun.Settings(20,0,0,4));
        for(int i=0;i<100 && p.status()!=LocalAirPreparation.Status.PREPARED;i++) { p.advance(1000,Long.MAX_VALUE);var job=p.takeWork();if(job!=null) p.complete(job.run()); }
        p.observe(0,1);p.complete(p.takeWork().run());assertNotNull(p.field());p.observe(10,1);var old=p.takeWork();
        w.revision++;assertNull(p.field());p.complete(old.run());assertNull(p.field());
        for(int i=0;i<100 && p.status()!=LocalAirPreparation.Status.PREPARED;i++) { p.advance(1000,Long.MAX_VALUE);var job=p.takeWork();if(job!=null) p.complete(job.run()); }
        p.observe(20,1);p.complete(p.takeWork().run());assertEquals(0,p.field().acceptedSteps);assertEquals(20,p.field().throughTick);
    }
}
