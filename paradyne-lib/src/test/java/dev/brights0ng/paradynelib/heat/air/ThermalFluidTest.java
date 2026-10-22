// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThermalFluidTest {
    private static final ThermalGeometry.Coefficients C=new ThermalGeometry.Coefficients(0,.2,1,3.5);
    private static double[] filled(FlowGeometry f,double v) { return ThermalTransportTest.filled(f.shapes.length,v); }
    private static ThermalGeometry geometry(FlowGeometry f) {
        return new ThermalGeometry(f,filled(f,1),filled(f,1),filled(f,2),filled(f,2),C);
    }
    private static double link(ThermalGeometry g,int a,int b) {
        double result=0;
        for(int e=0;e<g.low.length;e++) if((g.low[e]==a && g.high[e]==b) || (g.low[e]==b && g.high[e]==a)) { result+=g.conductance[e]; }
        return result;
    }
    private static void analyticPair(ThermalGeometry g,int a,int b,double k) {
        for(var mode:ThermalTransport.Mode.values()) {
            var t=new ThermalTransport(g,ThermalTransport.Settings.defaults(),mode);
            for(int i=0;i<g.states();i++) { t.setTemperature(i,20); }
            t.setTemperature(b,80);double before=t.energy(),dt=.5,ca=g.capacity(a),cb=g.capacity(b);
            double difference=60/(1+dt*k*(1/ca+1/cb));
            double expectedA=(ca*20+cb*80-cb*difference)/(ca+cb);
            var r=t.advance(dt,new double[g.flow.faces()],ThermalTransportTest.filled(g.flow.faces(),20));
            ThermalTransportTest.ready(r);assertEquals(0,r.exteriorEnergy());assertEquals(before,t.energy(),1e-8);
            assertEquals(expectedA,t.temperature(a),1e-8);assertEquals(expectedA+difference,t.temperature(b),1e-8);
        }
    }

    @Test void airFluidExchangeMatchesTwoCapacityBackwardEulerWithoutAnyAirPassage() {
        for(int axis=0;axis<3;axis++) for(boolean reverse:new boolean[]{false,true}) {
            var b=new FlowGeometry.Builder(axis==0?2:1,axis==1?2:1,axis==2?2:1);
            int position=reverse?0:1;b.cell(axis==0?position:0,axis==1?position:0,axis==2?position:0,FlowShape.WATER);
            var f=b.build();var g=geometry(f);int wet=reverse?0:1;
            assertEquals(1,f.nodes());assertEquals(0,f.faces());assertEquals(2,g.states());
            assertEquals(7,g.capacity(g.fluidState(wet)));assertEquals(0,g.unrepresentedFluidCells());
            analyticPair(g,0,g.fluidState(wet),1/(C.film()+.5/2));
        }
    }

    @Test void fluidFluidAndFluidSolidContactsUseTheExistingSeriesResistanceInBothDirections() {
        for(int axis=0;axis<3;axis++) for(boolean reverse:new boolean[]{false,true}) for(boolean solid:new boolean[]{false,true}) {
            var b=new FlowGeometry.Builder(axis==0?2:1,axis==1?2:1,axis==2?2:1);
            b.cell(0,0,0,reverse && solid?FlowShape.FULL:FlowShape.WATER);
            b.cell(axis==0?1:0,axis==1?1:0,axis==2?1:0,!reverse && solid?FlowShape.FULL:FlowShape.WATER);
            var f=b.build();var g=geometry(f);
            int a=reverse && solid?g.materialState(0):g.fluidState(0);
            int end=!reverse && solid?g.materialState(1):g.fluidState(1);
            assertEquals(0,f.nodes());assertEquals(0,f.faces());assertEquals(2,g.states());
            analyticPair(g,a,end,1/(.25+(solid?.5:.25)));
        }
    }

    @Test void waterloggedCellHasSeparateAirSolidAndFluidStorageAndCorrectInternalContact() {
        var shape=new FlowShape.Builder().solidBox(0,0,0,16,4,16).fluidBox(0,0,0,16,8,16).build();
        var f=new FlowGeometry.Builder(1,1,1).cell(0,0,0,shape).build();var g=geometry(f);
        assertEquals(3,g.states());assertEquals(.5,g.capacity(0));assertEquals(.875,g.capacity(g.materialState(0)));
        assertEquals(1.75,g.capacity(g.fluidState(0)));assertNotEquals(g.materialState(0),g.fluidState(0));
        assertEquals(2,g.low.length);double conductance=0;
        for(double k:g.conductance) { conductance+=k; }
        assertEquals(1/(.2+.25)+1/(.5+.25),conductance,1e-12);
        var t=ThermalTransportTest.transport(g);ThermalTransportTest.fill(t,g,20);t.setTemperature(g.materialState(0),80);
        double before=t.energy();ThermalTransportTest.ready(t.advance(.5,new double[0],new double[0]));
        assertTrue(t.temperature(g.fluidState(0))>20);assertTrue(t.temperature(0)>20);
        assertEquals(before,t.energy(),1e-8);
    }

    @Test void fluidDividerTransfersHeatButNeverConnectsTheTwoAirPockets() {
        var shape=new FlowShape.Builder().fluidBox(7,0,0,9,16,16).build();
        var f=new FlowGeometry.Builder(1,1,1).cell(0,0,0,shape).build();var g=geometry(f);
        assertEquals(2,f.nodes());assertEquals(0,f.faces());assertEquals(3,g.states());
        var t=ThermalTransportTest.transport(g);ThermalTransportTest.fill(t,g,20);t.setTemperature(0,80);
        double before=t.energy();ThermalTransportTest.ready(t.advance(.5,new double[0],new double[0]));
        assertTrue(t.temperature(1)>20);assertEquals(before,t.energy(),1e-8);
        var old=new ThermalGeometry(f,filled(f,1),filled(f,1),C);
        assertEquals(1,old.unrepresentedFluidCells());assertEquals(-1,old.fluidState(0));
        assertThrows(IllegalArgumentException.class,()->new CoupledAirGrid(old,FlowBoundary.builder(f).build(),
                FlowSettings.DEFAULT,ThermalTransport.Settings.defaults()));
    }

    @Test void partialFaceContactsUseActualOverlappingPatchesForEveryAxisAndOrientation() {
        for(int axis=0;axis<3;axis++) for(boolean reverse:new boolean[]{false,true}) {
            int split=(axis+1)%3;
            var lower=new FlowShape.Builder().fluidBox(0,0,0,split==0?8:16,split==1?8:16,split==2?8:16).build();
            var b=new FlowGeometry.Builder(axis==0?2:1,axis==1?2:1,axis==2?2:1);
            b.cell(0,0,0,reverse?FlowShape.FULL:lower);
            b.cell(axis==0?1:0,axis==1?1:0,axis==2?1:0,reverse?lower:FlowShape.FULL);
            var f=b.build();var g=geometry(f);int wet=reverse?1:0,solid=reverse?0:1;
            assertEquals(.5/(.25+.5),link(g,g.fluidState(wet),g.materialState(solid)),1e-12);
            assertEquals(.5/(.2+.5),link(g,0,g.materialState(solid)),1e-12);
            assertEquals(1/(.2+.25),link(g,0,g.fluidState(wet)),1e-12);
            var upper=new FlowShape.Builder().fluidBox(split==0?8:0,split==1?8:0,split==2?8:0,16,16,16).build();
            var crossed=new FlowGeometry.Builder(axis==0?2:1,axis==1?2:1,axis==2?2:1)
                    .cell(0,0,0,lower).cell(axis==0?1:0,axis==1?1:0,axis==2?1:0,upper).build();
            var h=geometry(crossed);assertEquals(0,crossed.faces());
            assertEquals(0,link(h,h.fluidState(0),h.fluidState(1)));
            assertEquals(.5/(.2+.25),link(h,0,h.fluidState(1)),1e-12);
            assertEquals(.5/(.2+.25),link(h,1,h.fluidState(0)),1e-12);
        }
    }

    @Test void anEnclosedFluidSourceUsesOneStorageOwnerAndPerFaceRoutingStillNeedsAnAirFace() {
        var f=new FlowGeometry.Builder(1,1,1).cell(0,0,0,FlowShape.WATER).openExterior(true).build();var g=geometry(f);
        var sources=ThermalSources.fromBlock(g,0,14,21,false);
        assertEquals(1,sources.length);assertEquals(g.fluidState(0),sources[0].state());
        assertEquals(0,ThermalSources.fromBlock(g,0,14,21,true).length);
        var t=ThermalTransportTest.transport(g);ThermalTransportTest.fill(t,g,20);t.setSources(sources);
        var r=t.advance(1,new double[0],new double[0]);ThermalTransportTest.ready(r);
        assertEquals(21,t.temperature(g.fluidState(0)));assertEquals(7,r.sourceEnergy());assertEquals(0,r.exteriorEnergy());
    }

    @Test void anOuterFluidSurfaceDoesNotAcquireAWeatherBathOrLoseItsStoredHeatOnReferenceChanges() {
        var shape=new FlowShape.Builder().fluidBox(0,0,0,16,8,16).build();
        var f=new FlowGeometry.Builder(1,1,1).cell(0,0,0,shape).openExterior(true).build();var g=geometry(f);
        var t=ThermalTransportTest.transport(g);ThermalTransportTest.fill(t,g,20);t.setTemperature(g.fluidState(0),80);
        double before=t.energy();t.setMixingReference(new double[]{-30});
        assertEquals(80,t.temperature(g.fluidState(0)));assertEquals(20,t.temperature(0));
        var r=t.advance(.5,new double[f.faces()],ThermalTransportTest.filled(f.faces(),-30));
        ThermalTransportTest.ready(r);assertEquals(0,r.exteriorEnergy());assertEquals(before,t.energy(),1e-8);
        assertTrue(t.temperature(0)>20);assertTrue(t.temperature(g.fluidState(0))<80);
    }

    @Test void fluidCapacitiesAndConductancesAreValidatedAndConstructionDoesNotRetainCallerArrays() {
        var f=new FlowGeometry.Builder(1,1,1).cell(0,0,0,FlowShape.WATER).build();
        for(double invalid:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->new ThermalGeometry(f,filled(f,1),filled(f,1),filled(f,invalid),filled(f,2),C));
        }
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,()->new ThermalGeometry(f,filled(f,1),filled(f,1),filled(f,2),filled(f,invalid),C));
        }
        assertThrows(IllegalArgumentException.class,()->new ThermalGeometry(f,filled(f,1),filled(f,1),new double[0],filled(f,2),C));
        assertThrows(IllegalArgumentException.class,()->new ThermalGeometry(f,filled(f,1),filled(f,1),null,filled(f,2),C));
        var tiny=new FlowGeometry.Builder(1,1,1).cell(0,0,0,new FlowShape.Builder().fluidBox(0,0,0,1,1,1).build()).build();
        assertThrows(IllegalArgumentException.class,()->new ThermalGeometry(tiny,filled(tiny,1),filled(tiny,1),filled(tiny,2),filled(tiny,Double.MIN_VALUE),C));
        double[] k=filled(f,2),capacity=filled(f,2);var g=new ThermalGeometry(f,filled(f,1),filled(f,1),k,capacity,C);
        k[0]=0;capacity[0]=999;assertEquals(7,g.capacity(g.fluidState(0)));
    }

    @Test void dryGeometryHasExactlyTheSameThermalOperatorWithEitherConstructor() {
        var h=CoupledAirBenchmark.house(1);var f=h.flow();double[] k=filled(f,.8),c=filled(f,.6);
        var old=new ThermalGeometry(f,k,c,ThermalGeometry.Coefficients.legacyMaterials());
        var wet=new ThermalGeometry(f,k,c,filled(f,2),filled(f,2),ThermalGeometry.Coefficients.legacyMaterials());
        assertArrayEquals(old.capacity,wet.capacity);assertArrayEquals(old.low,wet.low);assertArrayEquals(old.high,wet.high);
        assertArrayEquals(old.conductance,wet.conductance);assertEquals(0,wet.unrepresentedFluidCells());
    }

    @Test void storedFluidHeatDrivesCoupledBuoyancyAndRollsBackWithTheRestOfTheFieldOnFailure() {
        var f=new FlowGeometry.Builder(3,4,3).cell(1,0,1,FlowShape.WATER).build();var g=geometry(f);
        var bg=ThermalTransportTest.filled(f.nodes(),20);var ext=new double[f.faces()];
        var t=CoupledAirGridTest.grid(g,FlowBoundary.builder(f).build(),FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());
        t.setSources(new ThermalTransport.Source(g.fluidState(f.index(1,0,1)),14,65));
        double initial=t.energy(),delivered=0;
        for(int i=0;i<20;i++) { var r=t.step(.5,1,bg,ext);CoupledAirGridTest.ready(r);delivered+=r.sourceEnergy(); }
        assertEquals(initial+delivered,t.energy(),1e-6);
        assertTrue(t.componentVelocity(f.nodeAt(1.5,1.5,1.5),1)>1e-5);
        var limited=new FlowSettings(512,1e-8,1e-10,1e-7,1,0,1,1e-6,3000);
        var rejected=CoupledAirGridTest.grid(g,FlowBoundary.builder(f).build(),limited,ThermalTransport.Settings.defaults());
        rejected.setSources(new ThermalTransport.Source(g.fluidState(f.index(1,0,1)),14,65));
        double before=rejected.energy();var r=rejected.step(.5,1,bg,ext);
        assertEquals(CoupledAirGrid.Status.FLOW_FAILED,r.status());assertEquals(0,r.sourceEnergy());
        assertEquals(before,rejected.energy());
        for(int i=0;i<g.states();i++) { assertEquals(20,rejected.temperature(i)); }
        for(int e=0;e<f.faces();e++) { assertEquals(0,rejected.faceFlux(e)); }
    }
}
