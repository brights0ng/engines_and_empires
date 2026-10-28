// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.paradynelib.heat.air.TiledAirGeometry.*;

class TiledAirGeometryTest {
    @Test void exactNodeAndFaceLimitsRejectBeforeExcessGeometryAllocation() {
        assertThrows(IllegalArgumentException.class,()->new FlowGeometry.Builder(1,1,1).admissionLimits(0,6).build());
        assertThrows(IllegalArgumentException.class,()->new FlowGeometry.Builder(1,1,1).openExterior(true).admissionLimits(1,5).build());
        var air=new FlowGeometry.Builder(1,1,1).openExterior(true).admissionLimits(1,6).build();assertEquals(6,air.faces());
        var solid=new FlowGeometry.Builder(1,1,1).cell(0,0,0,FlowShape.FULL).admissionLimits(0,0).build();assertEquals(0,solid.nodes());assertEquals(0,solid.faces());
        assertThrows(IllegalArgumentException.class,()->join(List.of(tile(new Key(0,0,0),1,(x,y,z)->FlowShape.EMPTY)),1,true,C,1,5));
    }
    private static final Version VERSION=new Version(7,11,13);
    private static final ThermalGeometry.Coefficients C=new ThermalGeometry.Coefficients(.1,.2,1,3.5);
    private interface Shapes { FlowShape at(int x,int y,int z); }
    private static double[] filled(int n,double v) { double[] a=new double[n];Arrays.fill(a,v);return a; }
    private static int index(int x,int y,int z,int edge) { return (z*edge+y)*edge+x; }
    private static Tile tile(Key key,int edge,Shapes shapes) {
        int n=edge*edge*edge;var s=new FlowShape[n];double[] k=new double[n],cap=new double[n],fk=new double[n],fc=new double[n];
        for(int z=0;z<edge;z++) for(int y=0;y<edge;y++) for(int x=0;x<edge;x++) {
            int i=index(x,y,z,edge),wx=key.x()*edge+x,wy=key.y()*edge+y,wz=key.z()*edge+z;
            s[i]=shapes.at(wx,wy,wz);k[i]=1+.01*Math.floorMod(wx+2*wy+3*wz,7);cap[i]=1+.03*Math.floorMod(wx+wy+wz,5);
            fk[i]=2*k[i];fc[i]=2*cap[i];
        }
        return new Tile(key,VERSION,edge,s,k,cap,fk,fc);
    }
    private static Key next(Key k,int axis) { return new Key(k.x()+(axis==0?1:0),k.y()+(axis==1?1:0),k.z()+(axis==2?1:0)); }
    private static Joined pair(int edge,int axis,boolean open,Shapes shapes) {
        var a=new Key(0,0,0);var b=next(a,axis);
        return join(List.of(tile(b,edge,shapes),tile(a,edge,shapes)),2*edge*edge*edge,open,C);
    }
    /** Independent dense construction, using the same physical fixture but no tile flattening or seam metadata. */
    private static ThermalGeometry dense(int edge,int axis,boolean open,Shapes shapes) {
        int nx=axis==0?2*edge:edge,ny=axis==1?2*edge:edge,nz=axis==2?2*edge:edge,n=nx*ny*nz;
        var b=new FlowGeometry.Builder(nx,ny,nz).openExterior(open).revision(VERSION.geometry());
        double[] k=new double[n],cap=new double[n],fk=new double[n],fc=new double[n];
        for(int z=0;z<nz;z++) for(int y=0;y<ny;y++) for(int x=0;x<nx;x++) {
            int i=(z*ny+y)*nx+x;b.cell(x,y,z,shapes.at(x,y,z));
            k[i]=1+.01*Math.floorMod(x+2*y+3*z,7);cap[i]=1+.03*Math.floorMod(x+y+z,5);fk[i]=2*k[i];fc[i]=2*cap[i];
        }
        return new ThermalGeometry(b.build(),k,cap,fk,fc,C);
    }
    private static void sameGeometry(ThermalGeometry a,ThermalGeometry b) {
        var f=a.flow;var g=b.flow;assertEquals(g.nodes(),f.nodes());assertEquals(g.faces(),f.faces());assertEquals(g.components(),f.components());
        assertArrayEquals(g.low,f.low);assertArrayEquals(g.high,f.high);assertArrayEquals(g.axis,f.axis);assertArrayEquals(g.area,f.area);
        assertArrayEquals(g.distance,f.distance);assertArrayEquals(g.cx,f.cx);assertArrayEquals(g.cy,f.cy);assertArrayEquals(g.cz,f.cz);
        assertArrayEquals(b.capacity,a.capacity);assertArrayEquals(b.low,a.low);assertArrayEquals(b.high,a.high);assertArrayEquals(b.conductance,a.conductance);
    }
    private static CoupledAirGrid grid(ThermalGeometry g,double[] wind,boolean buoyancy) {
        var fs=new FlowSettings(512,1e-10,1e-12,1e-7,1,0,buoyancy?1:0,100,3000);
        var t=new CoupledAirGrid(g,FlowBoundary.wind(g.flow,wind[0],wind[1],wind[2]),fs,ThermalTransport.Settings.defaults());
        for(int i=0;i<g.states();i++) t.setTemperature(i,20);
        FlowGridTest.ready(t.initialize(wind[0],wind[1],wind[2]));return t;
    }
    private static CoupledAirGrid compareSteps(Joined joined,ThermalGeometry reference,int count,double[] wind,boolean buoyancy) {
        sameGeometry(joined.thermal(),reference);var g=joined.thermal();var f=joined.flow();
        var a=grid(g,wind,buoyancy);var b=grid(reference,wind,buoyancy);
        int source=f.nodeAt(1.5,1.5,1.5);assertTrue(source>=0);
        var sources=new ThermalTransport.Source[]{new ThermalTransport.Source(source,12,60)};
        a.setSources(sources);b.setSources(sources);
        for(int i=f.nodes();i<g.states();i++) { double temp=30+i%9;a.setTemperature(i,temp);b.setTemperature(i,temp); }
        var bg=filled(f.nodes(),20);var ext=filled(f.faces(),20);double before=a.energy(),ledger=0,maxT=0,maxFlux=0,maxDiv=0,maxResidual=0,maxLedger=0;
        for(int step=0;step<count;step++) {
            var left=a.step(.25,1,bg,ext);var right=b.step(.25,1,bg,ext);
            assertTrue(left.ready(),left.toString());assertTrue(right.ready(),right.toString());
            assertTrue(left.flow().pressure().maxDivergence()<=1e-7);assertEquals(right.flow().pressure(),left.flow().pressure());
            assertEquals(right.sourceEnergy(),left.sourceEnergy(),1e-12);assertEquals(right.exteriorEnergy(),left.exteriorEnergy(),1e-12);
            for(int i=0;i<g.states();i++) { maxT=Math.max(maxT,Math.abs(b.temperature(i)-a.temperature(i)));assertEquals(b.temperature(i),a.temperature(i),2e-9,"State "+i); }
            for(int i=0;i<f.faces();i++) { maxFlux=Math.max(maxFlux,Math.abs(b.faceFlux(i)-a.faceFlux(i)));assertEquals(b.faceFlux(i),a.faceFlux(i),2e-10,"Face "+i); }
            ledger+=left.sourceEnergy()+left.exteriorEnergy();assertEquals(before+ledger,a.energy(),1e-6);
            maxDiv=Math.max(maxDiv,left.flow().pressure().maxDivergence());
            maxResidual=Math.max(maxResidual,Math.max(left.first().maxTemperatureResidual(),left.second().maxTemperatureResidual()));
            maxLedger=Math.max(maxLedger,Math.abs(before+ledger-a.energy()));
        }
        System.out.println("TILE_COMPARE axis="+joined.seams().getFirst().axis()+" wind="+Arrays.toString(wind)+" steps="+count+" maxTemperatureDifference="+maxT+" maxFluxDifference="+maxFlux+" maxDivergence="+maxDiv+" maxThermalResidual="+maxResidual+" maxLedgerError="+maxLedger);
        return a;
    }
    @Test void allThreeSeamOrientationsUseSingleInteriorFacesAndMatchMixedDenseCouplingInBothDirections() {
        var partial=new FlowShape.Builder().solidBox(0,0,0,16,4,16).fluidBox(0,0,0,16,8,16).build();
        for(int axis=0;axis<3;axis++) {
            final int normal=axis;
            Shapes shapes=(x,y,z)->{
                int p=normal==0?x:normal==1?y:z;
                if(p==3 && (x+2*y+3*z)%4==0) return FlowShape.FULL;
                if(p==4 && (x+y+z)%3==0) return partial;
                return FlowShape.EMPTY;
            };
            var joined=pair(4,axis,true,shapes);var seam=joined.seams().getFirst();
            assertEquals(1,joined.seams().size());assertTrue(seam.faces()>0);assertEquals(axis,seam.axis());
            var seen=new HashSet<Integer>();
            for(int i=0;i<seam.faces();i++) {
                int face=seam.face(i);assertTrue(seen.add(face));assertFalse(joined.flow().exterior(face));
                assertThrows(IllegalArgumentException.class,()->FlowBoundary.builder(joined.flow()).prescribe(face,1));
            }
            for(int sign:new int[]{-1,1}) {
                double[] wind=new double[3];wind[axis]=sign*1.2;
                compareSteps(joined,dense(4,axis,true,shapes),8,wind,true);
            }
        }
    }
    @Test void fullSizeSixteenBlockTilesCarryAHeatPulseAcrossTheSeamWithoutOutdoorDilution() {
        var joined=pair(16,0,true,(x,y,z)->FlowShape.EMPTY);var g=joined.thermal();var f=joined.flow();
        sameGeometry(g,dense(16,0,true,(x,y,z)->FlowShape.EMPTY));
        assertEquals(256,joined.seams().getFirst().faces());assertEquals(1,f.components());
        // A single box has only its six exterior surfaces; two independent tiles would add two seam surfaces.
        assertEquals(2*(32*16+32*16+16*16),java.util.stream.IntStream.range(0,f.faces()).filter(f::exterior).count());
        var reference=dense(16,0,true,(x,y,z)->FlowShape.EMPTY);var a=grid(g,new double[]{1.5,0,0},false);var b=grid(reference,new double[]{1.5,0,0},false);
        int hot=f.nodeAt(15.5,8.5,8.5),receiver=f.nodeAt(16.5,8.5,8.5);a.setTemperature(hot,80);b.setTemperature(hot,80);
        double before=a.energy(),exchange=0,maxT=0,maxFlux=0;var bg=filled(f.nodes(),20);var ext=filled(f.faces(),20);
        for(int step=0;step<8;step++) {
            var s=a.step(.25,1,bg,ext);var r=b.step(.25,1,bg,ext);assertTrue(s.ready(),s.toString());assertTrue(r.ready(),r.toString());
            assertTrue(s.flow().pressure().maxDivergence()<=1e-7);exchange+=s.exteriorEnergy();
            for(int i=0;i<g.states();i++) { maxT=Math.max(maxT,Math.abs(b.temperature(i)-a.temperature(i)));assertEquals(b.temperature(i),a.temperature(i),2e-9); }
            for(int i=0;i<f.faces();i++) { maxFlux=Math.max(maxFlux,Math.abs(b.faceFlux(i)-a.faceFlux(i)));assertEquals(b.faceFlux(i),a.faceFlux(i),2e-10); }
        }
        assertTrue(a.temperature(receiver)>20.01,"Warmth crosses the interface into the other tile");
        assertEquals(before+exchange,a.energy(),1e-6);
        System.out.println("TILE_PULSE cells="+f.shapes.length+" seamFaces="+joined.seams().getFirst().faces()+" receiverCelsius="+a.temperature(receiver)+" maxTemperatureDifference="+maxT+" maxFluxDifference="+maxFlux+" ledgerError="+(before+exchange-a.energy()));
    }
    @Test void aSealedRoomStraddlingTheSeamRetainsHeatAndHasOneReturnCirculation() {
        Shapes room=(x,y,z)->x==0 || x==7 || y==0 || y==3 || z==0 || z==3?FlowShape.FULL:FlowShape.EMPTY;
        var joined=pair(4,0,true,room);assertEquals(1,joined.flow().components());
        assertEquals(4,joined.seams().getFirst().faces());
        var grid=compareSteps(joined,dense(4,0,true,room),24,new double[3],true);
        var seam=joined.seams().getFirst();double net=0,total=0;
        for(int i=0;i<seam.faces();i++) { double flux=grid.faceFlux(seam.face(i));net+=flux;total+=Math.abs(flux); }
        assertTrue(total>1e-6,"Return circulation crosses the tile seam");assertEquals(0,net,1e-7,"Sealed room has no net mass flux across its cut");
        System.out.println("TILE_ROOM seamAbsoluteFlux="+total+" seamNetFlux="+net);
        assertEquals(0,java.util.stream.IntStream.range(0,joined.flow().faces()).filter(joined.flow()::exterior).count());
    }
    @Test void aSealedOrPartialSeamPreservesDisconnectedPocketsAndCrossTileMaterialLinks() {
        var lower=new FlowShape.Builder().solidBox(0,0,0,16,8,16).build();
        var upper=new FlowShape.Builder().solidBox(0,8,0,16,16,16).build();
        for(boolean blocked:new boolean[]{false,true}) {
            Shapes shape=(x,y,z)->x==3?lower:x==4?(blocked?upper:lower):FlowShape.EMPTY;
            var j=pair(4,0,false,shape);sameGeometry(j.thermal(),dense(4,0,false,shape));
            assertEquals(blocked?0:16,j.seams().getFirst().faces());assertEquals(blocked?2:1,j.flow().components());
            for(int i=0;i<j.seams().getFirst().faces();i++) assertEquals(.5,j.flow().faceArea(j.seams().getFirst().face(i)));
            int a=j.thermal().materialState(j.flow().index(3,1,1)),b=j.thermal().materialState(j.flow().index(4,1,1));
            if(!blocked) assertTrue(java.util.stream.IntStream.range(0,j.thermal().low.length).anyMatch(e->j.thermal().low[e]==a && j.thermal().high[e]==b),"Solid conduction also crosses the seam");
        }
    }
    @Test void tileLocalSourcesMapOnceAcrossTheInterfaceAndMatchTheExistingPowerSemantics() {
        Shapes shape=(x,y,z)->x==3 && y==1 && z==1?FlowShape.FULL:FlowShape.EMPTY;
        var joined=pair(4,0,false,shape);var key=new Key(0,0,0);int c=joined.cell(key,index(3,1,1,4));
        assertEquals(joined.flow().index(3,1,1),c);
        for(boolean perFace:new boolean[]{false,true}) {
            var routed=ThermalSources.fromBlock(joined.thermal(),c,12,65,perFace);
            assertEquals(perFace?72:12,Arrays.stream(routed).mapToDouble(ThermalTransport.Source::power).sum(),1e-12);
            assertTrue(Arrays.stream(routed).anyMatch(s->joined.flow().cx[s.state()]>4),"Right tile is a source neighbor");
        }
    }
    @Test void fluidAndSolidStorageExchangeAcrossASealedSeamWithoutInventingAnAirPassage() {
        for(int axis=0;axis<3;axis++) for(boolean solid:new boolean[]{false,true}) {
            final int direction=axis;
            Shapes shapes=(x,y,z)->(direction==0?x:direction==1?y:z)==0?FlowShape.WATER:solid?FlowShape.FULL:FlowShape.WATER;
            var j=pair(1,axis,false,shapes);var g=j.thermal();sameGeometry(g,dense(1,axis,false,shapes));
            assertEquals(0,j.flow().nodes());assertEquals(0,j.seams().getFirst().faces());assertEquals(1,g.low.length);
            int a=g.fluidState(0),b=solid?g.materialState(1):g.fluidState(1);double ca=g.capacity(a),cb=g.capacity(b),k=g.conductance[0],dt=.5;
            var t=new ThermalTransport(g,ThermalTransport.Settings.defaults());t.setTemperature(a,80);t.setTemperature(b,20);double before=t.energy();
            var step=t.advance(dt,new double[0],new double[0]);assertTrue(step.ready(),step.toString());
            double difference=60/(1+dt*k*(1/ca+1/cb)),expected=(ca*80+cb*20-ca*difference)/(ca+cb);
            assertEquals(expected,t.temperature(a)-difference,1e-8);assertEquals(expected,t.temperature(b),1e-8);
            assertEquals(before,t.energy(),1e-8);assertEquals(0,step.exteriorEnergy());
        }
    }
    @Test void negativeTileCoordinatesOrderingAndInputCopiesPreserveUniqueCellOwnership() {
        int edge=2,n=8;var key=new Key(-1,-2,3);var shapes=new FlowShape[n];Arrays.fill(shapes,FlowShape.EMPTY);var material=filled(n,1);
        var a=new Tile(key,VERSION,edge,shapes,material,material,material,material);
        Arrays.fill(shapes,FlowShape.FULL);Arrays.fill(material,Double.NaN);
        var b=tile(next(key,0),edge,(x,y,z)->FlowShape.EMPTY);
        var joined=join(List.of(b,a),16,true,C);var reverse=join(List.of(a,b),16,true,C);
        assertEquals(-2,joined.originX());assertEquals(-4,joined.originY());assertEquals(6,joined.originZ());assertEquals(16,joined.flow().nodes());
        sameGeometry(joined.thermal(),reverse.thermal());var owned=new HashSet<Integer>();
        for(var k:List.of(key,next(key,0))) for(int c=0;c<n;c++) assertTrue(owned.add(joined.cell(k,c)));
        assertEquals(16,owned.size());assertThrows(UnsupportedOperationException.class,()->joined.seams().clear());
        assertThrows(IllegalArgumentException.class,()->joined.cell(new Key(99,0,0),0));
        assertThrows(IllegalArgumentException.class,()->joined.cell(key,n));
    }
    @Test void incompleteDuplicateStaleOrOverBudgetTilesCannotCreateInventedExteriorGeometry() {
        var a=tile(new Key(0,0,0),2,(x,y,z)->FlowShape.EMPTY);var b=tile(new Key(1,0,0),2,(x,y,z)->FlowShape.EMPTY);
        assertThrows(IllegalArgumentException.class,()->join(List.of(a,a),16,true,C));
        assertThrows(IllegalArgumentException.class,()->join(List.of(a,b),15,true,C));
        assertThrows(IllegalArgumentException.class,()->join(List.of(a,tile(new Key(2,0,0),2,(x,y,z)->FlowShape.EMPTY)),16,true,C));
        assertThrows(IllegalArgumentException.class,()->join(List.of(a,b,tile(new Key(0,1,0),2,(x,y,z)->FlowShape.EMPTY)),24,true,C));
        var empty=new FlowShape[8];Arrays.fill(empty,FlowShape.EMPTY);var material=filled(8,1);
        for(var version:List.of(new Version(8,11,13),new Version(7,12,13),new Version(7,11,14))) {
            var stale=new Tile(b.key(),version,2,empty,material,material,material,material);
            assertThrows(IllegalArgumentException.class,()->join(List.of(a,stale),16,true,C));
        }
        assertThrows(IllegalArgumentException.class,()->join(List.of(a,tile(b.key(),1,(x,y,z)->FlowShape.EMPTY)),16,true,C));
        assertThrows(IllegalArgumentException.class,()->join(List.of(tile(new Key(Integer.MAX_VALUE,0,0),2,(x,y,z)->FlowShape.EMPTY)),8,true,C));
    }
}
