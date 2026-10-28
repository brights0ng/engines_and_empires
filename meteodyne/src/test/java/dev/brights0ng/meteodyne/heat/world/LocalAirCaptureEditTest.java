// Written by Claude.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** Edits while a capture is in progress re-read only the captured cells they can affect. */
class LocalAirCaptureEditTest {
    private static final Cell AIR=LocalAirPreparationTest.AIR,SOLID=LocalAirPreparationTest.SOLID;
    private static final Bounds B=new Bounds(0,0,0,4,1,1);
    /** Air row; cell 0 also reads the outside neighbour (-1,0,0), every other cell only itself. */
    private static class World implements Source {
        final Map<Long,Cell> cells=new HashMap<>();
        final Map<Long,Integer> reads=new HashMap<>();
        long last;
        public long contextId() { return 1; }
        public long geometryRevision() { return 0; }
        public long materialRevision() { return 0; }
        public Cell sample(int x,int y,int z) {
            last=blockKey(x,y,z);reads.merge(last,1,Integer::sum);return cells.getOrDefault(last,AIR);
        }
        public Footprint lastReads() {
            return last==blockKey(0,0,0)?new Footprint(new long[]{last,blockKey(-1,0,0)}):new Footprint(new long[]{last});
        }
        Cell set(int x,int y,int z,Cell cell) { cells.put(blockKey(x,y,z),cell);return sample(x,y,z); }
    }
    private static Snapshot finish(LocalAirGeometryCapture capture) {
        while(capture.status()==Status.CAPTURING) capture.advance(1,Long.MAX_VALUE);
        assertEquals(Status.CAPTURED,capture.status());return capture.snapshot();
    }

    @Test void editAtAnUncapturedCellIsLeftForTheCursor() {
        var w=new World();var capture=new LocalAirGeometryCapture(B,4,w);capture.advance(2,Long.MAX_VALUE);
        assertFalse(capture.edited(blockKey(3,0,0),w.set(3,0,0,SOLID)));
        assertEquals(2,capture.sampledCells());assertEquals(0,capture.rereadCells());
        var snapshot=finish(capture);assertEquals(SOLID.shape(),snapshot.shape(3,0,0));assertEquals(0,capture.rereadCells());
    }
    @Test void identicalSampleAtACapturedCellIsNoChange() {
        var w=new World();var capture=new LocalAirGeometryCapture(B,4,w);capture.advance(3,Long.MAX_VALUE);
        assertFalse(capture.edited(blockKey(2,0,0),w.sample(2,0,0)));
        finish(capture);assertEquals(0,capture.rereadCells());assertEquals(4,capture.readCells());
    }
    @Test void changedCapturedCellIsReReadBeforeCaptureCompletes() {
        var w=new World();var capture=new LocalAirGeometryCapture(B,4,w);capture.advance(4,Long.MAX_VALUE);
        assertEquals(4,capture.sampledCells());
        assertTrue(capture.edited(blockKey(2,0,0),w.set(2,0,0,SOLID)));
        assertEquals(Status.CAPTURING,capture.status(),"A queued re-read keeps the capture open");
        assertThrows(IllegalStateException.class,capture::snapshot);
        int before=w.reads.get(blockKey(2,0,0));
        assertEquals(1,capture.advance(4,Long.MAX_VALUE),"One re-read costs one unit");
        assertEquals(Status.CAPTURED,capture.status());assertEquals(before+1,w.reads.get(blockKey(2,0,0)));
        assertEquals(1,capture.rereadCells());assertEquals(SOLID.shape(),capture.snapshot().shape(2,0,0));
        assertEquals(1,w.reads.get(blockKey(1,0,0)),"Unaffected cells are not re-read");
    }
    @Test void cellReadingAnEditedNeighbourIsReRead() {
        var w=new World();var capture=new LocalAirGeometryCapture(B,4,w);capture.advance(2,Long.MAX_VALUE);
        assertTrue(capture.edited(blockKey(-1,0,0),w.set(-1,0,0,SOLID)),"Cell 0's footprint read the outside neighbour");
        finish(capture);
        assertEquals(1,capture.rereadCells());assertEquals(2,w.reads.get(blockKey(0,0,0)));assertEquals(1,w.reads.get(blockKey(1,0,0)));
        assertFalse(capture.edited(blockKey(-2,0,0),w.set(-2,0,0,SOLID)),"Positions no captured cell read are not edits");
    }
}
