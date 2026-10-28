// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

/** Known block edits must preserve unrelated storage and the exact chronological energy ledger. */
class LocalAirBlockEditTest {
    private static final Bounds B=new Bounds(0,0,0,5,5,5);
    private static final Shape EMPTY=new Shape(new int[0],new int[0]);
    private static final Cell AIR=new Cell(EMPTY,1,4,2,8);
    private static final Cell WALL=new Cell(new Shape(new int[]{0,0,0,16,16,16},new int[0]),1,4,2,8);
    private static final Cell MIXED=new Cell(new Shape(new int[]{0,0,0,16,8,16},new int[]{0,0,0,16,12,16}),1,4,2,8);
    private static final Cell HEATER=new Cell(EMPTY,1,4,2,8,new Heat(10,Double.NaN,Double.NaN,false));
    private static final int EDIT=B.index(2,2,2);
    private static final class World implements LocalAirCoverage.World {
        long revision=1;
        final Map<Long,Cell> cells=new HashMap<>();
        long read;
        World() { cells.put(blockKey(1,1,1),HEATER);cells.put(blockKey(2,2,2),MIXED); }
        public long contextId() { return 908; }
        public long geometryRevision() { return revision; }
        public long materialRevision() { return 1; }
        public int minBuildY() { return 0; }
        public int maxBuildY() { return 10; }
        public Footprint lastReads() { return new Footprint(new long[]{read}); }
        public Cell sample(int x,int y,int z) {
            read=blockKey(x,y,z);
            var cell=cells.get(read);if(cell!=null) return cell;
            return x>=0 && x<5 && y>=0 && y<5 && z>=0 && z<5
                    && (x==0 || x==4 || y==0 || y==4 || z==0 || z==4)?WALL:AIR;
        }
        public LocalAirCoverage.History freeze() {
            var copy=new World();copy.revision=revision;copy.cells.clear();copy.cells.putAll(cells);
            return new LocalAirCoverage.History() {
                public LocalAirCoverage.World world() { return copy; }
                public void close() { }
            };
        }
    }
    private static LocalAirPreparation start(World world,int cap) {
        var p=new LocalAirPreparation(world,B,B.cells(),10_000,new LocalAirRun.Settings(20,0,0,cap));
        drive(p,0,1);drive(p,1000,1);return p;
    }
    private static void drive(LocalAirPreparation p,long tick,double scale) {
        for(int i=0;i<3000;i++) {
            p.advance(25,Long.MAX_VALUE);p.observe(tick,scale);
            var work=p.takeWork();if(work!=null) p.complete(work.run());
            var frame=p.field();if(frame!=null && frame.throughTick==tick) return;
        }
        fail(p.status()+" / "+p.runReason());
    }
    private static void edit(World w,LocalAirPreparation p,int x,int y,int z,Cell cell,long tick,double scale,boolean solid,boolean fluid) {
        long previous=w.revision++;w.cells.put(blockKey(x,y,z),cell);
        assertTrue(p.changeGeometry(x,y,z,previous,tick,scale,solid,fluid));
        assertNull(p.field());assertFalse(p.query(tick,1.5,2.5,2.5,40).currentSample().isPresent());
    }
    private static void balanced(LocalAirRun.Frame frame) {
        assertEquals(frame.energy,frame.initialEnergy+frame.sourceEnergy+frame.exteriorEnergy+frame.geometryEnergy,1e-6);
        assertTrue(Math.abs(frame.quality.energyBalanceError())<1e-6);
        assertTrue(frame.quality.maxDivergence()<1e-7);
    }
    @Test void replacingOnePhaseKeepsEveryOtherTemperatureAndTheLifetimeLedger() {
        for(boolean solid:new boolean[]{false,true}) {
            var w=new World();var p=start(w,2);var before=p.field();var old=p.published().thermal();
            assertTrue(before.temperature(old.materialState(EDIT))>20 && before.temperature(old.fluidState(EDIT))>20);
            edit(w,p,2,2,2,MIXED,1000,1,!solid,solid);drive(p,1000,1);
            var after=p.field();var next=p.published().thermal();
            assertEquals(20,after.temperature(solid?next.materialState(EDIT):next.fluidState(EDIT)));
            for(int state=0;state<next.states();state++) if(state!=(solid?next.materialState(EDIT):next.fluidState(EDIT)))
                assertEquals(before.temperature(state),after.temperature(state),1e-12);
            assertEquals(before.initialEnergy,after.initialEnergy);assertEquals(before.sourceEnergy,after.sourceEnergy);
            assertEquals(before.acceptedSteps,after.acceptedSteps);assertEquals(1,after.transfers);balanced(after);
        }
    }
    @Test void changedPhaseCoefficientsCannotInheritOldStorageEvenWhenCallerRetainsTheBlock() {
        var w=new World();var p=start(w,2);var before=p.field();
        edit(w,p,2,2,2,new Cell(MIXED.shape(),3,5,2,8),1000,1,true,true);drive(p,1000,1);
        var g=p.published().thermal();assertEquals(20,p.field().temperature(g.materialState(EDIT)));
        assertEquals(before.temperature(g.fluidState(EDIT)),p.field().temperature(g.fluidState(EDIT)),1e-12);
        balanced(p.field());
    }
    @Test void sameTickRemoveAndReplaceCannotResurrectTheOriginalMaterialsHeat() {
        var w=new World();var p=start(w,1);var before=p.field();
        edit(w,p,2,2,2,AIR,1000,1,false,false);
        p.advance(25,Long.MAX_VALUE);var retired=p.takeWork();
        edit(w,p,2,2,2,MIXED,1000,1,false,false);
        if(retired!=null) p.complete(retired.run());
        drive(p,1000,1);var g=p.published().thermal();var after=p.field();
        assertEquals(20,after.temperature(g.materialState(EDIT)));assertEquals(20,after.temperature(g.fluidState(EDIT)));
        assertEquals(1,after.transfers);assertEquals(before.sourceEnergy,after.sourceEnergy);balanced(after);
    }
    private record Result(LocalAirRun.Frame frame,int states) { }
    private static Result timeline(boolean delayed,int cap) {
        var w=new World();var p=start(w,cap);
        p.observe(1010,1);var outstanding=p.takeWork();assertNotNull(outstanding);
        edit(w,p,2,2,2,new Cell(WALL.shape(),1,4,2,8,new Heat(10,Double.NaN,Double.NaN,false)),1013,1,false,false);
        p.complete(outstanding.run());assertNull(p.field());
        if(!delayed) drive(p,1013,1);else p.advance(11,Long.MAX_VALUE);
        assertTrue(p.changeWeather(1017,new LocalAirRun.Weather(18,0,0),1));
        edit(w,p,2,2,2,AIR,1027,1,false,false);if(!delayed) drive(p,1027,1);
        p.observe(1031,2);
        edit(w,p,2,2,2,MIXED,1037,2,false,false);drive(p,1100,2);
        var frame=p.field();assertEquals(3,frame.transfers);assertEquals(591.5,frame.sourceEnergy,1e-7);
        assertTrue(frame.persistentThermal);balanced(frame);return new Result(frame,p.published().thermal().states());
    }
    @Test void delayedBlockSourceFluidWeatherAndRateEditsMatchSerialHistoryAcrossWorkerCaps() {
        for(int cap:new int[]{1,2,64}) {
            var control=timeline(false,cap);var observed=timeline(true,cap);var expected=control.frame;var actual=observed.frame;
            assertEquals(control.states,observed.states);
            assertEquals(expected.acceptedSteps,actual.acceptedSteps);assertEquals(expected.energy,actual.energy);
            for(int state=0;state<observed.states;state++) assertEquals(expected.temperature(state),actual.temperature(state));
            for(int state=0;state<actual.flow.nodes();state++) {
                assertEquals(expected.temperature(state),actual.temperature(state));
                for(int axis=0;axis<3;axis++) assertEquals(expected.velocity(state,axis),actual.velocity(state,axis));
            }
            for(int face=0;face<actual.flow.faces();face++) assertEquals(expected.flux(face),actual.flux(face));
        }
    }
    @Test void boundaryEditsRequireNewCoverageAndCannotPublishARoofedCutAsOutsideAir() {
        var w=new World();var p=start(w,1);
        edit(w,p,0,2,2,AIR,1000,1,false,true);
        for(int i=0;i<1000 && p.status()!=LocalAirPreparation.Status.REJECTED;i++) {
            p.advance(25,Long.MAX_VALUE);p.observe(1000,1);var work=p.takeWork();if(work!=null) p.complete(work.run());
        }
        assertEquals(LocalAirPreparation.Status.REJECTED,p.status(),p.runReason());
        assertNull(p.field());assertEquals(LocalAirReading.Status.UNAVAILABLE,p.query(1000,1.5,2.5,2.5,40).status());
    }
    @Test void completedHistoricalTopologyCanSealAsALayoutDonorAndResumeWithoutLosingHeat() {
        var w=new World();var p=start(w,1);
        edit(w,p,2,2,2,AIR,1000,1,false,false);assertFalse(p.readyForLayout());
        drive(p,1010,1);assertTrue(p.readyForLayout());var before=p.field();
        assertTrue(p.holdLayout(1010,1));
        var seed=p.layoutCheckpoint();assertNotNull(seed);assertEquals(before.energy,seed.frame().energy);
        p.resumeLayout();drive(p,1030,1);assertEquals(before.sourceEnergy+10,p.field().sourceEnergy,1e-8);
        assertEquals(1,p.field().transfers);balanced(p.field());
    }
}
