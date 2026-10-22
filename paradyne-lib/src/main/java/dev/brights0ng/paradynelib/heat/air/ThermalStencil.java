// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;

/** Cached unique-neighbor rows of the same thermal operator. Diffusion and directed advection share one entry.
 * Topology is immutable; face offsets route new volume fluxes without row searches during a step.
 */
final class ThermalStencil {
    final int[] start, column, faceLowOffset, faceHighOffset;
    final double[] base, diffusionDiagonal, referenceRhs;

    ThermalStencil(ThermalGeometry g) {
        var f = g.flow;
        int n = g.states(); int[] rawStart = new int[n + 1];
        for (int e = 0; e < g.low.length; e++) { rawStart[g.low[e] + 1]++; rawStart[g.high[e] + 1]++; }
        for (int e = 0; e < f.faces(); e++) {
            if (!f.exterior(e)) { rawStart[f.low[e] + 1]++; rawStart[f.high[e] + 1]++; }
        }
        for (int i = 0; i < n; i++) { rawStart[i + 1] += rawStart[i]; }
        int[] rawColumn = new int[rawStart[n]], cursor = rawStart.clone();
        for (int e = 0; e < g.low.length; e++) {
            rawColumn[cursor[g.low[e]]++] = g.high[e]; rawColumn[cursor[g.high[e]]++] = g.low[e];
        }
        for (int e = 0; e < f.faces(); e++) {
            if (!f.exterior(e)) { rawColumn[cursor[f.low[e]]++] = f.high[e]; rawColumn[cursor[f.high[e]]++] = f.low[e]; }
        }
        start = new int[n + 1]; int used = 0;
        int[] columns = new int[rawColumn.length];
        for (int i = 0; i < n; i++) {
            start[i] = used;
            for (int p = rawStart[i]; p < rawStart[i + 1]; p++) {
                int at = start[i]; while (at < used && columns[at] != rawColumn[p]) { at++; }
                if (at == used) { columns[used++] = rawColumn[p]; }
            }
        }
        start[n] = used; column = Arrays.copyOf(columns, used); base = new double[used];
        diffusionDiagonal = new double[n]; referenceRhs = new double[n];
        for (int e = 0; e < g.low.length; e++) {
            base[find(g.low[e], g.high[e])] += g.conductance[e];
            base[find(g.high[e], g.low[e])] += g.conductance[e];
        }
        for (int i = 0; i < n; i++) for (int p = start[i]; p < start[i + 1]; p++) { diffusionDiagonal[i] += base[p]; }
        faceLowOffset = new int[f.faces()]; faceHighOffset = new int[f.faces()];
        Arrays.fill(faceLowOffset, -1); Arrays.fill(faceHighOffset, -1);
        for (int e = 0; e < f.faces(); e++) {
            if (!f.exterior(e)) {
                faceLowOffset[e] = find(f.low[e], f.high[e]); faceHighOffset[e] = find(f.high[e], f.low[e]);
            }
        }
    }
    private int find(int row, int neighbor) {
        for (int p = start[row]; p < start[row + 1]; p++) { if (column[p] == neighbor) { return p; } }
        throw new IllegalStateException("Missing thermal neighbor");
    }
    void reference(double[] background) {
        Arrays.fill(referenceRhs, 0);
        for (int i = 0; i < background.length; i++) {
            double correction = 0;
            for (int p = start[i]; p < start[i + 1]; p++) {
                if (column[p] < background.length) { correction += base[p] * (background[i] - background[column[p]]); }
            }
            referenceRhs[i] = correction;
        }
    }
    void assemble(ThermalGeometry g, double seconds, double[] temperature, double[] flux,
                  double[] diagonal, double[] rhs, double[] weights, double[] candidate) {
        System.arraycopy(base, 0, weights, 0, base.length);
        for (int i = 0; i < diagonal.length; i++) {
            double mass = g.capacity[i] / seconds;
            diagonal[i] = mass + diffusionDiagonal[i]; rhs[i] = mass * temperature[i] + referenceRhs[i]; candidate[i] = temperature[i];
        }
        for (int e = 0; e < flux.length; e++) {
            if (faceLowOffset[e] < 0) { continue; }
            double q = flux[e];
            if (q >= 0) { diagonal[g.flow.low[e]] += q; weights[faceHighOffset[e]] += q; }
            else { diagonal[g.flow.high[e]] -= q; weights[faceLowOffset[e]] -= q; }
        }
    }
}
