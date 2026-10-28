// Written by Claude.
// Updated by Claude: tested the settled-owner gate after a topology edit and during its capture.
// Updated by Claude: tested that a history stage which is planning keeps the historical path.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirEditClassifierTest {
    private static final LocalAirAutomatic.Settings SETTINGS=new LocalAirAutomatic.Settings(4,32768,0,4);
    private static final LocalAirRun.Weather WEATHER=new LocalAirRun.Weather(20,0,0);
    private static final Heat SOURCE=new Heat(12,65,Double.NaN,false);
    /** LocalAirAutomaticTest's enclosed pocket, with per-position overrides and own-position read footprints. */
    private static class Factory implements LocalAirAutomatic.Factory {
        final Map<Long,Cell> overrides=new HashMap<>();
        public LocalAirRun.Weather weather(Bounds b) { return WEATHER; }
        public LocalAirAutomatic.Capture capture(Bounds b) {
            var epoch=new LocalAirDependencies(b);
            return new LocalAirAutomatic.Capture(new World(epoch::get,epoch,overrides),epoch);
        }
    }
    /** Frozen copies keep their revision and a copy of the overrides, so history stages read the edit-time world. */
    private static final class World implements LocalAirCoverage.World {
        final java.util.function.LongSupplier revision;final LocalAirDependencies epoch;final Map<Long,Cell> overrides;long last;
        World(java.util.function.LongSupplier revision,LocalAirDependencies epoch,Map<Long,Cell> overrides) { this.revision=revision;this.epoch=epoch;this.overrides=overrides; }
        public long contextId() { return 1; }
        public long geometryRevision() { return revision.getAsLong(); }
        public long materialRevision() { return 1; }
        public int minBuildY() { return 0; }
        public int maxBuildY() { return 32; }
        public Cell sample(int x,int y,int z) {
            if(epoch!=null) epoch.read(x,y,z);last=blockKey(x,y,z);
            var override=overrides.get(last);if(override!=null) return override;
            boolean inside=Math.floorMod(x,16)>5 && Math.floorMod(x,16)<10 && y>5 && y<10 && Math.floorMod(z,16)>5 && Math.floorMod(z,16)<10;
            if(!inside) return LocalAirPreparationTest.SOLID;
            var air=LocalAirPreparationTest.AIR;
            return Math.floorMod(x,16)==7 && y==6 && Math.floorMod(z,16)==7?new Cell(air.shape(),1,1,1,1,SOURCE):air;
        }
        public Footprint lastReads() { return new Footprint(new long[]{last}); }
        public LocalAirCoverage.History freeze() {
            long frozen=revision.getAsLong();var copy=new World(()->frozen,null,new HashMap<>(overrides));
            return new LocalAirCoverage.History() {
                public LocalAirCoverage.World world() { return copy; }
                public void close() { }
            };
        }
    }
    private static LocalAirPlanner.Plan plan() {
        var r=new LocalAirDemandRegistry(0);r.advance(0);
        r.put(new LocalAirDemandRegistry.Request(new LocalAirDemandRegistry.Key(LocalAirDemandRegistry.Kind.PLAYER,"p"),new LocalAirDemandRegistry.Cell(8,8,8),true,true));
        return new LocalAirPlanner(new LocalAirPlanner.Settings(0,1,64,64)).update(r.snapshot(),0,32,(x,z)->true);
    }
    private static void drain(LocalAirAutomatic auto,long tick,int rounds) {
        for(int i=0;i<rounds;i++) {
            auto.observe(tick,1);
            for(var d:auto.domains()) {
                d.preparation.advance(256,Long.MAX_VALUE);
                var work=d.preparation.takeWork();if(work!=null) d.preparation.complete(work.run());
            }
        }
    }
    /** Mirrors HeatWorld's server-thread hook: only GEOMETRY invalidates the owner. */
    private static LocalAirPreparation.EditOutcome edit(LocalAirAutomatic.Domain d,int x,int y,int z,long tick) {
        var outcome=d.preparation.classifyEdit(x,y,z,tick,1);
        if(outcome==LocalAirPreparation.EditOutcome.GEOMETRY) d.capture.dependencies().blockRevision(x,y,z,tick,"before","after");
        return outcome;
    }
    /** HeatWorld's full hook: GEOMETRY bumps the revision and then hands the edit to changeGeometry. */
    private static LocalAirPreparation.EditOutcome hook(LocalAirAutomatic.Domain d,int x,int y,int z,long tick) {
        var outcome=d.preparation.classifyEdit(x,y,z,tick,1);
        if(outcome==LocalAirPreparation.EditOutcome.GEOMETRY)
            d.preparation.changeGeometry(x,y,z,d.capture.dependencies().blockRevision(x,y,z,tick,"before","after"),tick,1);
        return outcome;
    }
    /** One accepted AIR->SOLID topology edit at (8,7,8) on a published owner. */
    private static LocalAirAutomatic.Domain edited(LocalAirAutomatic auto,Factory f) {
        var d=published(auto);f.overrides.put(blockKey(8,7,8),LocalAirPreparationTest.SOLID);
        assertEquals(LocalAirPreparation.EditOutcome.GEOMETRY,hook(d,8,7,8,10));
        assertEquals(1,d.preparation.topologyStages());assertNull(d.preparation.field());return d;
    }
    private static LocalAirAutomatic.Domain published(LocalAirAutomatic auto) {
        auto.reconcile(plan(),0,32);drain(auto,0,80);drain(auto,10,80);
        var d=auto.committedDomains().getFirst();assertNotNull(d.preparation.field(),auto.rows().toString());return d;
    }

    @Test void unchangedInteriorAirIsIgnoredWithoutRestart() {
        var auto=new LocalAirAutomatic(SETTINGS,new Factory());var d=published(auto);
        assertEquals(LocalAirPreparation.EditOutcome.IGNORED,edit(d,8,7,8,10));
        for(long tick=11;tick<=50;tick++) drain(auto,tick,2);
        assertTrue(d.preparation.restartCauses().isEmpty(),d.preparation.restartCauses().toString());
        assertEquals(0,d.preparation.restarts());assertNotNull(d.preparation.field());
        assertEquals(Map.of("IGNORED",1L),d.preparation.editOutcomes());
        assertEquals(d.preparation.editOutcomes(),auto.rows().getFirst().editOutcomes());auto.close();
    }
    @Test void heatOnlyChangesTakeTheSourcePath() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var d=published(auto);
        var air=LocalAirPreparationTest.AIR;long source=blockKey(7,6,7),tick=10;
        for(var heat:Arrays.asList(null,SOURCE,null,SOURCE)) {
            f.overrides.put(source,new Cell(air.shape(),1,1,1,1,heat));
            assertEquals(LocalAirPreparation.EditOutcome.SOURCE,edit(d,7,6,7,tick));
            for(int i=0;i<10;i++) drain(auto,++tick,2);
            assertNotNull(d.preparation.field(),d.preparation.description());
        }
        assertEquals(0,d.preparation.restarts());assertTrue(d.preparation.restartCauses().isEmpty());
        assertEquals(Map.of("SOURCE",4L),d.preparation.editOutcomes());auto.close();
    }
    @Test void shapeChangesStillInvalidate() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var d=published(auto);
        f.overrides.put(blockKey(8,7,8),LocalAirPreparationTest.SOLID);
        assertEquals(LocalAirPreparation.EditOutcome.GEOMETRY,edit(d,8,7,8,10));
        assertNull(d.preparation.field());assertEquals(1,d.preparation.restarts());auto.close();
    }
    @Test void snapshotContextCoversOtherCellsReadsButNotOwnPosition() {
        var bounds=new Bounds(0,0,0,4,1,1);long[] last={0};
        var source=new Source() {
            public long contextId() { return 1; }
            public long geometryRevision() { return 0; }
            public long materialRevision() { return 0; }
            public Cell sample(int x,int y,int z) { last[0]=blockKey(x,y,z);return LocalAirPreparationTest.AIR; }
            public Footprint lastReads() {
                if(last[0]==blockKey(0,0,0)) return new Footprint(new long[]{last[0],blockKey(1,0,0)});
                return last[0]==blockKey(3,0,0)?null:new Footprint(new long[]{last[0]});
            }
        };
        var capture=new LocalAirGeometryCapture(bounds,4,source);capture.advance(4,Long.MAX_VALUE);
        var snapshot=capture.snapshot();
        assertTrue(snapshot.context(blockKey(1,0,0)),"A neighbour read by another cell's footprint is context");
        assertFalse(snapshot.context(blockKey(0,0,0)),"A cell's own read is not context");
        assertFalse(snapshot.context(blockKey(1,0,1)),"Unread positions are not context");
        assertTrue(snapshot.context(blockKey(2,0,0)) && snapshot.context(blockKey(4,0,0)) && snapshot.context(blockKey(3,1,0)),
                "A cell without read evidence makes its six face neighbours context");
        assertFalse(snapshot.context(blockKey(3,0,0)));
    }
    @Test void settledOwnerAfterATopologyEditIgnoresNoOpEdits() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var d=edited(auto,f);long tick=10;
        for(int i=0;i<200 && d.preparation.field()==null;i++) drain(auto,++tick,2);
        for(int i=0;i<5;i++) drain(auto,++tick,2);
        assertNotNull(d.preparation.field(),d.preparation.description());assertEquals(1,d.preparation.topologyStages());
        assertEquals(LocalAirPreparation.EditOutcome.IGNORED,hook(d,8,8,8,tick));
        for(int i=0;i<40;i++) { hook(d,8,8,8,++tick);drain(auto,tick,2); }
        assertEquals(1,d.preparation.topologyStages());assertEquals(0,d.preparation.restarts(),d.preparation.restartCauses().toString());
        assertEquals(Map.of("GEOMETRY",1L,"IGNORED",41L),d.preparation.editOutcomes());assertNotNull(d.preparation.field());auto.close();
    }
    @Test void secondStageStillCapturingKeepsTheHistoricalPath() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var d=edited(auto,f);long tick=10;
        assertNotEquals(LocalAirPreparation.EditOutcome.IGNORED,d.preparation.classifyEdit(8,8,8,tick,1),"Two stages: not settled");
        for(int i=0;i<400 && d.preparation.status()!=LocalAirPreparation.Status.CAPTURING;i++) {
            // A zero allowance still promotes the new stage, but its baseline-reused capture cannot finish.
            auto.observe(++tick,1);d.preparation.advance(0,Long.MAX_VALUE);
            var work=d.preparation.takeWork();if(work!=null) d.preparation.complete(work.run());
        }
        assertEquals(LocalAirPreparation.Status.CAPTURING,d.preparation.status(),"The promoted stage is capturing");
        assertEquals(LocalAirPreparation.EditOutcome.GEOMETRY,d.preparation.classifyEdit(8,8,8,tick,1),"An incoming seed keeps the historical path");
        auto.close();
    }
    @Test void historyStagePlanningKeepsTheHistoricalPath() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var d=edited(auto,f);long tick=10;
        for(int i=0;i<400 && d.preparation.status()!=LocalAirPreparation.Status.PLAN_QUEUED;i++) {
            auto.observe(++tick,1);d.preparation.advance(256,Long.MAX_VALUE);
            if(d.preparation.status()==LocalAirPreparation.Status.PLAN_QUEUED) break;
            var work=d.preparation.takeWork();if(work!=null) d.preparation.complete(work.run());
        }
        assertEquals(LocalAirPreparation.Status.PLAN_QUEUED,d.preparation.status(),d.preparation.description());
        assertFalse(d.preparation.runReason().startsWith("Replaying"),"The history stage is promoted and alone");
        // (8,8,8) is unchanged interior air: the snapshot rules would call it IGNORED.
        assertNotEquals(LocalAirPreparation.EditOutcome.IGNORED,d.preparation.classifyEdit(8,8,8,tick,1),"Queued history stage");
        var work=d.preparation.takeWork();assertNotNull(work);
        assertEquals(LocalAirPreparation.Status.PLANNING,d.preparation.status());
        assertNotEquals(LocalAirPreparation.EditOutcome.IGNORED,d.preparation.classifyEdit(8,8,8,tick,1),"Planning history stage");
        auto.close();
    }
}
