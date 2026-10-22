// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThermalClockSegmentsTest {
    @Test void manuallyConstructedSegmentsRejectInvalidTimeAndScale() {
        assertThrows(IllegalArgumentException.class,()->new ThermalClock.Segment(-1,1,1));
        assertThrows(IllegalArgumentException.class,()->new ThermalClock.Segment(1,1,1));
        assertThrows(IllegalArgumentException.class,()->new ThermalClock.Segment(2,1,1));
        assertThrows(IllegalArgumentException.class,()->new ThermalClock.Segment(0,1,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->new ThermalClock.Segment(0,1,11));
    }
    @Test void chronologicalSnapshotPreservesOffStepChangesAndSameTickReplacement() {
        var c=new ThermalClock(2400);c.observe(0,1);c.observe(73,2);
        c.observe(1200,10);c.observe(1200,.5);c.observe(2399,10);c.observe(2400,.1);
        var expected=List.of(new ThermalClock.Segment(0,73,1),new ThermalClock.Segment(73,1200,2),
                new ThermalClock.Segment(1200,2399,.5),new ThermalClock.Segment(2399,2400,10));
        assertEquals(expected,c.segments(0,2400));
        assertEquals(120,expected.stream().mapToDouble(ThermalClock.Segment::gameSeconds).sum());
        assertEquals(c.interval(0,2400).thermalSeconds(),expected.stream().mapToDouble(ThermalClock.Segment::thermalSeconds).sum(),1e-12);
        assertTrue(c.segments(2400,2400).isEmpty());
    }

    @Test void snapshotIsImmutableAndSurvivesFutureHistoryPruning() {
        var c=new ThermalClock(2400);c.observe(100,2);
        var snapshot=c.segments(0,100);
        assertEquals(List.of(new ThermalClock.Segment(0,100,2)),snapshot);
        assertThrows(UnsupportedOperationException.class,()->snapshot.add(new ThermalClock.Segment(100,200,1)));
        c.observe(200,1);c.observe(10000,10);
        assertEquals(List.of(new ThermalClock.Segment(0,100,2)),snapshot);
        assertEquals(List.of(new ThermalClock.Segment(7600,10000,1)),c.segments(0,10000));
    }

    @Test void denseHistoryClampsToTheRetainedWindowAndMatchesTheAggregateClock() {
        var c=new ThermalClock(2400);
        for(int tick=0;tick<=10000;tick++) { c.observe(tick,(tick&1)==0?.1:10); }
        var segments=c.segments(0,10000);
        assertEquals(2400,segments.size());assertEquals(7600,segments.getFirst().startTick());
        assertEquals(10000,segments.getLast().endTick());
        assertEquals(c.interval(0,10000).thermalSeconds(),segments.stream().mapToDouble(ThermalClock.Segment::thermalSeconds).sum(),1e-9);
        assertThrows(IllegalArgumentException.class,()->c.segments(-1,10000));
        assertThrows(IllegalArgumentException.class,()->c.segments(0,9999));
        assertThrows(IllegalArgumentException.class,()->c.segments(10001,10000));
        assertThrows(IllegalArgumentException.class,()->new ThermalClock(2400).segments(0,0));
    }
}
