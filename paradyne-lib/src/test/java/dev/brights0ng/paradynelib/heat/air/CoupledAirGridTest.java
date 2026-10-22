// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoupledAirGridTest {
    static CoupledAirGrid grid(ThermalGeometry g, FlowBoundary b, FlowSettings fs, ThermalTransport.Settings ts) {
        var result = new CoupledAirGrid(g, b, fs, ts);
        for (int i = 0; i < g.states(); i++) { result.setTemperature(i, 20); }
        FlowGridTest.ready(result.initialize(0, 0, 0)); return result;
    }
    static void ready(CoupledAirGrid.Step step) {
        assertTrue(step.ready(), step.toString());
        assertTrue(step.flow().pressure().maxDivergence() <= 1e-7, step.toString());
        assertEquals(0, step.energyError(), 2e-8, step.toString());
    }
    static ThermalTransport.Source source(int i, double q, double limit) { return new ThermalTransport.Source(i, q, limit); }

    @Test void aSourceGeneratesAnEvolvingPlumeOrColdDescentAndReturnFlow() {
        var f = new FlowGeometry.Builder(5, 6, 3).build(); var g = ThermalTransportTest.geometry(f, .1, .035);
        for (double sign : new double[]{1, -1}) {
            var t = grid(g, FlowBoundary.builder(f).build(), FlowGridTest.accurate(), ThermalTransport.Settings.defaults());
            int node = f.nodeAt(2.5, sign > 0 ? 1.5 : 4.5, 1.5);
            t.setSources(source(node, sign * 20, 20 + sign * 40)); double before = t.energy(), supplied = 0;
            var bg = ThermalTransportTest.filled(f.nodes(), 20);
            for (int step = 0; step < 40; step++) {
                var result = t.step(.25, 1, bg, new double[f.faces()]); ready(result); supplied += result.sourceEnergy();
                assertEquals(0, result.exteriorEnergy());
            }
            int center = f.nodeAt(2.5, 3.5, 1.5), side = f.nodeAt(.5, 3.5, 1.5);
            assertTrue(t.componentVelocity(center, 1) * sign > .001);
            assertTrue(t.componentVelocity(side, 1) * sign < -.001);
            assertTrue((t.temperature(center) - 20) * sign > .01);
            assertEquals(before + supplied, t.energy(), 1e-6);
        }
    }

    @Test void unheatedAmbientAltitudeProfileDoesNotCreateCirculation() {
        var f = new FlowGeometry.Builder(3, 5, 3).build(); var g = ThermalTransportTest.geometry(f, 1, .035);
        var t = grid(g, FlowBoundary.builder(f).build(), FlowGridTest.accurate(), ThermalTransport.Settings.defaults());
        double[] bg = new double[f.nodes()];
        for (int i = 0; i < bg.length; i++) { bg[i] = 20 - .2 * f.cy[i]; t.setTemperature(i, bg[i]); }
        for (int step = 0; step < 5; step++) { ready(t.step(.5, 1, bg, new double[f.faces()])); }
        for (int i = 0; i < f.nodes(); i++) { assertEquals(bg[i], t.temperature(i), 1e-9); }
        for (int e = 0; e < f.faces(); e++) { assertEquals(0, t.faceFlux(e), 1e-9); }
    }

    @Test void responseScaleChangesSourceResponseWhileKeepingPhysicalStepExplicit() {
        var f = new FlowGeometry.Builder(1, 1, 1).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
        for (double scale : new double[]{.5, 1, 2, 10}) {
            var t = grid(g, FlowBoundary.builder(f).build(), FlowGridTest.accurate(), ThermalTransport.Settings.defaults());
            t.setSources(source(0, 2, Double.NaN)); var result = t.step(.25, scale, new double[]{20}, new double[0]); ready(result);
            assertEquals(.25, result.physicalSeconds()); assertEquals(.25 * scale, result.thermalSeconds());
            assertEquals(20 + .5 * scale, t.temperature(0)); assertEquals(.5 * scale, result.sourceEnergy());
        }
    }

    @Test void realOpeningsVentilateTheHeatedRoomButClosedWallsDoNot() {
        double[] warmth = new double[2];
        for (int open = 0; open < 2; open++) {
            var builder = new FlowGeometry.Builder(9, 5, 5).openExterior(true);
            for (int x = 2; x <= 6; x++) for (int y = 1; y <= 3; y++) for (int z = 1; z <= 3; z++) {
                boolean shell = x == 2 || x == 6 || y == 1 || y == 3 || z == 1 || z == 3;
                boolean doorway = open == 1 && (x == 2 || x == 6) && y == 2 && z == 2;
                if (shell && !doorway) { builder.cell(x, y, z, FlowShape.FULL); }
            }
            var f = builder.build(); var g = ThermalTransportTest.geometry(f, .1, 0);
            var t = grid(g, FlowBoundary.wind(f, 3, 0, 0), FlowGridTest.accurate(), ThermalTransport.Settings.defaults());
            int source = f.nodeAt(4.5, 2.5, 2.5); t.setSources(source(source, 20, 65));
            var bg = ThermalTransportTest.filled(f.nodes(), 20); var ext = ThermalTransportTest.filled(f.faces(), 20);
            for (int step = 0; step < 40; step++) { ready(t.step(.25, 1, bg, ext)); }
            for (int x = 3; x <= 5; x++) { warmth[open] += (t.temperature(f.nodeAt(x + .5, 2.5, 2.5)) - 20) / 3; }
            double draft = t.componentVelocity(source, 0);
            if (open == 0) { assertEquals(0, draft, 1e-8); }
            else { assertTrue(draft > .1); }
        }
        assertTrue(warmth[0] > 1.5 * warmth[1], "Sealed/open warmth: " + warmth[0] + "/" + warmth[1]);
        assertTrue(warmth[1] > 0);
    }

    @Test void flowSpeedFailureRollsBackFirstHalfAndDoesNotConsumeSourceEnergy() {
        var f = new FlowGeometry.Builder(3, 4, 3).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
        var fs = new FlowSettings(512, 1e-10, 1e-12, 1e-7, 1, 0, 1, .001, 3000);
        var t = grid(g, FlowBoundary.builder(f).build(), fs, ThermalTransport.Settings.defaults());
        t.setSources(source(f.nodeAt(1.5, 1.5, 1.5), 20, Double.NaN)); double before = t.energy();
        var result = t.step(.5, 1, ThermalTransportTest.filled(f.nodes(), 20), new double[f.faces()]);
        assertEquals(CoupledAirGrid.Status.FLOW_FAILED, result.status()); assertEquals(FlowGrid.Status.SPEED_LIMIT, result.flow().status());
        assertEquals(before, t.energy()); assertEquals(0, result.sourceEnergy());
        for (int i = 0; i < g.states(); i++) { assertEquals(20, t.temperature(i)); }
        for (int e = 0; e < f.faces(); e++) { assertEquals(0, t.faceFlux(e)); }
    }

    @Test void pressureFailureRollsBackFirstHalf() {
        var f = new FlowGeometry.Builder(5, 5, 3).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
        var fs = new FlowSettings(1, 1e-10, 1e-12, 1e-7, 1, 0, 1, 100, 3000);
        var t = grid(g, FlowBoundary.builder(f).build(), fs, ThermalTransport.Settings.defaults());
        t.setSources(source(f.nodeAt(2.5, 1.5, 1.5), 20, Double.NaN)); double before = t.energy();
        var result = t.step(.5, 1, ThermalTransportTest.filled(f.nodes(), 20), new double[f.faces()]);
        assertEquals(CoupledAirGrid.Status.FLOW_FAILED, result.status()); assertEquals(FlowGrid.Status.PRESSURE_FAILED, result.flow().status());
        assertEquals(before, t.energy());
    }

    @Test void secondHalfFailureRestoresThermalVelocityAndPressureWarmStart() {
        var f = new FlowGeometry.Builder(5, 5, 3).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
        var ts = new ThermalTransport.Settings(1, 1e-9, 1e-8, 1e-7);
        var t = grid(g, FlowBoundary.builder(f).build(), FlowGridTest.accurate(), ts);
        t.setSources(source(f.nodeAt(2.5, 1.5, 1.5), 20, Double.NaN)); double before = t.energy();
        var bg = ThermalTransportTest.filled(f.nodes(), 20);
        var result = t.step(.5, 1, bg, new double[f.faces()]);
        assertEquals(CoupledAirGrid.Status.SECOND_THERMAL_FAILED, result.status(), result.toString());
        assertTrue(result.first().ready()); assertTrue(result.flow().ready()); assertFalse(result.second().ready());
        assertEquals(before, t.energy()); assertEquals(0, result.sourceEnergy());
        for (int e = 0; e < f.faces(); e++) { assertEquals(0, t.faceFlux(e)); }
        t.setSources(); result = t.step(.5, 1, bg, new double[f.faces()]); ready(result);
        assertEquals(0, result.flow().pressure().iterations(), "Rejected pressure must not become the next warm start");
    }

    @Test void firstHalfFailureRetainsState() {
        var f = new FlowGeometry.Builder(3, 3, 2).build(); var g = ThermalTransportTest.geometry(f, 1, 0);
        var t = grid(g, FlowBoundary.builder(f).build(), FlowGridTest.accurate(), new ThermalTransport.Settings(1, 1e-12, 1e-12, 1e-7));
        t.setSources(source(0, 20, Double.NaN)); double before = t.energy();
        var result = t.step(.5, 1, ThermalTransportTest.filled(f.nodes(), 20), new double[f.faces()]);
        assertEquals(CoupledAirGrid.Status.FIRST_THERMAL_FAILED, result.status()); assertEquals(before, t.energy());
    }

    @Test void finalTemperatureBoundRejectsWholeStepWithoutClipping() {
        var f = new FlowGeometry.Builder(1, 1, 1).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
        var fs = new FlowSettings(512, 1e-10, 1e-12, 1e-7, 1, 0, 1, 100, 20.75);
        var t = grid(g, FlowBoundary.builder(f).build(), fs, ThermalTransport.Settings.defaults()); t.setSources(source(0, 1, Double.NaN));
        var result = t.step(1, 1, new double[]{20}, new double[0]);
        assertEquals(CoupledAirGrid.Status.TEMPERATURE_LIMIT, result.status()); assertEquals(20, t.temperature(0));
        assertEquals(0, result.sourceEnergy());
    }

    @Test void invalidPhysicalCatchupAndResponseScaleCannotAdvanceCoupledState() {
        var f = new FlowGeometry.Builder(1, 1, 1).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
        var t = grid(g, FlowBoundary.builder(f).build(), FlowGridTest.accurate(), ThermalTransport.Settings.defaults());
        t.setSources(source(0, 2, Double.NaN));
        assertEquals(CoupledAirGrid.Status.INVALID_INPUT, t.step(10, 1, new double[]{20}, new double[0]).status());
        assertEquals(CoupledAirGrid.Status.INVALID_INPUT, t.step(1, 0, new double[]{20}, new double[0]).status());
        assertEquals(CoupledAirGrid.Status.INVALID_INPUT, t.step(1, 1, new double[]{Double.NaN}, new double[0]).status());
        assertEquals(20, t.temperature(0));
    }
}
