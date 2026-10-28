// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirDemandRegistry.*;
import java.util.List;

class LocalAirDemandRegistryTest {
    private static final Cell A=new Cell(-17,64,15),B=new Cell(40,65,40);
    private static Key key(String id) { return new Key(Kind.PLAYER,id); }
    private static Request player(String id,Cell cell) { return new Request(key(id),cell,true,true); }
    @Test void sameCellSharesCoverageButPreservesIndependentOwnersAndRequirements() {
        var r=new LocalAirDemandRegistry(100);r.advance(0);
        r.put(new Request(new Key(Kind.TEMPERATURE_SENSOR,"temperature"),A,true,false));
        r.put(new Request(new Key(Kind.WIND_SENSOR,"wind"),A,false,true));
        var snapshot=r.snapshot();assertEquals(2,snapshot.entries().size());assertEquals(1,snapshot.anchors().size());
        var anchor=snapshot.anchors().getFirst();assertTrue(anchor.temperature());assertTrue(anchor.flow());assertEquals(2,anchor.activeOwners());
        assertThrows(UnsupportedOperationException.class,()->snapshot.entries().clear());
        r.clear();assertEquals(2,snapshot.entries().size());assertTrue(r.snapshot().entries().isEmpty());
    }
    @Test void movementRetainsOldCellThenExpiresExactlyAndReturningReusesTheCell() {
        var r=new LocalAirDemandRegistry(100);r.advance(10);r.put(player("p",A));long revision=r.snapshot().revision();
        r.put(player("p",A));assertEquals(revision,r.snapshot().revision());
        r.advance(20);r.put(player("p",B));assertEquals(2,r.snapshot().anchors().size());
        assertEquals(120,r.snapshot().entries().stream().filter(e->!e.active()).findFirst().orElseThrow().expiresAt());
        r.advance(30);r.put(player("p",A));
        assertEquals(2,r.snapshot().entries().size());assertEquals(1,r.snapshot().anchors().stream().filter(a->a.activeOwners()>0).count());
        r.advance(129);assertEquals(2,r.snapshot().entries().size());r.advance(130);assertEquals(1,r.snapshot().entries().size());
    }
    @Test void removedOrDisconnectedProducersExpireWithoutBeingExtendedByRepeatedReconciliation() {
        var r=new LocalAirDemandRegistry(5);r.advance(0);r.reconcile(Kind.PLAYER,List.of(player("p",A),player("q",B)));
        r.advance(1);r.reconcile(Kind.PLAYER,List.of(player("q",B)));
        r.advance(5);r.reconcile(Kind.PLAYER,List.of(player("q",B)));assertEquals(2,r.snapshot().entries().size());
        r.advance(6);assertEquals(1,r.snapshot().entries().size());
    }
    @Test void chunkUnloadPurgesGraceAndActiveCellsIncludingNegativeCoordinates() {
        var r=new LocalAirDemandRegistry(100);r.advance(0);r.put(player("p",A));r.put(player("q",A));
        r.advance(1);r.put(player("p",B));r.unloadChunk(-2,0);
        assertEquals(List.of(B),r.snapshot().anchors().stream().map(Anchor::cell).toList());
        r.forget(key("p"));assertTrue(r.snapshot().entries().isEmpty());
    }
    @Test void requirementsCanChangeWithoutLeakingOwnersAndInvalidSnapshotsAreAtomic() {
        var r=new LocalAirDemandRegistry(0);r.advance(0);var request=player("p",A);r.put(request);
        assertThrows(IllegalArgumentException.class,()->r.reconcile(Kind.PLAYER,List.of(request,request)));
        assertThrows(IllegalArgumentException.class,()->r.reconcile(Kind.PLACED_SOURCE,List.of(request)));
        assertEquals(1,r.snapshot().entries().size());r.remove(key("p"));assertTrue(r.snapshot().entries().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new Request(key("bad"),A,false,false));
        assertThrows(IllegalArgumentException.class,()->r.advance(-1));
    }
    @Test void ownerThreadAndMonotonicTimeAreEnforced() throws Exception {
        var r=new LocalAirDemandRegistry(5);r.advance(10);assertThrows(IllegalArgumentException.class,()->r.advance(9));
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread=new Thread(()->{try { r.snapshot(); }catch(Throwable t) { error.set(t); }});thread.start();thread.join();
        assertInstanceOf(IllegalStateException.class,error.get());
    }
}
