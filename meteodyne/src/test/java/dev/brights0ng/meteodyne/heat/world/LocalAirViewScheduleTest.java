// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalAirViewScheduleTest {
    private static LocalAirViewSchedule.State state(long owner,boolean history,boolean renderable,LocalAirReading.Status status,LocalAirReading.Reason reason) {
        return new LocalAirViewSchedule.State(owner,history,renderable,status,reason);
    }
    private static final LocalAirViewSchedule.State CURRENT=state(1,true,true,LocalAirReading.Status.CURRENT,LocalAirReading.Reason.NONE);
    private static final LocalAirViewSchedule.State PENDING=state(1,true,false,LocalAirReading.Status.PENDING,LocalAirReading.Reason.REFRESHING);
    @Test void aPendingHeartbeatNeverConsumesTheNextFullPublicationSlot() {
        var schedule=new LocalAirViewSchedule();assertTrue(schedule.advance(0,PENDING).packet());
        for(int tick=1;tick<10;tick++) assertFalse(schedule.advance(tick,PENDING).packet());
        assertTrue(schedule.advance(10,PENDING).packet());var first=schedule.advance(11,CURRENT);
        assertTrue(first.field());assertTrue(first.packet());assertTrue(first.caption());
        for(int tick=12;tick<21;tick++) assertFalse(schedule.advance(tick,CURRENT).field());
        assertTrue(schedule.advance(21,CURRENT).field());
    }
    @Test void invalidationOwnerChangesAndLostHistoryClearWithoutWaitingForTheFullPacketBudget() {
        var schedule=new LocalAirViewSchedule();assertTrue(schedule.advance(0,CURRENT).field());
        var pending=schedule.advance(1,PENDING);assertTrue(pending.packet());assertFalse(pending.field());
        assertFalse(schedule.advance(2,CURRENT).packet(),"Already sent field remains paced after early recovery");
        var moved=schedule.advance(3,state(2,true,true,LocalAirReading.Status.CURRENT,LocalAirReading.Reason.NONE));
        assertTrue(moved.packet());assertFalse(moved.field(),"Clear old owner immediately while pacing the next complete lattice");
        assertTrue(schedule.advance(4,state(2,false,false,LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.OUTSIDE_DOMAIN)).packet());
    }
    @Test void occupiedBodyPointsCanKeepTheValidSurroundingFieldAndStatusChangesNeedNoLattice() {
        var schedule=new LocalAirViewSchedule();schedule.advance(0,CURRENT);
        var occupied=schedule.advance(1,state(1,true,true,LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.OCCUPIED));
        assertTrue(occupied.caption());assertFalse(occupied.packet());assertFalse(occupied.field());
        assertThrows(IllegalArgumentException.class,()->schedule.advance(0,CURRENT));
    }
    @Test void delayedRealWeatherCompletionsPublishBetweenEveryAlignedRefreshWithoutRelaxingTheQueryGuard() {
        var b=new LocalAirGeometryCapture.Bounds(0,0,0,5,5,5);
        var p=new LocalAirPreparation(new LocalAirHistoryTest.World(),b,b.cells(),0,new LocalAirRun.Settings(20,0,0,4));
        for(int i=0;i<100 && p.field()==null;i++) {
            p.advance(256,Long.MAX_VALUE);p.observe(0,1);var work=p.takeWork();if(work!=null) p.complete(work.run());
        }
        assertNotNull(p.field());
        var schedule=new LocalAirViewSchedule();var cache=new LocalAirViewCache();LocalAirPreparation.Work pending=null;long completes=-1,lastFull=-10;
        int full=0,legacyOpportunities=0,withheld=0;
        for(long tick=0;tick<70;tick++) {
            if(tick%10==0) {assertTrue(p.changeWeather(tick,new LocalAirRun.Weather(21+tick*.01,.5,0),1));assertNull(p.field());}
            p.observe(tick,1);
            if(pending!=null && completes==tick) {p.complete(pending.run());pending=null;}
            var data=p.presentation(tick,1.5,1.5,2.5,40);boolean renderable=data.frame()!=null;
            var update=schedule.advance(tick,state(data.domainId(),data.retainHistory(),renderable,data.reading().status(),data.reading().reason()));
            if(tick%10==0 && renderable) legacyOpportunities++;
            if(tick%10==0) {assertEquals(LocalAirReading.Status.PENDING,data.reading().status());withheld++;}
            if(update.packet()) {
                var points=update.field()?LocalAirViewModel.samples(data.frame(),tick,40,1,false):List.<LocalAirViewNetwork.Point>of();
                cache.accept(new LocalAirViewCache.Frame(true,data.domainId(),data.retainHistory(),data.frame()==null?0:LocalAirDiagnostics.age(data.frame(),tick),40,points),tick);
                if(update.field()) {assertTrue(tick-lastFull>=10);lastFull=tick;full++;assertTrue(cache.current(tick));assertTrue(data.reading().currentSample().isPresent());}
            }
            if(tick%10==0) assertFalse(cache.current(tick),"Refresh never shows a field that has not incorporated current weather");
            if(pending==null) {pending=p.takeWork();if(pending!=null) completes=tick+2;}
        }
        assertEquals(0,legacyOpportunities,"The old fixed clock sees only the unavailable gap");assertEquals(7,withheld);assertEquals(7,full,"Every delayed current publication reaches the new schedule");p.close();
    }
}
