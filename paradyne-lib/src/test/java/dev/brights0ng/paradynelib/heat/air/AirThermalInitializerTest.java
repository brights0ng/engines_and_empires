// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirThermalInitializerTest {
    private static double[] filled(int count,double value) {var out=new double[count];Arrays.fill(out,value);return out;}
    private static ThermalGeometry geometry() {
        var mixed=new FlowShape.Builder().solidBox(0,0,0,16,4,16).fluidBox(0,0,0,16,8,16).build();
        var f=new FlowGeometry.Builder(5,3,3).openExterior(true).cell(2,1,1,mixed).cell(1,1,1,FlowShape.WATER).build();
        return new ThermalGeometry(f,filled(f.cells(),1),filled(f.cells(),1),filled(f.cells(),2),filled(f.cells(),2),ThermalGeometry.Coefficients.legacyMaterials());
    }
    @Test void sourceFreeUniformFieldsNeedNoInventedHeatAndRespectSliceAndDeadlineGuards() {
        var g=geometry();var f=g.flow;var init=new AirThermalInitializer(g,FlowBoundary.wind(f,1,0,.5),filled(g.states(),20),20,new ThermalTransport.Source[0],1,.5);
        assertEquals(0,init.advance(0,Long.MAX_VALUE));assertEquals(0,init.advance(256,System.nanoTime()-1));assertEquals(0,init.quality().iterations());assertFalse(init.ready());assertThrows(IllegalStateException.class,init::solution);
        int jobs=0;while(!init.ready() && jobs++<10000) assertTrue(init.advance(8,Long.MAX_VALUE)<=8);
        assertTrue(init.ready());for(int st=0;st<g.states();st++) assertEquals(20,init.solution().temperature(st),1e-8);
        assertTrue(init.quality().iterations()<=AirThermalInitializer.MAX_ITERATIONS);assertTrue(init.solution().quality.verificationDrift()<1e-3);
    }
    @Test void heatedAndCooledFluidBearingWindCasesMatchIndependentCoupledTimeIntegration() {
        var g=geometry();var f=g.flow;double worst=0;
        for(int direction=0;direction<8;direction++) for(int sign:new int[]{-1,1}) {
            int[][] directions={{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};double norm=Math.hypot(directions[direction][0],directions[direction][1]),x=directions[direction][0]/norm,z=directions[direction][1]/norm;var boundary=FlowBoundary.wind(f,x,0,z);
            int node=f.nodeAt(2.5,2.5,1.5);var sources=new ThermalTransport.Source[]{new ThermalTransport.Source(node,sign*4,sign>0?80:0)};
            var control=new CoupledAirGrid(g,boundary,FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());for(int st=0;st<g.states();st++) control.setTemperature(st,20);control.setSources(sources);assertTrue(control.initialize(x,0,z).ready());
            var bg=filled(f.nodes(),20);var ext=filled(f.faces(),20);double[] last=new double[g.states()],previousMean=new double[g.states()],lastMean=new double[g.states()],minimum=filled(g.states(),Double.POSITIVE_INFINITY),maximum=filled(g.states(),Double.NEGATIVE_INFINITY);
            for(int step=0;step<12000;step++) {if(step==11800) for(int st=0;st<last.length;st++) last[st]=control.temperature(st);assertTrue(control.step(.5,1,bg,ext).ready());if(step>=11600 && step<11800) for(int st=0;st<last.length;st++) previousMean[st]+=control.temperature(st)/200;
                if(step>=11800) for(int st=0;st<last.length;st++) {lastMean[st]+=control.temperature(st)/200;minimum[st]=Math.min(minimum[st],control.temperature(st));maximum[st]=Math.max(maximum[st],control.temperature(st));}}
            double referenceRange=0,referenceDrift=0;for(int st=0;st<last.length;st++) {referenceRange=Math.max(referenceRange,maximum[st]-minimum[st]);referenceDrift=Math.max(referenceDrift,Math.abs(last[st]-control.temperature(st)));}
            System.out.printf("Wind control direction=%d sign=%d final range=%.9f drift=%.9f%n",direction,sign,referenceRange,referenceDrift);
            var initial=filled(g.states(),20);var init=new AirThermalInitializer(g,boundary,initial,20,sources,x,z);
            int jobs=0;try {while(!init.ready() && jobs++<10000) init.advance(256,Long.MAX_VALUE);} catch(RuntimeException e) {throw new AssertionError("direction="+direction+", sign="+sign,e);}assertTrue(init.ready(),"direction="+direction+", sign="+sign);
            double error=0,drift=0;for(int st=0;st<g.states();st++) {error=Math.max(error,Math.abs(init.solution().temperature(st)-control.temperature(st)));drift=Math.max(drift,Math.abs(last[st]-control.temperature(st)));}
            // The independent control can retain a bounded cycle; qualify complete window means and ranges.
            // A same-phase endpoint can conceal it, while a different phase is not long-term drift.
            double meanDrift=0;for(int st=0;st<last.length;st++) meanDrift=Math.max(meanDrift,Math.abs(previousMean[st]-lastMean[st]));
            assertTrue(meanDrift<.001,"Independent control's consecutive 100-second thermal means must settle");
            assertTrue(referenceRange<.01,"Independent control must retain a bounded sub-0.01 C thermal cycle");assertTrue(error<.01,"All thermal phases must match the independent control: "+error);worst=Math.max(worst,error);
        }
        System.out.printf(java.util.Locale.ROOT,"Natural initializer wind/fluid controls: 16 cases, maximum all-state error %.9f C%n",worst);
    }
    @Test void incomingStateIsImmutableAndSolutionRequiresCompatibleGeometryAndFreshProjection() {
        var g=geometry();var f=g.flow;var initial=filled(g.states(),20);var init=new AirThermalInitializer(g,FlowBoundary.wind(f,0,0,0),initial,20,new ThermalTransport.Source[0],0,0);Arrays.fill(initial,999);
        for(int i=0;i<1000 && !init.ready();i++) init.advance(256,Long.MAX_VALUE);assertTrue(init.ready());
        var grid=new CoupledAirGrid(g,FlowBoundary.wind(f,0,0,0),FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());var t=filled(g.states(),20);assertTrue(init.solution().initialize(grid,t).ready());assertTrue(grid.initialized());assertEquals(20,grid.temperature(0));
        assertThrows(IllegalArgumentException.class,()->init.solution().initialize(grid,t));
        var other=geometry();var wrong=new CoupledAirGrid(other,FlowBoundary.wind(other.flow,0,0,0),FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());assertThrows(IllegalArgumentException.class,()->init.solution().initialize(wrong,t));
    }
}
