// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirCoverageReuseTest {
    private record Pos(int x,int y,int z) { long key() { return blockKey(x,y,z); } }
    private static final Bounds B=new Bounds(0,0,0,3,3,3);
    private static final Cell AIR=new Cell(new Shape(new int[0],new int[0]),1,1,1,1);
    private static final Cell SOLID=new Cell(new Shape(new int[]{0,0,0,16,16,16},new int[0]),1,1,1,1);
    private static final Cell CLOSED=new Cell(new Shape(new int[]{0,0,0,3,16,16},new int[0]),1,1,1,1);
    private static final Cell OPEN=new Cell(new Shape(new int[]{0,0,0,16,16,3},new int[0]),1,1,1,1);
    private static class World implements LocalAirCoverage.World {
        long context=1,revision=1,material=1;int min=0,max=6,reads,evidence=2;
        boolean contextBlocked;Pos dependent,dependency;
        Footprint last;
        final HashMap<Pos,Cell> cells=new HashMap<>();
        public long contextId() { return context; }
        public long geometryRevision() { return revision; }
        public long materialRevision() { return material; }
        public int minBuildY() { return min; }
        public int maxBuildY() { return max; }
        public Footprint lastReads() { return evidence==0?null:evidence==1?new Footprint(new long[0]):last; }
        public Cell sample(int x,int y,int z) {
            reads++;var p=new Pos(x,y,z);assertTrue(y>=min && y<max);
            boolean extra=p.equals(dependent);
            last=new Footprint(extra?new long[]{p.key(),dependency.key()}:new long[]{p.key()});
            return extra && contextBlocked?SOLID:cells.getOrDefault(p,y==min?SOLID:AIR);
        }
        public LocalAirCoverage.History freeze() {
            var copy=new World();copy.context=context;copy.revision=revision;copy.material=material;copy.min=min;copy.max=max;
            copy.evidence=evidence;copy.contextBlocked=contextBlocked;copy.dependent=dependent;copy.dependency=dependency;copy.cells.putAll(cells);
            return new LocalAirCoverage.History() {
                public LocalAirCoverage.World world() { return copy; }
                public void close() { }
            };
        }
    }
    private static Snapshot snapshot(World w,Bounds b) {
        var c=new LocalAirGeometryCapture(b,b.cells(),w);c.advance(b.cells(),Long.MAX_VALUE);return c.snapshot();
    }
    private static LocalAirCoverage.Certificate admit(World w,Bounds b) {
        var c=new LocalAirCoverage(LocalAirCoverage.plan(snapshot(w,b)),w,10_000);c.advance(10_000,Long.MAX_VALUE);
        assertEquals(LocalAirCoverage.Status.ADMITTED,c.status(),c.reason());return c.certificate();
    }
    private static LocalAirCoverage next(World w,Bounds b,LocalAirCoverage.Certificate old,Set<Long> edits) {
        return new LocalAirCoverage(LocalAirCoverage.plan(snapshot(w,b)),w,10_000,old,edits);
    }
    @Test void interiorEditReusesEveryColumnAndIssuesANewSnapshotBoundCertificate() {
        var w=new World();var old=admit(w,B);w.revision++;w.cells.put(new Pos(1,1,1),OPEN);
        var s=snapshot(w,B);var c=new LocalAirCoverage(LocalAirCoverage.plan(s),w,104,old,Set.of(blockKey(1,1,1)));
        int before=w.reads;assertEquals(0,c.advance(1,Long.MAX_VALUE));assertEquals(before,w.reads);
        assertEquals(0,c.sampledCells());assertEquals(104,c.reusedCells());assertEquals(LocalAirCoverage.Status.ADMITTED,c.status());
        assertFalse(old.current(w));assertTrue(c.certificate().current(w));
        assertThrows(IllegalArgumentException.class,()->old.bake(s,dev.brights0ng.paradynelib.heat.air.ThermalGeometry.Coefficients.legacyMaterials()));
        assertNotNull(c.certificate().bake(s,dev.brights0ng.paradynelib.heat.air.ThermalGeometry.Coefficients.legacyMaterials()));
    }
    @Test void editedRoofAndContextDependentEmptyShapesCannotReuseAStaleSkyProof() {
        for(boolean context:new boolean[]{false,true}) {
            var w=new World();var roof=new Pos(-1,4,1);var edit=context?new Pos(1,1,1):roof;
            if(context) {w.dependent=roof;w.dependency=edit;}
            var old=admit(w,B);w.revision++;
            if(context) w.contextBlocked=true;else w.cells.put(roof,SOLID);
            var c=next(w,B,old,Set.of(edit.key()));c.advance(1000,Long.MAX_VALUE);
            assertEquals(LocalAirCoverage.Status.INSUFFICIENT_COVERAGE,c.status());assertTrue(c.sampledCells()>0);
            assertThrows(IllegalStateException.class,c::certificate);
        }
    }
    @Test void absentOrEmptyReadEvidenceForcesFullCoverageCapture() {
        for(int evidence:new int[]{0,1}) {
            var w=new World();w.evidence=evidence;var old=admit(w,B);w.revision++;
            var c=next(w,B,old,Set.of(blockKey(1,1,1)));c.advance(1000,Long.MAX_VALUE);
            assertEquals(LocalAirCoverage.Status.ADMITTED,c.status());assertEquals(104,c.sampledCells());assertEquals(0,c.reusedCells());
        }
    }
    @Test void lowerColumnRequirementsAreRecapturedInsteadOfAssumingTheMissingSpanIsEmpty() {
        var w=new World();var opening=new Pos(0,1,1);w.cells.put(opening,SOLID);var old=admit(w,B);
        w.revision++;w.cells.remove(opening);var c=next(w,B,old,Set.of(opening.key()));c.advance(1000,Long.MAX_VALUE);
        assertEquals(LocalAirCoverage.Status.ADMITTED,c.status());assertEquals(10,c.sampledCells());assertEquals(94,c.reusedCells());
    }
    @Test void worldMaterialHeightBoundsAndRevisionChangesFallBackToFullProofs() {
        for(int change=0;change<6;change++) {
            var w=new World();
            // Keep the physical floor fixed when only the world's minimum build height changes.
            for(int x=0;x<3;x++)for(int z=0;z<3;z++)w.cells.put(new Pos(x,0,z),SOLID);
            var old=admit(w,B);Bounds b=B;
            switch(change) {
                case 0 -> w.context++;
                case 1 -> w.material++;
                case 2 -> w.min--;
                case 3 -> w.max++;
                case 4 -> b=new Bounds(1,0,0,3,3,3);
                default -> w.revision--;
            }
            var c=next(w,b,old,Set.of());c.advance(1000,Long.MAX_VALUE);
            assertEquals(0,c.reusedCells());assertTrue(c.sampledCells()>0);
        }
    }
    @Test void fullAdmissionQuotaDeadlineAndRevisionChecksStillApplyToReusableCoverage() {
        var w=new World();var old=admit(w,B);w.revision++;var s=snapshot(w,B);
        var limited=new LocalAirCoverage(LocalAirCoverage.plan(s),w,103,old,Set.of());
        assertEquals(LocalAirCoverage.Status.LIMIT_EXCEEDED,limited.status());assertEquals(0,limited.advance(1,Long.MAX_VALUE));
        var edits=new java.util.HashSet<Long>();var c=new LocalAirCoverage(LocalAirCoverage.plan(s),w,104,old,edits);
        edits.add(blockKey(-1,4,1));assertEquals(0,c.advance(1,System.nanoTime()-1));assertEquals(0,c.advance(0,Long.MAX_VALUE));
        assertEquals(0,c.reusedCells());assertEquals(0,c.advance(1,Long.MAX_VALUE));assertEquals(104,c.reusedCells());
        w.revision++;assertEquals(0,c.advance(1,Long.MAX_VALUE));assertEquals(LocalAirCoverage.Status.STALE,c.status());
        assertThrows(IllegalStateException.class,c::certificate);
    }
    @Test void negativePackedCoordinatesPreserveDependencyIntersections() {
        var w=new World();w.min=-3;w.max=3;var b=new Bounds(-7,-3,-9,3,3,3);var old=admit(w,b);w.revision++;
        var c=next(w,b,old,Set.of(blockKey(-6,-2,-8)));c.advance(1,Long.MAX_VALUE);
        assertEquals(104,c.reusedCells());var cert=c.certificate();w.revision++;var roof=new Pos(-8,1,-8);w.cells.put(roof,SOLID);
        c=next(w,b,cert,Set.of(roof.key()));c.advance(1000,Long.MAX_VALUE);
        assertEquals(LocalAirCoverage.Status.INSUFFICIENT_COVERAGE,c.status());assertTrue(c.sampledCells()>0);
    }
    private static void drive(LocalAirPreparation p,long tick,double scale) {
        for(int i=0;i<2000;i++) {
            p.advance(8,Long.MAX_VALUE);p.observe(tick,scale);var work=p.takeWork();if(work!=null)p.complete(work.run());
            var f=p.field();if(f!=null && f.throughTick==tick)return;
        }
        fail(p.status()+" / "+p.runReason());
    }
    private static LocalAirRun.Frame timeline(boolean delayed,int cap) {
        var w=new World();var b=new Bounds(0,0,0,5,3,3);var door=new Pos(2,1,1);w.cells.put(door,CLOSED);
        var p=new LocalAirPreparation(w,b,b.cells(),1000,new LocalAirRun.Settings(20,0.5,0,cap));drive(p,0,1);drive(p,100,1);
        long baseline=p.coverageReadCells();assertTrue(baseline>0);
        long revision=w.revision++;w.cells.put(door,OPEN);assertTrue(p.changeGeometry(2,1,1,revision,103,1));if(!delayed)drive(p,103,1);
        w.cells.put(new Pos(1,1,1),new Cell(AIR.shape(),1,1,1,1,new Heat(4,Double.NaN,Double.NaN,false)));
        assertTrue(p.changeSource(1,1,1,107,1));if(!delayed)drive(p,107,1);
        assertTrue(p.changeWeather(111,new LocalAirRun.Weather(25,-0.5,0),1));if(!delayed)drive(p,111,1);
        revision=w.revision++;w.cells.put(door,CLOSED);assertTrue(p.changeGeometry(2,1,1,revision,113,1));if(!delayed)drive(p,113,1);
        p.observe(117,2);assertTrue(p.changeWeather(121,new LocalAirRun.Weather(10,0.5,0),2));drive(p,200,2);
        var f=p.field();assertEquals(2,f.transfers);assertEquals(1,f.sourceRevision);assertEquals(35.2,f.sourceEnergy,1e-8);
        assertEquals(baseline,p.coverageReadCells());assertEquals(2*baseline,p.coverageReusedCells());
        w.revision++;assertNull(p.field());drive(p,220,2);
        assertEquals(0,p.field().transfers);assertTrue(p.coverageReadCells()>baseline);return f;
    }
    @Test void queuedTopologiesSourcesWeatherAndResponseReplayReuseOnlyTheirPredecessorsProofs() {
        for(int cap:new int[]{1,2,64}) {
            var a=timeline(false,cap);var b=timeline(true,cap);assertEquals(a.energy,b.energy);assertEquals(a.acceptedSteps,b.acceptedSteps);
            for(int i=0;i<a.flow.faces();i++)assertEquals(a.flux(i),b.flux(i));
            for(int i=0;i<a.flow.nodes();i++)assertEquals(a.temperature(i),b.temperature(i));
        }
    }
    private static LocalAirPreparation contextPreparation(World w) {
        w.dependent=new Pos(-1,4,1);w.dependency=new Pos(1,1,1);w.cells.put(new Pos(2,1,1),CLOSED);
        var p=new LocalAirPreparation(w,new Bounds(0,0,0,5,3,3),45,1000,new LocalAirRun.Settings(20,0,0,2));
        drive(p,0,1);drive(p,100,1);return p;
    }
    @Test void sourceChangesInReusedSkyContextTakeTheExistingColdInvalidationPath() {
        var w=new World();var p=contextPreparation(w);long revision=w.revision++;w.cells.put(new Pos(2,1,1),OPEN);
        assertTrue(p.changeGeometry(2,1,1,revision,103,1));drive(p,103,1);assertTrue(p.coverageReusedCells()>0);
        w.contextBlocked=true;
        assertFalse(p.changeSource(1,1,1,103,1));w.revision++; // HeatWorld increments the epoch for a rejected source refresh.
        assertNull(p.field());
        for(int i=0;i<1000 && p.status()!=LocalAirPreparation.Status.REJECTED;i++) {
            p.advance(8,Long.MAX_VALUE);p.observe(110,1);var work=p.takeWork();if(work!=null)p.complete(work.run());
        }
        assertEquals(LocalAirPreparation.Status.REJECTED,p.status());assertNull(p.field());
    }
    @Test void sourceEventsQueuedBeforeProofCaptureCannotReplayThroughAnUnverifiedBoundary() {
        var w=new World();var p=contextPreparation(w);long revision=w.revision++;w.cells.put(new Pos(2,1,1),OPEN);
        assertTrue(p.changeGeometry(2,1,1,revision,103,1));w.contextBlocked=true;
        assertTrue(p.changeSource(1,1,1,107,1));
        for(int i=0;i<1000 && p.status()!=LocalAirPreparation.Status.FAILED;i++) {
            p.advance(8,Long.MAX_VALUE);p.observe(110,1);var work=p.takeWork();if(work!=null)p.complete(work.run());
        }
        assertEquals(LocalAirPreparation.Status.FAILED,p.status());assertTrue(p.runReason().contains("sky context"));assertNull(p.field());
    }
    @Test void unknownSkyContextDoesNotAdmitSourceOnlyContinuity() {
        var w=new World();w.evidence=0;var p=contextPreparation(w);
        assertFalse(p.changeSource(1,1,1,100,1));
    }
}
