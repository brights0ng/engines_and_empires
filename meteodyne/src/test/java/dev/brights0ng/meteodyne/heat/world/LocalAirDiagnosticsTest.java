// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirDiagnosticsTest {
    private static final Cell AIR=new Cell(new Shape(new int[0],new int[0]),1,1,1,1);
    private static final Cell WALL=new Cell(new Shape(new int[]{0,0,0,16,16,16},new int[0]),1,1,1,1);
    private static final class World implements LocalAirCoverage.World {
        final Bounds bounds=new Bounds(-5,10,-8,4,4,4);
        long revision=1;
        boolean boundarySource;
        public long contextId() { return 31; }
        public long geometryRevision() { return revision; }
        public long materialRevision() { return 1; }
        public int minBuildY() { return 0; }
        public int maxBuildY() { return 32; }
        public Cell sample(int x,int y,int z) {
            x-=bounds.x();y-=bounds.y();z-=bounds.z();
            boolean wall=x==0 || y==0 || z==0 || x==3 || y==3 || z==3;
            if(x==(boundarySource?0:1) && y==1 && z==1) {
                var shape=wall?WALL.shape():AIR.shape();return new Cell(shape,1,1,1,1,new Heat(20,60,40,false));
            }
            return wall?WALL:AIR;
        }
    }
    private static LocalAirPreparation prepared(World w) {
        var p=new LocalAirPreparation(w,w.bounds,64,0,new LocalAirRun.Settings(20,0,0,2));
        for(int i=0;i<100 && p.status()!=LocalAirPreparation.Status.PREPARED;i++) {
            p.advance(64,Long.MAX_VALUE);var work=p.takeWork();if(work!=null) p.complete(work.run());
        }
        assertEquals(LocalAirPreparation.Status.PREPARED,p.status());p.observe(100,1);p.complete(p.takeWork().run());return p;
    }
    @Test void samplesDistinguishActualAirOccupiedOutsideAndMissingWithoutFakeCalm() {
        var p=prepared(new World());var frame=p.field();
        var air=LocalAirDiagnostics.sample(frame,119,-3.5,11.5,-6.5);
        assertEquals(LocalAirDiagnostics.Availability.AIR,air.availability());assertEquals(20,air.temperature());assertEquals(19,air.ageTicks());assertEquals(100,air.throughTick());
        assertEquals(frame.velocity(frame.flow.nodeAt(1.5,1.5,1.5),1),air.y());
        var wall=LocalAirDiagnostics.sample(frame,119,-4.5,10.5,-7.5);assertEquals(LocalAirDiagnostics.Availability.OCCUPIED,wall.availability());assertTrue(Double.isNaN(wall.x()));
        var outside=LocalAirDiagnostics.sample(frame,119,-1,11.5,-6.5);assertEquals(LocalAirDiagnostics.Availability.OUTSIDE_DOMAIN,outside.availability());assertTrue(Double.isNaN(outside.temperature()));
        var missing=LocalAirDiagnostics.sample(null,119,0,0,0);assertEquals(LocalAirDiagnostics.Availability.NO_FIELD,missing.availability());assertEquals(-1,missing.throughTick());assertTrue(Double.isNaN(missing.y()));
    }
    @Test void qualityReportsAcceptedSolverWorkAndConservativeEnergyLedger() {
        var p=prepared(new World());var initial=p.field();p.observe(140,1);
        for(var work=p.takeWork();work!=null;work=p.takeWork()) p.complete(work.run());
        var frame=p.field();assertNotNull(frame);assertEquals(4,frame.acceptedSteps);
        var q=frame.quality;assertTrue(q.maxDivergence()<=1e-7);assertTrue(q.maxThermalResidual()<=1e-8);
        assertTrue(q.maxStepEnergyError()<1e-7);assertEquals(0,q.energyBalanceError(),1e-6);assertTrue(q.thermalSweeps()>0);
        assertEquals(0,initial.quality.thermalSweeps());assertEquals(0,initial.acceptedSteps);
        String text=LocalAirDiagnostics.field(frame,147);assertTrue(text.contains("age/backlog 7 ticks"));assertTrue(text.contains("energy balance error"));
    }
    @Test void invalidSampleTimeAndNonfiniteCoordinatesAreRejected() {
        var p=prepared(new World());var frame=p.field();
        assertThrows(IllegalArgumentException.class,()->LocalAirDiagnostics.sample(frame,99,-3.5,11.5,-6.5));
        assertThrows(IllegalArgumentException.class,()->LocalAirDiagnostics.sample(null,100,Double.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->LocalAirDiagnostics.sample(frame,100,0,Double.POSITIVE_INFINITY,0));
        assertEquals("No published coupled field.",LocalAirDiagnostics.field(null,100));
    }
    @Test void timingSnapshotsAreOwnedAndCpuAvailabilityIsExplicit() {
        var metrics=new LocalAirMetrics();
        metrics.complete(new LocalAirMetrics.Timing(LocalAirMetrics.Stage.STEP,10,20,-1),false,false);
        var first=metrics.snapshot();assertEquals(0,first.get(LocalAirMetrics.Stage.STEP).cpuSamples());
        metrics.complete(new LocalAirMetrics.Timing(LocalAirMetrics.Stage.STEP,30,50,7),true,true);
        var row=metrics.snapshot().get(LocalAirMetrics.Stage.STEP);
        assertEquals(2,row.jobs());assertEquals(70,row.wallNanos());assertEquals(40,row.queueNanos());assertEquals(7,row.cpuNanos());assertEquals(1,row.cpuSamples());
        assertEquals(1,row.failed());assertEquals(1,row.discarded());assertEquals(50,row.maxWallNanos());assertEquals(30,row.maxQueueNanos());
        assertEquals(1,first.get(LocalAirMetrics.Stage.STEP).jobs());assertThrows(UnsupportedOperationException.class,first::clear);
        metrics.complete(null,false,false);assertEquals(2,metrics.snapshot().get(LocalAirMetrics.Stage.STEP).jobs());
    }
    @Test void realExecutorWorkRecordsStagesAndChargesStaleResultsOnce() throws Exception {
        var w=new World();var p=prepared(w);
        assertEquals(1,p.metrics().get(LocalAirMetrics.Stage.PLAN).jobs());assertEquals(1,p.metrics().get(LocalAirMetrics.Stage.BAKE).jobs());assertEquals(1,p.metrics().get(LocalAirMetrics.Stage.INITIALIZE).jobs());
        p.observe(120,1);var work=p.takeWork();LocalAirPreparation.Result result;
        try(var pool=Executors.newSingleThreadExecutor()) { result=pool.submit(work::run).get(); }
        assertEquals(LocalAirMetrics.Stage.STEP,result.timing().stage());assertTrue(result.timing().wallNanos()>0);assertTrue(result.timing().queueNanos()>=0);
        w.revision++;p.complete(result);p.complete(result);
        var row=p.metrics().get(LocalAirMetrics.Stage.STEP);assertEquals(1,row.jobs());assertEquals(1,row.discarded());assertNull(p.field());
    }
    @Test void rejectedInitializationRecordsFailureAndWithholdsSample() {
        var w=new World();w.boundarySource=true;var p=prepared(w);
        assertEquals(LocalAirRun.Status.FAILED,p.runStatus());assertNull(p.field());assertEquals(1,p.metrics().get(LocalAirMetrics.Stage.INITIALIZE).failed());
        assertEquals(LocalAirDiagnostics.Availability.NO_FIELD,LocalAirDiagnostics.sample(p.field(),100,0,0,0).availability());
    }
}
