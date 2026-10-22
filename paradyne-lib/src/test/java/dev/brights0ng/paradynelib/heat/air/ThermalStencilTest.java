// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThermalStencilTest {
    @Test void cachedReciprocalsPreserveExtremeRepresentableStorage() {
        for (double capacity : new double[]{1e-310, 1e308}) {
            var f = new FlowGeometry.Builder(1, 1, 1).cell(0, 0, 0, FlowShape.FULL).build();
            var g = new ThermalGeometry(f, new double[]{1}, new double[]{capacity},
                    new ThermalGeometry.Coefficients(0, 1, 0, 1));
            double temperature = capacity > 1 ? 1e-300 : 20;
            var t = new ThermalTransport(g, ThermalTransport.Settings.defaults(), ThermalTransport.Mode.COMPACT_CACHED);
            t.setTemperature(0, temperature);
            var result = t.advance(1, new double[f.faces()], new double[f.faces()]);
            ThermalTransportTest.ready(result);
            assertEquals(temperature, t.temperature(0), Math.abs(temperature) * 1e-12);
            assertEquals(0, result.energyError(), 1e-8);
        }
    }

    @Test void compactRowsContainEachNeighborOnceIncludingMixingAndAdvection() {
        var f = new FlowGeometry.Builder(5, 4, 3).build(); var g = ThermalTransportTest.geometry(f, 1, .035);
        var s = new ThermalStencil(g); int internal = 0;
        for (int e = 0; e < f.faces(); e++) { if (!f.exterior(e)) { internal++; } }
        assertEquals(2 * internal, s.column.length);
        assertEquals(s.column.length * 2, 2 * g.low.length + 2 * internal);
        for (int i = 0; i < g.states(); i++) {
            var neighbors = new HashSet<Integer>();
            for (int p = s.start[i]; p < s.start[i + 1]; p++) { assertTrue(neighbors.add(s.column[p])); }
        }
    }

    @Test void assembledRowsAgreeWithIndependentSignedFluxAndExchangeBudget() {
        var slab = new FlowShape.Builder().solidBox(0, 0, 0, 16, 8, 16).build();
        var f = new FlowGeometry.Builder(4, 4, 3).cell(1, 1, 1, slab).cell(2, 1, 1, FlowShape.FULL).build();
        var g = ThermalTransportTest.geometry(f, .7, .035); var s = new ThermalStencil(g);
        double[] bg = new double[f.nodes()], forcing = new double[f.nodes()];
        for (int i = 0; i < bg.length; i++) { bg[i] = 20 - .2 * f.cy[i]; forcing[i] = bg[i] + (i % 3 == 0 ? 15 : 0); }
        var flow = FlowGridTest.grid(f); FlowGridTest.ready(flow.projectInitial()); FlowGridTest.ready(flow.step(.5, forcing, bg));
        double[] q = new double[f.faces()]; for (int e = 0; e < q.length; e++) { q[e] = flow.faceFlux(e); }
        int n = g.states(); double[] old = new double[n], x = new double[n], diag = new double[n], rhs = new double[n];
        double[] weights = new double[s.column.length], candidate = new double[n], independent = new double[n]; double dt = .75;
        for (int i = 0; i < n; i++) { old[i] = 20 + Math.sin(i); x[i] = 25 + Math.cos(i); independent[i] = g.capacity[i] / dt * (x[i] - old[i]); }
        for (int e = 0; e < g.low.length; e++) {
            int a = g.low[e], b = g.high[e];
            double offset = a < bg.length && b < bg.length ? bg[a] - bg[b] : 0;
            double heat = g.conductance[e] * (x[a] - x[b] - offset);
            independent[a] += heat; independent[b] -= heat;
        }
        for (int e = 0; e < q.length; e++) {
            double heat = q[e] * x[q[e] >= 0 ? f.low[e] : f.high[e]];
            independent[f.low[e]] += heat; independent[f.high[e]] -= heat;
        }
        s.reference(bg); s.assemble(g, dt, old, q, diag, rhs, weights, candidate);
        for (int i = 0; i < n; i++) {
            double actual = diag[i] * x[i] - rhs[i];
            for (int p = s.start[i]; p < s.start[i + 1]; p++) { actual -= weights[p] * x[s.column[p]]; }
            assertEquals(independent[i], actual, 2e-12, "row " + i);
        }
    }

    @Test void allModesAgreeAcrossFluxReversalMaterialStorageAndSourceReferenceChanges() {
        var pane = new FlowShape.Builder().solidBox(7, 0, 0, 9, 16, 16).build();
        var slab = new FlowShape.Builder().solidBox(0, 0, 0, 16, 8, 16).build();
        var f = new FlowGeometry.Builder(6, 4, 3).openExterior(true).cell(2, 1, 1, pane).cell(3, 1, 1, slab).build();
        var g = ThermalTransportTest.geometry(f, 1, .035);
        var a = new ThermalTransport(g, ThermalTransport.Settings.defaults(), ThermalTransport.Mode.REFERENCE);
        var b = new ThermalTransport(g, ThermalTransport.Settings.defaults(), ThermalTransport.Mode.COMPACT_EVERY_SWEEP);
        var c = new ThermalTransport(g, ThermalTransport.Settings.defaults(), ThermalTransport.Mode.COMPACT_ADAPTIVE);
        var d = new ThermalTransport(g, ThermalTransport.Settings.defaults(), ThermalTransport.Mode.COMPACT_DIRECTED);
        var cached = new ThermalTransport(g, ThermalTransport.Settings.defaults(), ThermalTransport.Mode.COMPACT_CACHED);
        var grids = new ThermalTransport[]{a, b, c, d, cached};
        for (var t : grids) {
            for (int i = 0; i < g.states(); i++) { t.setTemperature(i, 20 + 5 * Math.sin(i)); }
            t.setSources(new ThermalTransport.Source(0, 15, 30), new ThermalTransport.Source(2, -5, 15));
        }
        for (int step = 0; step < 18; step++) {
            double direction = step < 9 ? 1 : -1;
            var flow = new FlowGrid(f, FlowBoundary.wind(f, direction * 2, 0, direction * .5), FlowGridTest.accurate());
            flow.seedUniform(direction * 2, 0, direction * .5); FlowGridTest.ready(flow.projectInitial());
            double[] bg = new double[f.nodes()];
            for (int i = 0; i < bg.length; i++) { bg[i] = (step < 9 ? 20 : 23) - .1 * f.cy[i]; }
            ThermalTransport.Step[] results = new ThermalTransport.Step[grids.length];
            for (int m = 0; m < grids.length; m++) {
                grids[m].setMixingReference(bg);
                results[m] = grids[m].step(step % 3 == 0 ? .25 : step % 3 == 1 ? .5 : 1, flow, ThermalTransportTest.filled(f.faces(), 20));
                ThermalTransportTest.ready(results[m]);
            }
            for (int i = 0; i < g.states(); i++) {
                assertEquals(a.temperature(i), b.temperature(i), 1e-7, "compact state " + i);
                assertEquals(a.temperature(i), c.temperature(i), 1e-7, "adaptive state " + i);
                assertEquals(a.temperature(i), d.temperature(i), 1e-7, "directed state " + i);
                assertEquals(a.temperature(i), cached.temperature(i), 1e-7, "cached state " + i);
            }
            assertEquals(results[0].sourceEnergy(), results[2].sourceEnergy(), 1e-7);
            assertEquals(results[0].exteriorEnergy(), results[2].exteriorEnergy(), 1e-7);
            assertEquals(results[0].sourceEnergy(), results[3].sourceEnergy(), 1e-7);
            assertEquals(results[0].exteriorEnergy(), results[3].exteriorEnergy(), 1e-7);
            assertEquals(results[0].sourceEnergy(), results[4].sourceEnergy(), 1e-7);
            assertEquals(results[0].exteriorEnergy(), results[4].exteriorEnergy(), 1e-7);
        }
    }

    @Test void adaptiveChecksAreReducedButCapAndAcceptanceAreAlwaysChecked() {
        var f = new FlowGeometry.Builder(5, 4, 3).build(); var g = ThermalTransportTest.geometry(f, 1, .035);
        var settings = ThermalTransport.Settings.defaults();
        var a = new ThermalTransport(g, settings, ThermalTransport.Mode.REFERENCE);
        var b = new ThermalTransport(g, settings, ThermalTransport.Mode.COMPACT_ADAPTIVE);
        for (var t : new ThermalTransport[]{a, b}) { ThermalTransportTest.fill(t, g, 20); t.setTemperature(0, 80); }
        var old = a.advance(1, new double[f.faces()], new double[f.faces()]);
        var optimized = b.advance(1, new double[f.faces()], new double[f.faces()]);
        ThermalTransportTest.ready(old); ThermalTransportTest.ready(optimized);
        assertEquals(old.sweeps(), old.residualChecks()); assertTrue(optimized.residualChecks() < optimized.sweeps());
        for (int i = 0; i < g.states(); i++) { assertEquals(a.temperature(i), b.temperature(i), 1e-7); }
        var capped = new ThermalTransport(g, new ThermalTransport.Settings(1, 1e-14, 1e-14, 1e-7), ThermalTransport.Mode.COMPACT_ADAPTIVE);
        ThermalTransportTest.fill(capped, g, 20); capped.setTemperature(0, 80);
        var failure = capped.advance(10, new double[f.faces()], new double[f.faces()]);
        assertFalse(failure.ready()); assertEquals(1, failure.residualChecks()); assertEquals(80, capped.temperature(0));
    }

    @Test void directedSweepsConvergeFasterForEitherWindDirectionAtTheSameAccuracy() {
        var f = new FlowGeometry.Builder(32, 1, 1).openExterior(true).build(); var g = ThermalTransportTest.geometry(f, 1, 0);
        for (double sign : new double[]{1, -1}) {
            var a = new ThermalTransport(g, ThermalTransport.Settings.defaults(), ThermalTransport.Mode.COMPACT_ADAPTIVE);
            var b = new ThermalTransport(g, ThermalTransport.Settings.defaults(), ThermalTransport.Mode.COMPACT_DIRECTED);
            for (var t : new ThermalTransport[]{a, b}) { ThermalTransportTest.fill(t, g, 20); t.setTemperature(16, 80); }
            var q = ThermalTransportTest.uniformFlux(f, sign * 6, 0, 0); var ext = ThermalTransportTest.filled(f.faces(), 20);
            var reference = a.advance(.5, q, ext); var directed = b.advance(.5, q, ext);
            ThermalTransportTest.ready(reference); ThermalTransportTest.ready(directed);
            assertTrue(directed.sweeps() < reference.sweeps(), reference.sweeps() + " / " + directed.sweeps());
            for (int i = 0; i < g.states(); i++) { assertEquals(a.temperature(i), b.temperature(i), 1e-7); }
        }
    }

    @Test void directedOrderSurvivesAllOctantChangesAndCalmFallbackWithoutLosingStates() {
        var f = new FlowGeometry.Builder(4, 4, 4).openExterior(true).build(); var g = ThermalTransportTest.geometry(f, .5, 0);
        var a = new ThermalTransport(g, ThermalTransport.Settings.defaults(), ThermalTransport.Mode.REFERENCE);
        var b = new ThermalTransport(g, ThermalTransport.Settings.defaults(), ThermalTransport.Mode.COMPACT_DIRECTED);
        for (var t : new ThermalTransport[]{a, b}) {
            for (int i = 0; i < g.states(); i++) { t.setTemperature(i, 20 + 5 * Math.sin(i)); }
        }
        for (int octant = 0; octant < 9; octant++) {
            var q = ThermalTransportTest.uniformFlux(f, octant == 8 ? 0 : (octant & 1) == 0 ? 2 : -2,
                    octant == 8 ? 0 : (octant & 2) == 0 ? 2 : -2, octant == 8 ? 0 : (octant & 4) == 0 ? 2 : -2);
            var ext = ThermalTransportTest.filled(f.faces(), 20);
            ThermalTransportTest.ready(a.advance(.5, q, ext)); ThermalTransportTest.ready(b.advance(.5, q, ext));
            for (int i = 0; i < g.states(); i++) { assertEquals(a.temperature(i), b.temperature(i), 1e-7); }
        }
    }

    @Test void directedFailureAtTheWorkCapStillChecksAndRetainsState() {
        var f = new FlowGeometry.Builder(6, 3, 2).openExterior(true).build(); var g = ThermalTransportTest.geometry(f, 1, 0);
        var t = new ThermalTransport(g, new ThermalTransport.Settings(1, 1e-14, 1e-14, 1e-7), ThermalTransport.Mode.COMPACT_DIRECTED);
        ThermalTransportTest.fill(t, g, 20); t.setTemperature(0, 80); double before = t.energy();
        var result = t.advance(1, ThermalTransportTest.uniformFlux(f, -3, 0, 1), ThermalTransportTest.filled(f.faces(), 20));
        assertEquals(ThermalTransport.Status.NOT_CONVERGED, result.status()); assertEquals(1, result.residualChecks());
        assertEquals(before, t.energy()); assertEquals(80, t.temperature(0));
    }
}
