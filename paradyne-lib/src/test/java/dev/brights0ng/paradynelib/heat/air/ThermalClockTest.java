// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ThermalClockTest {
    @Test void rateChangesAffectFutureTimeAndKeepGameTimeSeparate() {
        var clock=new ThermalClock(2400);
        clock.observe(0,1);
        clock.observe(100,2);
        clock.observe(200,2);
        assertEquals(10,clock.interval(0,200).gameSeconds());
        assertEquals(15,clock.interval(0,200).thermalSeconds());
        assertEquals(8,clock.interval(120,200).thermalSeconds());
    }

    @Test void sameTickChangesDoNotRescaleElapsedTime() {
        var clock=new ThermalClock(2400);
        clock.observe(0,1);
        clock.observe(100,2);
        clock.observe(100,0.5);
        assertEquals(5,clock.interval(0,100).thermalSeconds());
        clock.observe(200,0.5);
        assertEquals(7.5,clock.interval(0,200).thermalSeconds());
    }

    @Test void catchUpKeepsOnlyTheMostRecentGameTimeWithItsActualRates() {
        var clock=new ThermalClock(2400);
        clock.observe(0,1);
        clock.observe(2000,2);
        clock.observe(4000,2);
        assertEquals(120,clock.interval(0,4000).gameSeconds());
        assertEquals(220,clock.interval(0,4000).thermalSeconds());
    }

    @Test void longChangingHistoryPreservesTheRetainedWindow() {
        var clock=new ThermalClock(2400);
        double expected=0;
        for(int tick=0;tick<=10000;tick++) {
            double scale=(tick&1)==0?0.1:10;
            clock.observe(tick,scale);
            if(tick>=7600 && tick<10000) { expected+=scale/20; }
        }
        assertEquals(expected,clock.interval(0,10000).thermalSeconds(),1e-9);
    }

    @Test void rejectsInvalidSettingsAndTimeInsteadOfPausingOrClipping() {
        for(double invalid:new double[]{0,-1,0.099,10.001,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->ThermalClock.requireScale(invalid));
        }
        assertEquals(0.1,ThermalClock.requireScale(0.1));assertEquals(10,ThermalClock.requireScale(10));
        assertThrows(IllegalArgumentException.class,()->ThermalClock.thermalSeconds(Double.MAX_VALUE,10));
        assertThrows(IllegalArgumentException.class,()->ThermalClock.thermalSeconds(-1,1));
        var clock=new ThermalClock(2400);
        clock.observe(100,1);
        assertThrows(IllegalArgumentException.class,()->clock.observe(99,1));
        assertThrows(IllegalArgumentException.class,()->clock.interval(101,100));
    }

    @Test void completeThermalEvolutionMatchesUnityAtEqualThermalTimeUnderPrescribedWind() {
        // Same numerical thermal intervals, different elapsed game intervals: sources, walls, mixing and
        // wind transport must all follow the same clock. The prescribed wind vector is never scaled.
        for(double scale:new double[]{0.1,0.5,1,2,10}) {
            AirGrid control=fixture(), actual=fixture();
            double gameStep=0.5/scale;
            for(int step=0;step<80;step++) {
                control.step(0.5);
                actual.step(ThermalClock.thermalSeconds(gameStep,scale));
            }
            float[] expected=new float[9*9*9], values=new float[expected.length];
            control.exportOffsets(expected);actual.exportOffsets(values);
            assertArrayEquals(expected,values,"complete equation at scale "+scale);
            // Turning the sources off checks wall storage, cooling and ventilation on the same clock.
            control.clearSources();actual.clearSources();
            for(int step=0;step<80;step++) {
                control.step(0.5);actual.step(ThermalClock.thermalSeconds(gameStep,scale));
            }
            control.exportOffsets(expected);actual.exportOffsets(values);
            assertArrayEquals(expected,values,"cooling at scale "+scale);
        }
    }

    private static AirGrid fixture() {
        var grid=new AirGrid(9,9,9,AirParams.DEFAULT);
        for(int z=1;z<8;z++) for(int y=1;y<8;y++) for(int x=1;x<8;x++) {
            if(x==2 || x==6 || y==1 || y==6 || z==2 || z==6) { grid.setSolid(x,y,z,0.25,0.5,0); }
            else { grid.setAir(x,y,z,y>6); }
        }
        grid.setAir(4,3,2,false);grid.setAir(4,3,1,true);
        grid.setWind(3,-1);
        grid.sources().add(new AirGrid.Source(grid.index(4,2,4),75,30));
        grid.sources().add(new AirGrid.Source(grid.index(5,4,4),-15,-10));
        return grid;
    }
}
