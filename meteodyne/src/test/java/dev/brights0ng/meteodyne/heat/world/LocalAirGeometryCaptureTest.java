// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirGeometryCapture.*;
import dev.brights0ng.paradynelib.heat.air.ThermalGeometry;

class LocalAirGeometryCaptureTest {
    private static final Cell AIR=new Cell(new Shape(new int[0],new int[0]),1,1,2,2);
    private static final ThermalGeometry.Coefficients C=new ThermalGeometry.Coefficients(0,.2,1,4);
    private static class Input implements Source {
        public long contextId() { return 1; }
        long geometry=3,material=7;
        Cell cell=AIR;
        final List<String> visited=new ArrayList<>();
        public long geometryRevision() { return geometry; }
        public long materialRevision() { return material; }
        public Cell sample(int x,int y,int z) { visited.add(x+","+y+","+z);return cell; }
    }
    private static LocalAirGeometryCapture capture(Input s) { return new LocalAirGeometryCapture(new Bounds(-2,5,-3,2,2,2),8,s); }
    @Test void slicesRespectWorkDeadlineAndWorldCoordinates() {
        var source=new Input();var c=capture(source);
        assertEquals(0,c.advance(8,System.nanoTime()-1));assertEquals(0,c.advance(0,Long.MAX_VALUE));
        assertEquals(3,c.advance(3,Long.MAX_VALUE));assertEquals(Status.CAPTURING,c.status());
        assertThrows(IllegalStateException.class,c::snapshot);
        assertEquals(5,c.advance(99,Long.MAX_VALUE));assertEquals(Status.CAPTURED,c.status());
        assertEquals(List.of("-2,5,-3","-1,5,-3","-2,6,-3","-1,6,-3","-2,5,-2","-1,5,-2","-2,6,-2","-1,6,-2"),source.visited);
        assertEquals(0,c.advance(8,Long.MAX_VALUE));
        var prepared=c.snapshot().bakeClosedControl(C);
        assertEquals(8,prepared.flow().nodes());
        for(int f=0;f<prepared.flow().faces();f++) assertFalse(prepared.flow().exterior(f));
    }
    @Test void admissionRejectsInvalidDimensionsOverflowAndWorkAllowance() {
        assertThrows(IllegalArgumentException.class,()->new Bounds(0,0,0,0,1,1));
        assertThrows(ArithmeticException.class,()->new Bounds(Integer.MAX_VALUE,0,0,2,1,1));
        assertThrows(ArithmeticException.class,()->new Bounds(0,0,0,65536,65536,1));
        assertThrows(IllegalArgumentException.class,()->new LocalAirGeometryCapture(new Bounds(0,0,0,2,2,2),7,new Input()));
        assertThrows(IllegalArgumentException.class,()->capture(new Input()).advance(-1,Long.MAX_VALUE));
    }
    @Test void missingAndUnsupportedNeverPublishAsEmptyAir() {
        for(var status:List.of(Status.MISSING_GEOMETRY,Status.UNSUPPORTED_GEOMETRY)) {
            var source=new Input() { @Override public Cell sample(int x,int y,int z) { throw new Unavailable(status,"fixture"); } };
            var c=capture(source);assertEquals(1,c.advance(8,Long.MAX_VALUE));
            assertEquals(status,c.status());assertEquals("fixture",c.reason());assertEquals(0,c.sampledCells());
            assertThrows(IllegalStateException.class,c::snapshot);assertEquals(0,c.advance(8,Long.MAX_VALUE));
        }
    }
    @Test void geometryAndMaterialEditsInvalidateBetweenSlicesAndBeforeHandoff() {
        for(boolean material:new boolean[]{false,true}) for(boolean completed:new boolean[]{false,true}) {
            var source=new Input();var c=capture(source);c.advance(completed?8:2,Long.MAX_VALUE);
            if(material) source.material++;else source.geometry++;
            if(completed) assertThrows(IllegalStateException.class,c::snapshot);else assertEquals(0,c.advance(8,Long.MAX_VALUE));
            assertEquals(Status.STALE,c.status());assertThrows(IllegalStateException.class,c::snapshot);
        }
    }
    @Test void revisionChangeInsideSampleDiscardsThatCell() {
        var source=new Input() { @Override public Cell sample(int x,int y,int z) { material++;return AIR; } };
        var c=capture(source);assertEquals(1,c.advance(8,Long.MAX_VALUE));assertEquals(0,c.sampledCells());assertEquals(Status.STALE,c.status());
    }
    @Test void snapshotOwnsShapeAndMaterialValuesWithIndependentWetStorage() {
        int[] solid={0,0,0,16,4,16},fluid={0,0,0,16,8,16};
        var shape=new Shape(solid,fluid);solid[4]=16;fluid[4]=16;
        var source=new Input();source.cell=new Cell(shape,1,3,2,5);
        var c=new LocalAirGeometryCapture(new Bounds(0,0,0,1,1,1),1,source);c.advance(1,Long.MAX_VALUE);
        var snapshot=c.snapshot();source.cell=AIR;source.material++;
        var p=snapshot.bakeClosedControl(C);var g=p.thermal();
        assertEquals(.25,p.flow().solidVolume(0));assertEquals(.25,p.flow().fluidVolume(0));
        assertEquals(3,g.states());assertEquals(3,g.capacity(g.materialState(0)));assertEquals(5,g.capacity(g.fluidState(0)));
        assertEquals(0,g.unrepresentedFluidCells());assertEquals(3,snapshot.geometryRevision);assertEquals(7,snapshot.materialRevision);
    }
    @Test void captureStaysOnOwnerThreadButSnapshotBakesOnWorker() throws Exception {
        var c=capture(new Input());c.advance(8,Long.MAX_VALUE);var snapshot=c.snapshot();
        var failure=new AtomicReference<Throwable>();
        var worker=new Thread(()->{ try {
            assertThrows(IllegalStateException.class,()->c.advance(1,Long.MAX_VALUE));
            assertThrows(IllegalStateException.class,c::snapshot);
            assertEquals(8,snapshot.bakeClosedControl(C).flow().nodes());
        } catch(Throwable t) { failure.set(t); } });
        worker.start();worker.join();if(failure.get()!=null) throw new AssertionError(failure.get());
    }
    @Test void shapeAndMaterialValidationRejectsUnrepresentableInputs() {
        assertThrows(IllegalArgumentException.class,()->new Shape(new int[]{0,0,0,17,16,16},new int[0]));
        assertThrows(IllegalArgumentException.class,()->new Shape(new int[]{0},new int[0]));
        assertThrows(IllegalArgumentException.class,()->new Cell(AIR.shape(),Double.NaN,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new Cell(AIR.shape(),1,0,1,1));
        assertEquals(AIR.shape(),new Shape(new int[0],new int[0]));
    }
}
