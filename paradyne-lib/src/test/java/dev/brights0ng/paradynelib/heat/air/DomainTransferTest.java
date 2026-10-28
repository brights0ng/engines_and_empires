// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.paradynelib.heat.air.DomainTransfer.*;

class DomainTransferTest {
    private static ThermalGeometry geometry(int nx,int ny,int nz,FlowShape... shapes) {
        var b=new FlowGeometry.Builder(nx,ny,nz).openExterior(true);
        for(int i=0;i<shapes.length;i++) b.cell(i%nx,(i/nx)%ny,i/(nx*ny),shapes[i]);
        var f=b.build();var k=new double[nx*ny*nz];Arrays.fill(k,1);
        return new ThermalGeometry(f,k,k,k,k,new ThermalGeometry.Coefficients(.1,.2,1,3.5));
    }
    private static double[] temperatures(ThermalGeometry g,double start) { var t=new double[g.states()];for(int i=0;i<t.length;i++) t[i]=start+i;return t; }
    private static double energy(ThermalGeometry g,Mapping m) { double e=0;for(int i=0;i<g.states();i++) e+=g.capacity(i)*m.temperature(i);return e; }
    @Test void translatedResizesRetainTemperaturesInEveryAxisAndInitializeOnlyNewCells() {
        for(int axis=0;axis<3;axis++) {
            var old=geometry(axis==0?2:1,axis==1?2:1,axis==2?2:1);var next=geometry(axis==0?3:1,axis==1?3:1,axis==2?3:1);
            var origin=new Origin(-9,-5,-3);var shifted=new Origin(origin.x()-(axis==0?1:0),origin.y()-(axis==1?1:0),origin.z()-(axis==2?1:0));
            var source=new Donor(old,origin,new double[]{50,80},new double[old.flow.faces()]);var map=map(List.of(source),next,shifted,20);
            assertEquals(20,map.temperature(0));assertEquals(50,map.temperature(1));assertEquals(80,map.temperature(2));
            assertEquals(130,map.retainedEnergy);assertEquals(20,map.incomingEnergy);assertEquals(150,energy(next,map));
        }
    }
    @Test void mergeAndSplitUseDisjointOwnershipAndAccountForOutgoingEnergyExactly() {
        var two=geometry(2,1,1);var six=geometry(6,1,1);
        var a=new Donor(two,new Origin(0,0,0),new double[]{30,40},new double[two.flow.faces()]);
        var b=new Donor(two,new Origin(4,0,0),new double[]{70,80},new double[two.flow.faces()]);
        var merged=map(List.of(a,b),six,new Origin(0,0,0),20);
        assertEquals(220,merged.retainedEnergy);assertEquals(40,merged.incomingEnergy);assertEquals(260,energy(six,merged));
        var old=new Donor(six,new Origin(0,0,0),new double[]{30,40,50,60,70,80},new double[six.flow.faces()]);
        var left=map(List.of(old),two,new Origin(0,0,0),20);var right=map(List.of(old),two,new Origin(4,0,0),20);
        double outgoing=old.energy()-left.retainedEnergy-right.retainedEnergy;
        assertEquals(110,outgoing);assertEquals(old.energy(),energy(two,left)+energy(two,right)+outgoing);
    }
    @Test void partialAirSolidAndFluidKeepTheirOwnStorageAcrossOriginAndIndexChanges() {
        var partial=new FlowShape.Builder().solidBox(0,0,0,16,4,16).fluidBox(0,0,0,16,8,16).build();
        var old=geometry(2,1,1,partial,FlowShape.WATER);var next=geometry(3,1,1,FlowShape.EMPTY,partial,FlowShape.WATER);
        var t=temperatures(old,35);var donor=new Donor(old,new Origin(1,0,0),t,new double[old.flow.faces()]);var mapped=map(List.of(donor),next,new Origin(0,0,0),20);
        assertEquals(t[old.materialState(0)],mapped.temperature(next.materialState(1)));
        assertEquals(t[old.fluidState(0)],mapped.temperature(next.fluidState(1)));assertEquals(t[old.fluidState(1)],mapped.temperature(next.fluidState(2)));
        assertEquals(t[0],mapped.temperature(next.flow.starts[1]));assertEquals(donor.energy(),mapped.retainedEnergy,1e-12);
    }
    @Test void onlyCompatibleFacesCarryMotionAndNewOwnerAlwaysProjects() {
        var old=geometry(2,1,1);var next=geometry(3,1,1);var f=old.flow;
        var flow=new FlowGrid(f,FlowBoundary.wind(f,1,0,0),FlowGridTest.accurate());flow.seedUniform(1,0,0);FlowGridTest.ready(flow.projectInitial());
        var flux=new double[f.faces()];for(int i=0;i<flux.length;i++) flux[i]=flow.faceFlux(i);
        var mapped=map(List.of(new Donor(old,new Origin(0,0,0),new double[]{50,50},flux)),next,new Origin(0,0,0),20);
        for(int i=0;i<next.flow.faces();i++) if(next.flow.axis[i]==0 && next.flow.fx[i]==2) assertEquals(0,mapped.faceVelocity(i),"Old exterior cannot become an unprojected internal outlet");
        var grid=new CoupledAirGrid(next,FlowBoundary.wind(next.flow,1,0,0),FlowGridTest.accurate(),ThermalTransport.Settings.defaults());
        var result=mapped.initialize(grid);FlowGridTest.ready(result);assertEquals(50,grid.temperature(0));assertEquals(20,grid.temperature(2));
        var other=geometry(3,1,1);var wrong=new CoupledAirGrid(other,FlowBoundary.builder(other.flow).build(),FlowGridTest.accurate(),ThermalTransport.Settings.defaults());
        assertThrows(IllegalArgumentException.class,()->mapped.initialize(wrong));
    }
    @Test void copiedDonorsRejectOverlapChangedStorageAndChangedPockets() {
        var g=geometry(1,1,1);var t=new double[]{50};var flux=new double[g.flow.faces()];var d=new Donor(g,new Origin(0,0,0),t,flux);t[0]=0;Arrays.fill(flux,999);
        assertEquals(50,map(List.of(d),g,new Origin(0,0,0),20).temperature(0));
        assertThrows(IllegalArgumentException.class,()->map(List.of(d,d),g,new Origin(0,0,0),20));
        assertThrows(IllegalArgumentException.class,()->map(List.of(d),geometry(1,1,1,FlowShape.FULL),new Origin(0,0,0),20));
        var solid=geometry(1,1,1,FlowShape.FULL);var donor=new Donor(solid,new Origin(0,0,0),new double[]{40},new double[0]);
        var changed=new ThermalGeometry(solid.flow,new double[]{1},new double[]{2},new double[]{1},new double[]{1},ThermalGeometry.Coefficients.legacyMaterials());
        assertThrows(IllegalArgumentException.class,()->map(List.of(donor),changed,new Origin(0,0,0),20));
    }
}
