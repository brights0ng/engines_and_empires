// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirSparseTest {
    private static Bounds bounds(boolean vertical) {
        var tiles=vertical?List.of(new LocalAirPlanner.Tile(0,0,0),new LocalAirPlanner.Tile(0,1,0),new LocalAirPlanner.Tile(1,1,0))
                :List.of(new LocalAirPlanner.Tile(0,0,0),new LocalAirPlanner.Tile(1,0,0),new LocalAirPlanner.Tile(0,0,1));
        return LocalAirAutomatic.bounds(new LocalAirPlanner.Group(tiles.getFirst(),tiles,List.of(),List.of(),LocalAirPlanner.State.PROPOSED_UNVERIFIED,12288),0,32);
    }
    private static class World implements LocalAirCoverage.World {
        final Bounds bounds;int reads;boolean cut;
        World(Bounds bounds) { this.bounds=bounds; }
        public long contextId() { return 1; }public long geometryRevision() { return 1; }public long materialRevision() { return 1; }
        public int minBuildY() { return 0; }public int maxBuildY() { return 32; }
        public Cell sample(int x,int y,int z) {
            reads++;
            if(cut && x==24 && z==8 && y==16) return LocalAirPreparationTest.AIR;
            if(cut && x==24 && z==15 && y==8) return LocalAirPreparationTest.AIR;
            return LocalAirPreparationTest.SOLID;
        }
    }
    private static void drain(LocalAirPreparation p) {
        for(int i=0;i<150;i++) { p.advance(256,Long.MAX_VALUE);var work=p.takeWork();if(work!=null) p.complete(work.run()); }
    }
    @Test void captureAndBakeAllocateOnlySelectedTilesAndKeepNegativeOrigins() {
        var b=bounds(false);var w=new World(b) {
            @Override public Cell sample(int x,int y,int z) { assertTrue(bounds.contains(x,y,z),"Geometry capture cannot sample an omitted cell");return super.sample(x,y,z); }
        };
        var p=new LocalAirPreparation(w,b,b.cells(),0,null,16);drain(p);assertEquals(LocalAirPreparation.Status.PREPARED,p.status());
        assertEquals(12288,w.reads);assertEquals(12288,p.published().flow().cells());assertEquals(12288,p.published().thermal().states());
        assertEquals(-1,p.published().flow().index(24,8,24));p.close();
        var negative=new Bounds(-32,-16,-32,b.nx(),b.ny(),b.nz(),b.sparse());assertTrue(negative.contains(-24,-8,-24));assertFalse(negative.contains(-8,-8,-8));
    }
    @Test void internalConcaveCutsRequireRealExteriorEvidenceAndUnsupportedBottomCutsReject() {
        var b=bounds(false);var w=new World(b);w.cut=true;var p=new LocalAirPreparation(w,b,b.cells(),10000,null,16);drain(p);
        assertEquals(LocalAirPreparation.Status.REJECTED,p.status());assertNull(p.published());p.close();
        b=bounds(true);w=new World(b);w.cut=true;p=new LocalAirPreparation(w,b,b.cells(),10000,null,16);drain(p);
        assertEquals(LocalAirPreparation.Status.REJECTED,p.status());assertTrue(p.reason().contains("bottom coverage cut"));assertTrue(p.reason().contains("24, 16, 8"));p.close();
    }
    @Test void omittedChunksDoNotInvalidateUnlessTheyWereReadAsContextOrExteriorEvidence() {
        var d=new LocalAirDependencies(bounds(false));assertFalse(d.affectsBlock(24,8,24));d.chunkChanged(1,1);assertEquals(0,d.get());
        d.blockChanged(24,8,24);assertEquals(0,d.get());d.read(24,8,24);assertTrue(d.affectsBlock(24,8,24));d.chunkChanged(1,1);assertEquals(1,d.get());
    }
}
