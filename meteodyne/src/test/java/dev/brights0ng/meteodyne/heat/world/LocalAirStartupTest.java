// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirStartupTest {
    @Test void phaseClockSeparatesFirstPublicationCatchupAndWorkerCompletionWaitAndFreezes() {
        var clock=new AtomicLong();var trace=new LocalAirStartup(clock::get);
        trace.update(LocalAirStartup.Phase.CAPTURE,10,null);clock.set(10_000_000);
        trace.update(LocalAirStartup.Phase.ESTIMATE,11,null);clock.set(30_000_000);
        trace.completed(new LocalAirMetrics.Timing(LocalAirMetrics.Stage.ESTIMATE,2_000_000,3_000_000,2_000_000),15_000_000);
        trace.update(LocalAirStartup.Phase.CATCH_UP,40,10L);var first=trace.report();
        assertEquals(30,first.firstFieldMs());assertNull(first.caughtUpMs());
        assertEquals(15,first.completionWaitMs());assertEquals(3,first.workerWallMs());
        clock.set(50_000_000);trace.update(LocalAirStartup.Phase.CATCH_UP,41,40L);
        var done=trace.report();assertEquals(50,done.caughtUpMs());assertEquals("READY",done.phase());
        assertEquals(10,done.phaseMs().get("CAPTURE"));assertEquals(20,done.phaseMs().get("ESTIMATE"));assertEquals(20,done.phaseMs().get("CATCH_UP"));
        clock.set(90_000_000);trace.update(LocalAirStartup.Phase.CAPTURE,50,null);assertEquals(done,trace.report());
        assertThrows(UnsupportedOperationException.class,()->done.phaseMs().put("x",1.0));
    }
    @Test void stoppedRequestKeepsMissingPublicationExplicit() {
        var clock=new AtomicLong();var trace=new LocalAirStartup(clock::get);clock.set(12_000_000);
        trace.update(LocalAirStartup.Phase.STOPPED,1,null);clock.set(25_000_000);
        assertEquals(12,trace.report().elapsedMs());assertNull(trace.report().firstFieldMs());assertNull(trace.report().caughtUpMs());
    }
    @Test void pacedNaturalStartupReportsActualWorkerAndTickWaitCosts() throws Exception {
        var b=new Bounds(0,0,0,9,9,9);var heat=new Heat(12,80,Double.NaN,false);
        var world=new LocalAirPreparationTest.World() {
            @Override public int maxBuildY() { return 16; }
            @Override public Cell sample(int x,int y,int z) {
                if(y==0 || y<=5 && x>=2 && x<=6 && z>=2 && z<=6 && (x==2 || x==6 || z==2 || z==6 || y==5)) return LocalAirPreparationTest.SOLID;
                if(x==4 && y==1 && z==4) return new Cell(LocalAirPreparationTest.SOLID.shape(),1,1,1,1,heat,false,true);
                return LocalAirPreparationTest.AIR;
            }
        };
        var prep=new LocalAirPreparation(world,b,b.cells(),20_000,new LocalAirRun.Settings(20,0,0,4));
        prep.thermalInitialization(s->c->new LocalAirThermalState.Value(null,8,false,"CONTROLLED_ESTIMATE"));
        var executor=Executors.newSingleThreadExecutor();
        Future<LocalAirPreparation.Result> pending=null;
        double credit=0,reserved=0;
        try {
            long next=System.nanoTime();
            for(int tick=0;tick<600;tick++) {
                while(System.nanoTime()<next) LockSupport.parkNanos(Math.max(1,next-System.nanoTime()));
                prep.observe(tick,1);
                if(pending!=null && pending.isDone()) {var result=pending.get();credit+=reserved-result.nanos()/1e6;reserved=0;prep.complete(result);pending=null;}
                credit=Math.min(250,credit+12.5);
                prep.advance(256,System.nanoTime()+2_000_000L);prep.observe(tick,1);
                var report=prep.startup();
                if(report.caughtUpMs()!=null) {
                    var frame=prep.field();assertNotNull(frame);assertNotNull(frame.naturalInitialization);
                    assertTrue(frame.naturalInitialization.verificationDrift()<=.001);assertTrue(frame.quality.maxDivergence()<=1e-7);
                    assertTrue(report.firstFieldMs()<=report.caughtUpMs());assertTrue(report.completionWaitMs()>0);
                    System.out.println("PACED_STARTUP "+report);
                    System.out.println("PACED_WORK "+prep.metrics());return;
                }
                if(pending==null && credit>0) {
                    var work=prep.takeWork();
                    if(work!=null) {
                        var result=new CompletableFuture<LocalAirPreparation.Result>();pending=result;
                        if(work.estimate()) {reserved=Math.min(credit,LocalAirBatch.MAX_NANOS/1e6);credit-=reserved;LocalAirBatch.submit(executor,work,(long)(reserved*1e6),result::complete);}
                        else executor.execute(()->result.complete(work.run()));
                    }
                }
                next+=50_000_000L;
            }
            fail("Bounded startup did not qualify: "+prep.status()+" "+prep.runReason());
        } finally {
            prep.close();executor.shutdownNow();assertTrue(executor.awaitTermination(30,TimeUnit.SECONDS));
        }
    }
}
