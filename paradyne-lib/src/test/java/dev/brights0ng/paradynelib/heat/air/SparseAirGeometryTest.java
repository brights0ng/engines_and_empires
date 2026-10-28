// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.paradynelib.heat.air.TiledAirGeometry.*;

class SparseAirGeometryTest {
    private static final ThermalGeometry.Coefficients C=new ThermalGeometry.Coefficients(.1,.2,1,3.5);
    private static final FlowShape MIXED=new FlowShape.Builder().solidBox(0,0,0,16,4,16).fluidBox(0,0,0,16,8,16).build();
    private static FlowShape shape(int x,int y,int z) { return x==2 && y==0 && z==0?MIXED:FlowShape.EMPTY; }
    private static Tile tile(Key key,int edge,boolean mixed) {
        int n=edge*edge*edge;var shapes=new FlowShape[n];var k=new double[n];Arrays.fill(k,1);
        for(int c=0;c<n;c++) shapes[c]=mixed?shape(key.x()*edge+c%edge,key.y()*edge+(c/edge)%edge,key.z()*edge+c/(edge*edge)):FlowShape.EMPTY;
        return new Tile(key,new Version(1,1,1),edge,shapes,k,k,k,k);
    }
    private static Joined lShape() { return joinSparse(List.of(tile(new Key(0,0,1),2,true),tile(new Key(1,0,0),2,true),tile(new Key(0,0,0),2,true)),24,true,C,96,288); }
    private static ThermalGeometry reference() {
        var xyz=new ArrayList<Integer>();for(int z=0;z<4;z++) for(int y=0;y<2;y++) for(int x=0;x<4;x++) if(x<2 || z<2) { xyz.add(x);xyz.add(y);xyz.add(z); }
        var layout=CellLayout.tiles(1,xyz.stream().mapToInt(Integer::intValue).toArray());var b=new FlowGeometry.Builder(layout).openExterior(true);
        for(int c=0;c<layout.cells();c++) b.cell(layout.x(c),layout.y(c),layout.z(c),shape(layout.x(c),layout.y(c),layout.z(c)));
        var k=new double[layout.cells()];Arrays.fill(k,1);return new ThermalGeometry(b.build(),k,k,k,k,C);
    }
    private record FaceKey(int axis,double x,double y,double z,int low,int high) { }
    private static FaceKey faceKey(FlowGeometry f,int i) {
        return new FaceKey(f.axis[i],f.fx[i],f.fy[i],f.fz[i],f.low[i]<0?-1:f.low[i]-f.starts[f.nodeCell[f.low[i]]],f.high[i]<0?-1:f.high[i]-f.starts[f.nodeCell[f.high[i]]]);
    }
    @Test void compactTileAddressingHasNoPhantomCellsOrFacesAndIsInputOrderIndependent() {
        var joined=lShape();var f=joined.flow();assertEquals(24,f.cells());assertEquals(-1,f.index(3,1,3));assertEquals(-1,f.nodeAt(3.5,1.5,3.5));
        assertEquals(2,joined.seams().size());assertEquals(1,f.components());assertEquals(24,joined.thermal().airStates(),"Mixed cell retains one half-volume air pocket");
        var seen=new HashSet<Integer>();for(int c=0;c<f.cells();c++) assertTrue(seen.add(f.index(f.cellX(c),f.cellY(c),f.cellZ(c))));
        for(var seam:joined.seams()) for(int i=0;i<seam.faces();i++) assertFalse(f.exterior(seam.face(i)));
        var ref=reference();assertEquals(ref.states(),joined.thermal().states());assertEquals(ref.flow.faces(),f.faces());
        var keys=new HashSet<FaceKey>();for(int i=0;i<f.faces();i++) assertTrue(keys.add(faceKey(f,i)));
        for(int i=0;i<ref.flow.faces();i++) assertTrue(keys.contains(faceKey(ref.flow,i)));
    }
    @Test void independentCellEnumerationMatchesCoupledTransportForEveryWindOctant() {
        var joined=lShape();var g=joined.thermal();var f=g.flow;var ref=reference();var h=ref.flow;
        var faceMap=new HashMap<FaceKey,Integer>();for(int i=0;i<h.faces();i++) faceMap.put(faceKey(h,i),i);
        for(int octant=0;octant<8;octant++) {
            double x=(octant&1)==0?1:-1,y=(octant&2)==0?.2:-.2,z=(octant&4)==0?.5:-.5;
            var a=new CoupledAirGrid(g,FlowBoundary.wind(f,x,y,z),FlowGridTest.accurate(),ThermalTransport.Settings.defaults());
            var b=new CoupledAirGrid(ref,FlowBoundary.wind(h,x,y,z),FlowGridTest.accurate(),ThermalTransport.Settings.defaults());
            for(int i=0;i<g.states();i++) a.setTemperature(i,20);for(int i=0;i<ref.states();i++) b.setTemperature(i,20);
            int c=f.index(2,0,0),d=h.index(2,0,0);a.setTemperature(g.materialState(c),50);b.setTemperature(ref.materialState(d),50);a.setTemperature(g.fluidState(c),70);b.setTemperature(ref.fluidState(d),70);
            a.setSources(new ThermalTransport.Source(f.nodeAt(.5,.5,.5),4,60));b.setSources(new ThermalTransport.Source(h.nodeAt(.5,.5,.5),4,60));
            FlowGridTest.ready(a.initialize(x,y,z));FlowGridTest.ready(b.initialize(x,y,z));
            var bg=new double[f.nodes()];var rg=new double[h.nodes()];var ext=new double[f.faces()];var re=new double[h.faces()];Arrays.fill(bg,20);Arrays.fill(rg,20);Arrays.fill(ext,20);Arrays.fill(re,20);
            for(int step=0;step<8;step++) {
                var sa=a.step(.25,1,bg,ext);var sb=b.step(.25,1,rg,re);assertTrue(sa.ready(),sa.toString());assertTrue(sb.ready(),sb.toString());
                assertTrue(sa.flow().pressure().maxDivergence()<=1e-7);assertEquals(b.energy(),a.energy(),2e-7);
                for(int i=0;i<f.cells();i++) {
                    int j=h.index(f.cellX(i),f.cellY(i),f.cellZ(i));
                    for(int k=f.starts[i];k<f.starts[i+1];k++) assertEquals(b.temperature(h.starts[j]+k-f.starts[i]),a.temperature(k),2e-8);
                    if(g.materialState(i)>=0) assertEquals(b.temperature(ref.materialState(j)),a.temperature(g.materialState(i)),2e-8);
                    if(g.fluidState(i)>=0) assertEquals(b.temperature(ref.fluidState(j)),a.temperature(g.fluidState(i)),2e-8);
                }
                for(int i=0;i<f.faces();i++) assertEquals(b.faceFlux(faceMap.get(faceKey(f,i))),a.faceFlux(i),2e-8);
            }
        }
    }
    @Test void enormousOmittedEnvelopeDoesNotAllocateOrIterateThroughItsVolume() {
        var joined=joinSparse(List.of(tile(new Key(0,0,0),16,false),tile(new Key(1_000_000,0,1_000_000),16,false)),8192,true,C,8192,98304);
        var f=joined.flow();assertEquals(8192,f.cells());assertEquals(16_000_016,f.nx);assertEquals(2,f.components());assertEquals(-1,f.index(8_000_000,8,8_000_000));
        var t=new CoupledAirGrid(joined.thermal(),FlowBoundary.wind(f,1,0,0),FlowGridTest.accurate(),ThermalTransport.Settings.defaults());for(int i=0;i<joined.thermal().states();i++) t.setTemperature(i,20);
        FlowGridTest.ready(t.initialize(1,0,0));var bg=new double[f.nodes()];var ext=new double[f.faces()];Arrays.fill(bg,20);Arrays.fill(ext,20);
        assertTrue(t.step(.25,1,bg,ext).ready());assertEquals(20,t.temperature(0),1e-9);
    }
    @Test void sparseDonorsWithOverlappingEnvelopesTransferOnlyCellsTheyActuallyOwn() {
        var f=lShape().thermal();var temps=new double[f.states()];Arrays.fill(temps,45);
        var a=new DomainTransfer.Donor(f,new DomainTransfer.Origin(0,0,0),temps,new double[f.flow.faces()]);
        var cell=new FlowGeometry.Builder(2,2,2).openExterior(true).build();var k=new double[8];Arrays.fill(k,1);var g=new ThermalGeometry(cell,k,k,k,k,C);
        var t=new double[g.states()];Arrays.fill(t,70);var b=new DomainTransfer.Donor(g,new DomainTransfer.Origin(2,0,2),t,new double[cell.faces()]);
        var target=new FlowGeometry.Builder(4,2,4).openExterior(true).cell(2,0,0,MIXED).build();var all=new double[32];Arrays.fill(all,1);var thermal=new ThermalGeometry(target,all,all,all,all,C);
        var mapped=DomainTransfer.map(List.of(a,b),thermal,new DomainTransfer.Origin(0,0,0),20);
        assertEquals(70,mapped.temperature(target.nodeAt(3.5,.5,3.5)));assertEquals(45,mapped.temperature(target.nodeAt(.5,.5,.5)));assertEquals(0,mapped.incomingEnergy);
    }
}
