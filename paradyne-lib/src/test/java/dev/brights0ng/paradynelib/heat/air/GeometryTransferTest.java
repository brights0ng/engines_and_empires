// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeometryTransferTest {
    private static ThermalGeometry thermal(FlowGeometry flow) {
        double[] values=new double[flow.nx*flow.ny*flow.nz];Arrays.fill(values,1);
        return new ThermalGeometry(flow,values,values,values,values,ThermalGeometry.Coefficients.legacyMaterials());
    }
    private static double energy(ThermalGeometry g,double[] t) { double sum=0;for(int i=0;i<t.length;i++) sum+=g.capacity(i)*t[i];return sum; }
    private static double[] temperatures(ThermalGeometry g) { double[] t=new double[g.states()];for(int i=0;i<t.length;i++) t[i]=21+i*.3;return t; }
    private static double mappedEnergy(ThermalGeometry g,GeometryTransfer.Mapping m) { double sum=0;for(int i=0;i<g.states();i++) sum+=g.capacity(i)*m.temperature(i);return sum; }
    @Test void identicalGeometryRetainsAirSolidFluidTemperaturesAndCompatibleFaces() {
        var flow=new FlowGeometry.Builder(3,2,2).cell(1,0,0,FlowShape.FULL).cell(2,0,1,FlowShape.WATER).build();
        var g=thermal(flow);var t=temperatures(g);double[] flux=new double[flow.faces()];
        for(int f=0;f<flux.length;f++) flux[f]=.2*flow.faceArea(f);
        var mapped=GeometryTransfer.map(g,g,t,flux,20);
        for(int i=0;i<t.length;i++) assertEquals(t[i],mapped.temperature(i),1e-12);
        for(int f=0;f<flux.length;f++) assertEquals(.2,mapped.faceVelocity(f),1e-12);
        assertEquals(0,mapped.incomingEnergy);assertEquals(0,mapped.outgoingEnergy);
    }
    @Test void splitPocketsDoNotAverageUnrelatedOldAirAndMergingUsesOnlyLocalOverlap() {
        var split=new FlowShape.Builder().solidBox(7,0,0,9,16,16).build();
        var old=thermal(new FlowGeometry.Builder(1,1,1).cell(0,0,0,split).build());
        var next=thermal(new FlowGeometry.Builder(1,1,1).build());
        double[] t={10,40,25};
        var merged=GeometryTransfer.map(old,next,t,new double[old.flow.faces()],20);
        assertEquals(10*7.0/16+40*7.0/16+20*2.0/16,merged.temperature(0),1e-12);
        var repartitioned=GeometryTransfer.map(next,old,new double[]{30},new double[next.flow.faces()],20);
        assertEquals(30,repartitioned.temperature(0));assertEquals(30,repartitioned.temperature(1));
        assertEquals(20,repartitioned.temperature(old.materialState(0)));
    }
    @Test void rotatingThinMaterialRetainsItsOwnedStorageAndBooksAirGeometryExchange() {
        var closed=new FlowShape.Builder().solidBox(0,0,0,3,16,16).build();
        var opened=new FlowShape.Builder().solidBox(0,0,0,16,16,3).build();
        var a=thermal(new FlowGeometry.Builder(2,1,1).cell(0,0,0,closed).build());
        var b=thermal(new FlowGeometry.Builder(2,1,1).cell(0,0,0,opened).build());
        double[] t=new double[a.states()];Arrays.fill(t,40);t[a.materialState(0)]=65;
        var mapped=GeometryTransfer.map(a,b,t,new double[a.flow.faces()],20);
        assertEquals(65,mapped.temperature(b.materialState(0)),1e-12);
        assertEquals(40,mapped.temperature(b.flow.nodeAt(1.5,.5,.5)),1e-12);
        assertTrue(mapped.incomingEnergy>0);assertTrue(mapped.outgoingEnergy>0);
        assertEquals(energy(a,t)+mapped.incomingEnergy-mapped.outgoingEnergy,mappedEnergy(b,mapped),1e-9);
    }
    @Test void closingAndReopeningReprojectsWithoutLeakageOrInventedUniformWind() {
        var a=thermal(new FlowGeometry.Builder(3,2,2).build());
        var grid=new CoupledAirGrid(a,FlowBoundary.wind(a.flow,1,0,0),FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());
        for(int i=0;i<a.states();i++) grid.setTemperature(i,30);
        assertTrue(grid.initialize(1,0,0).ready());double[] flux=new double[a.flow.faces()];for(int f=0;f<flux.length;f++) flux[f]=grid.faceFlux(f);
        var builder=new FlowGeometry.Builder(3,2,2);for(int y=0;y<2;y++) for(int z=0;z<2;z++) builder.cell(1,y,z,FlowShape.FULL);
        var b=thermal(builder.build());var mapped=GeometryTransfer.map(a,b,temperatures(a),flux,20);
        var next=new CoupledAirGrid(b,FlowBoundary.wind(b.flow,0,0,0),FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());
        var projected=mapped.initialize(next);assertTrue(projected.ready(),projected.toString());assertTrue(projected.pressure().maxDivergence()<1e-7);
        for(int i=0;i<b.states();i++) assertEquals(mapped.temperature(i),next.temperature(i));
        assertThrows(IllegalArgumentException.class,()->mapped.initialize(next));
    }
    @Test void newFacesOnlyInheritMotionOnPatchesThatWerePreviouslyOpen() {
        var half=new FlowShape.Builder().solidBox(0,0,0,16,8,16).build();
        var a=thermal(new FlowGeometry.Builder(2,1,1).cell(0,0,0,half).build());
        var b=thermal(new FlowGeometry.Builder(2,1,1).build());double[] flux=new double[a.flow.faces()];
        for(int f=0;f<flux.length;f++) flux[f]=2*a.flow.faceArea(f);
        var mapped=GeometryTransfer.map(a,b,temperatures(a),flux,20);
        int internal=-1;for(int f=0;f<b.flow.faces();f++) if(!b.flow.exterior(f)) internal=f;
        assertTrue(internal>=0);assertEquals(1,mapped.faceVelocity(internal),1e-12);
    }
    @Test void rejectsBadInputsAndUnrelatedTargetOwners() {
        var a=thermal(new FlowGeometry.Builder(1,1,1).build());var b=thermal(new FlowGeometry.Builder(2,1,1).build());
        assertThrows(IllegalArgumentException.class,()->GeometryTransfer.map(a,b,new double[]{20},new double[a.flow.faces()],20));
        assertThrows(IllegalArgumentException.class,()->GeometryTransfer.map(a,a,new double[]{Double.NaN},new double[a.flow.faces()],20));
        var mapped=GeometryTransfer.map(a,a,new double[]{20},new double[a.flow.faces()],20);
        var different=thermal(new FlowGeometry.Builder(1,1,1).build());
        var unrelated=new CoupledAirGrid(different,FlowBoundary.wind(different.flow,0,0,0),FlowSettings.DEFAULT,ThermalTransport.Settings.defaults());
        assertThrows(IllegalArgumentException.class,()->mapped.initialize(unrelated));
    }
}
