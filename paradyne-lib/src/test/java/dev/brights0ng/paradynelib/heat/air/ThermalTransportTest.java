// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThermalTransportTest {
    static double[] filled(int n, double value) { double[] a = new double[n]; Arrays.fill(a, value); return a; }
    static ThermalGeometry geometry(FlowGeometry g, double mixing, double wallK) {
        return new ThermalGeometry(g, filled(g.shapes.length, 1), filled(g.shapes.length, 1),
                new ThermalGeometry.Coefficients(mixing, .15, wallK, 3.5));
    }
    static ThermalTransport transport(ThermalGeometry g) { return new ThermalTransport(g, ThermalTransport.Settings.defaults()); }
    static void ready(ThermalTransport.Step step) {
        assertTrue(step.ready(), step.toString());
        assertTrue(step.maxTemperatureResidual() <= 1e-9, step.toString());
        assertEquals(0, step.energyError(), 1e-8, step.toString());
    }
    static void fill(ThermalTransport t, ThermalGeometry g, double value) {
        for (int i = 0; i < g.states(); i++) { t.setTemperature(i, value); }
    }
    static double[] uniformFlux(FlowGeometry g, double x, double y, double z) {
        double[] q = new double[g.faces()];
        for (int f = 0; f < q.length; f++) { q[f] = g.area[f] * (g.axis[f] == 0 ? x : g.axis[f] == 1 ? y : z); }
        return q;
    }

    @Test void slabStoresIndependentVolumeWeightedAirAndMaterialTemperatures() {
        var slab = new FlowShape.Builder().solidBox(0, 0, 0, 16, 8, 16).build();
        var f = new FlowGeometry.Builder(1, 1, 1).cell(0, 0, 0, slab).build();
        var g = geometry(f, 0, .035); var t = transport(g);
        int material = g.materialState(0);
        assertEquals(.5, g.capacity(0)); assertEquals(1.75, g.capacity(material));
        t.setTemperature(0, 60); t.setTemperature(material, 10);
        double before = t.energy();
        ready(t.advance(1, new double[f.faces()], new double[f.faces()]));
        assertTrue(t.temperature(0) < 60 && t.temperature(0) > 10);
        assertTrue(t.temperature(material) > 10 && t.temperature(material) < 60);
        assertEquals(before, t.energy(), 1e-8);
        assertEquals(1, g.conductance.length);
        assertEquals(1 / (.15 + .5 / .035), g.conductance[0], 1e-12);
    }

    @Test void sealedMixingConservesHeatAndStaysWithinInitialBounds() {
        var f = new FlowGeometry.Builder(4, 3, 2).build(); var g = geometry(f, 1, .035); var t = transport(g);
        fill(t, g, 5); t.setTemperature(0, 80); double before = t.energy();
        for (int step = 0; step < 5; step++) {
            ready(t.advance(2, new double[f.faces()], new double[f.faces()]));
            for (int i = 0; i < g.states(); i++) { assertTrue(t.temperature(i) >= 5 && t.temperature(i) <= 80); }
        }
        assertEquals(before, t.energy(), 5e-8);
        assertTrue(t.temperature(1) > 5);
    }

    @Test void uniformTemperatureSurvivesProjectedObstacleFlow() {
        var f = new FlowGeometry.Builder(7, 4, 3).openExterior(true).cell(3, 1, 1, FlowShape.FULL).build();
        var flow = new FlowGrid(f, FlowBoundary.wind(f, 3, 0, 0), FlowGridTest.accurate());
        flow.seedUniform(3, 0, 0); FlowGridTest.ready(flow.projectInitial());
        var g = geometry(f, 1, .035); var t = transport(g); fill(t, g, 23);
        ready(t.step(.5, flow, filled(f.faces(), 23)));
        for (int i = 0; i < g.states(); i++) { assertEquals(23, t.temperature(i), 1e-8); }
    }

    @Test void warmPulseUsesUpwindStateInBothDirectionsAndAccountsForOutflow() {
        for (double speed : new double[]{2, -2}) {
            var f = new FlowGeometry.Builder(4, 1, 1).openExterior(true).build();
            var g = geometry(f, 0, 0); var t = transport(g); fill(t, g, 10);
            int upstream = speed > 0 ? 0 : 3; t.setTemperature(upstream, 70);
            var result = t.advance(.5, uniformFlux(f, speed, 0, 0), filled(f.faces(), 10)); ready(result);
            // For q*dt=1, backward Euler gives 40,25,17.5,13.75 from the inflow side.
            for (int k = 0; k < 4; k++) { assertEquals(10 + 60 / Math.pow(2, k + 1), t.temperature(speed > 0 ? k : 3 - k), 1e-9); }
            assertEquals(-3.75, result.exteriorEnergy(), 1e-9);
            assertEquals(result.energyAfter(), result.energyBefore() + result.exteriorEnergy(), 1e-8);
        }
    }

    @Test void incomingWeatherChangePreservesStoredMaterialWarmth() {
        var slab = new FlowShape.Builder().solidBox(0, 0, 0, 16, 8, 16).build();
        var f = new FlowGeometry.Builder(1, 1, 1).openExterior(true).cell(0, 0, 0, slab).build();
        var g = geometry(f, 0, 0); var t = transport(g); fill(t, g, 50);
        ready(t.advance(1, uniformFlux(f, 1, 0, 0), filled(f.faces(), 0)));
        assertEquals(25, t.temperature(0), 1e-9); assertEquals(50, t.temperature(g.materialState(0)));
    }

    @Test void tinyAirVolumesUseBoundedImplicitTransport() {
        var tiny = new FlowShape.Builder().solidBox(0, 0, 0, 16, 16, 15)
                .solidBox(0, 0, 15, 16, 15, 16).build();
        var f = new FlowGeometry.Builder(2, 1, 1).openExterior(true).cell(0, 0, 0, tiny).cell(1, 0, 0, tiny).build();
        var g = geometry(f, 0, 0); var t = transport(g); fill(t, g, 10); t.setTemperature(0, 90);
        var result = t.advance(10, uniformFlux(f, 20, 0, 0), filled(f.faces(), 10)); ready(result);
        assertEquals(1.0 / 256, g.capacity(0)); assertTrue(result.sweeps() <= 3);
        assertTrue(t.temperature(0) > 10 && t.temperature(0) < 11);
        assertTrue(t.temperature(1) > 10 && t.temperature(1) < 11);
    }

    @Test void disconnectedPocketsDoNotMixAcrossThinPartition() {
        var pane = new FlowShape.Builder().solidBox(7, 0, 0, 9, 16, 16).build();
        var f = new FlowGeometry.Builder(1, 1, 1).cell(0, 0, 0, pane).build();
        var g = geometry(f, 1, 0); var t = transport(g); fill(t, g, 10); t.setTemperature(0, 80);
        ready(t.advance(10, new double[f.faces()], new double[f.faces()]));
        assertEquals(80, t.temperature(0)); assertEquals(10, t.temperature(1));
    }

    @Test void oneSubcellCavityAndFluidOccupancyHaveNoInventedThermalConnections() {
        var cavity = new FlowShape.Builder().solidBox(0, 0, 0, 7, 16, 16).solidBox(8, 0, 0, 16, 16, 16)
                .solidBox(7, 0, 0, 8, 7, 16).solidBox(7, 8, 0, 8, 16, 16)
                .solidBox(7, 7, 0, 8, 8, 7).solidBox(7, 7, 8, 8, 8, 16).build();
        var f = new FlowGeometry.Builder(2, 1, 1).cell(0, 0, 0, cavity).cell(1, 0, 0, FlowShape.WATER).build();
        var g = geometry(f, 0, .035); var t = transport(g); fill(t, g, 10); t.setTemperature(0, 70);
        assertEquals(1.0 / 4096, g.capacity(0)); assertEquals(-1, g.materialState(1));
        double before = t.energy(); ready(t.advance(10, new double[0], new double[0]));
        assertEquals(before, t.energy(), 1e-8); assertTrue(t.temperature(0) < 70);
    }

    @Test void coldInflowHasAnalyticSolutionWithoutTemperatureClamping() {
        var f = new FlowGeometry.Builder(1, 1, 1).openExterior(true).build();
        var g = geometry(f, 0, 0); var t = transport(g); t.setTemperature(0, -10);
        ready(t.advance(2, uniformFlux(f, 3, 0, 0), filled(f.faces(), -80)));
        assertEquals((-10 - 6 * 80) / 7.0, t.temperature(0), 1e-10);
    }

    @Test void largeDomainResolvesSmallHeatChangesAgainstLargeStoredEnergy() {
        var f = new FlowGeometry.Builder(48, 48, 48).openExterior(true).build();
        var g = geometry(f, 0, 0); var t = transport(g); fill(t, g, 20);
        t.setTemperature(f.nodeAt(24.5, 24.5, 24.5), 60);
        var result = t.advance(.25, uniformFlux(f, 6, 0, 2), filled(f.faces(), 20)); ready(result);
        assertEquals(result.energyAfter(), t.energy(), 1e-8);
    }

    @Test void solidWallRetainsHeatAndTransfersOnlyThroughMaterialLinks() {
        var f = new FlowGeometry.Builder(3, 1, 1).cell(1, 0, 0, FlowShape.FULL).build();
        var g = geometry(f, 0, .035); var t = transport(g); fill(t, g, 10); t.setTemperature(0, 80);
        assertEquals(0, f.faces()); assertEquals(2, g.conductance.length);
        double before = t.energy(); ready(t.advance(10, new double[0], new double[0]));
        assertTrue(t.temperature(g.materialState(1)) > 10); assertTrue(t.temperature(1) > 10);
        assertTrue(t.temperature(g.materialState(1)) > t.temperature(1)); assertEquals(before, t.energy(), 1e-8);
    }

    @Test void materialContactUsesOverlappingPatchesAndActualMaterialOwner() {
        var bottom = new FlowShape.Builder().solidBox(0, 0, 0, 16, 8, 16).build();
        var top = new FlowShape.Builder().solidBox(0, 8, 0, 16, 16, 16).build();
        var f = new FlowGeometry.Builder(2, 1, 1).cell(0, 0, 0, bottom).cell(1, 0, 0, top).build();
        var g = geometry(f, 0, .035);
        double[] area = new double[f.nodes()];
        double perArea = 1 / (.15 + .5 / .035);
        for (int e = 0; e < g.low.length; e++) {
            assertTrue(g.low[e] < f.nodes()); assertTrue(g.high[e] >= f.nodes());
            area[g.low[e]] += g.conductance[e] / perArea;
        }
        for (int i = 0; i < f.nodes(); i++) { assertEquals(f.solidInterfaceArea(i), area[i], 1e-12); }
        assertEquals(4, g.conductance.length); // own horizontal surface and neighboring half-face for each pocket
    }

    @Test void solidConductionHasAnalyticTwoCapacitySolution() {
        var f = new FlowGeometry.Builder(2, 1, 1).cell(0, 0, 0, FlowShape.FULL).cell(1, 0, 0, FlowShape.FULL).build();
        var g = geometry(f, 0, .035); var t = transport(g); t.setTemperature(0, 80); t.setTemperature(1, 10);
        ready(t.advance(1, new double[0], new double[0]));
        double difference = 70 / (1 + 2 * .035 / 3.5);
        assertEquals(45 + difference / 2, t.temperature(0), 1e-9);
        assertEquals(45 - difference / 2, t.temperature(1), 1e-9);
    }

    @Test void responseScaleChangesThermalTimeWithoutChangingWind() {
        var f = new FlowGeometry.Builder(4, 1, 1).openExterior(true).build(); var g = geometry(f, 0, 0);
        var a = transport(g); var b = transport(g); fill(a, g, 10); fill(b, g, 10);
        a.setTemperature(0, 80); b.setTemperature(0, 80); double[] q = uniformFlux(f, 3, 0, 0), original = q.clone();
        ready(a.advance(ThermalClock.thermalSeconds(.5, 2), q, filled(f.faces(), 10)));
        ready(b.advance(1, q, filled(f.faces(), 10)));
        for (int i = 0; i < g.states(); i++) { assertEquals(a.temperature(i), b.temperature(i)); }
        assertArrayEquals(original, q);
    }

    @Test void failureAndInvalidInputsRetainAllPublishedTemperatures() {
        var f = new FlowGeometry.Builder(3, 2, 1).build(); var g = geometry(f, 1, .035);
        var t = new ThermalTransport(g, new ThermalTransport.Settings(1, 1e-14, 1e-14, 1e-7));
        fill(t, g, 10); t.setTemperature(0, 80); double before = t.energy();
        assertEquals(ThermalTransport.Status.NOT_CONVERGED, t.advance(10, new double[f.faces()], new double[f.faces()]).status());
        assertEquals(80, t.temperature(0)); assertEquals(10, t.temperature(1)); assertEquals(before, t.energy());
        double[] q = new double[f.faces()]; q[0] = 1;
        assertEquals(ThermalTransport.Status.UNBALANCED_FLOW, t.advance(1, q, new double[q.length]).status());
        q[0] = Double.NaN;
        assertEquals(ThermalTransport.Status.INVALID_INPUT, t.advance(1, q, new double[q.length]).status());
        assertEquals(ThermalTransport.Status.INVALID_INPUT, t.advance(Double.NaN, new double[q.length], new double[q.length]).status());
        assertEquals(before, t.energy());
    }

    @Test void rejectsUninitializedOrDifferentGeometryFlowAndInvalidSettings() {
        var f = new FlowGeometry.Builder(1, 1, 1).build(); var g = geometry(f, 0, 0); var t = transport(g);
        var other = FlowGridTest.grid(new FlowGeometry.Builder(1, 1, 1).build()); FlowGridTest.ready(other.projectInitial());
        assertEquals(ThermalTransport.Status.INVALID_INPUT, t.step(1, other, new double[0]).status());
        assertEquals(ThermalTransport.Status.INVALID_INPUT, t.step(1, FlowGridTest.grid(f), new double[0]).status());
        assertThrows(IllegalArgumentException.class, () -> t.setTemperature(0, -273.15));
        assertThrows(IllegalArgumentException.class, () -> new ThermalTransport.Settings(0, 1e-9, 1e-8, 1e-7));
        assertThrows(IllegalArgumentException.class, () -> new ThermalGeometry.Coefficients(-1, .15, .035, 3.5));
    }
}
