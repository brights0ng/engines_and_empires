// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirDemandRegistry.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirPlanner.*;

class LocalAirPlannerTest {
    private static LocalAirDemandRegistry registry() { var r=new LocalAirDemandRegistry(100);r.advance(0);return r; }
    private static Request request(Kind kind,String id,int x,int y,int z) { return new Request(new Key(kind,id),new Cell(x,y,z),true,true); }
    private static Request player(String id,int x) { return request(Kind.PLAYER,id,x,8,8); }
    private static LocalAirPlanner planner(int total,int component) { return new LocalAirPlanner(new Settings(0,1,total,component)); }
    private static Plan update(LocalAirPlanner p,LocalAirDemandRegistry r) { return p.update(r.snapshot(),-64,320,(x,z)->true); }
    @Test void sharedRequestsAndFaceNeighborsFormOneGroupWithoutBridgingDistantDemand() {
        var r=registry();r.put(player("a",8));r.put(request(Kind.TEMPERATURE_SENSOR,"b",8,8,8));
        r.put(player("c",24));r.put(player("d",1000));var plan=update(planner(64,64),r);
        assertEquals(2,plan.groups().size());assertEquals(3,plan.proposedTiles());
        var nearby=plan.groups().getFirst();assertEquals(2,nearby.tiles().size());assertEquals(3,nearby.requests().size());
        assertEquals(List.of(new Tile(0,0,0),new Tile(1,0,0)),nearby.tiles());
        assertEquals(3L*4096,plan.proposedCells());
        assertThrows(UnsupportedOperationException.class,()->nearby.tiles().clear());
        assertThrows(UnsupportedOperationException.class,()->plan.groups().clear());
    }
    @Test void negativeCoordinatesUseFloorTilesAndWorldHeightClipsOnlyVerticalExtent() {
        var r=registry();r.put(request(Kind.PLAYER,"p",-8,-59,-8));var p=planner(64,64);
        var plan=p.update(r.snapshot(),-60,300,(x,z)->true);var group=plan.groups().getFirst();
        assertEquals(List.of(new Tile(-1,-4,-1)),group.tiles());assertEquals(12L*16*16,group.cells());
        var wide=new LocalAirPlanner(new Settings(8,1,64,64));r.clear();r.put(player("p",8));
        assertEquals(27,update(wide,r).proposedTiles(),"The calculation margin extends outside the requested radius");
    }
    @Test void diagonalTilesHaveNoSharedFaceAndRemainSeparateGroups() {
        var r=registry();r.put(player("a",8));r.put(request(Kind.PLAYER,"b",24,24,8));
        assertEquals(2,update(planner(64,64),r).groups().size());
    }
    @Test void movementInsideTileKeepsLayoutWhileGraceProtectsCrossingAndExpires() {
        var r=registry();var p=planner(64,64);r.put(player("p",8));var first=update(p,r);
        r.advance(1);r.put(player("p",9));var moved=update(p,r);
        assertTrue(moved.demandRevision()>first.demandRevision());assertEquals(first.layoutRevision(),moved.layoutRevision());
        r.advance(2);r.put(player("p",24));var crossed=update(p,r);assertEquals(2,crossed.proposedTiles());
        assertTrue(crossed.layoutRevision()>moved.layoutRevision());
        r.advance(101);assertEquals(2,update(p,r).proposedTiles());
        r.advance(102);assertEquals(1,update(p,r).proposedTiles());assertEquals(1,first.proposedTiles(),"Old plans stay immutable");
    }
    @Test void aMissingColumnBlocksItsWholeConnectedGroupAndArrivalReplansWithoutDemandChange() {
        var r=registry();r.put(player("a",8));r.put(player("b",24));r.put(player("c",1000));var p=planner(64,64);
        var queried=new HashSet<Column>();
        var blocked=p.update(r.snapshot(),-64,320,(x,z)->{assertTrue(queried.add(new Column(x,z)),"Availability queried once per column");return x!=1;});
        assertEquals(State.MISSING_CHUNKS,blocked.groups().getFirst().state());assertEquals(2,blocked.groups().getFirst().tiles().size());
        assertEquals(List.of(new Column(1,0)),blocked.groups().getFirst().missingChunks());assertEquals(1,blocked.proposedTiles());
        var arrived=update(p,r);assertEquals(3,arrived.proposedTiles());assertEquals(blocked.demandRevision(),arrived.demandRevision());
        assertTrue(arrived.layoutRevision()>blocked.layoutRevision());
    }
    @Test void capacityReservesWholeGroupsWithPlayersBeforeOtherActiveAndGraceRequests() {
        var r=registry();r.put(request(Kind.PLACED_SOURCE,"fire",8,8,8));r.put(player("player",1000));var p=planner(1,64);
        var plan=update(p,r);assertEquals(State.TOTAL_LIMIT,plan.groups().getFirst().state());
        assertEquals(State.PROPOSED_UNVERIFIED,plan.groups().getLast().state());
        r.remove(new Key(Kind.PLAYER,"player"));plan=update(p,r);
        assertEquals(State.PROPOSED_UNVERIFIED,plan.groups().getFirst().state());assertEquals(State.TOTAL_LIMIT,plan.groups().getLast().state());
        r.clear();r.put(player("a",8));r.put(player("b",24));plan=update(planner(64,1),r);
        assertEquals(State.COMPONENT_LIMIT,plan.groups().getFirst().state());assertEquals(0,plan.proposedTiles());
        assertEquals(2,plan.groups().getFirst().tiles().size(),"Never split a connected group to fit its cap");
    }
    @Test void candidateAndVisitWorkLimitsRejectTheWholeLayoutBeforeAnyWorldReads() {
        var r=registry();for(int i=0;i<513;i++) r.put(player("p"+i,8));
        var p=new LocalAirPlanner(new Settings(32,32,512,512));
        var visits=p.update(r.snapshot(),-64,320,(x,z)->{fail("No world reads on work overflow");return true;});
        assertTrue(visits.failure().contains("visit"));assertTrue(visits.groups().isEmpty());assertTrue(visits.tileVisits()<=MAX_VISITS);
        r.clear();for(int i=0;i<8;i++) r.put(player("p"+i,i*10000+8));
        var candidates=p.update(r.snapshot(),-64,320,(x,z)->{fail("No world reads on candidate overflow");return true;});
        assertTrue(candidates.failure().contains("Candidate"));assertEquals(0,candidates.proposedTiles());
        r.clear();for(int i=0;i<MAX_REQUESTS+1;i++) r.put(player("p"+i,8));
        assertTrue(update(p,r).failure().contains("Request"));
    }
    @Test void extremeCoordinatesHeightAndPolicyAreValidatedWithoutWrapping() {
        assertThrows(IllegalArgumentException.class,()->new Settings(-1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new Settings(0,0,1,1));
        assertThrows(IllegalArgumentException.class,()->new Settings(0,1,513,1));
        var r=registry();var p=planner(1,1);r.put(player("p",Integer.MAX_VALUE));
        assertTrue(update(p,r).unsupported().getFirst().reason().contains("coordinate"));r.clear();r.put(request(Kind.PLAYER,"p",8,320,8));
        r.put(player("valid",8));var plan=update(p,r);
        assertTrue(plan.unsupported().getFirst().reason().contains("height"));assertEquals(1,plan.proposedTiles(),"Unsupported requests do not block unrelated valid coverage");
        assertThrows(UnsupportedOperationException.class,()->plan.unsupported().clear());
        assertThrows(IllegalArgumentException.class,()->p.update(r.snapshot(),5,5,(x,z)->true));
    }
    @Test void requestOrderDoesNotChangeCapacitySelectionAndThreadOwnershipIsEnforced() throws Exception {
        var a=registry();a.put(player("p",1000));a.put(request(Kind.PLACED_SOURCE,"s",8,8,8));
        var b=registry();b.put(request(Kind.PLACED_SOURCE,"s",8,8,8));b.put(player("p",1000));
        var p=planner(1,64);assertEquals(update(p,a).groups(),update(planner(1,64),b).groups());
        var error=new AtomicReference<Throwable>();var thread=new Thread(()->{try { p.current(); }catch(Throwable t) {error.set(t);}});
        thread.start();thread.join();assertInstanceOf(IllegalStateException.class,error.get());
    }
}
