// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirSkyCacheTest {
    private record Pos(int x,int y,int z) { }
    private static final Cell AIR=new Cell(new Shape(new int[0],new int[0]),1,1,1,1);
    private static final Cell SOLID=new Cell(new Shape(new int[]{0,0,0,16,16,16},new int[0]),1,1,1,1);
    private static class World implements LocalAirCoverage.World {
        final LocalAirSkyCache cache=new LocalAirSkyCache();
        final Map<Pos,Cell> cells=new HashMap<>();
        int min=-64,max=320,ground=-61,reads,checks;
        long material=1;
        Pos last,context;
        boolean loaded=true,footprints=true,bulk=true;
        Runnable duringRead=()->{};
        public long contextId() { return 1; }
        public long geometryRevision() { return 1; }
        public long materialRevision() { return material; }
        public int minBuildY() { return min; }
        public int maxBuildY() { return max; }
        public LocalAirSkyCache skyCache() { return cache; }
        public boolean emptySkySection(int x,int y,int z) {
            checks++;if(!loaded) throw new Unavailable(Status.MISSING_GEOMETRY,"unloaded fixture");
            return bulk && y*16>ground && cells.entrySet().stream().noneMatch(e->e.getKey().x>>4==x && Math.floorDiv(e.getKey().y,16)==y && e.getKey().z>>4==z && !e.getValue().shape().empty());
        }
        public Cell sample(int x,int y,int z) {
            reads++;last=new Pos(x,y,z);duringRead.run();
            if(!loaded) throw new Unavailable(Status.MISSING_GEOMETRY,"unloaded fixture");
            return cells.getOrDefault(last,y<=ground?SOLID:AIR);
        }
        public Footprint lastReads() {
            if(!footprints) return null;
            return new Footprint(context==null?new long[]{blockKey(last.x,last.y,last.z)}:
                    new long[]{blockKey(last.x,last.y,last.z),blockKey(context.x,context.y,context.z)});
        }
        void edit(Pos p,Cell cell) { cells.put(p,cell);cache.blockChanged(p.x,p.z); }
    }
    private static LocalAirSkyCache.Result finish(World w,int x,int low,int z) {
        for(int i=0;i<1024;i++) { var result=w.cache.advance(w,x,low,z);if(result.state()!=LocalAirSkyCache.State.CHECKING) return result; }
        throw new AssertionError("Sky index made no progress");
    }
    @Test void clearSuffixServesEveryHigherHeightAndRecordsItsFirstLowerObstruction() {
        var w=new World();var clear=finish(w,0,-60,0);assertEquals(LocalAirSkyCache.State.CLEAR,clear.state());
        assertTrue(w.reads<=16,"Tall empty sky uses section proofs");assertTrue(w.cache.report().bulkCells()>=352);
        int reads=w.reads,checks=w.checks;
        assertTrue(finish(w,0,100,0).hit());assertEquals(reads,w.reads);assertEquals(checks,w.checks);
        var blocked=finish(w,0,-64,0);assertEquals(LocalAirSkyCache.State.BLOCKED,blocked.state());assertEquals(-61,blocked.obstruction());
        reads=w.reads;assertTrue(finish(w,0,-62,0).hit());assertEquals(LocalAirSkyCache.State.CLEAR,finish(w,0,-60,0).state());assertEquals(reads,w.reads);
    }
    @Test void differentOwnersShareColumnsAndOtherColumnsShareEmptySectionClassification() {
        var w=new World();finish(w,0,-60,0);int checks=w.checks;
        var other=new World() {
            public LocalAirSkyCache skyCache() { return w.cache; }
            public boolean emptySkySection(int x,int y,int z) { return w.emptySkySection(x,y,z); }
            public Cell sample(int x,int y,int z) { return w.sample(x,y,z); }
            public Footprint lastReads() { return w.lastReads(); }
        };
        var hit=w.cache.advance(other,0,100,0);assertTrue(hit.hit());
        finish(w,1,-60,0);assertEquals(checks,w.checks,"Section palette is checked once for neighboring columns");
        assertEquals(2,w.cache.report().columns());
    }
    @Test void roofPlacementRemovalAndContextChunkChangesInvalidateEvidenceButUnrelatedChunksDoNot() {
        var w=new World();finish(w,0,-60,0);finish(w,40,-60,0);
        var roof=new Pos(0,200,0);w.edit(roof,SOLID);
        assertTrue(finish(w,40,-60,0).hit());var blocked=finish(w,0,-60,0);assertEquals(200,blocked.obstruction());
        w.edit(roof,AIR);assertEquals(LocalAirSkyCache.State.CLEAR,finish(w,0,-60,0).state());
        w.context=new Pos(32,-49,0);w.cache.chunkChanged(0,0);finish(w,0,-60,0);
        w.cache.chunkChanged(2,0);assertFalse(w.cache.advance(w,0,-60,0).hit(),"Shape context registers its neighboring chunk epoch");
    }
    @Test void materialBuildHeightUnloadAndWorldLifetimeCannotReuseOldProofs() {
        var w=new World();finish(w,0,-60,0);w.material++;
        assertFalse(w.cache.advance(w,0,-60,0).hit());finish(w,0,-60,0);
        w.max=336;assertFalse(w.cache.advance(w,0,-60,0).hit());finish(w,0,-60,0);
        w.cache.chunkChanged(0,0);w.loaded=false;assertThrows(Unavailable.class,()->finish(w,0,-60,0));
        w.loaded=true;w.cache.chunkChanged(0,0);assertEquals(LocalAirSkyCache.State.CLEAR,finish(w,0,-60,0).state());
        var other=new World();other.cells.put(new Pos(0,300,0),SOLID);assertEquals(300,finish(other,0,-60,0).obstruction());
    }
    @Test void returnedEvidenceIsRevalidatedAfterTheNewOwnerRegistersItsDependencies() {
        var w=new World();var clear=finish(w,0,-60,0);assertTrue(clear.current());w.cache.blockChanged(0,0);assertFalse(clear.current());
        var racing=new World() { public void retainReads(ReadBounds reads) { cache.blockChanged(reads.lowX(),reads.lowZ()); } };
        var b=new Bounds(0,-61,0,3,3,3);var capture=new LocalAirGeometryCapture(b,b.cells(),racing);capture.advance(b.cells(),Long.MAX_VALUE);
        var coverage=new LocalAirCoverage(LocalAirCoverage.plan(capture.snapshot()),racing,100_000);
        for(int i=0;i<40;i++) {coverage.advance(20,Long.MAX_VALUE);assertEquals(LocalAirCoverage.Status.CHECKING,coverage.status(),"An edit during dependency registration prevents stale proof publication");}
    }
    @Test void storageIsBoundedAndOffThreadInvalidationDoesNotRequireWorldReads() throws Exception {
        var w=new World();
        for(int x=0;x<=LocalAirSkyCache.MAX_COLUMNS;x++) assertEquals(LocalAirSkyCache.State.CLEAR,finish(w,x,319,0).state());
        assertEquals(LocalAirSkyCache.MAX_COLUMNS,w.cache.report().columns());assertFalse(finish(w,0,319,0).hit(),"Evicted column rebuilds from valid section summaries");
        var thread=new Thread(()->w.cache.blockChanged(0,0));thread.start();thread.join();
        assertFalse(w.cache.advance(w,0,319,0).hit());
    }
    @Test void partialSolidsFluidsAndThinOccupancyUseTheSameObstructionRuleAsScalarCoverage() {
        var slab=new Cell(new Shape(new int[]{0,0,0,16,8,16},new int[0]),1,1,1,1);
        var water=new Cell(new Shape(new int[0],new int[]{0,0,0,16,16,16}),1,1,1,1);
        var thin=new Cell(new Shape(new int[]{0,0,0,16,1,16},new int[0]),1,1,1,1);
        for(var cell:List.of(SOLID,slab,water,thin)) {
            var w=new World();w.cells.put(new Pos(0,17,0),cell);w.cells.put(new Pos(0,3,0),SOLID);
            var result=finish(w,0,-60,0);assertEquals(LocalAirSkyCache.State.BLOCKED,result.state());assertEquals(17,result.obstruction());
            assertEquals(LocalAirSkyCache.State.CLEAR,finish(w,0,18,0).state());
        }
    }
    @Test void missingFootprintsFallBackAndAnEditDuringSamplingCannotPublishAStaleSuffix() {
        var w=new World();w.footprints=false;assertEquals(LocalAirSkyCache.State.FALLBACK,finish(w,0,-60,0).state());assertEquals(0,w.cache.report().columns());
        var racing=new World();racing.duringRead=()->racing.cache.chunkChanged(0,0);
        for(int i=0;i<32;i++) assertNotEquals(LocalAirSkyCache.State.CLEAR,racing.cache.advance(racing,0,-60,0).state());
        racing.duringRead=()->{};assertEquals(LocalAirSkyCache.State.CLEAR,finish(racing,0,-60,0).state());
    }
    @Test void coverageConsumesBoundedIndexOperationsRetainsProofsAndHonorsTotalEvidenceLimit() {
        var w=new World();var b=new Bounds(0,-61,0,3,3,3);var capture=new LocalAirGeometryCapture(b,b.cells(),w);capture.advance(b.cells(),Long.MAX_VALUE);
        var plan=LocalAirCoverage.plan(capture.snapshot());var denied=new LocalAirCoverage(plan,w,1);assertEquals(LocalAirCoverage.Status.LIMIT_EXCEEDED,denied.status());assertEquals(0,w.checks);
        var coverage=new LocalAirCoverage(plan,w,100_000);assertEquals(0,coverage.advance(1,System.nanoTime()-1));
        int used=0;while(coverage.status()==LocalAirCoverage.Status.CHECKING) {int next=coverage.advance(3,Long.MAX_VALUE);assertTrue(next<=3);used+=next;}
        assertEquals(LocalAirCoverage.Status.ADMITTED,coverage.status(),coverage.reason());assertNotNull(coverage.certificate());assertTrue(used<400);assertTrue(coverage.bulkCells()>7000);
        int reads=w.reads,checks=w.checks;var reused=new LocalAirCoverage(plan,w,100_000);reused.advance(100,Long.MAX_VALUE);
        assertEquals(LocalAirCoverage.Status.ADMITTED,reused.status());assertEquals(reads,w.reads);assertEquals(checks,w.checks);assertTrue(reused.reusedCells()>7000);
    }
}
