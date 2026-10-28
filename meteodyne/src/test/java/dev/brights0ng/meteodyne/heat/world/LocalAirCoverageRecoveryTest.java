// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirPlanner.*;

class LocalAirCoverageRecoveryTest {
    private static Group group(int... xs) {
        var tiles=new ArrayList<Tile>();for(int x:xs) tiles.add(new Tile(x,0,0));
        return new Group(tiles.getFirst(),tiles,List.of(),List.of(),State.PROPOSED_UNVERIFIED,tiles.size()*4096L);
    }
    private static Plan plan(int cap,Group... groups) { return new Plan(0,0,0,new Settings(0,1,cap,cap),List.of(groups),List.of(),0,""); }
    private static LocalAirCoverageRecovery.Rejection rejection(Group group,int x,int y,int z,int axis,int side) {
        var cut=new LocalAirCoverage.Cut(x,y,z,axis,side);
        return new LocalAirCoverageRecovery.Rejection(LocalAirAutomatic.bounds(group,0,32),new LocalAirCoverage.Failure(cut,x,y,z,"fixture obstruction"));
    }
    @Test void expandsOnlyTheRejectedFaceAndKeepsTheExpansionUntilDemandChanges() {
        var recovery=new LocalAirCoverageRecovery();var g=group(0);var p=plan(8,g);
        var grown=recovery.update(p,0,32,List.of(rejection(g,15,8,8,0,1)),t->true);
        assertEquals(List.of(new Tile(0,0,0),new Tile(1,0,0)),grown.groups().getFirst().tiles());assertEquals(8192,grown.groups().getFirst().cells());
        assertEquals(grown.groups(),recovery.update(p,0,32,List.of(),t->true).groups());
        assertEquals(List.of(new Tile(4,0,0)),recovery.update(plan(8,group(4)),0,32,List.of(),t->true).groups().getFirst().tiles());
        assertEquals(g.tiles(),recovery.update(p,0,32,List.of(),t->true).groups().getFirst().tiles());
    }
    @Test void growthMergesTouchingRequestsWithoutInventingIndependentExteriorSeams() {
        var recovery=new LocalAirCoverageRecovery();var g=group(0);
        var result=recovery.update(plan(8,g,group(2)),0,32,List.of(rejection(g,15,8,8,0,1)),t->true);
        assertEquals(1,result.groups().size());assertEquals(3,result.groups().getFirst().tiles().size());
        assertEquals(12288,result.groups().getFirst().cells());
    }
    @Test void newPolicyDropsPreviousGrowthAndEveryPlannerAndDomainBudgetApplies() {
        var recovery=new LocalAirCoverageRecovery();var g=group(0);var east=rejection(g,15,8,8,0,1);
        recovery.update(plan(8,g),0,32,List.of(east),t->true);
        assertEquals(g.tiles(),recovery.update(plan(1,g),0,32,List.of(),t->true).groups().getFirst().tiles());
        var two=new Plan(0,0,0,new Settings(0,1,2,8),List.of(g,group(4)),List.of(),0,"");
        var total=new LocalAirCoverageRecovery().update(two,0,32,List.of(east),t->true);
        assertTrue(total.notes().values().iterator().next().contains("total planner"));assertEquals(2,total.groups().size());
        var eight=group(0,1,2,3,4,5,6,7);
        var capped=new LocalAirCoverageRecovery().update(plan(64,eight),0,32,List.of(rejection(eight,127,8,8,0,1)),t->true);
        assertEquals(32768,capped.groups().getFirst().cells());assertTrue(capped.notes().values().iterator().next().contains("per-domain"));
        var top=new LocalAirCoverageRecovery().update(plan(8,g),0,32,List.of(rejection(g,8,15,8,1,1)),t->true);
        assertEquals(List.of(new Tile(0,0,0),new Tile(0,1,0)),top.groups().getFirst().tiles());
    }
    @Test void heightChunkAndPlannerLimitsBlockGrowthWithoutChangingTheOriginalProposal() {
        var g=group(0);
        var down=rejection(g,8,0,8,1,0);var east=rejection(g,15,8,8,0,1);
        for(var result:List.of(new LocalAirCoverageRecovery().update(plan(8,g),0,32,List.of(down),t->true),
                new LocalAirCoverageRecovery().update(plan(8,g),0,32,List.of(east),t->false),
                new LocalAirCoverageRecovery().update(plan(1,g),0,32,List.of(east),t->true))) {
            assertEquals(g.tiles(),result.groups().getFirst().tiles());assertTrue(result.notes().values().stream().allMatch(s->s.startsWith("Coverage blocked")));assertFalse(result.notes().isEmpty());
        }
        var recovery=new LocalAirCoverageRecovery();recovery.update(plan(8,g),0,32,List.of(east),t->false);
        var loaded=recovery.update(plan(8,g),0,32,List.of(east),t->true);assertEquals(2,loaded.groups().getFirst().tiles().size());
    }
    @Test void deferredNeighborCannotBeAbsorbedAsUsableCoverageAndNegativeTilesStayAligned() {
        var g=group(-1);var other=group(1);
        other=new Group(other.key(),other.tiles(),List.of(),List.of(new Column(1,0)),State.MISSING_CHUNKS,4096);
        var recovery=new LocalAirCoverageRecovery();var result=recovery.update(plan(8,g,other),0,32,List.of(rejection(g,-1,8,8,0,1)),t->true);
        assertEquals(2,result.groups().size());assertEquals(g.tiles(),result.groups().getFirst().tiles());
        assertTrue(result.notes().values().iterator().next().contains("deferred"));
        result=new LocalAirCoverageRecovery().update(plan(8,g),0,32,List.of(rejection(g,-16,8,8,0,0)),t->true);
        assertEquals(List.of(new Tile(-2,0,0),new Tile(-1,0,0)),result.groups().getFirst().tiles());
    }
}
