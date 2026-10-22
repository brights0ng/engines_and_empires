// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirDependenciesTest {
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
