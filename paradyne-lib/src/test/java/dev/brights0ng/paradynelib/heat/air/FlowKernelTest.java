// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowKernelTest {
    @Test void allKernelsAgreeOnCoupledSourcesAndMaterialStorageAtSupportedBenchmarkSteps() {
        var pane=new FlowShape.Builder().solidBox(7,0,0,9,16,16).build();
        var f=new FlowGeometry.Builder(6,4,3).openExterior(true).cell(2,1,1,pane).build();
        var g=ThermalTransportTest.geometry(f,1,.035);
        for(double sign:new double[]{1,-1}) {
            var kernels=FlowGrid.Kernel.values();var grids=new CoupledAirGrid[kernels.length];
            for(int m=0;m<grids.length;m++) {
                grids[m]=new CoupledAirGrid(g,FlowBoundary.wind(f,sign*2,0,sign*.5),FlowSettings.DEFAULT,
                        ThermalTransport.Settings.defaults(),ThermalTransport.Mode.COMPACT_CACHED,true,kernels[m]);
                for(int i=0;i<g.states();i++) { grids[m].setTemperature(i,20+Math.sin(i)); }
                FlowGridTest.ready(grids[m].initialize(sign*2,0,sign*.5));
                grids[m].setSources(new ThermalTransport.Source(0,20,25),new ThermalTransport.Source(5,-10,15));
            }
            for(int step=0;step<18;step++) {
                double dt=step%3==0?.25:step%3==1?.5:1,scale=step%3==0?.5:step%3==1?1:2;
                var bg=ThermalTransportTest.filled(f.nodes(),step<9?20:22);
                var ext=ThermalTransportTest.filled(f.faces(),step<9?20:22);
                for(var grid:grids) { CoupledAirGridTest.ready(grid.step(dt,scale,bg,ext)); }
                for(int m=1;m<grids.length;m++) {
                    for(int i=0;i<g.states();i++) { assertEquals(grids[0].temperature(i),grids[m].temperature(i),1e-7); }
                    for(int face=0;face<f.faces();face++) { assertEquals(grids[0].faceFlux(face),grids[m].faceFlux(face),1e-7); }
                }
            }
        }
    }

    @Test void specializedPressureMatchesReferenceWithIrregularRowsAndWarmStarts() {
        var cross=new FlowShape.Builder().solidBox(7,0,0,9,16,16).solidBox(0,7,0,16,9,16).build();
        for(boolean open:new boolean[]{false,true}) {
            var g=new FlowGeometry.Builder(5,4,3).openExterior(open).cell(1,1,1,cross).cell(3,1,1,cross).build();
            var boundary=open?FlowBoundary.wind(g,2,0,.5):FlowBoundary.builder(g).build();
            for(var method:PressureProjector.Preconditioner.values()) {
                var a=new PressureProjector(g,boundary,method,false);
                var b=new PressureProjector(g,boundary,method,true);
                double[] tentative=new double[g.faces()],old=tentative.clone(),specialized=tentative.clone();
                for(int step=0;step<5;step++) {
                    for(int f=0;f<tentative.length;f++) { tentative[f]=Math.sin(f*.3+step*.2); }
                    var qa=a.project(tentative,.5,old,FlowSettings.DEFAULT);
                    var qb=b.project(tentative,.5,specialized,FlowSettings.DEFAULT);
                    assertTrue(qa.ready(),qa.toString());assertEquals(qa,qb);
                    assertArrayEquals(old,specialized);
                    for(int i=0;i<g.nodes();i++) { assertEquals(a.pressureCorrection(i),b.pressureCorrection(i)); }
                }
            }
        }
    }

    @Test void cachedCrossingsMatchReferenceAcrossRandomPartialGeometryAndLongTraces() {
        var pane=new FlowShape.Builder().solidBox(7,0,0,9,16,16).build();
        var slab=new FlowShape.Builder().solidBox(0,0,0,16,8,16).build();
        var fence=new FlowShape.Builder().solidBox(6,0,6,10,16,10).solidBox(0,6,7,16,10,9).build();
        var g=new FlowGeometry.Builder(9,7,5).cell(4,3,2,pane).cell(2,2,1,slab).cell(6,4,3,fence)
                .cell(3,3,2,FlowShape.FULL).cell(5,2,2,FlowShape.WATER).build();
        var random=new Random(0x51afe);
        for(int i=0;i<20_000;i++) {
            double x=random.nextDouble()*g.nx,y=random.nextDouble()*g.ny,z=random.nextDouble()*g.nz;
            if(g.nodeAt(x,y,z)<0) { i--;continue; }
            compareTrace(g,x,y,z,random.nextDouble()*16-8,random.nextDouble()*16-8,random.nextDouble()*16-8);
        }
    }

    @Test void cachedCrossingsRetainCornerThinWallAndExtremeDirectionHandling() {
        var pane=new FlowShape.Builder().solidBox(7,0,0,9,16,16).build();
        var g=new FlowGeometry.Builder(5,5,3).cell(2,2,1,pane).cell(1,2,1,FlowShape.FULL).build();
        for(double tweak:new double[]{0,1e-13,-1e-13,1e-9,-1e-9}) {
            compareTrace(g,1.5,1.5,1.5,2,2+tweak,0);
            compareTrace(g,3.5,3.5,1.5,-2,-2+tweak,0);
        }
        for(double tiny:new double[]{0,Double.MIN_VALUE,-Double.MIN_VALUE,Double.MIN_NORMAL,-Double.MIN_NORMAL}) {
            compareTrace(g,.5,.5,.5,3,tiny,0);
            compareTrace(g,.5,.5,.5,tiny,0,0);
        }
        var t=new FlowGeometry.Trace();g.trace(1.5,1.5,1.5,2,2,0,t,true);
        assertTrue(t.blocked);assertTrue(t.x<2);assertTrue(t.y<2);
    }

    private static void compareTrace(FlowGeometry g,double x,double y,double z,double dx,double dy,double dz) {
        var a=new FlowGeometry.Trace();var b=new FlowGeometry.Trace();
        g.trace(x,y,z,dx,dy,dz,a,false);g.trace(x,y,z,dx,dy,dz,b,true);
        assertEquals(a.blocked,b.blocked);assertEquals(a.node,b.node);
        assertEquals(a.x,b.x,1e-10);assertEquals(a.y,b.y,1e-10);assertEquals(a.z,b.z,1e-10);
        assertEquals(b.node,g.nodeAt(b.x,b.y,b.z));
    }
}
