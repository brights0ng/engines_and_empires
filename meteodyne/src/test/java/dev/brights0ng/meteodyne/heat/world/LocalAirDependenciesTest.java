// Written by ChatGPT.
// Updated by ChatGPT: verify bounded immutable invalidation diagnostics and restart attribution.
package dev.brights0ng.meteodyne.heat.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirDependenciesTest {
    @Test void diagnosticEventsRetainStatesPositionsScopeAndOnlyTheLastEightEvents() {
        var d=new LocalAirDependencies(new Bounds(0,0,0,2,2,2));d.read(10,3,10);d.read(10,9,10);
        d.blockChanged(100,0,100,1,"old","new");assertTrue(d.diagnostics().recent().isEmpty());
        d.blockChanged(1,1,1,2,"lava[level=0]","lava[level=1]");
        var snapshot=d.diagnostics();var event=snapshot.recent().getFirst();
        assertEquals("domain",event.scope());assertEquals(2,event.tick());assertEquals(1,event.x());
        assertEquals("lava[level=0]",event.before());assertEquals("lava[level=1]",event.after());
        d.blockChanged(10,5,10,3,"air","fire");assertEquals("shape/sky read interval",d.diagnostics().recent().getLast().scope());
        for(int i=0;i<20;i++) d.chunkChanged(0,0,4+i,"CHUNK_LOAD_APPLIED");
        assertEquals(8,d.diagnostics().recent().size());assertEquals(22,d.get());
        assertEquals(20,d.diagnostics().counts().get("CHUNK_LOAD_APPLIED"));assertEquals(1,snapshot.recent().size());
        assertThrows(UnsupportedOperationException.class,()->snapshot.recent().clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.counts().clear());
    }
    @Test void offThreadCauseAndHistoryLossRemainVisibleWithoutWorldAccess() throws Exception {
        var d=new LocalAirDependencies(new Bounds(0,0,0,1,1,1));d.historyReads(true);
        var worker=new Thread(()->d.blockChanged(50,60,70,-1,"stone","air"));worker.start();worker.join();
        var event=d.diagnostics().recent().getFirst();assertEquals(-1,event.tick());assertEquals("historical read guard",event.scope());
        d.historyLost(3,4,5,100);assertEquals("HISTORY_LOST",d.diagnostics().recent().getLast().cause());assertEquals(2,d.get());
    }
    @Test void actualRestartsCoalesceRevisionChangesAndAlsoAttributeMaterialAndContextChanges() {
        long[] context={19};
        var w=new LocalAirPreparationTest.World() { @Override public long contextId() { return context[0]; } };
        var p=new LocalAirPreparation(w,new Bounds(0,0,0,1,2,1),2,100);
        assertEquals(0,p.restarts());w.revision+=3;
        p.status();assertEquals(1,p.restarts());assertTrue(p.lastRestart().contains("geometry 1 -> 4"));
        w.material++;context[0]++;p.status();assertEquals(2,p.restarts());
        assertTrue(p.lastRestart().contains("materials 1 -> 2"));assertTrue(p.lastRestart().contains("context 19 -> 20"));
        p.status();assertEquals(2,p.restarts());assertEquals(3,p.attempts());
    }
    @Test void unreadDomainCellsAreProtectedButUnrelatedEditsAndChunksAreIgnored() {
        var d=new LocalAirDependencies(new Bounds(-17,0,15,3,4,3));
        d.blockChanged(-16,3,17);assertEquals(1,d.get());
        d.blockChanged(-16,4,17);d.blockChanged(-18,3,17);d.chunkChanged(1,1);assertEquals(1,d.get());
        d.chunkChanged(-2,0);d.chunkChanged(-1,1);assertEquals(3,d.get());
    }
    @Test void externalShapeAndSkyReadsProtectTheirIntervalsAndChunks() {
        var d=new LocalAirDependencies(new Bounds(0,0,0,1,1,1));
        d.read(30,5,-30);d.read(30,100,-30);
        d.blockChanged(30,50,-30);assertEquals(1,d.get());
        d.blockChanged(30,101,-30);d.blockChanged(31,50,-30);assertEquals(1,d.get());
        d.chunkChanged(1,-2);assertEquals(2,d.get());
        // Revisions do not clear dependencies: the dry-shape cache and old work still rely on them.
        d.getAndIncrement();d.blockChanged(30,5,-30);assertEquals(4,d.get());
    }
    @Test void dependencyBudgetFallsBackConservatively() {
        var d=new LocalAirDependencies(new Bounds(0,0,0,1,1,1));
        for(int x=0;x<=65_536;x++) d.read(x,0,7);
        d.blockChanged(-100,300,-100);d.chunkChanged(-100,-100);assertEquals(2,d.get());
    }
    @Test void offThreadEditsInvalidateRegisteredDependencies() throws Exception {
        var d=new LocalAirDependencies(new Bounds(0,0,0,1,1,1));d.read(16,20,16);
        var worker=new Thread(()->{d.blockChanged(16,20,16);d.blockChanged(200,20,200);d.chunkChanged(1,1);});
        worker.start();worker.join();assertEquals(2,d.get());
    }
    @Test void relevantEditRejectsOldWorkerPublicationWhileDistantEditKeepsIt() {
        var dependencies=new LocalAirDependencies(new Bounds(0,0,0,1,1,1));
        var source=new LocalAirPreparationTest.World() {
            @Override public long geometryRevision() { return dependencies.get(); }
            @Override public Cell sample(int x,int y,int z) {
                dependencies.read(x,y,z);return LocalAirPreparationTest.SOLID;
            }
        };
        var p=new LocalAirPreparation(source,new Bounds(0,0,0,1,1,1),1,0);
        p.advance(1,Long.MAX_VALUE);var plan=p.takeWork();
        dependencies.blockChanged(100,0,100);p.complete(plan.run());
        p.advance(1,Long.MAX_VALUE);var bake=p.takeWork();var result=bake.run();
        dependencies.blockChanged(0,0,0);p.complete(result);assertNull(p.published());
        assertEquals(LocalAirPreparation.Status.CAPTURING,p.status());
    }
    @Test void admittedSkyCertificateDependsOnOutsideColumnsThroughWorldCeiling() {
        var bounds=new Bounds(0,0,0,1,2,1);var d=new LocalAirDependencies(bounds);
        var source=new LocalAirPreparationTest.World() {
            @Override public long geometryRevision() { return d.get(); }
            @Override public Cell sample(int x,int y,int z) { d.read(x,y,z);return super.sample(x,y,z); }
        };
        var capture=new LocalAirGeometryCapture(bounds,2,source);capture.advance(2,Long.MAX_VALUE);
        var evidence=new LocalAirCoverage(LocalAirCoverage.plan(capture.snapshot()),source,100);
        evidence.advance(100,Long.MAX_VALUE);var certificate=evidence.certificate();assertTrue(certificate.current(source));
        d.blockChanged(10,4,0);assertTrue(certificate.current(source));
        d.blockChanged(-1,4,0);assertFalse(certificate.current(source));
    }
    @Test void historicalReadsConservativelyInvalidateUnknownOffThreadAndChunkChangesUntilReleased() {
        var d=new LocalAirDependencies(new Bounds(0,0,0,1,1,1));d.historyReads(true);
        d.blockChanged(100,100,100);d.chunkChanged(100,100);assertEquals(2,d.get());
        d.historyReads(false);d.blockChanged(100,100,100);d.chunkChanged(100,100);assertEquals(2,d.get());
    }
}
