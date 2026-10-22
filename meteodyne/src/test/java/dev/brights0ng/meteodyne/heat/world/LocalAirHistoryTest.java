// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;

class LocalAirHistoryTest {
    private static final Bounds B=new Bounds(0,0,0,5,5,5);
    private static final Cell AIR=new Cell(new Shape(new int[0],new int[0]),1,1,1,1);
    private static final Cell WALL=new Cell(new Shape(new int[]{0,0,0,16,16,16},new int[0]),1,1,1,1);
    private static final Heat HEAT=new Heat(10,Double.NaN,Double.NaN,false);
    static class World implements LocalAirCoverage.World {
        long revision=1;boolean openLower,openUpper,lit=true;
        long lastRead;
        public Footprint lastReads() { return new Footprint(new long[]{lastRead}); }
        public long contextId() { return 321; }
        public long geometryRevision() { return revision; }
        public long materialRevision() { return 1; }
        public int minBuildY() { return 0; }
        public int maxBuildY() { return 8; }
        public Cell sample(int x,int y,int z) {
            lastRead=blockKey(x,y,z);
            if(x==2 && (y==1 || y==2) && z==2) {
                boolean open=y==1?openLower:openUpper;
                return new Cell(new Shape(open?new int[]{0,0,0,16,16,3}:new int[]{0,0,0,3,16,16},new int[0]),1,1,1,1);
            }
            if(x==1 && y==1 && z==2) return new Cell(AIR.shape(),1,1,1,1,lit?HEAT:null);
            return x==0 || x==4 || y==0 || y==4 || z==0 || z==4 || x==2?WALL:AIR;
        }
        public LocalAirCoverage.History freeze() {
            var copy=new World();copy.revision=revision;copy.openLower=openLower;copy.openUpper=openUpper;copy.lit=lit;
            return new LocalAirCoverage.History() {
                public LocalAirCoverage.World world() { return copy; }
                public void close() { }
            };
        }
    }
    private static LocalAirPreparation start(World w,int cap) {
        var p=new LocalAirPreparation(w,B,B.cells(),0,new LocalAirRun.Settings(20,0,0,cap));drive(p,0,1);
        drive(p,100,1);return p;
    }
    private static void drive(LocalAirPreparation p,long tick,double scale) {
        for(int i=0;i<2000;i++) {
            p.advance(20,Long.MAX_VALUE);p.observe(tick,scale);var work=p.takeWork();if(work!=null) p.complete(work.run());
            var frame=p.field();if(frame!=null && frame.throughTick==tick) return;
        }
        fail(p.status()+" / "+p.runReason());
    }
    private static void door(World w,LocalAirPreparation p,boolean open,long tick,double scale) {
        long revision=w.revision++;w.openLower=open;assertTrue(p.changeGeometry(2,1,2,revision,tick,scale));
        revision=w.revision++;w.openUpper=open;assertTrue(p.changeGeometry(2,2,2,revision,tick,scale));assertNull(p.field());
    }
    private static LocalAirRun.Frame timeline(boolean delayed,int cap) {
        var w=new World();var p=start(w,cap);
        door(w,p,true,103,1);if(!delayed) drive(p,103,1);
        else p.advance(7,Long.MAX_VALUE); // Capture may be partly complete when the next event arrives.
        w.lit=false;assertTrue(p.changeSource(1,1,2,107,1));assertNull(p.field());if(!delayed) drive(p,107,1);
        door(w,p,false,113,1);if(!delayed) drive(p,113,1);
        p.observe(117,2);
        w.lit=true;assertTrue(p.changeSource(1,1,2,121,2));if(!delayed) drive(p,121,2);
        door(w,p,true,127,2);drive(p,200,2);
        var frame=p.field();assertEquals(3,frame.transfers);assertEquals(2,frame.sourceRevision);
        assertEquals(132.5,frame.sourceEnergy,1e-7);assertEquals(14.15,frame.thermalSeconds,1e-12);
        assertTrue(frame.temperature(frame.flow.nodeAt(1.5,1.5,2.5))>20);
        assertEquals(w.revision,frame.geometryRevision);assertTrue(Math.abs(frame.quality.energyBalanceError())<1e-6);
        assertEquals(B.cells()+8,p.captureReadCells());assertEquals(3L*B.cells()-8,p.captureReusedCells());
        assertTrue(frame.quality.maxDivergence()<1e-7);return frame;
    }
    @Test void delayedRapidTopologySourceAndRateEventsMatchSerialProcessingAcrossWorkerCaps() {
        for(int cap:new int[]{1,2,64}) {
            var expected=timeline(false,cap);var actual=timeline(true,cap);
            assertEquals(expected.energy,actual.energy);assertEquals(expected.acceptedSteps,actual.acceptedSteps);
            for(int i=0;i<actual.flow.nodes();i++) {
                assertEquals(expected.temperature(i),actual.temperature(i));
                for(int a=0;a<3;a++) assertEquals(expected.velocity(i,a),actual.velocity(i,a));
            }
            for(int i=0;i<actual.flow.faces();i++) assertEquals(expected.flux(i),actual.flux(i));
        }
    }
    @Test void sameTickReplacementAfterInitializationRetainsSourceEventsAndTheOriginalTransferSeed() {
        var w=new World();var p=start(w,2);door(w,p,true,103,1);drive(p,103,1);
        w.lit=false;assertTrue(p.changeSource(1,1,2,103,1));p.observe(103,1);var old=p.takeWork();assertNotNull(old);
        long revision=w.revision++;w.openUpper=false;assertTrue(p.changeGeometry(2,2,2,revision,103,1));
        p.complete(old.run());assertNull(p.field());
        drive(p,130,1);var frame=p.field();assertEquals(1,frame.transfers);assertEquals(1,frame.sourceRevision);
        assertEquals(51.5,frame.sourceEnergy,1e-8);assertEquals(w.revision,frame.geometryRevision);
    }
    @Test void topologyHistoryLimitFailsAndOutstandingWorkCannotReviveTheField() {
        var w=new World();var p=start(w,1);p.observe(110,1);var work=p.takeWork();assertNotNull(work);
        for(int i=0;i<LocalAirPreparation.MAX_TOPOLOGIES;i++) {
            long revision=w.revision++;w.openLower=!w.openLower;
            assertTrue(p.changeGeometry(2,1,2,revision,110+i,1));
        }
        assertEquals(LocalAirPreparation.Status.FAILED,p.status());assertTrue(p.runReason().contains("admission"));
        p.complete(work.run());assertNull(p.field());assertNull(p.takeWork());
    }
    @Test void sourceHistoryLimitFailsWithoutSilentlyDroppingEdges() {
        var w=new World();var p=start(w,1);door(w,p,true,103,1);
        for(int i=0;i<LocalAirPreparation.MAX_HISTORY_EVENTS-1;i++) {
            w.lit=!w.lit;assertTrue(p.changeSource(1,1,2,103,1));
        }
        assertEquals(LocalAirPreparation.Status.FAILED,p.status());assertNull(p.field());assertTrue(p.runReason().contains("admission"));
    }
    @Test void unknownRevisionAndExplicitCloseRetireEveryQueuedTopology() {
        var w=new World();var p=start(w,1);p.observe(110,1);var work=p.takeWork();
        door(w,p,true,113,1);door(w,p,false,117,1);w.revision++;assertNull(p.field());p.complete(work.run());
        drive(p,150,1);assertEquals(0,p.field().transfers);assertEquals(0,p.field().sourceEnergy);
        p.observe(160,1);work=p.takeWork();door(w,p,true,163,1);door(w,p,false,167,1);
        p.close();p.complete(work.run());assertNull(p.field());assertNull(p.takeWork());assertEquals(LocalAirPreparation.Status.CLOSED,p.status());
    }
    @Test void completedHistoricalStageCanKeepRunningBeyondTheHistoryWindow() {
        var w=new World();var p=start(w,64);door(w,p,true,103,1);drive(p,103,1);
        drive(p,2000,1);drive(p,4000,1);
        assertEquals(1,p.field().transfers);assertEquals(4000,p.field().throughTick);
        assertEquals(2000,p.field().sourceEnergy,1e-6);
    }
    @Test void sourceChangesBeforeTheFirstDoorCannotBeResurrectedFromTheBaseSnapshot() {
        var w=new World();var p=start(w,2);w.lit=false;
        assertTrue(p.changeSource(1,1,2,100,1));drive(p,100,1);
        door(w,p,true,103,1);drive(p,150,1);
        assertEquals(50,p.field().sourceEnergy,1e-8);assertEquals(1,p.field().sourceRevision);
        assertEquals(B.cells()+3,p.captureReadCells());assertEquals(B.cells()-3,p.captureReusedCells());
    }
}
