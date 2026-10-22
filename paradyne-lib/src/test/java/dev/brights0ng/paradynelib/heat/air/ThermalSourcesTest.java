// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThermalSourcesTest {
    private static ThermalTransport.Source source(int i, double power, double limit) {
        return new ThermalTransport.Source(i, power, limit);
    }
    @Test void thermostatDeliversOnlyEnergyNeededInBothDirections() {
        var f = new FlowGeometry.Builder(1, 1, 1).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
        for (double sign : new double[]{1, -1}) {
            var t = ThermalTransportTest.transport(g); t.setTemperature(0, 20);
            t.setSources(source(0, sign * 10, 20 + sign * 5));
            var result = t.advance(1, new double[0], new double[0]); ThermalTransportTest.ready(result);
            assertEquals(20 + sign * 5, t.temperature(0)); assertEquals(sign * 5, result.sourceEnergy());
            assertEquals(sign * 10, result.requestedSourceEnergy()); assertEquals(sign * 5, result.withheldSourceEnergy());
            assertEquals(result.energyAfter(), result.energyBefore() + result.sourceEnergy(), 1e-9);
            t.setTemperature(0, 20 + sign * 10);
            result = t.advance(1, new double[0], new double[0]); ThermalTransportTest.ready(result);
            assertEquals(20 + sign * 10, t.temperature(0)); assertEquals(0, result.sourceEnergy());
        }
    }
    @Test void thermostatAllowsUnrelatedHeatToExceedItsLimit() {
        var f = new FlowGeometry.Builder(2, 1, 1).build(); var g = ThermalTransportTest.geometry(f, 1, 0);
        var t = ThermalTransportTest.transport(g); t.setTemperature(0, 50); t.setTemperature(1, 100);
        t.setSources(source(0, 100, 25));
        var result = t.advance(1, new double[f.faces()], new double[f.faces()]); ThermalTransportTest.ready(result);
        assertTrue(t.temperature(0) > 50); assertEquals(0, result.sourceEnergy());
    }
    @Test void sourcePowerIsSummedAndStrictestNetDirectionLimitWins() {
        var f = new FlowGeometry.Builder(1, 1, 1).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
        for (double sign : new double[]{1, -1}) {
            var t = ThermalTransportTest.transport(g); t.setTemperature(0, 20);
            t.setSources(source(0, sign * 20, 20 + sign * 6), source(0, -sign * 5, 20 + sign * 4));
            var result = t.advance(1, new double[0], new double[0]); ThermalTransportTest.ready(result);
            assertEquals(20 + sign * 4, t.temperature(0)); assertEquals(sign * 4, result.sourceEnergy());
            assertEquals(sign * 15, result.requestedSourceEnergy());
            t.setSources(source(0, 20, 25), source(0, -20, 15));
            result = t.advance(1, new double[0], new double[0]); ThermalTransportTest.ready(result);
            assertEquals(0, result.sourceEnergy());
        }
    }
    @Test void thermostatBalancesVentilationAndStorageRatherThanClampingAwayHeat() {
        var f = new FlowGeometry.Builder(1, 1, 1).openExterior(true).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
        var t = ThermalTransportTest.transport(g); t.setTemperature(0, 20); t.setSources(source(0, 100, 25));
        var result = t.advance(1, ThermalTransportTest.uniformFlux(f, 2, 0, 0), ThermalTransportTest.filled(f.faces(), 20));
        ThermalTransportTest.ready(result); assertEquals(25, t.temperature(0));
        assertEquals(15, result.sourceEnergy()); assertEquals(-10, result.exteriorEnergy());
        assertEquals(5, result.energyAfter() - result.energyBefore());
    }
    @Test void sourceLawMatchesLegacyIsolatedCellControl() {
        var p = AirParams.DEFAULT;
        var isolated = new AirParams(0, p.openingMix(), p.film(), 0, p.solidCap(), p.exposedLoss(), p.exposedWind(),
                p.convect(), p.convectMax(), p.stable(), p.rise(), p.riseMax(), p.maxStep(), p.sweeps(),
                p.powerUnit(), p.radiantK(), p.radiantMin(), p.radiantFloor());
        for (double sign : new double[]{1, -1}) {
            var legacy = new AirGrid(3, 3, 3, isolated); legacy.addSource(1, 1, 1, sign * 10, sign * 5);
            var f = new FlowGeometry.Builder(1, 1, 1).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
            var t = ThermalTransportTest.transport(g); t.setTemperature(0, 20); t.setSources(source(0, sign * 10, 20 + sign * 5));
            for (int i = 0; i < 8; i++) {
                legacy.step(.25); ThermalTransportTest.ready(t.advance(.25, new double[0], new double[0]));
                assertEquals(legacy.offset(1, 1, 1), t.temperature(0) - 20, 1e-12);
            }
        }
    }
    @Test void badSourceChangeRetainsPreviouslyPreparedSources() {
        var f = new FlowGeometry.Builder(1, 1, 1).build(); var g = ThermalTransportTest.geometry(f, 0, 0);
        var t = ThermalTransportTest.transport(g); t.setSources(source(0, 2, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> t.setSources(source(1, 99, Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> t.setSources(source(0, Double.MAX_VALUE, Double.NaN), source(0, Double.MAX_VALUE, Double.NaN)));
        ThermalTransportTest.ready(t.advance(1, new double[0], new double[0])); assertEquals(2, t.temperature(0));
        assertThrows(IllegalArgumentException.class, () -> source(0, Double.NaN, 20));
        assertThrows(IllegalArgumentException.class, () -> source(0, 2, Double.POSITIVE_INFINITY));
    }
    @Test void blockRoutingPreservesTotalAndPerFacePowerWithoutOpeningSourceBlock() {
        var f = new FlowGeometry.Builder(3, 3, 3).cell(1, 1, 1, FlowShape.FULL).build(); var g = ThermalTransportTest.geometry(f, 0, .035);
        int cell = f.index(1, 1, 1), originalFaces = f.faces();
        for (boolean perFace : new boolean[]{false, true}) {
            var sources = ThermalSources.fromBlock(g, cell, 12, 65, perFace); double power = 0;
            assertEquals(6, sources.length);
            for (var source : sources) { assertTrue(source.state() < f.nodes()); power += source.power(); }
            assertEquals(perFace ? 72 : 12, power, 1e-12);
        }
        assertEquals(originalFaces, f.faces()); assertEquals(-1, f.nodeAt(1.5, 1.5, 1.5));
    }
    @Test void splitNeighborSharesOneFacesPowerInsteadOfMultiplyingItByPocketCount() {
        var pane = new FlowShape.Builder().solidBox(0, 0, 7, 16, 16, 9).build();
        var f = new FlowGeometry.Builder(2, 1, 1).cell(0, 0, 0, FlowShape.FULL).cell(1, 0, 0, pane).build();
        var g = ThermalTransportTest.geometry(f, 0, .035);
        var sources = ThermalSources.fromBlock(g, 0, 12, Double.NaN, true);
        assertEquals(2, sources.length); assertEquals(6, sources[0].power()); assertEquals(6, sources[1].power());
    }
    @Test void enclosedSourceUsesMaterialFallbackAndPerFaceSourceHasNoAirOutput() {
        var f = new FlowGeometry.Builder(1, 1, 1).cell(0, 0, 0, FlowShape.FULL).build(); var g = ThermalTransportTest.geometry(f, 0, .035);
        var sources = ThermalSources.fromBlock(g, 0, 75, 65, false);
        assertEquals(1, sources.length); assertEquals(g.materialState(0), sources[0].state());
        assertEquals(0, ThermalSources.fromBlock(g, 0, 75, 65, true).length); assertEquals(0, f.faces());
        var t = ThermalTransportTest.transport(g); t.setSources(sources);
        var result = t.advance(1, new double[0], new double[0]); ThermalTransportTest.ready(result);
        assertEquals(75, result.sourceEnergy(), 1e-9);
    }
}
