// Written by Claude.
package dev.brights0ng.meteodyne.heat.world;

import java.util.EnumSet;
import dev.brights0ng.meteodyne.heat.world.LocalAirSoak.Action;
import dev.brights0ng.meteodyne.heat.world.LocalAirSoak.Mode;
import dev.brights0ng.meteodyne.heat.world.LocalAirSoak.Schedule;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalAirSoakScheduleTest {
    @Test void calmNeverActs() {
        var calm=new Schedule(Mode.CALM);
        for(long tick:new long[]{0,10,20,40,100,200,600,1200}) assertTrue(calm.actions(tick).isEmpty(),"tick "+tick);
    }
    @Test void busyActionSetsFollowTheirPeriods() {
        var busy=new Schedule(Mode.BUSY);
        assertEquals(EnumSet.allOf(Action.class),busy.actions(0));
        assertEquals(EnumSet.of(Action.REDSTONE),busy.actions(10));
        assertEquals(EnumSet.of(Action.CROP,Action.REDSTONE),busy.actions(20));
        assertEquals(EnumSet.of(Action.CROP,Action.LEAF,Action.REDSTONE),busy.actions(40));
        assertEquals(EnumSet.of(Action.CROP,Action.REDSTONE,Action.CHUNK),busy.actions(100));
        assertEquals(EnumSet.of(Action.FURNACE,Action.CROP,Action.LEAF,Action.REDSTONE,Action.CHUNK),busy.actions(200));
        assertEquals(EnumSet.allOf(Action.class),busy.actions(600));
        assertTrue(busy.actions(7).isEmpty());
    }
    @Test void sameSeedGivesSameCropTargets() {
        var a=new Schedule(Mode.BUSY,42);var b=new Schedule(Mode.BUSY,42);var other=new Schedule(Mode.BUSY,7);
        boolean differs=false;
        for(int i=0;i<64;i++) {
            int pick=a.nextCrop(144);
            assertEquals(pick,b.nextCrop(144));
            assertTrue(pick>=0 && pick<144);
            differs|=pick!=other.nextCrop(144);
        }
        assertTrue(differs,"a different seed gives a different sequence");
    }
}
