// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirIncrementalTest {
    private static final Bounds B=new Bounds(0,0,0,5,3,3);
    private static final Shape AIR=new Shape(new int[0],new int[0]);
    private static final Shape THIN=new Shape(new int[]{0,0,0,3,16,16},new int[0]);
    private static final Heat HEAT=new Heat(10,Double.NaN,Double.NaN,false);
    private static final long DOOR=blockKey(2,1,1),FIRE=blockKey(1,1,1);
    private static class Input implements Source {
        long revision=1,material=1,context=1;int reads;
        boolean changed,lit=true,evidence=true;
        Footprint last;
        public long contextId() { return context; }
        public long geometryRevision() { return revision; }
        public long materialRevision() { return material; }
        public Cell sample(int x,int y,int z) {
            reads++;long own=blockKey(x,y,z);
            boolean dependent=x==3 && y==1 && z==1;
            last=new Footprint(dependent?new long[]{own,DOOR}:new long[]{own});
            return new Cell(changed && (own==DOOR || dependent)?THIN:AIR,2,3,4,5,own==FIRE && lit?HEAT:null);
        }
        public Footprint lastReads() { return evidence?last:null; }
    }
    private static Snapshot initial(Input s) {
        var c=new LocalAirGeometryCapture(B,B.cells(),s);c.advance(B.cells(),Long.MAX_VALUE);return c.snapshot();
    }
    @Test void classifiedCellAndContextChangesMatchFullCaptureWithOnlyThreeReads() {
        var s=new Input();var baseline=initial(s);s.changed=true;s.lit=false;s.revision++;
        var incremental=new LocalAirGeometryCapture(B,B.cells(),s,baseline,Set.of(DOOR,FIRE));
        assertEquals(3,incremental.advance(8,Long.MAX_VALUE));assertEquals(3,incremental.readCells());
        assertEquals(B.cells()-3,incremental.reusedCells());var actual=incremental.snapshot();
        var control=new LocalAirGeometryCapture(B,B.cells(),s);control.advance(B.cells(),Long.MAX_VALUE);var expected=control.snapshot();
        for(int z=0;z<B.nz();z++) for(int y=0;y<B.ny();y++) for(int x=0;x<B.nx();x++)
            assertEquals(expected.shape(x,y,z),actual.shape(x,y,z));
        assertTrue(actual.sameMaterials(expected));assertNull(actual.heat(21));assertNotNull(baseline.heat(21));
        assertEquals(AIR,baseline.shape(2,1,1));assertEquals(THIN,actual.shape(3,1,1));
    }
    @Test void missingReadEvidenceFallsBackToFullWorldSampling() {
        var s=new Input();s.evidence=false;var baseline=initial(s);s.revision++;s.changed=true;
        var c=new LocalAirGeometryCapture(B,B.cells(),s,baseline,Set.of(DOOR));
        assertEquals(B.cells(),c.advance(B.cells(),Long.MAX_VALUE));assertEquals(0,c.reusedCells());
    }
    @Test void workDeadlineAndRevisionGuardsApplyEvenWhenMostCellsAreReusable() {
        var s=new Input();var baseline=initial(s);s.revision++;s.changed=true;
        var c=new LocalAirGeometryCapture(B,B.cells(),s,baseline,Set.of(DOOR));
        assertEquals(0,c.advance(8,System.nanoTime()-1));assertEquals(0,c.sampledCells());
        assertEquals(0,c.advance(0,Long.MAX_VALUE));assertEquals(0,c.sampledCells());
        assertEquals(1,c.advance(1,Long.MAX_VALUE));assertEquals(Status.CAPTURING,c.status());
        assertEquals(1,c.advance(1,Long.MAX_VALUE));assertEquals(Status.CAPTURED,c.status());
        assertEquals(B.cells()-2,c.reusedCells());s.revision++;
        assertThrows(IllegalStateException.class,c::snapshot);assertEquals(Status.STALE,c.status());
    }
    @Test void incompatibleBaselineIdentityOrMaterialsCannotBeReused() {
        var s=new Input();var baseline=initial(s);s.material++;
        assertThrows(IllegalArgumentException.class,()->new LocalAirGeometryCapture(B,B.cells(),s,baseline,Set.of(DOOR)));
        s.material--;s.context++;
        assertThrows(IllegalArgumentException.class,()->new LocalAirGeometryCapture(B,B.cells(),s,baseline,Set.of(DOOR)));
        s.context--;s.revision--;
        assertThrows(IllegalArgumentException.class,()->new LocalAirGeometryCapture(B,B.cells(),s,baseline,Set.of(DOOR)));
        s.revision++;assertThrows(IllegalArgumentException.class,()->new LocalAirGeometryCapture(new Bounds(1,0,0,5,3,3),B.cells(),s,baseline,Set.of(DOOR)));
    }
    @Test void unchangedEvidenceCanFinishWithoutWorldReadsAndOwnsItsInputEdits() {
        var s=new Input();var baseline=initial(s);s.revision++;
        var edits=new java.util.HashSet<Long>();var c=new LocalAirGeometryCapture(B,B.cells(),s,baseline,edits);edits.add(DOOR);
        assertEquals(0,c.advance(1,Long.MAX_VALUE));assertEquals(Status.CAPTURED,c.status());
        assertEquals(B.cells(),c.reusedCells());assertEquals(0,c.readCells());
        assertEquals(baseline.shape(2,1,1),c.snapshot().shape(2,1,1));
    }
}
