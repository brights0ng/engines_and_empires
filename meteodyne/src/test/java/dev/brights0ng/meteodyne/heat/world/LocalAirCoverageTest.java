// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;
import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;

class LocalAirCoverageTest {
    private record Pos(int x,int y,int z) { }
    private static final Cell AIR=new Cell(new Shape(new int[0],new int[0]),1,1,1,1);
    private static final Cell SOLID=new Cell(new Shape(new int[]{0,0,0,16,16,16},new int[0]),1,1,1,1);
    private static final Cell WATER=new Cell(new Shape(new int[0],new int[]{0,0,0,16,16,16}),1,1,2,2);
    private static final Cell SLAB=new Cell(new Shape(new int[]{0,0,0,16,8,16},new int[0]),1,1,1,1);
    private static final Bounds B=new Bounds(0,0,0,3,3,3);
    private static final ThermalGeometry.Coefficients C=ThermalGeometry.Coefficients.legacyMaterials();
    private static class World implements LocalAirCoverage.World {
        long context=1,geometry=1,material=1;
        int min=0,max=6,reads;
        Pos unavailable;
        Status unavailableStatus=Status.MISSING_GEOMETRY;
        final Map<Pos,Cell> cells=new HashMap<>();
        public long contextId() { return context; }
        public long geometryRevision() { return geometry; }
        public long materialRevision() { return material; }
        public int minBuildY() { return min; }
        public int maxBuildY() { return max; }
        public Cell sample(int x,int y,int z) {
            reads++;var pos=new Pos(x,y,z);
            if(pos.equals(unavailable)) throw new Unavailable(unavailableStatus,"fixture unavailable");
            assertTrue(y>=min && y<max,"Read outside world height");
            return cells.getOrDefault(pos,y==0?SOLID:AIR);
        }
    }
    private static Snapshot snapshot(World w,Bounds b) {
        var capture=new LocalAirGeometryCapture(b,b.cells(),w);
        capture.advance(b.cells(),Long.MAX_VALUE);assertEquals(Status.CAPTURED,capture.status());return capture.snapshot();
    }
    private static LocalAirCoverage coverage(World w,Snapshot s) { return new LocalAirCoverage(LocalAirCoverage.plan(s),w,10_000); }
    private static LocalAirCoverage.Certificate admit(World w,Snapshot s) {
        var c=coverage(w,s);c.advance(10_000,Long.MAX_VALUE);
        assertEquals(LocalAirCoverage.Status.ADMITTED,c.status(),c.reason());return c.certificate();
    }
    @Test void openSkyProducesBoundedDeduplicatedColumnEvidenceAndOpenFaces() {
        var w=new World();var s=snapshot(w,B);var c=coverage(w,s);
        assertEquals(0,c.advance(10,System.nanoTime()-1));assertEquals(0,c.advance(0,Long.MAX_VALUE));
        assertEquals(2,c.advance(2,Long.MAX_VALUE));assertEquals(2,c.sampledCells());
        assertThrows(IllegalStateException.class,c::certificate);
        while(c.status()==LocalAirCoverage.Status.CHECKING) assertTrue(c.advance(7,Long.MAX_VALUE)<=7);
        // Nine footprint columns and twelve immediate outside columns; center starts at top y=2.
        assertEquals(104,c.sampledCells());
        var p=c.certificate().bake(s,C);int exterior=0;
        for(int f=0;f<p.flow().faces();f++) if(p.flow().exterior(f)) exterior++;
        assertEquals(33,exterior);assertEquals(0,p.thermal().unrepresentedFluidCells());
    }
    @Test void roofedCutAndObstructedNeighborRejectInsteadOfBecomingWeather() {
        for(Pos obstruction:new Pos[]{new Pos(0,4,1),new Pos(-1,1,1),new Pos(-1,5,1)}) {
            var w=new World();w.cells.put(obstruction,SOLID);var c=coverage(w,snapshot(w,B));c.advance(10_000,Long.MAX_VALUE);
            assertEquals(LocalAirCoverage.Status.INSUFFICIENT_COVERAGE,c.status());assertThrows(IllegalStateException.class,c::certificate);
        }
    }
    @Test void partialAndBottomAirCutsRejectBeforeAnyCoverageReads() {
        for(Cell bottom:new Cell[]{AIR,SLAB}) {
            var w=new World();w.cells.put(new Pos(1,0,1),bottom);var s=snapshot(w,B);int before=w.reads;var c=coverage(w,s);
            assertEquals(LocalAirCoverage.Status.INSUFFICIENT_COVERAGE,c.status());assertEquals(0,c.advance(10_000,Long.MAX_VALUE));assertEquals(before,w.reads);
        }
        var w=new World();w.cells.put(new Pos(0,1,1),SLAB);var c=coverage(w,snapshot(w,B));
        assertEquals(LocalAirCoverage.Status.INSUFFICIENT_COVERAGE,c.status());
    }
    @Test void missingOrUnsupportedColumnNeverGetsCertificate() {
        for(Status unavailable:new Status[]{Status.MISSING_GEOMETRY,Status.UNSUPPORTED_GEOMETRY}) {
            var w=new World();var s=snapshot(w,B);w.unavailable=new Pos(-1,4,1);w.unavailableStatus=unavailable;
            var c=coverage(w,s);c.advance(10_000,Long.MAX_VALUE);
            assertEquals(unavailable.name(),c.status().name());assertThrows(IllegalStateException.class,c::certificate);
        }
    }
    @Test void admissionBudgetIsCheckedBeforeWorldReads() {
        var w=new World();var s=snapshot(w,B);int before=w.reads;
        var c=new LocalAirCoverage(LocalAirCoverage.plan(s),w,103);
        assertEquals(LocalAirCoverage.Status.LIMIT_EXCEEDED,c.status());assertEquals(before,w.reads);
        assertThrows(IllegalArgumentException.class,()->new LocalAirCoverage(LocalAirCoverage.plan(s),w,-1));
        assertThrows(IllegalArgumentException.class,()->c.advance(-1,Long.MAX_VALUE));
    }
    @Test void sealedDomainNeedsNoSkyReadsAndWetInteriorKeepsItsOwnState() {
        var w=new World();
        for(int z=0;z<3;z++) for(int y=0;y<3;y++) for(int x=0;x<3;x++) w.cells.put(new Pos(x,y,z),SOLID);
        w.cells.put(new Pos(1,1,1),WATER);
        var s=snapshot(w,B);var c=new LocalAirCoverage(LocalAirCoverage.plan(s),w,0);
        assertEquals(LocalAirCoverage.Status.ADMITTED,c.status());assertEquals(0,c.sampledCells());
        var p=c.certificate().bake(s,C);assertEquals(0,p.flow().faces());assertTrue(p.thermal().fluidState(13)>=0);
    }
    @Test void fullyRepresentedRoofedRoomStaysSealedInsideAnAdmittedOutdoorDomain() {
        var w=new World();
        for(Pos pos:new Pos[]{new Pos(1,1,2),new Pos(3,1,2),new Pos(2,1,1),new Pos(2,1,3),new Pos(2,2,2)}) w.cells.put(pos,SOLID);
        var s=snapshot(w,new Bounds(0,0,0,5,4,5));var g=admit(w,s).bake(s,C).flow();
        int room=g.nodeAt(2.5,1.5,2.5);assertTrue(room>=0);int exterior=0;
        for(int face=0;face<g.faces();face++) {
            assertNotEquals(room,g.faceLow(face));assertNotEquals(room,g.faceHigh(face));
            if(g.exterior(face)) exterior++;
        }
        assertTrue(exterior>0);assertTrue(g.components()>1);
    }
    @Test void allCaptureEpochsAndHeightChangesRejectAtEveryHandoff() {
        for(int epoch=0;epoch<4;epoch++) for(int phase=0;phase<3;phase++) {
            var w=new World();var s=snapshot(w,B);LocalAirCoverage c=null;
            if(phase>0) { c=coverage(w,s);c.advance(phase==1?1:10_000,Long.MAX_VALUE); }
            switch(epoch) { case 0->w.geometry++;case 1->w.material++;case 2->w.context++;case 3->w.max++; }
            if(phase==0) c=coverage(w,s);
            if(phase==2) assertThrows(IllegalStateException.class,c::certificate);else c.advance(10_000,Long.MAX_VALUE);
            // Height is captured when coverage starts; a changed ceiling before that start is valid.
            if(epoch==3 && phase==0) assertEquals(LocalAirCoverage.Status.ADMITTED,c.status());
            else assertEquals(LocalAirCoverage.Status.STALE,c.status());
        }
    }
    @Test void revisionDuringColumnSampleRejectsImmediately() {
        var w=new World();var s=snapshot(w,B);
        var changing=new World() { @Override public Cell sample(int x,int y,int z) { geometry++;return AIR; } };
        var c=coverage(changing,s);assertEquals(1,c.advance(100,Long.MAX_VALUE));assertEquals(LocalAirCoverage.Status.STALE,c.status());
    }
    @Test void certificateCannotAdmitAnotherSnapshotOrWorld() {
        var w=new World();var a=snapshot(w,B);var b=snapshot(w,B);var cert=admit(w,a);
        assertThrows(IllegalArgumentException.class,()->cert.bake(b,C));
        var gate=new LocalAirGeometryHandoff(w);
        assertThrows(IllegalArgumentException.class,()->gate.begin(b,cert));
        w.context++;assertThrows(IllegalStateException.class,()->gate.begin(a,cert));
    }
    @Test void delayedJobsCannotRepublishAfterReplacementInvalidationOrFailure() {
        var w=new World();var s=snapshot(w,B);var cert=admit(w,s);var gate=new LocalAirGeometryHandoff(w);
        var first=gate.begin(s,cert);var second=gate.begin(s,cert);
        gate.failed(first);assertFalse(gate.complete(first.bake(C)));assertTrue(gate.complete(second.bake(C)));
        assertNotNull(gate.published());assertFalse(gate.complete(second.bake(C)));
        var third=gate.begin(s,cert);assertNull(gate.published());gate.invalidate();assertFalse(gate.complete(third.bake(C)));
        var fourth=gate.begin(s,cert);gate.failed(fourth);assertFalse(gate.complete(fourth.bake(C)));assertNull(gate.published());
    }
    @Test void dirtyResultsAndPreviouslyPublishedGeometryAreWithheld() {
        for(boolean material:new boolean[]{false,true}) for(boolean complete:new boolean[]{false,true}) {
            var w=new World();var s=snapshot(w,B);var gate=new LocalAirGeometryHandoff(w);var job=gate.begin(s,admit(w,s));var done=job.bake(C);
            if(complete) assertTrue(gate.complete(done));
            if(material) w.material++;else w.geometry++;
            if(!complete) assertFalse(gate.complete(done));assertNull(gate.published());
        }
    }
    @Test void workerOnlyReceivesImmutablePlanCertificateAndJob() throws Exception {
        var w=new World();var s=snapshot(w,B);var c=coverage(w,s);c.advance(10_000,Long.MAX_VALUE);
        var cert=c.certificate();var gate=new LocalAirGeometryHandoff(w);var job=gate.begin(s,cert);
        var result=new AtomicReference<LocalAirGeometryHandoff.Completion>();var error=new AtomicReference<Throwable>();
        var thread=new Thread(()->{ try {
            assertThrows(IllegalStateException.class,()->c.advance(1,Long.MAX_VALUE));
            assertThrows(IllegalStateException.class,c::certificate);assertThrows(IllegalStateException.class,gate::published);
            assertNotNull(LocalAirCoverage.plan(s));result.set(job.bake(C));
            assertThrows(IllegalStateException.class,()->gate.complete(result.get()));
        } catch(Throwable t) { error.set(t); } });
        thread.start();thread.join();if(error.get()!=null) throw new AssertionError(error.get());
        assertTrue(gate.complete(result.get()));assertNotNull(gate.published());
    }
}
