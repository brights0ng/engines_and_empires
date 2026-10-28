// Updated by ChatGPT: tested saved removed coverage, re-entry and owner retirement.
// Written by ChatGPT.
// Updated by ChatGPT: tested paired view frames, chunk loss and saved-warmth presentation.
// Updated by ChatGPT: verified rejected-cut recovery and retained donor behavior under limits.
// Updated by Claude: tested restart-cause and weather grid-rebuild counters.
// Updated by Claude: tested continuous no-op and real edits while a fresh owner captures.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirAutomaticTest {
    private static final LocalAirAutomatic.Settings SETTINGS=new LocalAirAutomatic.Settings(4,32768,0,4);
    private static final LocalAirRun.Weather WEATHER=new LocalAirRun.Weather(20,0,0);
    private static class Factory implements LocalAirAutomatic.Factory {
        int created,reads;boolean openBottom,corruptTarget,loaded=true,ownReads;
        final Map<Long,Cell> overrides=new HashMap<>();
        public LocalAirRun.Weather weather(Bounds b) { return WEATHER; }
        public LocalAirAutomatic.Capture capture(Bounds b) {
            created++;var epoch=new LocalAirDependencies(b);long id=created;long[] last={0};
            var world=new LocalAirCoverage.World() {
                public long contextId() { return id; }
                public long geometryRevision() { return epoch.get(); }
                public long materialRevision() { return 1; }
                public int minBuildY() { return 0; }
                public int maxBuildY() { return 32; }
                public Cell sample(int x,int y,int z) {
                    if(!loaded) throw new Unavailable(LocalAirGeometryCapture.Status.MISSING_GEOMETRY,"Chunk unavailable");
                    reads++;epoch.read(x,y,z);last[0]=blockKey(x,y,z);
                    var override=overrides.get(last[0]);if(override!=null) return override;
                    if(corruptTarget && b.x()==32 && x==39 && y==6 && z==7) return LocalAirPreparationTest.SOLID;
                    boolean inside=Math.floorMod(x,16)>5 && Math.floorMod(x,16)<10 && y>5 && y<10 && Math.floorMod(z,16)>5 && Math.floorMod(z,16)<10;
                    if(openBottom && y==0) return LocalAirPreparationTest.AIR;
                    if(!inside) return LocalAirPreparationTest.SOLID;
                    var air=LocalAirPreparationTest.AIR;
                    return Math.floorMod(x,16)==7 && y==6 && Math.floorMod(z,16)==7?new Cell(air.shape(),1,1,1,1,new Heat(12,65,Double.NaN,false)):air;
                }
                public Footprint lastReads() { return ownReads?new Footprint(new long[]{last[0]}):null; }
            };
            return new LocalAirAutomatic.Capture(world,epoch);
        }
    }
    private static LocalAirDemandRegistry registry() { var r=new LocalAirDemandRegistry(0);r.advance(0);return r; }
    private static void request(LocalAirDemandRegistry r,String id,int x) {
        r.put(new LocalAirDemandRegistry.Request(new LocalAirDemandRegistry.Key(LocalAirDemandRegistry.Kind.PLAYER,id),new LocalAirDemandRegistry.Cell(x,8,8),true,true));
    }
    private static LocalAirPlanner.Plan plan(LocalAirDemandRegistry r) {
        return new LocalAirPlanner(new LocalAirPlanner.Settings(0,1,64,64)).update(r.snapshot(),0,32,(x,z)->true);
    }
    private static void drain(LocalAirAutomatic auto,long tick) {
        for(int i=0;i<80;i++) {
            auto.observe(tick,1);
            for(var d:auto.domains()) {
                assertTrue(d.preparation.advance(256,Long.MAX_VALUE)<=256);
                var work=d.preparation.takeWork();if(work!=null) d.preparation.complete(work.run());
            }
        }
    }
    private static class RecoveryFactory extends Factory {
        boolean chunks=true;
        int slit=15,reach=23;
        boolean split;
        boolean unsupportedTarget;
        public boolean loaded(LocalAirPlanner.Tile tile) { return chunks; }
        public LocalAirAutomatic.Capture capture(Bounds b) {
            created++;long id=created;var epoch=new LocalAirDependencies(b);
            return new LocalAirAutomatic.Capture(new LocalAirCoverage.World() {
                public long contextId() { return id; }
                public long geometryRevision() { return epoch.get(); }
                public long materialRevision() { return 1; }
                public int minBuildY() { return 0; }
                public int maxBuildY() { return 32; }
                public Cell sample(int x,int y,int z) {
                    reads++;epoch.read(x,y,z);
                    if(unsupportedTarget && b.nx()>16 && x==20) throw new Unavailable(LocalAirGeometryCapture.Status.UNSUPPORTED_GEOMETRY,"fixture unsupported geometry");
                    if(y==7 && z==8 && x>=7 && x<=reach && (!split || x!=15)) {
                        if(x==slit) return new Cell(new Shape(new int[]{0,0,0,16,8,16},new int[0]),1,1,1,1);
                        return LocalAirPreparationTest.AIR;
                    }
                    return LocalAirPreparationTest.SOLID;
                }
            },epoch);
        }
    }
    /** One tick per round with HeatWorld's hook order: the block write, classification, then the tick's
     * capture slice. Only GEOMETRY bumps the dependency revision. Returns the outcomes seen while capturing. */
    private static Map<String,Long> editEachTick(LocalAirAutomatic auto,Factory f,java.util.function.LongPredicate flip,int x,int y,int z) {
        var d=auto.committedDomains().getFirst();var captureOutcomes=new TreeMap<String,Long>();long key=blockKey(x,y,z);
        for(long tick=0;tick<400 && d.preparation.field()==null;tick++) {
            boolean capturing=d.preparation.status()==LocalAirPreparation.Status.CAPTURING;
            if(capturing && flip.test(tick) && f.overrides.remove(key)==null) f.overrides.put(key,LocalAirPreparationTest.SOLID);
            var outcome=d.preparation.classifyEdit(x,y,z,tick,1,false);
            if(outcome==LocalAirPreparation.EditOutcome.GEOMETRY) d.capture.dependencies().blockRevision(x,y,z,tick,"before","after",LocalAirDependencies.APPLY_AUTOMATIC);
            if(capturing) captureOutcomes.merge(outcome.name(),1L,Long::sum);
            auto.observe(tick,1);
            assertTrue(d.preparation.advance(256,Long.MAX_VALUE)<=256);
            var work=d.preparation.takeWork();if(work!=null) d.preparation.complete(work.run());
        }
        assertSame(d,auto.committedDomains().getFirst());assertNotNull(d.preparation.field(),auto.rows().toString());
        return captureOutcomes;
    }
    @Test void continuousNoopEditsDuringCapturePublish() {
        var f=new Factory();f.ownReads=true;var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);
        var outcomes=editEachTick(auto,f,tick->false,8,7,8);
        assertTrue(outcomes.getOrDefault("IGNORED",0L)>=10,"Edits landed throughout the capture: "+outcomes);
        var row=auto.rows().getFirst();var d=auto.committedDomains().getFirst();
        assertEquals(0,row.restarts(),row.restartCauses().toString());assertEquals(0,row.rereadCells());
        assertFalse(d.preparation.editOutcomes().containsKey("GEOMETRY"),d.preparation.editOutcomes().toString());auto.close();
    }
    @Test void continuousRealEditsDuringCapturePublish() {
        var f=new Factory();f.ownReads=true;var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);
        var outcomes=editEachTick(auto,f,tick->tick%5==0,8,7,8);
        assertTrue(outcomes.getOrDefault("RECAPTURE",0L)>=1,"A captured cell flipped before capture completed: "+outcomes);
        var row=auto.rows().getFirst();
        assertEquals(0,row.restarts(),row.restartCauses().toString());assertTrue(row.rereadCells()>0);
        assertEquals(0,row.topologyStages());auto.close();
    }
    @Test void rejectedInitialOwnerExpandsAndPublishesInsteadOfWaitingForAnImpossibleSeed() {
        var f=new RecoveryFactory();var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);
        auto.reconcile(plan(r),0,32);drain(auto,0);
        var rejected=auto.committedDomains().getFirst();assertEquals(LocalAirPreparation.Status.REJECTED,rejected.preparation.status());
        assertTrue(auto.presentation(0,8.5,7.5,8.5,40).detail().contains("maxX"));
        auto.reconcile(plan(r),0,32);assertEquals(LocalAirPreparation.Status.CLOSED,rejected.preparation.status());
        assertEquals(8192,auto.cells());assertEquals(2,f.created);assertEquals(0,auto.transfers());
        drain(auto,0);assertTrue(auto.query(0,8.5,7.5,8.5,40).currentSample().isPresent(),auto.rows().toString());
        auto.reconcile(plan(r),0,32);assertEquals(2,f.created);assertEquals(8192,auto.cells());auto.close();
    }
    @Test void unloadedExpansionReportsTheBlockAndWaitsWithoutRepeatedAllocationThenRecovers() {
        var f=new RecoveryFactory();f.chunks=false;var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);
        auto.reconcile(plan(r),0,32);drain(auto,0);
        for(int i=0;i<4;i++) auto.reconcile(plan(r),0,32);
        assertEquals(1,f.created);assertEquals(4096,auto.cells());
        var missing=auto.presentation(0,8.5,7.5,8.5,40);assertTrue(missing.reading().currentSample().isEmpty());assertTrue(missing.detail().contains("not loaded"),missing.detail());
        assertTrue(auto.decisions().stream().anyMatch(d->d.state().equals("COVERAGE_BLOCKED")));
        f.chunks=true;auto.reconcile(plan(r),0,32);drain(auto,0);assertTrue(auto.query(0,8.5,7.5,8.5,40).currentSample().isPresent());auto.close();
    }
    @Test void rejectedReplacementExpandsAndTransfersFromTheHealthyDonor() {
        var f=new RecoveryFactory();f.slit=31;f.reach=39;f.split=true;
        var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);drain(auto,0);
        var donor=auto.committedDomains().getFirst();assertNotNull(donor.preparation.field());
        request(r,"q",24);auto.reconcile(plan(r),0,32);drain(auto,10);
        assertTrue(auto.transferState().startsWith("CANCELLED"));assertSame(donor,auto.committedDomains().getFirst());assertNotNull(donor.preparation.field());
        auto.reconcile(plan(r),0,32);assertEquals(16384,auto.cells());assertSame(donor,auto.committedDomains().getFirst());
        drain(auto,10);assertEquals(1,auto.transfers());assertEquals(12288,auto.cells());assertEquals(48,auto.committedDomains().getFirst().bounds.nx());
        assertTrue(auto.query(10,8.5,7.5,8.5,40).currentSample().isPresent());assertEquals(0,auto.lastTransfer().error(),1e-6);auto.close();
    }
    @Test void recoveryRespectsRuntimeHeadroomAndLeavesHealthyDonorReadable() {
        var f=new RecoveryFactory();f.slit=31;f.reach=39;f.split=true;
        var auto=new LocalAirAutomatic(new LocalAirAutomatic.Settings(2,12288,0,4),f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);drain(auto,0);
        var donor=auto.committedDomains().getFirst();request(r,"q",24);auto.reconcile(plan(r),0,32);drain(auto,10);
        for(int i=0;i<4;i++) auto.reconcile(plan(r),0,32);
        assertEquals(2,f.created);assertEquals(4096,auto.cells());assertSame(donor,auto.committedDomains().getFirst());
        assertTrue(auto.query(10,8.5,7.5,8.5,40).currentSample().isPresent());
        var missing=auto.presentation(10,23.5,7.5,8.5,40);assertTrue(missing.reading().currentSample().isEmpty());assertTrue(missing.detail().contains("headroom"),missing.detail());
        assertTrue(auto.decisions().stream().anyMatch(d->d.state().equals("TRANSFER_HEADROOM")));auto.close();
    }
    @Test void nonExpandableRejectedReplacementDoesNotAllocateForeverAndWorldEditAllowsRetry() {
        var f=new RecoveryFactory();f.slit=31;f.reach=39;f.split=true;f.unsupportedTarget=true;
        var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);drain(auto,0);
        request(r,"q",24);auto.reconcile(plan(r),0,32);drain(auto,10);
        for(int i=0;i<4;i++) auto.reconcile(plan(r),0,32);
        assertEquals(2,f.created);assertTrue(auto.query(10,8.5,7.5,8.5,40).currentSample().isPresent());
        assertTrue(auto.presentation(10,23.5,7.5,8.5,40).detail().contains("fixture unsupported geometry"));
        f.unsupportedTarget=false;auto.beforeEdit(20,7,8);auto.reconcile(plan(r),0,32);drain(auto,20);
        auto.reconcile(plan(r),0,32);drain(auto,20);assertTrue(auto.query(20,23.5,7.5,8.5,40).currentSample().isPresent(),auto.rows().toString());auto.close();
    }
    @Test void committedPresentationFollowsOwnersHidesTargetsAndPairsTheReadingWithOneGuardedFrame() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"a",8);request(r,"b",72);
        auto.reconcile(plan(r),0,32);drain(auto,0);drain(auto,10);
        var a=auto.presentation(10,7.5,6.5,7.5,40);var b=auto.presentation(10,71.5,6.5,7.5,40);
        assertNotEquals(a.domainId(),b.domainId());assertTrue(a.retainHistory());assertNotNull(a.frame());
        assertEquals(a.reading().currentSample().orElseThrow().temperature(),LocalAirDiagnostics.sample(a.frame(),10,7.5,6.5,7.5).temperature());
        var gap=auto.presentation(10,40.5,8.5,8.5,40);assertNull(gap.frame());assertFalse(gap.retainHistory());assertTrue(gap.reading().sample().isEmpty());
        request(r,"grow",24);auto.reconcile(plan(r),0,32);
        var pending=auto.presentation(10,23.5,6.5,7.5,40);assertEquals(LocalAirReading.Status.PENDING,pending.reading().status());assertNull(pending.frame());assertFalse(pending.retainHistory());
        assertEquals(a.domainId(),auto.presentation(10,7.5,6.5,7.5,40).domainId());
        drain(auto,20);var grown=auto.presentation(20,7.5,6.5,7.5,40);assertNotEquals(a.domainId(),grown.domainId());assertTrue(grown.reading().currentSample().isPresent());
        var stale=auto.presentation(100,grown.frame().bounds.x()+7.5,6.5,7.5,0);
        assertEquals(LocalAirReading.Status.STALE,stale.reading().status());assertTrue(stale.retainHistory());assertTrue(LocalAirViewModel.samples(stale.frame(),100,0,1,true).isEmpty());
        var domain=auto.committedDomains().stream().filter(d->d.bounds.nx()==32).findFirst().orElseThrow();domain.capture.dependencies().getAndIncrement();
        var invalid=auto.presentation(20,7.5,6.5,7.5,40);assertNull(invalid.frame());assertEquals(LocalAirReading.Status.PENDING,invalid.reading().status());
        auto.close();assertNull(auto.presentation(20,7.5,6.5,7.5,40).frame());
    }
    @Test void presentationWithdrawsOnChunkLossAndRestoresSavedWarmthAfterReloadAndNewRuntime() {
        var saved=new HashMap<Long,LocalAirThermalState.Saved>();
        var f=new Factory() {
            public LocalAirThermalState.Provider thermal() { return snapshot->cell->{var b=snapshot.bounds;var old=saved.get(blockKey(b.x()+b.cellX(cell),b.y()+b.cellY(cell),b.z()+b.cellZ(cell)));return new LocalAirThermalState.Value(old,0,old!=null,"SAVED_OR_AMBIENT");}; }
            public void save(LocalAirRun.Seed seed) {var b=seed.snapshot().bounds;for(int c=0;c<b.cells();c++) saved.put(blockKey(b.x()+b.cellX(c),b.y()+b.cellY(c),b.z()+b.cellZ(c)),LocalAirThermalState.save(seed,c));}
        };
        var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);drain(auto,0);drain(auto,10);
        var domain=auto.committedDomains().getFirst();f.save(domain.preparation.thermalCheckpoint());
        assertTrue(auto.presentation(10,7.5,6.5,7.5,0).reading().currentSample().orElseThrow().temperature()>20);
        f.loaded=false;domain.capture.dependencies().chunkChanged(0,0);
        assertNull(auto.presentation(10,7.5,6.5,7.5,40).frame());drain(auto,20);
        assertEquals(LocalAirReading.Reason.REJECTED,auto.presentation(20,7.5,6.5,7.5,40).reading().reason());
        f.loaded=true;domain.capture.dependencies().chunkChanged(0,0);drain(auto,20);
        var restored=auto.presentation(20,7.5,6.5,7.5,0);assertTrue(restored.reading().currentSample().orElseThrow().temperature()>20);assertTrue(restored.frame().restoredCells>0);assertEquals(0,restored.frame().sourceEnergy);
        auto.close();assertTrue(auto.presentation(20,7.5,6.5,7.5,40).reading().sample().isEmpty());
        auto=new LocalAirAutomatic(SETTINGS,f);auto.reconcile(plan(r),0,32);drain(auto,20);
        var reentered=auto.presentation(20,7.5,6.5,7.5,0);assertNotEquals(restored.domainId(),reentered.domainId());assertTrue(reentered.reading().currentSample().orElseThrow().temperature()>20);assertEquals(0,reentered.frame().sourceEnergy);auto.close();
    }
    @Test void identicalLayoutsReuseOneOwnerAndARealTiledBakePublishesCoupledHeat() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);
        auto.reconcile(plan(r),0,32);var owner=auto.domains().getFirst();auto.reconcile(plan(r),0,32);
        assertEquals(1,f.created);assertSame(owner,auto.domains().getFirst());
        assertEquals(0,owner.preparation.advance(256,System.nanoTime()-1));
        drain(auto,0);assertNotNull(owner.preparation.field());drain(auto,10);
        var reading=auto.query(10,7.5,6.5,7.5,0);assertEquals(LocalAirReading.Status.CURRENT,reading.status());
        assertTrue(reading.currentSample().orElseThrow().temperature()>20);assertTrue(owner.preparation.field().sourceEnergy>0);
        auto.close();assertTrue(auto.domains().isEmpty());
    }
    @Test void layoutChangeRetainsDonorsUntilTheReplacementIsReadyAndCanBeCancelled() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);
        var old=auto.domains().getFirst();drain(auto,0);var field=old.preparation.field();
        request(r,"q",24);auto.reconcile(plan(r),0,32);assertEquals(1,auto.committedDomains().size());assertEquals(2,auto.domains().size());
        assertEquals("TRANSFERRING",auto.decisions().getFirst().state());assertSame(field,old.preparation.field());
        assertEquals(LocalAirReading.Status.PENDING,auto.query(0,23.5,6.5,7.5,40).status());
        auto.beforeEdit(7,6,7);assertTrue(auto.transferState().startsWith("CANCELLED"));assertEquals(1,auto.domains().size());
        r.remove(new LocalAirDemandRegistry.Key(LocalAirDemandRegistry.Kind.PLAYER,"p"));auto.reconcile(plan(r),0,32);
        assertEquals("TRANSFERRING",auto.decisions().getFirst().state(),"Adjacent owners must transfer rather than allocate an independent exterior seam");
        r.clear();request(r,"far",72);auto.reconcile(plan(r),0,32);
        assertEquals(2,auto.domains().size());assertTrue(auto.rows().stream().anyMatch(LocalAirAutomatic.Row::retained));
        auto.close();
    }
    @Test void growMergeSplitAndShrinkCommitAtomicallyAtACommonTickWithoutDuplicatingSourceHistory() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();
        request(r,"a",8);request(r,"c",40);auto.reconcile(plan(r),0,32);drain(auto,0);drain(auto,10);
        assertEquals(2,auto.committedDomains().size());double before=auto.totalSourceEnergy();assertTrue(before>0);
        var old=auto.committedDomains();request(r,"b",24);auto.reconcile(plan(r),0,32);assertEquals(3,auto.domains().size());
        drain(auto,20);assertEquals("COMMITTED",auto.transferState());assertEquals(1,auto.committedDomains().size());assertEquals(1,auto.transfers());
        assertEquals(before+12,auto.totalSourceEnergy(),1e-7,"Two donors each supply 12 power for the next half second, exactly once");
        for(var d:old) assertEquals(LocalAirPreparation.Status.CLOSED,d.preparation.status());
        assertEquals(0,auto.lastTransfer().outgoingEnergy(),1e-7);assertEquals(0,auto.lastTransfer().error(),1e-7);
        var merged=auto.committedDomains().getFirst();assertEquals(48,merged.bounds.nx());
        double retained=merged.preparation.field().energy;assertTrue(retained>0);
        r.remove(new LocalAirDemandRegistry.Key(LocalAirDemandRegistry.Kind.PLAYER,"b"));auto.reconcile(plan(r),0,32);
        assertEquals(1,auto.committedDomains().size());drain(auto,30);
        assertEquals(2,auto.committedDomains().size());assertEquals(2,auto.transfers());assertEquals(2,auto.lastTransfer().targets());assertTrue(auto.lastTransfer().outgoingEnergy()>0);
        for(var d:auto.committedDomains()) {
            var frame=d.preparation.field();assertNotNull(frame);assertTrue(frame.temperature(frame.flow.nodeAt(7.5,6.5,7.5))>20,"Overlapping air remains warm after split");
        }
        r.clear();request(r,"grow",8);request(r,"grow2",24);auto.reconcile(plan(r),0,32);drain(auto,40);
        assertTrue(auto.transfers()>=3);auto.close();
    }
    @Test void transferHeadroomAndStaleTargetsNeverRetireTheCurrentOwner() {
        var f=new Factory();var r=registry();request(r,"p",8);
        var limited=new LocalAirAutomatic(new LocalAirAutomatic.Settings(2,8192,0,4),f);limited.reconcile(plan(r),0,32);drain(limited,0);
        request(r,"q",24);limited.reconcile(plan(r),0,32);assertEquals("TRANSFER_HEADROOM",limited.decisions().getFirst().state());assertEquals(1,limited.domains().size());limited.close();
        r.remove(new LocalAirDemandRegistry.Key(LocalAirDemandRegistry.Kind.PLAYER,"q"));var auto=new LocalAirAutomatic(SETTINGS,new Factory());auto.reconcile(plan(r),0,32);drain(auto,0);
        var original=auto.committedDomains().getFirst();request(r,"q",24);auto.reconcile(plan(r),0,32);var target=auto.domains().getLast();
        target.preparation.advance(8192,Long.MAX_VALUE);var work=target.preparation.takeWork();assertNotNull(work);
        target.capture.dependencies().getAndIncrement();auto.observe(1,1);assertTrue(auto.transferState().startsWith("CANCELLED"));
        target.preparation.complete(work.run());auto.observe(2,1);assertEquals(List.of(original),auto.committedDomains());assertEquals(1,auto.domains().size());assertEquals(0,auto.transfers());auto.close();
    }
    @Test void oneFailedSplitTargetCancelsTheWholeBatchAndDonorResumesWithoutSkippedTime() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"a",8);request(r,"b",24);request(r,"c",40);
        auto.reconcile(plan(r),0,32);drain(auto,0);drain(auto,10);var original=auto.committedDomains().getFirst();
        f.corruptTarget=true;r.remove(new LocalAirDemandRegistry.Key(LocalAirDemandRegistry.Kind.PLAYER,"b"));auto.reconcile(plan(r),0,32);
        var targets=auto.domains().stream().filter(d->d!=original).toList();assertEquals(2,targets.size());
        drain(auto,20);assertTrue(auto.transferState().startsWith("CANCELLED"));assertEquals(List.of(original),auto.committedDomains());assertEquals(0,auto.transfers());
        for(var d:targets) assertEquals(LocalAirPreparation.Status.CLOSED,d.preparation.status());
        drain(auto,30);assertEquals(54,original.preparation.field().sourceEnergy,1e-6,"Three source histories advance once through the cancelled barrier");
        assertTrue(original.preparation.field().temperature(original.preparation.field().flow.nodeAt(7.5,6.5,7.5))>20);auto.close();
    }
    @Test void totalLimitsIncludeRetainedOwnersAndIrregularGroupsExcludeTheirOmittedTiles() {
        var f=new Factory();var auto=new LocalAirAutomatic(new LocalAirAutomatic.Settings(1,4096,0,1),f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);
        r.clear();request(r,"far",72);auto.reconcile(plan(r),0,32);assertEquals("RUNTIME_LIMIT",auto.decisions().getFirst().state());assertEquals(4096,auto.cells());
        auto.close();request(r,"left",56);request(r,"up",56);
        var tiles=List.of(new LocalAirPlanner.Tile(0,0,0),new LocalAirPlanner.Tile(1,0,0),new LocalAirPlanner.Tile(0,1,0));
        var group=new LocalAirPlanner.Group(tiles.getFirst(),tiles,List.of(),List.of(),LocalAirPlanner.State.PROPOSED_UNVERIFIED,12288);
        var sparse=LocalAirAutomatic.bounds(group,0,32);assertNotNull(sparse.sparse());assertEquals(12288,sparse.cells());assertEquals(-1,sparse.index(24,24,8));
        var single=new LocalAirPlanner.Group(tiles.getFirst(),List.of(tiles.getFirst()),List.of(),List.of(),LocalAirPlanner.State.PROPOSED_UNVERIFIED,4096);
        assertThrows(IllegalArgumentException.class,()->LocalAirAutomatic.bounds(single,1,32));
    }
    @Test void sparseGrowthAndDenseToSparseShrinkRetainWarmCellsWithoutAllocatingTheHole() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"a",8);request(r,"b",24);
        var key=new LocalAirDemandRegistry.Key(LocalAirDemandRegistry.Kind.PLAYER,"c");
        r.put(new LocalAirDemandRegistry.Request(key,new LocalAirDemandRegistry.Cell(8,8,24),true,true));
        auto.reconcile(plan(r),0,32);assertEquals(12288,auto.cells());var original=auto.committedDomains().getFirst();assertNotNull(original.bounds.sparse());
        drain(auto,0);drain(auto,10);assertEquals(LocalAirReading.Reason.OUTSIDE_DOMAIN,auto.query(10,24.5,8.5,24.5,40).reason());
        var d=new LocalAirDemandRegistry.Key(LocalAirDemandRegistry.Kind.PLAYER,"d");r.put(new LocalAirDemandRegistry.Request(d,new LocalAirDemandRegistry.Cell(24,8,24),true,true));
        auto.reconcile(plan(r),0,32);assertEquals(28672,auto.cells());drain(auto,20);
        assertEquals("COMMITTED",auto.transferState());assertNull(auto.committedDomains().getFirst().bounds.sparse());
        r.remove(d);auto.reconcile(plan(r),0,32);drain(auto,30);var target=auto.committedDomains().getFirst();
        assertEquals(2,auto.transfers());assertEquals(12288,auto.cells());assertNotNull(target.bounds.sparse());
        assertTrue(auto.query(30,7.5,6.5,7.5,0).currentSample().orElseThrow().temperature()>20);assertEquals(0,auto.lastTransfer().error(),1e-6);auto.close();
    }
    @Test void missingPlannerGroupsWithdrawCurrentReadingsAndGeometryEditsRejectOldWorkerResults() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);
        var d=auto.domains().getFirst();d.preparation.advance(4096,Long.MAX_VALUE);var work=d.preparation.takeWork();assertNotNull(work);
        var result=work.run();d.capture.dependencies().getAndIncrement();d.preparation.complete(result);
        assertNull(d.preparation.field());assertEquals(LocalAirPreparation.Status.CAPTURING,d.preparation.status());
        var missing=new LocalAirPlanner(new LocalAirPlanner.Settings(0,1,64,64)).update(r.snapshot(),0,32,(x,z)->false);
        auto.reconcile(missing,0,32);assertEquals("MISSING_CHUNKS",auto.decisions().getFirst().state());
        assertFalse(auto.domains().getFirst().wanted);auto.close();
    }
    @Test void explicitInvalidationCountsGeometryRestartCause() {
        var f=new Factory();var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);
        var d=auto.domains().getFirst();d.capture.dependencies().getAndIncrement();d.preparation.status();
        assertEquals(1L,d.preparation.restartCauses().get("GEOMETRY:EXPLICIT"));
        assertEquals(d.preparation.restartCauses(),auto.rows().getFirst().restartCauses());auto.close();
    }
    @Test void windSignChangeCountsOneGridRebuild() {
        var f=new Factory() {
            boolean windy;
            @Override public LocalAirRun.Weather weather(Bounds b) { return windy?new LocalAirRun.Weather(20,2,0):WEATHER; }
        };
        var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);drain(auto,0);
        var prep=auto.domains().getFirst().preparation;assertNotNull(prep.field(),auto.rows().toString());
        f.windy=true;
        for(long tick=1;tick<=40;tick++) drain(auto,tick);
        assertNotNull(prep.field(),auto.rows().toString());
        assertEquals(1,prep.field().gridRebuilds);assertEquals(1,prep.weatherRevisions());auto.close();
    }
    @Test void realCoverageRejectionDoesNotPublishAndStoppingDiscardsInFlightCompletion() {
        var f=new Factory();f.openBottom=true;var auto=new LocalAirAutomatic(SETTINGS,f);var r=registry();request(r,"p",8);auto.reconcile(plan(r),0,32);drain(auto,0);
        assertEquals(LocalAirPreparation.Status.REJECTED,auto.domains().getFirst().preparation.status());
        assertEquals(LocalAirReading.Reason.REJECTED,auto.query(0,8.5,8.5,8.5,40).reason());auto.close();
        f.openBottom=false;auto.reconcile(plan(r),0,32);var prep=auto.domains().getFirst().preparation;prep.advance(4096,Long.MAX_VALUE);var job=prep.takeWork();auto.close();
        prep.complete(job.run());assertEquals(LocalAirPreparation.Status.CLOSED,prep.status());assertNull(prep.field());
    }
    @Test void removedCoverageIsSavedAndReentryRestoresItWithoutOverwritingLiveOverlap() {
        var saved=new HashMap<Long,LocalAirThermalState.Saved>();
        var f=new Factory() {
            @Override public LocalAirThermalState.Provider thermal() { return snapshot->cell->{
                var b=snapshot.bounds;var old=saved.get(blockKey(b.x()+b.cellX(cell),b.y()+b.cellY(cell),b.z()+b.cellZ(cell)));
                return new LocalAirThermalState.Value(old,0,old!=null,"SAVED_OR_AMBIENT");
            }; }
            @Override public void save(LocalAirRun.Seed seed) {
                if(!seed.frame().persistentThermal) return;var b=seed.snapshot().bounds;
                for(int c=0;c<b.cells();c++) saved.put(blockKey(b.x()+b.cellX(c),b.y()+b.cellY(c),b.z()+b.cellZ(c)),LocalAirThermalState.save(seed,c));
            }
        };
        var r=registry();request(r,"a",8);request(r,"b",24);var auto=new LocalAirAutomatic(SETTINGS,f);auto.reconcile(plan(r),0,32);drain(auto,0);drain(auto,10);
        var original=auto.committedDomains().getFirst();assertNotNull(original.preparation.field());assertTrue(original.preparation.field().persistentThermal);
        r.remove(new LocalAirDemandRegistry.Key(LocalAirDemandRegistry.Kind.PLAYER,"b"));auto.reconcile(plan(r),0,32);drain(auto,20);
        assertEquals(1,auto.transfers());assertEquals(16,auto.committedDomains().getFirst().bounds.nx());
        var removed=saved.get(blockKey(23,6,7));assertNotNull(removed);assertTrue(removed.offsets()[0]>0);
        request(r,"b",24);auto.reconcile(plan(r),0,32);drain(auto,30);
        assertEquals(2,auto.transfers());var expanded=auto.committedDomains().getFirst();var frame=expanded.preparation.field();assertNotNull(frame);
        assertTrue(frame.temperature(frame.flow.nodeAt(23.5,6.5,7.5))>20,"Reentered coverage uses saved warmth rather than ambient");
        assertTrue(frame.temperature(frame.flow.nodeAt(7.5,6.5,7.5))>20,"Live donor overlap takes precedence over disk estimates");
        assertEquals(0,auto.lastTransfer().error(),1e-6);assertTrue(frame.restoredCells>0);
        r.clear();request(r,"far",72);auto.reconcile(plan(r),0,32);drain(auto,40);
        assertEquals(1,auto.committedDomains().size(),"Inactive coverage releases ownership after saving");assertEquals(LocalAirPreparation.Status.CLOSED,expanded.preparation.status());
        auto.close();assertFalse(saved.isEmpty());
    }

}
