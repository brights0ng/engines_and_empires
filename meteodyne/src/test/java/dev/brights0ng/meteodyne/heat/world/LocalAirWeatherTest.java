// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirWeatherTest {
    private static final Bounds B=new Bounds(0,0,0,5,5,5);
    private static LocalAirRun.Weather weather(double t,double x,double z) { return new LocalAirRun.Weather(t,x,z); }
    private static void drive(LocalAirPreparation p,long tick,double scale) {
        for(int i=0;i<3000;i++) {
            p.advance(20,Long.MAX_VALUE);p.observe(tick,scale);
            var work=p.takeWork();if(work!=null) p.complete(work.run());
            var f=p.field();if(f!=null && f.throughTick==tick) return;
        }
        fail(p.status()+" / "+p.runReason());
    }
    private static LocalAirPreparation start(LocalAirHistoryTest.World w,int cap) {
        var p=new LocalAirPreparation(w,B,B.cells(),0,new LocalAirRun.Settings(20,0,0,cap));
        drive(p,0,1);drive(p,100,1);return p;
    }
    private static void door(LocalAirHistoryTest.World w,LocalAirPreparation p,boolean open,long tick) {
        long rev=w.revision++;w.openLower=open;assertTrue(p.changeGeometry(2,1,2,rev,tick,1));
        rev=w.revision++;w.openUpper=open;assertTrue(p.changeGeometry(2,2,2,rev,tick,1));
    }
    @Test void openExteriorWarmingCoolingAndGustReversalUseTheBoundaryEnergyLedger() {
        var air=new Cell(new Shape(new int[0],new int[0]),1,1,1,1);
        var wall=new Cell(new Shape(new int[]{0,0,0,16,16,16},new int[0]),1,1,1,1);
        var w=new LocalAirCoverage.World() {
            public long contextId() { return 99; }
            public long geometryRevision() { return 1; }
            public long materialRevision() { return 1; }
            public int minBuildY() { return 0; }
            public int maxBuildY() { return 8; }
            public Cell sample(int x,int y,int z) { return y==0?wall:air; }
        };
        var p=new LocalAirPreparation(w,B,B.cells(),10_000,new LocalAirRun.Settings(20,0,0,2));drive(p,0,1);
        var initial=p.field();assertTrue(p.changeWeather(0,weather(30,0.5,0),1));drive(p,0,1);
        assertEquals(initial.energy,p.field().energy,1e-8);drive(p,20,1);var warm=p.field();
        assertTrue(warm.energy>initial.energy);assertTrue(warm.exteriorEnergy>0);assertEquals(0,warm.sourceEnergy);
        assertTrue(p.changeWeather(20,weather(5,-0.5,0),1));drive(p,20,1);
        assertEquals(warm.energy,p.field().energy,1e-8);drive(p,40,1);var cool=p.field();
        assertTrue(cool.energy<warm.energy);assertTrue(cool.exteriorEnergy<warm.exteriorEnergy);
        assertEquals(0,cool.transfers);assertTrue(cool.quality.maxDivergence()<1e-7);
        assertTrue(Math.abs(cool.quality.energyBalanceError())<1e-6);
    }
    @Test void instantaneousAmbientAndWindChangesRetainEveryTemperatureAndTheEnergyLedger() {
        var p=start(new LocalAirHistoryTest.World(),2);var before=p.field();
        assertTrue(p.changeWeather(100,weather(35,1,-0.5),1));assertNull(p.field());drive(p,100,1);
        var after=p.field();assertEquals(before.energy,after.energy,1e-8);
        assertEquals(before.initialEnergy,after.initialEnergy);assertEquals(before.sourceEnergy,after.sourceEnergy);
        assertEquals(before.acceptedSteps,after.acceptedSteps);assertEquals(0,after.transfers);
        for(int i=0;i<after.flow.nodes();i++) assertEquals(before.temperature(i),after.temperature(i));
        assertEquals(1,after.weatherRevision);assertTrue(Math.abs(after.quality.energyBalanceError())<1e-6);
        assertTrue(p.changeWeather(100,weather(-10,-1,0.5),1));drive(p,100,1);
        assertEquals(after.energy,p.field().energy,1e-8);assertEquals(2,p.field().weatherRevision);
    }
    @Test void ambientRelativeSourceLimitsFollowWeatherWithoutResettingStoredHeat() {
        var w=new LocalAirHistoryTest.World() {
            public Cell sample(int x,int y,int z) {
                var c=super.sample(x,y,z);
                return c.heat()==null?c:new Cell(c.shape(),1,1,1,1,new Heat(10,Double.NaN,5,false));
            }
        };
        var p=start(w,2);var before=p.field();
        assertTrue(p.changeWeather(100,weather(-10,0,0),1));drive(p,100,1);
        assertEquals(before.energy,p.field().energy,1e-8);drive(p,120,1);
        assertEquals(before.sourceEnergy,p.field().sourceEnergy,1e-8);
        assertTrue(p.changeWeather(120,weather(40,0,0),1));drive(p,140,1);
        assertTrue(p.field().sourceEnergy>before.sourceEnergy);assertEquals(0,p.field().transfers);
    }
    private static LocalAirRun.Frame timeline(boolean delayed,int cap) {
        var w=new LocalAirHistoryTest.World();var p=start(w,cap);
        assertTrue(p.changeWeather(103,weather(30,0.5,0),1));if(!delayed) drive(p,103,1);
        door(w,p,true,107);if(!delayed) drive(p,107,1);else p.advance(7,Long.MAX_VALUE);
        assertTrue(p.changeWeather(111,weather(5,-0.5,0.25),1));if(!delayed) drive(p,111,1);
        w.lit=false;assertTrue(p.changeSource(1,1,2,113,1));if(!delayed) drive(p,113,1);
        door(w,p,false,117);if(!delayed) drive(p,117,1);
        assertTrue(p.changeWeather(117,weather(22,0,0),1));if(!delayed) drive(p,117,1);
        p.observe(121,2);assertTrue(p.changeWeather(127,weather(15,0.2,-0.1),2));
        drive(p,200,2);var f=p.field();
        assertEquals(4,f.weatherRevision);assertEquals(2,f.transfers);assertEquals(1,f.sourceRevision);
        assertEquals(56.5,f.sourceEnergy,1e-7);assertEquals(13.95,f.thermalSeconds,1e-12);
        assertTrue(Math.abs(f.quality.energyBalanceError())<1e-6);assertTrue(f.quality.maxDivergence()<1e-7);
        return f;
    }
    @Test void delayedWeatherDoorSourceAndRateHistoryMatchesSerialAcrossWorkerCaps() {
        for(int cap:new int[]{1,2,64}) {
            var a=timeline(false,cap);var b=timeline(true,cap);
            assertEquals(a.energy,b.energy);assertEquals(a.acceptedSteps,b.acceptedSteps);
            for(int i=0;i<a.flow.nodes();i++) {
                assertEquals(a.temperature(i),b.temperature(i));
                for(int axis=0;axis<3;axis++) assertEquals(a.velocity(i,axis),b.velocity(i,axis));
            }
            for(int i=0;i<a.flow.faces();i++) assertEquals(a.flux(i),b.flux(i));
        }
    }
    @Test void staleWorkerCannotPublishOldWeatherButRetainsItsComputedProgress() {
        var p=start(new LocalAirHistoryTest.World(),1);p.observe(110,1);var old=p.takeWork();assertNotNull(old);
        assertTrue(p.changeWeather(113,weather(10,1,0),1));p.complete(old.run());assertNull(p.field());
        drive(p,130,1);assertEquals(65,p.field().sourceEnergy,1e-7);assertEquals(1,p.field().weatherRevision);
        assertEquals(0,p.field().transfers);assertEquals(B.cells(),p.captureReadCells());
    }
    private static LocalAirRun.Frame sameTickBarrier(boolean delayed) {
        var w=new LocalAirHistoryTest.World();var p=start(w,2);
        assertTrue(p.changeWeather(100,weather(25,0.5,0),1));if(!delayed) drive(p,100,1);
        door(w,p,true,100);if(!delayed) drive(p,100,1);
        assertTrue(p.changeWeather(100,weather(15,-0.5,0),1));drive(p,120,1);return p.field();
    }
    @Test void weatherOnBothSidesOfASameTickTopologyBarrierRetainsEventOrder() {
        var a=sameTickBarrier(false);var b=sameTickBarrier(true);
        assertEquals(a.energy,b.energy);assertEquals(a.acceptedSteps,b.acceptedSteps);
        for(int i=0;i<a.flow.faces();i++) assertEquals(a.flux(i),b.flux(i));
    }
    @Test void sameTickReplacementAndIdenticalSamplesDoNotReplayAnObsoleteBoundary() {
        var p=start(new LocalAirHistoryTest.World(),2);
        assertTrue(p.changeWeather(100,weather(30,1,0),1));
        assertTrue(p.changeWeather(100,weather(10,-1,0),1));drive(p,100,1);
        assertEquals(weather(10,-1,0),p.field().weather);assertEquals(2,p.field().weatherRevision);
        assertTrue(p.changeWeather(110,weather(10,-1,0),1));drive(p,110,1);assertEquals(2,p.field().weatherRevision);
    }
    @Test void historyAdmissionFailsExplicitlyAndCannotBeRevivedByOldWork() {
        var p=start(new LocalAirHistoryTest.World(),1);p.observe(110,1);var old=p.takeWork();
        for(int i=0;i<257;i++) p.changeWeather(110+i,weather(20+i%2,0,0),1);
        assertEquals(LocalAirPreparation.Status.FAILED,p.status());assertTrue(p.runReason().contains("admission"));
        p.complete(old.run());assertNull(p.field());assertNull(p.takeWork());
    }
    @Test void steadySamplingPrunesHistoryWithoutResettingHeatOrRevision() {
        var p=start(new LocalAirHistoryTest.World(),64);
        for(int tick=110;tick<=3000;tick+=10) {
            assertTrue(p.changeWeather(tick,weather(20+(tick/10)%2,0,0),1));drive(p,tick,1);
        }
        assertEquals(1500,p.field().sourceEnergy,1e-6);assertEquals(3000,p.field().throughTick);
        assertEquals(B.cells(),p.captureReadCells());assertEquals(0,p.field().transfers);
    }
    @Test void unavailableWeatherRetiresOutstandingWorkAndInvalidInputsReject() {
        var p=start(new LocalAirHistoryTest.World(),1);p.observe(110,1);var old=p.takeWork();
        p.weatherUnavailable("provider failure");p.complete(old.run());assertNull(p.field());
        assertEquals(LocalAirPreparation.Status.FAILED,p.status());assertTrue(p.runReason().contains("provider failure"));
        assertThrows(IllegalArgumentException.class,()->weather(Double.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->weather(-274,0,0));
        assertThrows(IllegalArgumentException.class,()->weather(20,101,0));
    }
}
