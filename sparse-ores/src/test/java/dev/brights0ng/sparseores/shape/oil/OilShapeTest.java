// Written by Gemini.
package dev.brights0ng.sparseores.shape.oil;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OilShapeTest {

    @Test
    void testAllTrapTypesCoverage() {
        for (OilTrapType trap : OilTrapType.values()) {
            assertNotNull(trap.displayName());
            assertNotNull(trap.id());
            assertEquals(trap, OilTrapType.byId(trap.id()));
        }
    }

    @Test
    void testAnticlineGeometryAndShrinkage() {
        AnticlinePotential anticline = new AnticlinePotential(100, 200, 160.0, 50.0, Math.PI / 4.0, 12345L);
        assertEquals(OilTrapType.ANTICLINE, anticline.trapType());
        assertEquals(100, anticline.centerX());
        assertEquals(200, anticline.centerZ());

        // Center must be at peak potential
        assertTrue(anticline.potential(100, 200) >= 0.99);
        assertTrue(anticline.contains(100, 200, 0.05));

        // Outside bounding radius must be zero
        double r = anticline.maxRadius();
        assertEquals(0.0, anticline.potential(100 + r + 10, 200));

        // Chunk containment
        assertTrue(anticline.touchesChunk(100 >> 4, 200 >> 4, 1.0));
        assertFalse(anticline.touchesChunk((100 + 2000) >> 4, 200 >> 4, 1.0));

        // Shrinkage test: fullness 1.0 vs 0.5 vs 0.1
        double[][] polyFull = anticline.polygon(1.0, 32);
        double[][] polyHalf = anticline.polygon(0.5, 32);
        double[][] polyLow = anticline.polygon(0.1, 32);
        double[][] polyEmpty = anticline.polygon(0.0, 32);

        assertEquals(32, polyFull.length);
        assertEquals(32, polyHalf.length);
        assertEquals(32, polyLow.length);
        assertEquals(0, polyEmpty.length);

        double spanFull = extent(polyFull, 100, 200);
        double spanHalf = extent(polyHalf, 100, 200);
        double spanLow = extent(polyLow, 100, 200);

        assertTrue(spanFull > spanHalf, "Full span " + spanFull + " should exceed half span " + spanHalf);
        assertTrue(spanHalf > spanLow, "Half span " + spanHalf + " should exceed low span " + spanLow);
    }

    @Test
    void testFaultWedgeGeometryAndShrinkage() {
        FaultWedgePotential wedge = new FaultWedgePotential(0, 0, 120.0, 80.0, 0.0, 54321L);
        assertEquals(OilTrapType.FAULT_WEDGE, wedge.trapType());

        // Core near fault line
        assertTrue(wedge.potential(0, 0) >= 0.95);

        // Across the fault (u < 0) must be sealed (0 potential)
        assertEquals(0.0, wedge.potential(-5, 0));
        assertEquals(0.0, wedge.potential(-20, 10));

        // Along dipping strata (u > 0)
        assertTrue(wedge.potential(30, 0) > 0.0);
        assertEquals(0.0, wedge.potential(150, 0)); // past dipLength

        // Polygon test
        double[][] polyFull = wedge.polygon(1.0, 32);
        double[][] polyHalf = wedge.polygon(0.5, 32);
        assertTrue(polyFull.length > 10);
        assertTrue(polyHalf.length > 10);

        double spanFull = extent(polyFull, 0, 0);
        double spanHalf = extent(polyHalf, 0, 0);
        assertTrue(spanFull > spanHalf);
    }

    @Test
    void testRibbonGeometryAndShrinkage() {
        RibbonPotential ribbon = new RibbonPotential(50, 50, 350.0, 60.0, 0.0, 99999L);
        assertEquals(OilTrapType.RIBBON, ribbon.trapType());

        // Center must be high
        assertTrue(ribbon.potential(50, 50) > 0.5);

        // Outside width must be zero
        assertEquals(0.0, ribbon.potential(50, 50 + 100));

        double[][] polyFull = ribbon.polygon(1.0, 32);
        double[][] polyHalf = ribbon.polygon(0.5, 32);
        assertTrue(polyFull.length > 10);
        assertTrue(polyHalf.length > 10);

        double spanFull = extent(polyFull, 50, 50);
        double spanHalf = extent(polyHalf, 50, 50);
        assertTrue(spanFull > spanHalf);
    }

    @Test
    void testCrescentGeometryAndShrinkage() {
        CrescentPotential crescent = new CrescentPotential(0, 0, 120.0, 35.0, Math.PI * 0.7, 0.0);
        assertEquals(OilTrapType.CRESCENT, crescent.trapType());

        // Center at apex
        assertTrue(crescent.potential(0, 0) >= 0.95);

        // Center of the dome itself (-120, 0) is salt plug, must be 0
        assertEquals(0.0, crescent.potential(-120, 0));

        double[][] polyFull = crescent.polygon(1.0, 32);
        double[][] polyHalf = crescent.polygon(0.5, 32);
        assertTrue(polyFull.length > 10);

        double spanFull = extent(polyFull, 0, 0);
        double spanHalf = extent(polyHalf, 0, 0);
        assertTrue(spanFull > spanHalf);
    }

    @Test
    void testMoundGeometryAndShrinkage() {
        MoundPotential mound = new MoundPotential(-100, -100, 100.0, 77777L);
        assertEquals(OilTrapType.MOUND, mound.trapType());

        assertTrue(mound.potential(-100, -100) >= 0.99);

        double[][] polyFull = mound.polygon(1.0, 32);
        double[][] polyHalf = mound.polygon(0.5, 32);
        assertTrue(polyFull.length == 32);

        double spanFull = extent(polyFull, -100, -100);
        double spanHalf = extent(polyHalf, -100, -100);
        assertTrue(spanFull > spanHalf);
    }

    @Test
    void testSheetGeometryAndShrinkage() {
        SheetPotential sheet = new SheetPotential(200, 300, 200.0, 88888L);
        assertEquals(OilTrapType.SHEET, sheet.trapType());

        assertTrue(sheet.potential(200, 300) >= 0.99);

        double[][] polyFull = sheet.polygon(1.0, 32);
        double[][] polyHalf = sheet.polygon(0.5, 32);
        assertTrue(polyFull.length == 32);

        double spanFull = extent(polyFull, 200, 300);
        double spanHalf = extent(polyHalf, 200, 300);
        assertTrue(spanFull > spanHalf);
    }

    @Test
    void testGeneratorOffline() {
        for (long seed = 0; seed < 20; seed++) {
            OilDepositGenerator.GeneratedReservoir res = OilDepositGenerator.generate(1000L + seed, (int) seed * 100, (int) seed * 100, seed, null);
            assertNotNull(res);
            assertNotNull(res.trapType());
            assertNotNull(res.field());
            assertTrue(res.capacity() >= 500_000, "Capacity should be at least 500k mB, got " + res.capacity());
            assertTrue(res.capacity() <= 250_000_000, "Capacity should not exceed 250M mB, got " + res.capacity());
        }
    }

    @Test
    void validateOilDepositsOverlapAndVariation() {
        dev.brights0ng.sparseores.OreLayer oilLayer = new dev.brights0ng.sparseores.OreLayer("crude_oil", 700);
        int overlaps = 0;
        double minCenterDist = Double.MAX_VALUE;
        double minBoundaryGap = Double.MAX_VALUE;
        int minCapacity = Integer.MAX_VALUE;
        int maxCapacity = Integer.MIN_VALUE;
        double minRadius = Double.MAX_VALUE;
        double maxRadius = Double.MIN_VALUE;

        for (long worldSeed = 100; worldSeed <= 103; worldSeed++) {
            dev.brights0ng.sparseores.OreMap map = new dev.brights0ng.sparseores.OreMap(worldSeed, oilLayer);
            List<dev.brights0ng.sparseores.Deposit> deposits = map.depositsInBox(-6000, -6000, 6000, 6000);
            List<OilDepositGenerator.GeneratedReservoir> reservoirs = new java.util.ArrayList<>();
            for (dev.brights0ng.sparseores.Deposit d : deposits) {
                OilDepositGenerator.GeneratedReservoir r = OilDepositGenerator.generate(worldSeed, d.x(), d.z(), d.seed(), null);
                reservoirs.add(r);
                minCapacity = Math.min(minCapacity, r.capacity());
                maxCapacity = Math.max(maxCapacity, r.capacity());
                minRadius = Math.min(minRadius, r.field().maxRadius());
                maxRadius = Math.max(maxRadius, r.field().maxRadius());
            }

            for (int i = 0; i < reservoirs.size(); i++) {
                OilDepositGenerator.GeneratedReservoir r1 = reservoirs.get(i);
                for (int j = i + 1; j < reservoirs.size(); j++) {
                    OilDepositGenerator.GeneratedReservoir r2 = reservoirs.get(j);
                    double dist = Math.hypot(r1.field().centerX() - r2.field().centerX(), r1.field().centerZ() - r2.field().centerZ());
                    minCenterDist = Math.min(minCenterDist, dist);
                    double gap = dist - (r1.field().maxRadius() + r2.field().maxRadius());
                    minBoundaryGap = Math.min(minBoundaryGap, gap);
                    if (gap < 0) {
                        overlaps++;
                    }
                }
            }
        }
        System.out.println("Validation Results: minCenterDist=" + minCenterDist
                + ", minBoundaryGap=" + minBoundaryGap
                + ", overlaps=" + overlaps
                + ", capacityRange=[" + minCapacity + ", " + maxCapacity + "]"
                + ", radiusRange=[" + minRadius + ", " + maxRadius + "]");

        assertEquals(0, overlaps, "Oil deposits must NEVER overlap!");
        assertTrue(minBoundaryGap > 0, "Minimum boundary gap must be strictly positive!");
        assertTrue(minCapacity <= 4_000_000, "Should generate small pocket deposits with <= 4M mB");
        assertTrue(maxCapacity >= 80_000_000, "Should generate supergiant deposits with >= 80M mB");
        assertTrue(minRadius <= 45.0, "Should generate small pocket radii <= 45m");
        assertTrue(maxRadius >= 110.0, "Should generate large field radii >= 110m");
    }

    private static double extent(double[][] poly, int cx, int cz) {
        double maxDistSq = 0.0;
        for (double[] pt : poly) {
            double dx = pt[0] - cx;
            double dz = pt[1] - cz;
            maxDistSq = Math.max(maxDistSq, dx * dx + dz * dz);
        }
        return Math.sqrt(maxDistSq);
    }
}
