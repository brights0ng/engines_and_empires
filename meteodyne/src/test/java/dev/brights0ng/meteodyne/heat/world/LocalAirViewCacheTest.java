// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalAirViewCacheTest {
    private static final LocalAirViewNetwork.Point POINT=new LocalAirViewNetwork.Point(1,2,3,7,1,2,3);
    private static LocalAirViewCache.Frame packet(long owner,boolean history,long age,int limit,List<LocalAirViewNetwork.Point> points) {
        return new LocalAirViewCache.Frame(true,owner,history,age,limit,points);
    }
    @Test void pendingSameOwnerKeepsOnlyHistoricalCubesButMovementAndOwnerSwitchesClearThem() {
        var cache=new LocalAirViewCache();cache.accept(packet(1,true,0,40,List.of(POINT)),100);
        assertTrue(cache.current(100));cache.accept(packet(1,true,0,40,List.of()),101);
        assertEquals(List.of(POINT),cache.points());assertFalse(cache.current(101));
        cache.accept(packet(2,true,0,40,List.of()),102);assertTrue(cache.points().isEmpty());
        cache.accept(packet(2,true,0,40,List.of(POINT)),103);
        cache.accept(packet(2,false,0,40,List.of()),104);assertTrue(cache.points().isEmpty());
    }
    @Test void receivingAnOldFieldDoesNotResetItsAgeAndAnExpiredFieldCannotShowArrows() {
        var cache=new LocalAirViewCache();cache.accept(packet(1,true,38,40,List.of(POINT)),100);
        assertTrue(cache.current(102));assertFalse(cache.current(103));assertFalse(cache.current(99));
        cache.accept(packet(1,true,41,40,List.of(POINT)),105);assertFalse(cache.current(105));
        cache.accept(packet(1,true,0,0,List.of(POINT)),110);assertTrue(cache.current(110));assertFalse(cache.current(111));
        cache.accept(new LocalAirViewCache.Frame(false,0,false,0,40,List.of()),112);
        assertTrue(cache.points().isEmpty());assertFalse(cache.current(112));
        cache.accept(packet(3,true,0,40,List.of(POINT)),120);cache.clear();assertTrue(cache.points().isEmpty());
    }
    @Test void worldAnchoredLatticesKeepOverlapFixedAcrossNegativeOriginsAndSparseHoles() {
        var a=new LocalAirGeometryCapture.Bounds(-16,0,-16,32,16,32);
        var b=new LocalAirGeometryCapture.Bounds(0,0,-16,32,16,32);
        var first=new java.util.HashSet<String>();var second=new java.util.HashSet<String>();
        LocalAirViewModel.lattice(a,3,true,(x,y,z)->{
            assertEquals(0,Math.floorMod(x,3));assertEquals(0,Math.floorMod(y,3));assertEquals(0,Math.floorMod(z,3));
            if(b.contains(x,y,z)) first.add(x+","+y+","+z);
        });
        LocalAirViewModel.lattice(b,3,true,(x,y,z)->{if(a.contains(x,y,z)) second.add(x+","+y+","+z);});
        assertEquals(first,second);assertFalse(first.isEmpty());
        var layout=dev.brights0ng.paradynelib.heat.air.CellLayout.tiles(16,new int[]{0,0,0,1000000,0,1000000});
        var sparse=new LocalAirGeometryCapture.Bounds(-16,0,-16,layout.nx,layout.ny,layout.nz,layout);
        int[] count={0};LocalAirViewModel.lattice(sparse,3,true,(x,y,z)->{assertTrue(sparse.contains(x,y,z));assertEquals(0,Math.floorMod(x,3));count[0]++;});
        assertTrue(count[0]>0 && count[0]<sparse.cells());
    }
}
