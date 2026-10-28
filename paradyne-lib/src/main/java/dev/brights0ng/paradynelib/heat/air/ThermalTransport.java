// Updated by ChatGPT: reused the thermal operator for private stationary initialization.
// Written by ChatGPT; extended by ChatGPT with thermostat sources, coupled restoration, compact traversal, and cached reciprocals.
// Fixed by ChatGPT: include stationary fluid storage in wind-directed sweep orders.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;

/** Single-owner backward-Euler upwind transport plus symmetric air/material exchange.
 * Temperatures are absolute Celsius, not offsets; changing incoming weather cannot reset stored heat.
 * A step commits only after row residual and total energy budget pass. Work arrays are retained.
 * Prepared sources use the existing summed-power/strictest-limit thermostat law.
 * Radiation, scheduling, and momentum are owned separately.
 */
public final class ThermalTransport {
    /** Same equations and acceptance contract; reference retains duplicate neighbors and checks every sweep. */
    public enum Mode { REFERENCE, COMPACT_EVERY_SWEEP, COMPACT_ADAPTIVE, COMPACT_DIRECTED, COMPACT_CACHED }
    public static final Mode DEFAULT_MODE = Mode.COMPACT_CACHED;
    /** Already routed air/material power in C*air-cell/s; limit is absolute Celsius or NaN for unlimited. */
    public record Source(int state, double power, double limit) {
        public Source {
            if (state < 0 || !Double.isFinite(power) || !(Double.isNaN(limit) || supported(limit))) {
                throw new IllegalArgumentException("Invalid thermal source");
            }
        }
    }
    public enum Status { READY, INVALID_INPUT, UNBALANCED_FLOW, NOT_CONVERGED }
    public record Settings(int maxSweeps, double temperatureTolerance, double energyTolerance, double maxDivergence) {
        public Settings {
            if (maxSweeps < 1 || !positive(temperatureTolerance) || !positive(energyTolerance) || !positive(maxDivergence)) {
                throw new IllegalArgumentException("Positive finite solver settings required");
            }
        }
        public static Settings defaults() { return new Settings(512, 1e-9, 1e-8, 1e-7); }
    }
    public record Step(Status status, int sweeps, double maxTemperatureResidual, double energyBefore,
                       double energyAfter, double exteriorEnergy, double energyError, long nanos,
                       double requestedSourceEnergy, double sourceEnergy, int residualChecks,
                       long sweepNanos, long qualityNanos) {
        public boolean ready() { return status == Status.READY; }
        /** Signed withheld energy: negative for cooling sources. */
        public double withheldSourceEnergy() { return requestedSourceEnergy - sourceEnergy; }
    }
    private final ThermalGeometry g;
    private final Settings settings;
    private final Mode mode;
    private final ThermalStencil stencil;
    private final int[] start, column, face, exteriorFaces, directedOrder;
    private final int[] spatialOrder;
    private int directedKey = -1;
    private final double[] base, weight, diagonal, rhs, flux, balance, power, limit, mixingReference;
    private double[] temperature, candidate;
    private final double[] inverseDiagonal;

    public ThermalTransport(ThermalGeometry geometry, Settings settings) {
        this(geometry, settings, DEFAULT_MODE);
    }
    public ThermalTransport(ThermalGeometry geometry, Settings settings, Mode mode) {
        g = geometry; this.settings = settings; this.mode = java.util.Objects.requireNonNull(mode);
        int n = g.states(); var f = g.flow;
        if (mode == Mode.REFERENCE) {
            stencil = null; start = new int[n + 1];
            for (int e = 0; e < g.low.length; e++) { start[g.low[e] + 1]++; start[g.high[e] + 1]++; }
            for (int e = 0; e < f.faces(); e++) {
                if (!f.exterior(e)) { start[f.low[e] + 1]++; start[f.high[e] + 1]++; }
            }
            for (int i = 0; i < n; i++) { start[i + 1] += start[i]; }
            column = new int[start[n]]; face = new int[column.length]; Arrays.fill(face, -1);
            base = new double[column.length]; weight = new double[column.length];
            int[] cursor = start.clone();
            for (int e = 0; e < g.low.length; e++) {
                edge(cursor, g.low[e], g.high[e], -1, g.conductance[e]);
                edge(cursor, g.high[e], g.low[e], -1, g.conductance[e]);
            }
            for (int e = 0; e < f.faces(); e++) {
                if (!f.exterior(e)) { edge(cursor, f.low[e], f.high[e], e, 0); edge(cursor, f.high[e], f.low[e], e, 0); }
            }
        } else {
            stencil = new ThermalStencil(g); start = stencil.start; column = stencil.column; base = stencil.base;
            face = null; weight = new double[column.length];
        }
        int exteriorCount = 0;
        for (int e = 0; e < f.faces(); e++) { if (f.exterior(e)) { exteriorCount++; } }
        exteriorFaces = new int[exteriorCount]; exteriorCount = 0;
        for (int e = 0; e < f.faces(); e++) { if (f.exterior(e)) { exteriorFaces[exteriorCount++] = e; } }
        diagonal = new double[n]; rhs = new double[n]; temperature = new double[n]; candidate = new double[n];
        flux = new double[f.faces()]; balance = new double[f.nodes()];
        power = new double[n]; limit = new double[n]; Arrays.fill(limit, Double.NaN);
        mixingReference = new double[g.airStates()];
        directedOrder = mode == Mode.COMPACT_DIRECTED || mode == Mode.COMPACT_CACHED ? new int[n] : null;
        spatialOrder=directedOrder!=null && !f.layout.dense()?new int[f.cells()]:null;
        inverseDiagonal = mode == Mode.COMPACT_CACHED ? new double[n] : null;
    }
    private void edge(int[] cursor, int row, int col, int flowFace, double conductance) {
        int p = cursor[row]++; column[p] = col; face[p] = flowFace; base[p] = conductance;
    }
    private static boolean positive(double v) { return Double.isFinite(v) && v > 0; }
    private static boolean supported(double t) { return Double.isFinite(t) && t > -273.15; }
    public double temperature(int state) { return temperature[state]; }
    /** Mix local disturbances about the supplied ambient profile, preserving an unheated altitude gradient.
     * This affects only air/air diffusion, with opposite pair contributions; stored temperatures are unchanged.
     * Advection and material exchange continue to use absolute temperatures.
     */
    public void setMixingReference(double[] background) {
        if (background.length != mixingReference.length) { throw new IllegalArgumentException("Ambient profile must match air states"); }
        for (double t : background) { if (!supported(t)) { throw new IllegalArgumentException("Unsupported ambient temperature"); } }
        boolean changed = false;
        for (int i = 0; i < background.length; i++) { if (mixingReference[i] != background[i]) { changed = true; break; } }
        if (changed) {
            System.arraycopy(background, 0, mixingReference, 0, background.length);
            if (stencil != null) { stencil.reference(mixingReference); }
        }
    }
    /** Source changes rebuild only these arrays, never geometry/pressure. Invalid changes retain previous sources. */
    public void setSources(Source... sources) {
        double[] nextPower = new double[power.length], nextLimit = new double[limit.length];
        Arrays.fill(nextLimit, Double.NaN);
        for (Source source : sources) {
            if (source == null || source.state() >= power.length) { throw new IllegalArgumentException("Source outside thermal geometry"); }
            nextPower[source.state()] += source.power();
            if (!Double.isFinite(nextPower[source.state()])) { throw new IllegalArgumentException("Unrepresentable summed source power"); }
        }
        // Match AirGrid: sum all powers, then select the strictest limit in the net direction.
        for (Source source : sources) {
            int i = source.state(); double bound = source.limit(), old = nextLimit[i];
            if (Double.isNaN(bound) || nextPower[i] == 0) { continue; }
            nextLimit[i] = Double.isNaN(old) ? bound : nextPower[i] > 0 ? Math.min(old, bound) : Math.max(old, bound);
        }
        System.arraycopy(nextPower, 0, power, 0, power.length);
        System.arraycopy(nextLimit, 0, limit, 0, limit.length);
    }
    void copyTemperatures(double[] out) { System.arraycopy(temperature, 0, out, 0, temperature.length); }
    void restoreTemperatures(double[] saved) { System.arraycopy(saved, 0, temperature, 0, temperature.length); }
    void copyAirTemperatures(double[] out) { System.arraycopy(temperature, 0, out, 0, g.airStates()); }
    public void setTemperature(int state, double celsius) {
        if (!supported(celsius)) { throw new IllegalArgumentException("Finite temperature above absolute zero required"); }
        temperature[state] = celsius;
    }
    public double energy() {
        double sum = 0, correction = 0;
        for (int i = 0; i < temperature.length; i++) {
            double value = g.capacity[i] * temperature[i] - correction;
            double next = sum + value; correction = (next - sum) - value; sum = next;
        }
        return sum;
    }

    /** Snapshot one compatible projected field; never accepts flow from a different geometry owner. */
    public Step step(double thermalSeconds, FlowGrid flow, double[] exteriorCelsius) {
        if (!flow.belongsTo(g.flow) || !flow.valid()) { return rejected(Status.INVALID_INPUT, System.nanoTime()); }
        for (int e = 0; e < flux.length; e++) { flux[e] = flow.faceFlux(e); }
        return advance(thermalSeconds, flux, exteriorCelsius);
    }

    /** Prescribed-flow control: signed volume/second on the same shared faces, exterior temperature per face.
     * Flux must be balanced to the configured volume-normalized tolerance; input arrays are not retained.
     * thermalSeconds includes response scaling; it does not change the supplied physical wind speeds.
     */
    public Step advance(double thermalSeconds, double[] faceFlux, double[] exteriorCelsius) {
        long begin = System.nanoTime();
        var f = g.flow;
        if (!positive(thermalSeconds) || faceFlux.length != flux.length || exteriorCelsius.length != flux.length) {
            return rejected(Status.INVALID_INPUT, begin);
        }
        Arrays.fill(balance, 0);
        for (int e = 0; e < flux.length; e++) {
            double q = faceFlux[e];
            if (!Double.isFinite(q) || (f.exterior(e) && !supported(exteriorCelsius[e]))) {
                return rejected(Status.INVALID_INPUT, begin);
            }
            flux[e] = q;
            if (f.low[e] >= 0) { balance[f.low[e]] += q; }
            if (f.high[e] >= 0) { balance[f.high[e]] -= q; }
        }
        for (int i = 0; i < balance.length; i++) {
            if (!Double.isFinite(balance[i]) || Math.abs(balance[i]) / f.volume[i] > settings.maxDivergence()) {
                return rejected(Status.UNBALANCED_FLOW, begin);
            }
        }
        boolean directed = directedOrder != null && prepareDirectedOrder();
        boolean adaptive = mode == Mode.COMPACT_ADAPTIVE || directedOrder != null;
        double before = energy();
        double requested = 0, requestedCorrection = 0;
        for (double q : power) {
            double delta = thermalSeconds * q - requestedCorrection;
            double next = requested + delta; requestedCorrection = (next - requested) - delta; requested = next;
        }
        if (!Double.isFinite(requested)) { return rejected(Status.INVALID_INPUT, begin); }
        if (stencil != null) {
            stencil.assemble(g, thermalSeconds, temperature, flux, diagonal, rhs, weight, candidate);
        } else for (int i = 0; i < temperature.length; i++) {
            double mass = g.capacity[i] / thermalSeconds;
            diagonal[i] = mass; rhs[i] = mass * temperature[i]; candidate[i] = temperature[i];
            for (int p = start[i]; p < start[i + 1]; p++) {
                double incoming = 0, outgoing = 0;
                int e = face[p];
                if (e >= 0) {
                    double q = f.low[e] == i ? flux[e] : -flux[e];
                    incoming = Math.max(0, -q); outgoing = Math.max(0, q);
                }
                weight[p] = base[p] + incoming;
                diagonal[i] += base[p] + outgoing;
                if (i < mixingReference.length && column[p] < mixingReference.length) {
                    rhs[i] += base[p] * (mixingReference[i] - mixingReference[column[p]]);
                }
            }
        }
        for (int e : exteriorFaces) {
            int i = f.low[e] >= 0 ? f.low[e] : f.high[e];
            double out = f.low[e] >= 0 ? flux[e] : -flux[e];
            if (out > 0) { diagonal[i] += out; }
            else { rhs[i] -= out * exteriorCelsius[e]; }
        }
        for (int i = 0; i < temperature.length; i++) {
            if (!positive(diagonal[i]) || !Double.isFinite(rhs[i])) { return rejected(Status.INVALID_INPUT, begin); }
            if (inverseDiagonal != null) {
                double inverse = 1 / diagonal[i];
                // Preserve division for extreme coefficients whose reciprocal cannot be represented normally.
                inverseDiagonal[i] = inverse >= Double.MIN_NORMAL && Double.isFinite(inverse) ? inverse : 0;
            }
        }
        double residual = Double.POSITIVE_INFINITY, after = before, exterior = 0, error = 0, sourceEnergy = 0;
        int checks = 0; long sweepNanos = 0, qualityNanos = 0;
        for (int sweep = 1; sweep <= settings.maxSweeps(); sweep++) {
            long sweepBegin = System.nanoTime();
            double maxChange = 0;
            // Follow coherent transport when available; otherwise alternate the natural traversal.
            for (int k = 0; k < temperature.length; k++) {
                int i = directed ? directedOrder[k] : (sweep & 1) == 1 ? k : temperature.length - 1 - k;
                double value = rhs[i];
                for (int p = start[i]; p < start[i + 1]; p++) { value += weight[p] * candidate[column[p]]; }
                double numerator = value + deliveredPower(i, value);
                double next = inverseDiagonal != null && inverseDiagonal[i] != 0
                        ? numerator * inverseDiagonal[i] : numerator / diagonal[i];
                if (adaptive) { maxChange = Math.max(maxChange, Math.abs(next - candidate[i])); }
                candidate[i] = next;
                if (!supported(candidate[i])) { return rejected(Status.NOT_CONVERGED, begin); }
            }
            sweepNanos += System.nanoTime() - sweepBegin;
            // Change is only a cheap check trigger, never an acceptance criterion. Always check at the work cap.
            if (adaptive && sweep != settings.maxSweeps() && (sweep & 7) != 0
                    && maxChange > settings.temperatureTolerance() * .1) { continue; }
            long qualityBegin = System.nanoTime(); checks++;
            residual = 0;
            double change = 0, changeCorrection = 0;
            sourceEnergy = 0; double sourceCorrection = 0;
            for (int i = 0; i < temperature.length; i++) {
                double local = rhs[i];
                for (int p = start[i]; p < start[i + 1]; p++) { local += weight[p] * candidate[column[p]]; }
                double delivered = deliveredPower(i, local);
                double r = local + delivered - diagonal[i] * candidate[i];
                double sourceDelta = thermalSeconds * delivered - sourceCorrection;
                double sourceNext = sourceEnergy + sourceDelta;
                sourceCorrection = (sourceNext - sourceEnergy) - sourceDelta; sourceEnergy = sourceNext;
                // Residual / storage rate measures the unresolved temperature change, even in tiny pockets.
                residual = Math.max(residual, Math.abs(r) * thermalSeconds / g.capacity[i]);
                // Sum energy changes directly: subtracting two large domain totals loses small heat transfers.
                double delta = g.capacity[i] * (candidate[i] - temperature[i]) - changeCorrection;
                double next = change + delta; changeCorrection = (next - change) - delta; change = next;
            }
            after = before + change;
            exterior = 0;
            double exteriorCorrection = 0;
            for (int e : exteriorFaces) {
                int i = f.low[e] >= 0 ? f.low[e] : f.high[e];
                double out = f.low[e] >= 0 ? flux[e] : -flux[e];
                double delta = -thermalSeconds * out * (out > 0 ? candidate[i] : exteriorCelsius[e]) - exteriorCorrection;
                double next = exterior + delta; exteriorCorrection = (next - exterior) - delta; exterior = next;
            }
            error = change - exterior - sourceEnergy;
            qualityNanos += System.nanoTime() - qualityBegin;
            if (Double.isFinite(after) && Double.isFinite(exterior) && Double.isFinite(residual)
                    && Double.isFinite(sourceEnergy)
                    && residual <= settings.temperatureTolerance() && Math.abs(error) <= settings.energyTolerance()) {
                double[] swap = temperature; temperature = candidate; candidate = swap;
                return new Step(Status.READY, sweep, residual, before, after, exterior, error, System.nanoTime() - begin, requested, sourceEnergy,
                        checks, sweepNanos, qualityNanos);
            }
        }
        return new Step(Status.NOT_CONVERGED, settings.maxSweeps(), residual, before, after, exterior, error, System.nanoTime() - begin, requested, sourceEnergy,
                checks, sweepNanos, qualityNanos);
    }
    /** Initialization-only stationary relaxation; partial estimates are private to the initializer.
     * Uses the same stencil, thermostat and divergence checks, with no transient capacity term.
     * Does not represent elapsed time or produce a gameplay energy ledger.
     */
    double relaxStationary(double[] field,double[] exteriorCelsius,int sweeps) {
        if(field.length!=flux.length || exteriorCelsius.length!=flux.length || sweeps<1) throw new IllegalArgumentException("Invalid stationary field");
        var f=g.flow;Arrays.fill(balance,0);
        for(int e=0;e<flux.length;e++) {
            flux[e]=field[e];
            if(!Double.isFinite(flux[e]) || f.exterior(e) && !supported(exteriorCelsius[e])) throw new IllegalArgumentException("Invalid stationary boundary");
            if(f.low[e]>=0) balance[f.low[e]]+=flux[e];if(f.high[e]>=0) balance[f.high[e]]-=flux[e];
        }
        for(int i=0;i<balance.length;i++) if(Math.abs(balance[i])/f.volume[i]>settings.maxDivergence()) throw new IllegalStateException("Unbalanced stationary flow");
        if(stencil==null) throw new IllegalStateException("Stationary initialization requires the shared compact stencil");
        stencil.assemble(g,Double.POSITIVE_INFINITY,temperature,flux,diagonal,rhs,weight,candidate);
        for(int e:exteriorFaces) { int i=f.low[e]>=0?f.low[e]:f.high[e];double out=f.low[e]>=0?flux[e]:-flux[e];if(out>0) diagonal[i]+=out;else rhs[i]-=out*exteriorCelsius[e]; }
        for(int sweep=0;sweep<sweeps;sweep++) for(int k=0;k<candidate.length;k++) {
            int i=(sweep&1)==0?k:candidate.length-1-k;if(diagonal[i]==0) continue;
            double value=rhs[i];for(int p=start[i];p<start[i+1];p++) value+=weight[p]*candidate[column[p]];
            candidate[i]=(value+deliveredPower(i,value))/diagonal[i];
            if(!supported(candidate[i])) throw new IllegalStateException("Invalid stationary temperature");
        }
        double residual=0;
        for(int i=0;i<candidate.length;i++) {
            if(diagonal[i]==0) {if(power[i]!=0) throw new IllegalStateException("Source has no thermal equilibrium");continue;}
            double local=rhs[i];for(int p=start[i];p<start[i+1];p++) local+=weight[p]*candidate[column[p]];
            residual=Math.max(residual,Math.abs((local+deliveredPower(i,local))/diagonal[i]-candidate[i]));
        }
        double[] swap=temperature;temperature=candidate;candidate=swap;return residual;
    }
    private double deliveredPower(int state, double withoutSourceRhs) {
        double q = power[state];
        if (q == 0 || Double.isNaN(limit[state])) { return q; }
        double needed = diagonal[state] * limit[state] - withoutSourceRhs;
        return q > 0 ? Math.min(q, Math.max(0, needed)) : Math.max(q, Math.min(0, needed));
    }
    /** Ordering only: projected face flux, coefficients and stopping tolerances remain unchanged.
     * Closed recirculation has near-zero net directional flux and keeps alternating natural sweeps.
     * Cache one octant order; changes overwrite the retained array rather than allocating per step.
     */
    private boolean prepareDirectedOrder() {
        double x = 0, y = 0, z = 0, ax = 0, ay = 0, az = 0;
        var f = g.flow;
        for (int e = 0; e < flux.length; e++) {
            double q = flux[e];
            if (f.axis[e] == 0) { x += q; ax += Math.abs(q); }
            else if (f.axis[e] == 1) { y += q; ay += Math.abs(q); }
            else { z += q; az += Math.abs(q); }
        }
        boolean dx = Math.abs(x) > .25 * ax, dy = Math.abs(y) > .25 * ay, dz = Math.abs(z) > .25 * az;
        if (!dx && !dy && !dz) { return false; }
        int key = (dx && x < 0 ? 1 : 0) | (dy && y < 0 ? 2 : 0) | (dz && z < 0 ? 4 : 0);
        if (key == directedKey) { return true; }
        int count = 0;
        if(spatialOrder!=null) {
            for(int i=0;i<spatialOrder.length;i++) spatialOrder[i]=i;
            sortCells(0,spatialOrder.length-1,key);
            for(int cell:spatialOrder) count=appendCell(cell,count);
        } else for (int iz = 0; iz < f.nz; iz++) for (int iy = 0; iy < f.ny; iy++) for (int ix = 0; ix < f.nx; ix++) {
            int px = (key & 1) == 0 ? ix : f.nx - 1 - ix;
            int py = (key & 2) == 0 ? iy : f.ny - 1 - iy;
            int pz = (key & 4) == 0 ? iz : f.nz - 1 - iz;
            int cell = f.index(px, py, pz);
            count=appendCell(cell,count);
        }
        if (count != directedOrder.length) { throw new IllegalStateException("Incomplete thermal sweep order"); }
        directedKey = key; return true;
    }
    private int appendCell(int cell,int count) {
        var f=g.flow;for(int node=f.starts[cell];node<f.starts[cell+1];node++) directedOrder[count++]=node;
        if(g.material[cell]>=0) directedOrder[count++]=g.material[cell];int fluid=g.fluidState(cell);if(fluid>=0) directedOrder[count++]=fluid;return count;
    }
    private int compareCells(int a,int b,int key) {
        var f=g.flow;int c=Integer.compare(f.cellZ(a),f.cellZ(b));if(c!=0) return (key&4)==0?c:-c;
        c=Integer.compare(f.cellY(a),f.cellY(b));if(c!=0) return (key&2)==0?c:-c;
        c=Integer.compare(f.cellX(a),f.cellX(b));return (key&1)==0?c:-c;
    }
    private void sortCells(int low,int high,int key) {
        int i=low,j=high,pivot=spatialOrder[(low+high)>>>1];
        while(i<=j) {
            while(compareCells(spatialOrder[i],pivot,key)<0) i++;
            while(compareCells(spatialOrder[j],pivot,key)>0) j--;
            if(i<=j) { int t=spatialOrder[i];spatialOrder[i++]=spatialOrder[j];spatialOrder[j--]=t; }
        }
        if(low<j) sortCells(low,j,key);if(i<high) sortCells(i,high,key);
    }
    private Step rejected(Status status, long begin) {
        double energy = energy();
        return new Step(status, 0, Double.NaN, energy, energy, 0, Double.NaN, System.nanoTime() - begin, 0, 0, 0, 0, 0);
    }
}
