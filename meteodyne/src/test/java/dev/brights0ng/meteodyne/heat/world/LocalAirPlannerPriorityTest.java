// Written by Claude.
package dev.brights0ng.meteodyne.heat.world;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirDemandRegistry.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirPlanner.*;

/** Player-distance ordering and the bounding-box component cap. */
class LocalAirPlannerPriorityTest {
    private static LocalAirDemandRegistry registry() { var r=new LocalAirDemandRegistry(100);r.advance(0);return r; }
    private static void source(LocalAirDemandRegistry r,String id,int x,int y,int z) {
        r.put(new Request(new Key(Kind.PLACED_SOURCE,id),new Cell(x,y,z),true,true));
    }
    private static Plan update(Settings settings,LocalAirDemandRegistry r,List<Cell> players) {
        return new LocalAirPlanner(settings).update(r.snapshot(),-64,320,(x,z)->true,players);
    }
    private static Group at(Plan plan,int tileX) {
        return plan.groups().stream().filter(g->g.key().x()==tileX).findFirst().orElseThrow();
    }

    @Test void theGroupNearerThePlayerWinsTheLastTileRegardlessOfCoordinates() {
        var r=registry();source(r,"low",8,8,8);source(r,"high",1000,8,8); // tiles x=0 and x=62
        var settings=new Settings(0,1,1,64);
        var nearHigh=update(settings,r,List.of(new Cell(1000,8,8)));
        assertEquals(State.PROPOSED_UNVERIFIED,at(nearHigh,62).state());assertEquals(State.TOTAL_LIMIT,at(nearHigh,0).state());
        assertEquals(0,at(nearHigh,62).playerDistance());assertEquals(62,at(nearHigh,0).playerDistance());
        var nearLow=update(settings,r,List.of(new Cell(20,8,8)));
        assertEquals(State.PROPOSED_UNVERIFIED,at(nearLow,0).state());assertEquals(State.TOTAL_LIMIT,at(nearLow,62).state());
        // The nearest of several players ranks a group; with no player, the first tile breaks the tie.
        var both=update(settings,r,List.of(new Cell(-5000,8,8),new Cell(1010,8,8)));
        assertEquals(State.PROPOSED_UNVERIFIED,at(both,62).state());
        var none=update(settings,r,List.of());
        assertEquals(State.PROPOSED_UNVERIFIED,at(none,0).state());assertEquals(Integer.MAX_VALUE,at(none,0).playerDistance());
    }
    @Test void aTwelveTileBoxIsOneProposedGroupAtComponentTilesTwelve() {
        var r=registry();source(r,"a",15,15,15);source(r,"b",31,15,15); // tiles x 0..2, y 0..1, z 0..1
        var plan=update(new Settings(0,1,12,12),r,List.of());
        assertEquals(1,plan.groups().size());var group=plan.groups().getFirst();
        assertEquals(12,group.tiles().size());assertEquals(12,boxTiles(group.tiles()));
        assertEquals(State.PROPOSED_UNVERIFIED,group.state());assertEquals(49_152L,plan.proposedCells());
        assertEquals(49_152L,componentCells(plan.settings()));
        assertEquals(State.COMPONENT_LIMIT,update(new Settings(0,1,12,11),r,List.of()).groups().getFirst().state());
    }
    @Test void anLShapedGroupWhoseBoxExceedsComponentTilesIsComponentLimited() {
        var r=registry();
        source(r,"x0",15,8,8);source(r,"x1",47,8,8);source(r,"x2",79,8,8); // tiles x 0..5 at z 0
        source(r,"z0",8,8,31);source(r,"z1",8,8,63);source(r,"z2",8,8,79); // tiles z 1..5 at x 0
        var plan=update(new Settings(0,1,64,12),r,List.of());
        assertEquals(1,plan.groups().size());var group=plan.groups().getFirst();
        assertEquals(11,group.tiles().size(),"Fewer tiles than the cap");assertEquals(36,boxTiles(group.tiles()));
        assertEquals(State.COMPONENT_LIMIT,group.state());assertEquals(0,plan.proposedTiles());
        assertEquals(State.PROPOSED_UNVERIFIED,update(new Settings(0,1,64,36),r,List.of()).groups().getFirst().state());
    }
}
