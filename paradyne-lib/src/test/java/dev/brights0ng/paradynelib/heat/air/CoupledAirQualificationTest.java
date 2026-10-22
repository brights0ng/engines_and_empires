// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoupledAirQualificationTest {
    @Test void deeperGroundPreservesTheWholeHouseAndItsHeightAboveTheSurface() {
        var a=CoupledAirBenchmark.house(0,8,8,3);var b=CoupledAirBenchmark.house(0,8,8,6);
        assertEquals(3,b.y0()-a.y0());assertEquals(3,b.flow().ny-a.flow().ny);
        for(int z=0;z<10;z++) for(int y=0;y<5;y++) for(int x=0;x<10;x++) {
            int ca=a.flow().index(a.x0()+x,a.y0()+y,a.x0()+z),cb=b.flow().index(b.x0()+x,b.y0()+y,b.x0()+z);
            assertSame(a.flow().shapes[ca],b.flow().shapes[cb]);
            int ma=a.thermal().materialState(ca),mb=b.thermal().materialState(cb);
            if(ma>=0) { assertEquals(a.thermal().capacity[ma],b.thermal().capacity[mb]); }
        }
        assertTrue(b.flow().nodeAt(b.mid()+.5,b.y0()+1.5,b.mid()+.5)>=0);
    }

    @Test void paddingPreservesHouseOccupancyMaterialsAndSourcePosition() {
        var reference=CoupledAirBenchmark.house(1);
        for(int padding:new int[]{6,8,16}) {
            var h=CoupledAirBenchmark.house(1,padding,padding);
            assertEquals(12+2*padding,h.flow().nx);assertEquals(10+padding,h.flow().ny);
            for(int z=0;z<10;z++) for(int y=0;y<5;y++) for(int x=0;x<10;x++) {
                int a=reference.flow().index(reference.x0()+x,reference.y0()+y,reference.x0()+z);
                int b=h.flow().index(h.x0()+x,h.y0()+y,h.x0()+z);
                assertSame(reference.flow().shapes[a],h.flow().shapes[b]);
                int ma=reference.thermal().materialState(a),mb=h.thermal().materialState(b);
                assertEquals(ma>=0,mb>=0);
                if(ma>=0) { assertEquals(reference.thermal().capacity[ma],h.thermal().capacity[mb]); }
            }
            int source=h.flow().nodeAt(h.mid()+.5,h.y0()+1.5,h.mid()+.5);
            assertTrue(source>=0);assertEquals(1,h.thermal().capacity[source]);
        }
    }

    @Test void physicalSamplesStayAlignedAndMaterialVelocityCannotDiluteAirVelocityError() {
        for(double dt:new double[]{1,.5,.25}) { assertEquals(1,dt*CoupledAirQualification.substeps(dt)); }
        assertThrows(IllegalArgumentException.class,()->CoupledAirQualification.substeps(.3));
        assertThrows(IllegalArgumentException.class,()->CoupledAirQualification.substeps(Double.NaN));
        assertThrows(IllegalArgumentException.class,()->CoupledAirQualification.substeps(.01));
        var error=CoupledAirQualification.error(new double[]{20,3,0,0,30,0,0,0},new double[]{20,0,0,0,30,0,0,0},new boolean[]{true,false});
        assertEquals(0,(double)error.get("rmsTemperatureC"));assertEquals(Math.sqrt(3),(double)error.get("rmsVelocity"));
    }

    @Test void coldExteriorHasNoImplicitDiffusiveReservoirWithoutAdvection() {
        var f=new FlowGeometry.Builder(1,1,1).openExterior(true).build();
        var g=ThermalTransportTest.geometry(f,1,.035);var t=new ThermalTransport(g,ThermalTransport.Settings.defaults());
        t.setTemperature(0,80);double before=t.energy();
        ThermalTransportTest.ready(t.advance(1,new double[f.faces()],ThermalTransportTest.filled(f.faces(),20)));
        assertEquals(80,t.temperature(0));assertEquals(before,t.energy());
    }

    @Test void materialAtTheDomainBoundaryIsInsulatedFromUnrepresentedMaterial() {
        var f=new FlowGeometry.Builder(1,1,1).cell(0,0,0,FlowShape.FULL).openExterior(true).build();
        var g=ThermalTransportTest.geometry(f,1,.035);var t=new ThermalTransport(g,ThermalTransport.Settings.defaults());
        t.setTemperature(g.materialState(0),80);double before=t.energy();
        ThermalTransportTest.ready(t.advance(10,new double[f.faces()],new double[f.faces()]));
        assertEquals(80,t.temperature(g.materialState(0)),1e-12);assertEquals(before,t.energy(),1e-10);
    }

    @Test void fluidOccupancyHasNoThermalStateOrConductiveBridgeInThisCheckpoint() {
        // Historical-constructor control. The explicit fluid-material path is covered by ThermalFluidTest;
        // CoupledAirGrid now refuses this omitted-fluid representation.
        var f=new FlowGeometry.Builder(3,1,1).cell(1,0,0,FlowShape.WATER).build();
        var g=ThermalTransportTest.geometry(f,1,.035);assertEquals(2,g.states());assertEquals(-1,g.materialState(1));
        var t=new ThermalTransport(g,ThermalTransport.Settings.defaults());t.setTemperature(0,80);t.setTemperature(1,20);
        ThermalTransportTest.ready(t.advance(10,new double[f.faces()],new double[f.faces()]));
        assertEquals(80,t.temperature(0));assertEquals(20,t.temperature(1));
    }
}
