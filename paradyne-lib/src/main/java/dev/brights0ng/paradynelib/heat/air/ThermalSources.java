// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.ArrayList;
import java.util.List;

/** Offline source routing on the same physical shapes, with no change to airflow openings.
 * Power is already converted with AirParams.gridPower; radiant power remains outside the air solver.
 * Limits are absolute Celsius, prepared by the caller from existing table/ambient limits.
 */
public final class ThermalSources {
    private ThermalSources() { }

    public static ThermalTransport.Source[] fromBlock(ThermalGeometry thermal, int cell, double power,
                                                      double limit, boolean perFace) {
        var g = thermal.flow;
        if (cell < 0 || cell >= g.shapes.length) { throw new IllegalArgumentException("Source cell outside geometry"); }
        // Validate power/limit even if this block ends up with no targets.
        new ThermalTransport.Source(0, power, limit);
        var shape = g.shapes[cell];
        List<ThermalTransport.Source> result = new ArrayList<>();
        if (shape.components() > 0) {
            double airVolume = 0;
            for (double v : shape.volume) { airVolume += v; }
            for (int k = 0; k < shape.components(); k++) {
                result.add(new ThermalTransport.Source(g.starts[cell] + k, power * shape.volume[k] / airVolume, limit));
            }
            return result.toArray(ThermalTransport.Source[]::new);
        }
        int x = cell % g.nx, y = (cell / g.nx) % g.ny, z = cell / (g.nx * g.ny);
        int faces = 0;
        for (int axis = 0; axis < 3; axis++) for (int side = 0; side < 2; side++) {
            int coordinate = axis == 0 ? x : axis == 1 ? y : z;
            int size = axis == 0 ? g.nx : axis == 1 ? g.ny : g.nz;
            if ((side == 0 && coordinate == 0) || (side == 1 && coordinate + 1 == size)) { continue; }
            int neighbor = cell + (side == 0 ? -1 : 1) * (axis == 0 ? 1 : axis == 1 ? g.nx : g.nx * g.ny);
            FlowShape adjacent = g.shapes[neighbor];
            double[] area = new double[adjacent.components()]; double total = 0;
            for (int v = 0; v < 16; v++) for (int u = 0; u < 16; u++) {
                int k = adjacent.patch(axis, 1 - side, u, v);
                if (k >= 0) { area[k]++; total++; }
            }
            if (total == 0) { continue; }
            faces++;
            for (int k = 0; k < area.length; k++) {
                if (area[k] > 0) { result.add(new ThermalTransport.Source(g.starts[neighbor] + k, power * area[k] / total, limit)); }
            }
        }
        if (!perFace && faces > 0) {
            for (int i = 0; i < result.size(); i++) {
                var s = result.get(i); result.set(i, new ThermalTransport.Source(s.state(), s.power() / faces, s.limit()));
            }
        }
        if (!perFace && faces == 0) {
            // Retain the legacy enclosed-source fallback: warm/cool its material; do not invent an air outlet.
            int state=thermal.material[cell]>=0?thermal.material[cell]:thermal.fluidState(cell);
            if(state>=0) { result.add(new ThermalTransport.Source(state, power, limit)); }
        }
        return result.toArray(ThermalTransport.Source[]::new);
    }
}
