// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirPreparation.Status.*;

class LocalAirPreparationTest {
    static final Cell AIR=new Cell(new Shape(new int[0],new int[0]),1,1,1,1);
    static final Cell SOLID=new Cell(new Shape(new int[]{0,0,0,16,16,16},new int[0]),1,1,1,1);
    static class World implements LocalAirCoverage.World {
        long revision=1,material=1;
        int reads;
        boolean missing,roof;
        public long contextId() { return 19; }
        public long geometryRevision() { return revision; }
        public long materialRevision() { return material; }
        public int minBuildY() { return 0; }
        public int maxBuildY() { return 5; }
        public Cell sample(int x,int y,int z) {
            reads++;
            if(missing) throw new Unavailable(Status.MISSING_GEOMETRY,"unloaded");
            return y==0 || (roof && y==3)?SOLID:AIR;
        }
    }
    private static LocalAirPreparation create(World w) { return new LocalAirPreparation(w,new Bounds(0,0,0,1,2,1),2,100); }
    private static void drain(LocalAirPreparation p) {
        for(int i=0;i<100 && p.status()!=PREPARED && p.status()!=REJECTED && p.status()!=FAILED;i++) {
            assertTrue(p.advance(3,Long.MAX_VALUE)<=3);
            var job=p.takeWork();if(job!=null) p.complete(job.run());
        }
    }
    @Test void capturePlanEvidenceBakeAndPublicationRequireSeparateStages() {
        var w=new World();var p=create(w);
        assertEquals(0,p.advance(2,System.nanoTime()-1));assertNull(p.takeWork());
        assertEquals(2,p.advance(2,Long.MAX_VALUE));assertEquals(PLAN_QUEUED,p.status());assertNull(p.published());
        var plan=p.takeWork();assertEquals(PLANNING,p.status());assertNull(p.takeWork());
        p.complete(plan.run());assertEquals(COVERAGE,p.status());assertNull(p.published());
        p.advance(100,Long.MAX_VALUE);assertEquals(BAKE_QUEUED,p.status());
        var bake=p.takeWork();assertEquals(BAKING,p.status());assertNull(p.published());
        p.complete(bake.run());assertEquals(PREPARED,p.status());assertNotNull(p.published());
    }
    @Test void invalidatedPhysicalJobsRetainTheirSlotUntilCollected() {
        var w=new World();var p=create(w);p.advance(2,Long.MAX_VALUE);var old=p.takeWork();
        w.revision++;assertEquals(CAPTURING,p.status());p.advance(2,Long.MAX_VALUE);
        assertEquals(PLAN_QUEUED,p.status());assertNull(p.takeWork());
        p.complete(old.run());assertEquals(PLAN_QUEUED,p.status());assertNotNull(p.takeWork());
    }
    @Test void staleBakeCannotPublishAndRebuildUsesTheChangedWorld() {
        var w=new World();var p=create(w);p.advance(2,Long.MAX_VALUE);p.complete(p.takeWork().run());
        p.advance(100,Long.MAX_VALUE);var old=p.takeWork();var completed=old.run();
        w.roof=true;w.revision++;p.complete(completed);assertNull(p.published());
        drain(p);assertEquals(REJECTED,p.status());assertNull(p.published());
    }
    @Test void missingChunksRetryOnlyAfterAnInvalidation() {
        var w=new World();w.missing=true;var p=create(w);drain(p);assertEquals(REJECTED,p.status());
        int reads=w.reads;drain(p);assertEquals(reads,w.reads);
        w.missing=false;w.revision++;drain(p);assertEquals(PREPARED,p.status());
    }
    @Test void materialReloadClearsPublishedGeometryBeforeNextTick() {
        var w=new World();var p=create(w);drain(p);assertNotNull(p.published());
        w.material++;assertNull(p.published());assertEquals(CAPTURING,p.status());drain(p);assertNotNull(p.published());
    }
    @Test void workerFailureIsTerminalUntilInputsChange() {
        var w=new World();var p=create(w);p.advance(2,Long.MAX_VALUE);var work=p.takeWork();
        p.complete(new LocalAirPreparation.Result(work,null,null,7,new IllegalStateException("fixture worker failure")));
        assertEquals(FAILED,p.status());assertTrue(p.reason().contains("fixture worker failure"));assertNull(p.takeWork());assertNull(p.published());
        w.revision++;drain(p);assertEquals(PREPARED,p.status());
    }
    @Test void closeAndReplacementCannotAcceptAnOldCompletion() {
        var w=new World();var old=create(w);old.advance(2,Long.MAX_VALUE);var job=old.takeWork();old.close();
        var replacement=create(w);replacement.advance(2,Long.MAX_VALUE);var current=replacement.takeWork();
        replacement.complete(job.run());assertEquals(PLANNING,replacement.status());
        old.complete(job.run());assertEquals(CLOSED,old.status());assertNull(old.published());
        replacement.complete(current.run());drain(replacement);assertNotNull(replacement.published());
    }
    @Test void workRunsOnWorkerWithoutReadingTheWorld() throws Exception {
        var w=new World();var p=create(w);p.advance(2,Long.MAX_VALUE);var job=p.takeWork();int reads=w.reads;
        var result=new AtomicReference<LocalAirPreparation.Result>();var error=new AtomicReference<Throwable>();
        var worker=new Thread(()->{ try {
            result.set(job.run());assertThrows(IllegalStateException.class,p::published);
            assertThrows(IllegalStateException.class,()->p.complete(result.get()));
        } catch(Throwable t) { error.set(t); } });worker.start();worker.join();
        if(error.get()!=null) throw new AssertionError(error.get());assertEquals(reads,w.reads);p.complete(result.get());drain(p);assertNotNull(p.published());
    }
    @Test void admissionLimitsPreventWorkAndReportCoverageRejection() {
        var w=new World();assertThrows(IllegalArgumentException.class,()->new LocalAirPreparation(w,new Bounds(0,0,0,2,2,2),7,100));
        var p=new LocalAirPreparation(w,new Bounds(0,0,0,1,2,1),2,0);drain(p);assertEquals(REJECTED,p.status());assertTrue(p.reason().contains("limit"));
    }
    @Test void foreignShapeExceptionFailsTheRequestWithoutEscapingIntoTheTick() {
        var w=new World() { @Override public Cell sample(int x,int y,int z) { throw new IllegalStateException("foreign shape failure"); } };
        var p=create(w);assertEquals(2,p.advance(2,Long.MAX_VALUE));assertEquals(FAILED,p.status());
        assertTrue(p.reason().contains("foreign shape failure"));assertNull(p.published());assertNull(p.takeWork());
    }
}
