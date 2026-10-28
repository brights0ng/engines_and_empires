// Written by Claude.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** Edits during the open-sky check requeue only the column proofs whose reads contain them. */
class LocalAirCoverageEditTest {
    private static final Bounds B=new Bounds(0,0,0,3,3,3);
    /** Open sky over a solid floor; every sample reads only its own position. */
    private static class World implements LocalAirCoverage.World {
        final Map<Long,Integer> reads=new HashMap<>();
        long last;
        public long contextId() { return 1; }
        public long geometryRevision() { return 1; }
        public long materialRevision() { return 1; }
        public int minBuildY() { return 0; }
        public int maxBuildY() { return 6; }
        public Cell sample(int x,int y,int z) {
            last=blockKey(x,y,z);reads.merge(last,1,Integer::sum);return y==0?LocalAirPreparationTest.SOLID:LocalAirPreparationTest.AIR;
        }
        public Footprint lastReads() { return new Footprint(new long[]{last}); }
        int column(int x,int z) { int n=0;for(int y=0;y<6;y++) n+=reads.getOrDefault(blockKey(x,y,z),0);return n; }
    }

    @Test void onlyProofsThatReadTheEditAreRequeuedAndReProved() {
        var w=new World();
        var capture=new LocalAirGeometryCapture(B,B.cells(),w);capture.advance(B.cells(),Long.MAX_VALUE);var snapshot=capture.snapshot();
        w.reads.clear();
        var c=new LocalAirCoverage(LocalAirCoverage.plan(snapshot),w,10_000);c.advance(10_000,Long.MAX_VALUE);
        assertEquals(LocalAirCoverage.Status.ADMITTED,c.status(),c.reason());
        var admitted=c.progress();int sampled=c.sampledCells();
        int edited=w.column(-1,1),corner=w.column(1,1),other=w.column(3,1);
        assertFalse(c.edited(10,3,10),"A position no proof read is not an edit");
        assertFalse(c.edited(-1,0,1),"Below the column's first read");
        assertEquals(LocalAirCoverage.Status.ADMITTED,c.status());assertEquals(0,c.requeuedColumns());
        assertTrue(c.edited(-1,3,1));
        assertEquals(LocalAirCoverage.Status.CHECKING,c.status());assertEquals(1,c.requeuedColumns());
        assertThrows(IllegalStateException.class,c::certificate);
        c.advance(10_000,Long.MAX_VALUE);
        assertEquals(LocalAirCoverage.Status.ADMITTED,c.status(),c.reason());assertEquals(admitted,c.progress());
        assertEquals(2*edited,w.column(-1,1),"The requeued column is read again from its start");
        assertEquals(corner,w.column(1,1),"Proofs elsewhere are untouched");assertEquals(other,w.column(3,1));
        assertEquals(sampled+edited,c.sampledCells());
        c.certificate().bake(snapshot,dev.brights0ng.paradynelib.heat.air.ThermalGeometry.Coefficients.legacyMaterials());
    }
    @Test void partialColumnRestartsWhenItsReadRowsAreEdited() {
        var w=new World();
        var capture=new LocalAirGeometryCapture(B,B.cells(),w);capture.advance(B.cells(),Long.MAX_VALUE);
        var c=new LocalAirCoverage(LocalAirCoverage.plan(capture.snapshot()),w,10_000);
        c.advance(30,Long.MAX_VALUE);assertEquals(LocalAirCoverage.Status.CHECKING,c.status());
        var before=c.progress();
        assertTrue(c.edited(-1,1,1),"A pending column that already read the edited row restarts");
        assertNotEquals(before,c.progress(),"Verified progress drops by the rows that must be read again");
        c.advance(10_000,Long.MAX_VALUE);assertEquals(LocalAirCoverage.Status.ADMITTED,c.status(),c.reason());
        assertTrue(c.progress().startsWith("Checking open-sky coverage: 104 / 104 cells"),c.progress());
    }
}
