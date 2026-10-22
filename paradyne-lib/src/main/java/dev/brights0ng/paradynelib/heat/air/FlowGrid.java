// Written by ChatGPT; extended by ChatGPT with thermal ownership, deferred commits, cached sampling, and kernel controls.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;

/** Pure, single-owner momentum prototype. Physical seconds only; supplied temperatures are not transported here.
 * Failed steps never publish candidates. Replacing immutable geometry requires a new owner and projection.
 */
public final class FlowGrid {
    /** Arithmetic controls for offline comparisons; all modes share geometry and acceptance settings. */
    public enum Kernel {
        REFERENCE(false,false), UNROLLED_PRESSURE(true,false), CACHED_TRACE(false,true), OPTIMIZED(true,true);
        final boolean unrollPressure,cachedTrace;
        Kernel(boolean unrollPressure,boolean cachedTrace) { this.unrollPressure=unrollPressure;this.cachedTrace=cachedTrace; }
    }
    public static final Kernel DEFAULT_KERNEL=Kernel.CACHED_TRACE;
    public enum Status { READY, INVALID_INPUT, SPEED_LIMIT, PRESSURE_FAILED }
    public record Step(Status status, PressureProjector.Quality pressure, long transportNanos,
                       long pressureNanos, int blockedTraces) {
        public boolean ready() { return status==Status.READY; }
    }
    private final FlowGeometry g;
    private final FlowBoundary boundary;
    private final FlowSettings settings;
    private final PressureProjector projector;
    private final Kernel kernel;
    private double[] velocity, projected;
    private final double[] candidate, reconstructed, sideArea;
    private final int[] originX, originY, originZ;
    private final FlowGeometry.Trace trace=new FlowGeometry.Trace();
    private boolean valid, reconstructedValid, prepared;

    public FlowGrid(FlowGeometry geometry,FlowBoundary boundary,FlowSettings settings) {
        this(geometry,boundary,settings,PressureProjector.Preconditioner.MODIFIED_INCOMPLETE_CHOLESKY);
    }

    public FlowGrid(FlowGeometry geometry,FlowBoundary boundary,FlowSettings settings,PressureProjector.Preconditioner method) {
        this(geometry,boundary,settings,method,true);
    }

    /** Package-local arithmetic control for offline comparison; geometry and tracing are shared. */
    FlowGrid(FlowGeometry geometry,FlowBoundary boundary,FlowSettings settings,PressureProjector.Preconditioner method,
             boolean cacheSamplingOrigins) {
        this(geometry,boundary,settings,method,cacheSamplingOrigins,DEFAULT_KERNEL);
    }

    FlowGrid(FlowGeometry geometry,FlowBoundary boundary,FlowSettings settings,PressureProjector.Preconditioner method,
             boolean cacheSamplingOrigins,Kernel kernel) {
        if(boundary.geometry!=geometry) { throw new IllegalArgumentException("Boundary belongs to different geometry"); }
        this.kernel=java.util.Objects.requireNonNull(kernel);
        g=geometry;this.boundary=boundary;this.settings=settings;
        projector=new PressureProjector(g,boundary,method,kernel.unrollPressure);
        velocity=new double[g.faces()];projected=new double[g.faces()];candidate=new double[g.faces()];
        reconstructed=new double[Math.multiplyExact(g.nodes(),6)];
        sideArea=new double[reconstructed.length];
        originX=cacheSamplingOrigins?new int[g.nodes()]:null;
        originY=cacheSamplingOrigins?new int[g.nodes()]:null;
        originZ=cacheSamplingOrigins?new int[g.nodes()]:null;
        for(int i=0;cacheSamplingOrigins && i<g.nodes();i++) {
            int cell=g.nodeCell[i];
            originX[i]=cell%g.nx;originY[i]=(cell/g.nx)%g.ny;originZ[i]=cell/(g.nx*g.ny);
        }
        for(int i=0;i<g.nodes();i++) for(int j=g.incidentStart[i];j<g.incidentStart[i+1];j++) {
            int f=g.incident[j];sideArea[i*6+g.axis[f]*2+(g.low[f]==i?1:0)]+=g.area[f];
        }
    }

    /** Initialization only. Published fields can only change through a successful projection. */
    public void seedUniform(double x,double y,double z) {
        if(valid || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) { throw new IllegalStateException("Invalid seed"); }
        for(int f=0;f<velocity.length;f++) { velocity[f]=g.axis[f]==0?x:g.axis[f]==1?y:z; }
    }
    public void seedFace(int face,double speed) {
        if(valid || !Double.isFinite(speed)) { throw new IllegalStateException("Invalid seed"); }
        velocity[face]=speed;
    }
    public boolean valid() { return valid; }
    // Geometry identity, rather than revision alone, prevents transporting heat on unrelated faces.
    boolean belongsTo(FlowGeometry geometry) { return g == geometry; }
    public boolean usesIncompleteCholesky() { return projector.usesIncompleteCholesky(); }
    public long revision() { return g.revision; }
    public double faceVelocity(int face) { requireValid();return velocity[face]; }
    public double faceFlux(int face) { return faceVelocity(face)*g.area[face]; }
    public double componentVelocity(int node,int axis) {
        if(axis<0 || axis>2) { throw new IllegalArgumentException("Velocity axis must be 0..2"); }
        requireValid();reconstruct();return (reconstructed[node*6+axis*2]+reconstructed[node*6+axis*2+1])*0.5;
    }
    private void requireValid() { if(!valid) { throw new IllegalStateException("No valid projected flow"); } }

    public Step projectInitial() {
        if(valid) { throw new IllegalStateException("Flow is already initialized"); }
        prepared=false;
        System.arraycopy(velocity,0,candidate,0,velocity.length);
        Step result=projectCandidate(0,0,1);
        if(result.ready()) { acceptPrepared(); }
        return result;
    }

    /** Semi-Lagrangian velocity transport, damping, Boussinesq forcing, then pressure projection.
     * Temperatures/background are Celsius per air component; equal profiles give exactly zero buoyancy.
     */
    public Step step(double seconds,double[] temperature,double[] background) {
        Step result=prepareStep(seconds,temperature,background);
        if(result.ready()) { acceptPrepared(); }
        return result;
    }

    /** Coupled owner defers pressure/velocity acceptance until the second thermal half succeeds. */
    Step prepareStep(double seconds,double[] temperature,double[] background) {
        requireValid();
        prepared=false;
        if(!Double.isFinite(seconds) || seconds<=0 || seconds>settings.maxStep()
                || !Double.isFinite(seconds*settings.maxSpeed())
                || temperature.length!=g.nodes() || background.length!=g.nodes()) { return invalid(); }
        for(int i=0;i<temperature.length;i++) {
            if(!supportedTemperature(temperature[i]) || !supportedTemperature(background[i])) { return invalid(); }
        }
        long start=System.nanoTime();int blocked=0;reconstruct();
        double damping=Math.exp(-settings.damping()*seconds);
        for(int f=0;f<velocity.length;f++) {
            if(boundary.fixed[f]) { candidate[f]=boundary.velocity[f];continue; }
            int l=g.low[f],h=g.high[f],normal=g.axis[f];
            // The representative point belongs to an actual overlapping opening, even for a nonconvex aperture.
            int from=velocity[f]>=0?(l>=0?l:h):(h>=0?h:l);
            double x=g.fx[f],y=g.fy[f],z=g.fz[f];
            double side=from==l?-1e-7:1e-7;
            if(normal==0) { x+=side; } else if(normal==1) { y+=side; } else { z+=side; }
            double vx=normal==0?velocity[f]:sample(from,0,x,y,z);
            double vy=normal==1?velocity[f]:sample(from,1,x,y,z);
            double vz=normal==2?velocity[f]:sample(from,2,x,y,z);
            g.trace(x,y,z,-seconds*vx,-seconds*vy,-seconds*vz,trace,kernel.cachedTrace);
            if(trace.blocked) { blocked++; }
            double speed=sample(trace.node,normal,trace.x,trace.y,trace.z)*damping;
            if(normal==1) {
                double force=0;int count=0;
                if(l>=0) { force+=(temperature[l]-background[l])/(background[l]+273.15);count++; }
                if(h>=0) { force+=(temperature[h]-background[h])/(background[h]+273.15);count++; }
                speed+=seconds*9.81*settings.buoyancyScale()*force/count;
            }
            candidate[f]=speed;
        }
        return projectCandidate(System.nanoTime()-start,blocked,seconds);
    }

    private boolean supportedTemperature(double value) {
        return Double.isFinite(value) && value>-273.15 && value<=settings.maxTemperatureC();
    }
    private Step invalid() { return new Step(Status.INVALID_INPUT,null,0,0,0); }

    private Step projectCandidate(long transportNanos,int blocked,double dt) {
        for(int f=0;f<candidate.length;f++) {
            double value=boundary.fixed[f]?boundary.velocity[f]:candidate[f];
            if(!Double.isFinite(value) || Math.abs(value)>settings.maxSpeed()) {
                return new Step(Status.SPEED_LIMIT,null,transportNanos,0,blocked);
            }
        }
        long start=System.nanoTime();
        PressureProjector.Quality quality=projector.projectCandidate(candidate,dt,projected,settings);
        long elapsed=System.nanoTime()-start;
        if(!quality.ready()) { return new Step(Status.PRESSURE_FAILED,quality,transportNanos,elapsed,blocked); }
        for(double value:projected) {
            if(!Double.isFinite(value) || Math.abs(value)>settings.maxSpeed()) {
                return new Step(Status.SPEED_LIMIT,quality,transportNanos,elapsed,blocked);
            }
        }
        prepared=true;
        return new Step(Status.READY,quality,transportNanos,elapsed,blocked);
    }

    void copyPreparedFlux(double[] out) {
        if(!prepared || out.length!=projected.length) { throw new IllegalStateException("No compatible prepared flow"); }
        for(int f=0;f<out.length;f++) { out[f]=projected[f]*g.area[f]; }
    }
    void acceptPrepared() {
        if(!prepared) { throw new IllegalStateException("No prepared flow"); }
        projector.acceptCandidate();
        double[] swap=velocity;velocity=projected;projected=swap;
        valid=true;reconstructedValid=false;prepared=false;
    }
    void discardPrepared() { prepared=false; }

    /** Area-weighted velocities on each side of each component, with fully closed sides at zero.
     * Interpolation stays inside that component; it never samples another pocket across a thin partition.
     */
    private void reconstruct() {
        if(reconstructedValid) { return; }
        Arrays.fill(reconstructed,0);
        for(int i=0;i<g.nodes();i++) {
            for(int j=g.incidentStart[i];j<g.incidentStart[i+1];j++) {
                int f=g.incident[j],side=g.low[f]==i?1:0;
                reconstructed[i*6+g.axis[f]*2+side]+=velocity[f]*g.area[f];
            }
        }
        for(int k=0;k<reconstructed.length;k++) { if(sideArea[k]>0) { reconstructed[k]/=sideArea[k]; } }
        reconstructedValid=true;
    }
    private double sample(int node,int axis,double x,double y,double z) {
        if(originX==null) {
            int cell=g.nodeCell[node];int ix=cell%g.nx,iy=(cell/g.nx)%g.ny,iz=cell/(g.nx*g.ny);
            double fraction=axis==0?x-ix:axis==1?y-iy:z-iz;
            fraction=Math.max(0,Math.min(1,fraction));
            int offset=node*6+axis*2;
            return reconstructed[offset]*(1-fraction)+reconstructed[offset+1]*fraction;
        }
        double fraction=axis==0?x-originX[node]:axis==1?y-originY[node]:z-originZ[node];
        fraction=Math.max(0,Math.min(1,fraction));
        int offset=node*6+axis*2;
        return reconstructed[offset]*(1-fraction)+reconstructed[offset+1]*fraction;
    }
}
