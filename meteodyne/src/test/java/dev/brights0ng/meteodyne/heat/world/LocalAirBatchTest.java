// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirBatchTest {
    private static LocalAirPreparation preparation() {
        var b=new Bounds(0,0,0,9,9,9);
        var world=new LocalAirPreparationTest.World() {
            public int maxBuildY() {return 16;}
            public Cell sample(int x,int y,int z) {
                if(y==0 || y<=5 && x>=2 && x<=6 && z>=2 && z<=6 && (x==2 || x==6 || z==2 || z==6 || y==5)) return LocalAirPreparationTest.SOLID;
                if(x==4 && y==1 && z==4) return new Cell(LocalAirPreparationTest.SOLID.shape(),1,1,1,1,new Heat(12,80,Double.NaN,false),false,true);
                return LocalAirPreparationTest.AIR;
            }
        };
        var prep=new LocalAirPreparation(world,b,b.cells(),20000,new LocalAirRun.Settings(20,0,0,4));
        prep.thermalInitialization(s->c->new LocalAirThermalState.Value(null,8,false,"ESTIMATE"));return prep;
    }
    private static LocalAirPreparation.Work estimate(LocalAirPreparation prep) {
        for(int i=0;i<100;i++) {
            prep.observe(0,1);prep.advance(1000,Long.MAX_VALUE);prep.observe(0,1);
            var work=prep.takeWork();if(work!=null) {if(work.estimate()) return work;prep.complete(work.run());}
        }
        throw new AssertionError("No estimate queued");
    }
    @Test void prepaidContinuationsYieldToOtherExecutorWorkAndAggregateOnlyActualActiveCost() {
        var prep=preparation();var work=estimate(prep);
        var queue=new ArrayDeque<Runnable>();var output=new ArrayList<LocalAirPreparation.Result>();
        LocalAirBatch.submit(queue::add,work,LocalAirBatch.MAX_NANOS,output::add);
        boolean[] other={false};Runnable legacy=()->other[0]=true;queue.add(legacy);
        queue.remove().run();assertSame(legacy,queue.peek());queue.remove().run();
        for(int i=0;!queue.isEmpty() && i<64;i++) queue.remove().run();
        assertTrue(queue.isEmpty());assertTrue(other[0]);assertEquals(1,output.size());
        var result=output.getFirst();assertFalse(result.failed());assertTrue(result.timing().slices()>=1 && result.timing().slices()<=64);
        assertEquals(result.nanos(),result.timing().wallNanos());
        assertTrue(result.nanos()<=LocalAirBatch.MAX_NANOS+result.timing().maxSliceNanos());
        assertNull(prep.field());prep.complete(result);prep.close();
    }
    @Test void closingOwnerCancelsQueuedContinuationWithoutAnotherNumericalSliceOrPublication() {
        var prep=preparation();var work=estimate(prep);
        var queue=new ArrayDeque<Runnable>();var output=new ArrayList<LocalAirPreparation.Result>();
        LocalAirBatch.submit(queue::add,work,LocalAirBatch.MAX_NANOS,output::add);queue.remove().run();
        prep.close();while(!queue.isEmpty()) queue.remove().run();
        assertEquals(1,output.size());assertEquals(1,output.getFirst().timing().slices());
        prep.complete(output.getFirst());assertNull(prep.field());assertNull(prep.takeWork());
        assertEquals(1,prep.metrics().get(LocalAirMetrics.Stage.ESTIMATE).discarded());
    }
}
