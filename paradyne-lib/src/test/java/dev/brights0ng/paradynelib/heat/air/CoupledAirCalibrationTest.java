// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoupledAirCalibrationTest {
    @Test void refinedCalibrationUsesPhysicalWindowsAndLeavesTheLegacyControlAtOneSecond() {
        var protocol=new CoupledAirCalibration.Protocol(1,1,1);
        var checkpoints=new java.util.ArrayList<java.util.Map<String,Object>>();
        var rows=CoupledAirCalibration.run(ThermalTransport.DEFAULT_MODE,true,FlowGrid.DEFAULT_KERNEL,protocol,.5,checkpoints::add);
        var old=CoupledAirCalibration.run(ThermalTransport.DEFAULT_MODE,true,FlowGrid.DEFAULT_KERNEL,protocol);
        assertEquals(3,checkpoints.size());
        for(int i=0;i<3;i++) {
            @SuppressWarnings("unchecked") var row=(java.util.Map<String,Object>)rows.get(i);
            @SuppressWarnings("unchecked") var control=(java.util.Map<String,Object>)old.get(i);
            assertEquals(2,row.get("acceptedSteps"));assertEquals(1.0,row.get("advancedPhysicalSeconds"));
            assertEquals(75.0,row.get("deliveredSourceEnergy"));assertEquals("",row.get("firstFailure"));
            assertEquals(control.get("legacyFinalRoomOffsetC"),row.get("legacyFinalRoomOffsetC"));
            assertEquals(control.get("legacyFinalMaterialOffsetC"),row.get("legacyFinalMaterialOffsetC"));
            @SuppressWarnings("unchecked") var windows=(java.util.List<java.util.Map<String,Object>>)row.get("settlingWindows");
            assertEquals(2,windows.getFirst().get("coupledSamples"));
        }
        var h=CoupledAirBenchmark.house(0);var f=h.flow();
        var t=CoupledAirGridTest.grid(h.thermal(),FlowBoundary.wind(f,0,0,0),FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());
        t.setSources(new ThermalTransport.Source(f.nodeAt(h.mid()+.5,h.y0()+1.5,h.mid()+.5),75,Double.NaN));
        double[] bg=ThermalTransportTest.filled(f.nodes(),20),ext=ThermalTransportTest.filled(f.faces(),20);
        CoupledAirGridTest.ready(t.step(.5,1,bg,ext));
        double[] first=new double[h.thermal().states()],firstFlux=new double[f.faces()];
        for(int i=0;i<first.length;i++) { first[i]=t.temperature(i); }
        for(int e=0;e<firstFlux.length;e++) { firstFlux[e]=t.faceFlux(e); }
        CoupledAirGridTest.ready(t.step(.5,1,bg,ext));
        double meanChange=0,range=0,meanFlux=0;
        for(int i=0;i<first.length;i++) {
            meanChange=Math.max(meanChange,Math.abs(.5*(first[i]+t.temperature(i))-20));
            range=Math.max(range,Math.abs(first[i]-t.temperature(i)));
        }
        for(int e=0;e<firstFlux.length;e++) { meanFlux=Math.max(meanFlux,Math.abs(.5*(firstFlux[e]+t.faceFlux(e)))); }
        @SuppressWarnings("unchecked") var row=(java.util.Map<String,Object>)rows.getFirst();
        @SuppressWarnings("unchecked") var windows=(java.util.List<java.util.Map<String,Object>>)row.get("settlingWindows");
        assertEquals(h.mean(t),row.get("coupledFinalRoomOffsetC"));
        assertEquals(meanChange,(double)windows.getFirst().get("maxCoupledWindowMeanChangeC"),1e-12);
        assertEquals(range,(double)windows.getFirst().get("maxLocalTemperatureRangeC"),1e-12);
        assertEquals(meanFlux,(double)windows.getFirst().get("maxFaceWindowMeanChange"),1e-12);
        assertThrows(IllegalArgumentException.class,()->CoupledAirCalibration.run(
                ThermalTransport.DEFAULT_MODE,true,FlowGrid.DEFAULT_KERNEL,protocol,.3,r->{}));
    }

    @Test void samplingTheSameCyclePhaseCannotFalselyQualifyStrictSteadiness() {
        var m=new CoupledAirCalibration.SteadyMonitor();
        for(int i=0;i<10;i++) { assertFalse(m.observe(0,0,0,0,.04,0,.04)); }
        assertEquals(0,m.stableWindows());
        for(int i=0;i<10;i++) { assertFalse(m.observe(0,0,0,0,0,.04,0)); }
        assertEquals(0,m.stableWindows());
        for(int i=0;i<5;i++) { assertFalse(m.observe(0,0,0,0,0,0,0)); }
        assertTrue(m.observe(0,0,0,0,0,0,0));
    }

    @Test void qualificationCannotUseEmptyWindowsOrExceedItsWorkCap() {
        assertThrows(IllegalArgumentException.class,()->new CoupledAirCalibration.Protocol(0,6,6000));
        assertThrows(IllegalArgumentException.class,()->new CoupledAirCalibration.Protocol(100,0,6000));
        assertThrows(IllegalArgumentException.class,()->new CoupledAirCalibration.Protocol(100,6,500));
        assertThrows(IllegalArgumentException.class,()->CoupledAirCalibration.Protocol.named("unknown"));
    }

    @Test void thermalStatisticsAreDistinctFromStrictSteadinessAndStillRejectDriftOrLargeRoomSwings() {
        var m=new CoupledAirCalibration.ThermalMonitor();
        for(int i=0;i<6;i++) { assertEquals(i==5,m.observe(.0005,0,.0001,.005)); }
        assertFalse(m.observe(.1,0,0,0));assertEquals(0,m.stableWindows());
        assertFalse(m.observe(0,0,1,0));
        assertFalse(m.observe(0,0,0,.1));
        assertFalse(m.observe(0,Double.NaN,0,0));
        assertEquals(7,CoupledAirCalibration.trailingMean(new double[]{0,1,2,3,7,7,7},6,3));
    }

    @Test void eachModelsOwnTargetChangesTheReportedWarmupTime() {
        double[] heating={0,4,8,10,11,12,12};
        assertEquals(5,CoupledAirCalibration.firstCrossing(heating,6,.95*12,true));
        assertEquals(3,CoupledAirCalibration.firstCrossing(heating,6,.95*10,true));
        assertEquals(-1,CoupledAirCalibration.firstCrossing(heating,4,.95*12,true));
        assertEquals(-1,CoupledAirCalibration.firstCrossing(heating,6,Double.NaN,true));
        assertEquals(-1,CoupledAirCalibration.firstCrossing(heating,6,0,true));
    }

    @Test void coolingIsNormalizedToItsOwnStartingWarmth() {
        double[] a={12,9,6,3,1},b={20,15,10,5,2};
        assertEquals(2,CoupledAirCalibration.firstCrossing(a,4,.5*a[0],false));
        assertEquals(2,CoupledAirCalibration.firstCrossing(b,4,.5*b[0],false));
        assertEquals(4,CoupledAirCalibration.firstCrossing(b,4,.1*b[0],false));
    }

    @Test void stableRoomAloneCannotQualifyMovingFlowHiddenStorageOrNonfiniteState() {
        var m=new CoupledAirCalibration.SteadyMonitor();
        for(int i=0;i<5;i++) { assertFalse(m.observe(0,0,0,0)); }
        assertFalse(m.observe(0,0,1,0));assertEquals(0,m.stableWindows());
        assertFalse(m.observe(0,0,0,1));
        assertFalse(m.observe(1,0,0,0));
        assertFalse(m.observe(0,1,0,0));
        assertFalse(m.observe(Double.NaN,0,0,0));
        for(int i=0;i<5;i++) { assertFalse(m.observe(0,0,0,0)); }
        assertTrue(m.observe(0,0,0,0));
    }
}
