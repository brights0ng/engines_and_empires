// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalAirViewTest {
    @Test void pendingCoverageAndRejectedCutsHaveAnExplicitReadoutWithoutAnInventedMeasurement() {
        var missing=LocalAirReading.missing(LocalAirReading.Status.PENDING,LocalAirReading.Reason.PREPARING);
        var data=new LocalAirPresentation(1,true,missing,null,"Checking open-sky coverage: 13 / 104 cells");
        String label=LocalAirViewModel.label(data);assertTrue(label.contains("PENDING"));assertTrue(label.contains("13 / 104"));assertFalse(label.contains("blocks/s"));
        var rejected=LocalAirReading.missing(LocalAirReading.Status.UNAVAILABLE,LocalAirReading.Reason.REJECTED);
        assertTrue(LocalAirViewModel.label(new LocalAirPresentation(1,true,rejected,null,"Coverage blocked: world height limit")).contains("Coverage blocked: world height limit"));
        var stale=new LocalAirReading(LocalAirReading.Status.STALE,LocalAirReading.Reason.AGE_LIMIT,Optional.of(new LocalAirReading.Sample(100,30,123,4,5,6)));
        assertTrue(LocalAirViewModel.label(new LocalAirPresentation(1,true,stale,null,"Updating coupled field")).contains("age 30 ticks"));
    }
    @Test void sparseLatticeVisitsOnlyRepresentedCellsEvenWhenTheEnvelopeIsHuge() {
        var layout=dev.brights0ng.paradynelib.heat.air.CellLayout.tiles(16,new int[]{0,0,0,1000000,0,1000000});
        var b=new LocalAirGeometryCapture.Bounds(-16,0,-16,layout.nx,layout.ny,layout.nz,layout);
        int[] count={0};LocalAirViewModel.lattice(b,3,(x,y,z)->{
            assertTrue(b.contains(x,y,z));assertEquals(0,(x-b.x())%3);assertEquals(0,y%3);assertEquals(0,(z-b.z())%3);count[0]++;
        });
        assertEquals(6*6*6+5*6*5,count[0]);
    }
    @Test void latticeCoversDomainBeyondViewerRangeAndAnchorsSpacingToItsMinimum() {
        var bounds=new LocalAirGeometryCapture.Bounds(-10,64,20,17,13,17);
        var points=new java.util.HashSet<String>();
        LocalAirViewModel.lattice(bounds,1,(x,y,z)->assertTrue(points.add(x+","+y+","+z)));
        assertEquals(3757,points.size());assertTrue(points.contains("6,76,36"));assertTrue(points.contains("-10,64,20"));
        points.clear();LocalAirViewModel.lattice(bounds,2,(x,y,z)->{
            assertEquals(0,(x+10)%2);assertEquals(0,(y-64)%2);assertEquals(0,(z-20)%2);points.add(x+","+y+","+z);
        });
        assertEquals(9*7*9,points.size());
        assertThrows(IllegalArgumentException.class,()->LocalAirViewModel.lattice(bounds,0,(x,y,z)->{}));
        assertThrows(IllegalArgumentException.class,()->LocalAirViewModel.lattice(bounds,33,(x,y,z)->{}));
    }
    @Test void arrowsPointDownstreamAndHaveBoundedLengthEvenForVerticalFlow() {
        for(var vector:new double[][]{{4,0,0},{0,-4,0},{0,0,-100}}) {
            var points=LocalAirViewModel.glyph(vector[0],vector[1],vector[2]);
            assertEquals(6,points.size());
            var tip=points.get(3);assertTrue(tip.length()<=0.800001);
            assertTrue(tip.x()*vector[0]+tip.y()*vector[1]+tip.z()*vector[2]>0);
            assertTrue(points.stream().allMatch(p->Double.isFinite(p.x()+p.y()+p.z()) && p.length()<=0.800001));
            assertTrue(points.get(4).distanceTo(points.get(5))>0);
        }
        assertEquals(1,LocalAirViewModel.glyph(0,0,0).size());
    }
    @Test void staleAndMissingReadoutsCannotMasqueradeAsLiveMeasurements() {
        var sample=new LocalAirReading.Sample(100,30,123,4,5,6);
        var stale=new LocalAirReading(LocalAirReading.Status.STALE,LocalAirReading.Reason.AGE_LIMIT,Optional.of(sample));
        assertTrue(LocalAirViewModel.label(stale).contains("STALE"));assertFalse(LocalAirViewModel.label(stale).contains("123"));
        assertTrue(LocalAirViewModel.label(LocalAirReading.missing(LocalAirReading.Status.PENDING,LocalAirReading.Reason.REFRESHING)).contains("no current reading"));
        var current=new LocalAirReading(LocalAirReading.Status.CURRENT,LocalAirReading.Reason.NONE,Optional.of(sample));
        assertTrue(LocalAirViewModel.label(current).contains("123.00 C"));assertTrue(LocalAirViewModel.label(current).contains("age 30"));
    }
}
