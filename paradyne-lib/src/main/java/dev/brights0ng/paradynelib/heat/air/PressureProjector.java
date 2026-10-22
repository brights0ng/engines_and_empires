// Written by ChatGPT; extended by ChatGPT with a retained triangular-kernel control.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;

/** Cached symmetric flux-form stencil, preconditioned CG and warm starts. Volumes enter diagnostics, not matrix rows.
 * Each sealed component has one symmetric reference elimination. Incompatible prescribed flux is rejected.
 */
public final class PressureProjector {
    public enum Preconditioner { DIAGONAL, INCOMPLETE_CHOLESKY, MODIFIED_INCOMPLETE_CHOLESKY }
    public enum Status { READY, ITERATION_LIMIT, INCOMPATIBLE_BOUNDARY, NON_FINITE, DIVERGENCE_LIMIT }
    public record Quality(Status status,int iterations,double initialResidual,double finalResidual,
                          double rmsDivergence,double maxDivergence,double boundaryImbalance) {
        public boolean ready() { return status==Status.READY; }
    }
    private final FlowGeometry g;
    private final FlowBoundary boundary;
    private final double[] pressure,x,b,r,z,d,q,net,componentSum;
    private final boolean[] pinned,open;
    private final PressureStencil stencil;

    public PressureProjector(FlowGeometry geometry,FlowBoundary boundary) {
        this(geometry,boundary,Preconditioner.MODIFIED_INCOMPLETE_CHOLESKY);
    }

    public PressureProjector(FlowGeometry geometry,FlowBoundary boundary,Preconditioner method) {
        this(geometry,boundary,method,false);
    }

    PressureProjector(FlowGeometry geometry,FlowBoundary boundary,Preconditioner method,boolean unroll) {
        if(boundary.geometry!=geometry) { throw new IllegalArgumentException("Boundary belongs to different geometry"); }
        g=geometry;this.boundary=boundary;int n=g.nodes();
        double[] weights=new double[g.faces()],diagonal=new double[n];pressure=new double[n];x=new double[n];b=new double[n];
        r=new double[n];z=new double[n];d=new double[n];q=new double[n];net=new double[n];
        pinned=new boolean[n];open=new boolean[g.components];componentSum=new double[g.components];
        for(int f=0;f<g.faces();f++) {
            if(boundary.fixed[f]) { continue; }
            weights[f]=g.area[f]/g.distance[f];int l=g.low[f],h=g.high[f];
            if(l>=0) { diagonal[l]+=weights[f]; } if(h>=0) { diagonal[h]+=weights[f]; }
            if(l<0 || h<0) { open[g.component[l<0?h:l]]=true; }
        }
        boolean[] chosen=new boolean[g.components];
        for(int i=0;i<n;i++) {
            int c=g.component[i];
            if(!open[c] && !chosen[c]) { pinned[i]=true;chosen[c]=true;diagonal[i]=1; }
        }
        stencil=new PressureStencil(g,weights,diagonal,pinned,java.util.Objects.requireNonNull(method),unroll);
    }

    public boolean usesIncompleteCholesky() { return stencil.incomplete(); }

    public double pressureCorrection(int node) { return pressure[node]; }

    /** Corrected output is usable only when quality.ready(). All buffers are retained by this owner. */
    public Quality project(double[] tentative,double dt,double[] corrected,FlowSettings settings) {
        Quality quality=projectCandidate(tentative,dt,corrected,settings);
        if(quality.ready()) { acceptCandidate(); }
        return quality;
    }

    // The flow owner must also check corrected speed before accepting this pressure warm start.
    void acceptCandidate() { System.arraycopy(x,0,pressure,0,x.length); }

    Quality projectCandidate(double[] tentative,double dt,double[] corrected,FlowSettings settings) {
        if(tentative.length!=g.faces() || corrected.length!=g.faces() || !Double.isFinite(dt) || dt<=0) {
            throw new IllegalArgumentException("Invalid projection input");
        }
        divergence(tentative);Arrays.fill(componentSum,0);
        for(int i=0;i<b.length;i++) {
            b[i]=-net[i]/dt;componentSum[g.component[i]]+=b[i];
            if(!Double.isFinite(b[i])) { return quality(Status.NON_FINITE,0,Double.NaN,Double.NaN,tentative); }
        }
        for(int c=0;c<open.length;c++) {
            if(!open[c] && Math.abs(componentSum[c])>settings.absoluteResidual()*Math.max(1,Math.sqrt(g.nodes()))) {
                return quality(Status.INCOMPATIBLE_BOUNDARY,0,norm(b),norm(b),tentative);
            }
        }
        for(int i=0;i<b.length;i++) { if(pinned[i]) { b[i]=0; }x[i]=pressure[i]; }
        apply(x,q);
        for(int i=0;i<r.length;i++) { r[i]=b[i]-q[i]; }
        double initial=norm(r),residual=initial;
        if(!Double.isFinite(initial)) { return quality(Status.NON_FINITE,0,initial,initial,tentative); }
        double target=settings.absoluteResidual()+settings.relativeResidual()*initial;
        double rz=precondition();System.arraycopy(z,0,d,0,z.length);
        int iterations=0;
        for(int iteration=0;iteration<=settings.pressureIterations();iteration++) {
            iterations=iteration;
            if(residual<=target) {
                correct(tentative,dt,corrected);
                Quality result=quality(Status.READY,iteration,initial,residual,corrected);
                if(result.maxDivergence<=settings.divergenceTolerance()) {
                    return result;
                }
            }
            if(iteration==settings.pressureIterations()) { break; }
            double denom=stencil.applyAndDot(d,q);
            if(!(denom>0) || !Double.isFinite(denom) || !Double.isFinite(rz)) { break; }
            double alpha=rz/denom;
            double squares=0;
            for(int i=0;i<x.length;i++) { x[i]+=alpha*d[i];r[i]-=alpha*q[i];squares+=r[i]*r[i]; }
            // Periodic true residual prevents a recursive residual from hiding accumulated cancellation.
            if((iteration&7)==7) {
                apply(x,q);squares=0;for(int i=0;i<r.length;i++) { r[i]=b[i]-q[i];squares+=r[i]*r[i]; }
            }
            residual=r.length==0?0:Math.sqrt(squares/r.length);
            double next=precondition(),beta=rz==0?0:next/rz;rz=next;
            for(int i=0;i<d.length;i++) { d[i]=z[i]+beta*d[i]; }
        }
        apply(x,q);for(int i=0;i<r.length;i++) { r[i]=b[i]-q[i]; }residual=norm(r);
        correct(tentative,dt,corrected);
        Status status=!Double.isFinite(residual)?Status.NON_FINITE:residual<=target?Status.DIVERGENCE_LIMIT:Status.ITERATION_LIMIT;
        return quality(status,iterations,initial,residual,corrected);
    }

    private double precondition() {
        return stencil.precondition(r,z);
    }
    void apply(double[] input,double[] output) {
        stencil.apply(input,output);
    }
    private void correct(double[] input,double dt,double[] output) {
        for(int f=0;f<input.length;f++) {
            int l=g.low[f],h=g.high[f];
            output[f]=boundary.fixed[f]?boundary.velocity[f]:input[f]+dt*((l<0?0:x[l])-(h<0?0:x[h]))/g.distance[f];
        }
    }
    private void divergence(double[] velocity) {
        Arrays.fill(net,0);
        for(int f=0;f<velocity.length;f++) {
            double flux=(boundary.fixed[f]?boundary.velocity[f]:velocity[f])*g.area[f];
            if(g.low[f]>=0) { net[g.low[f]]+=flux; } if(g.high[f]>=0) { net[g.high[f]]-=flux; }
        }
    }
    private Quality quality(Status status,int iteration,double initial,double residual,double[] velocity) {
        divergence(velocity);double squares=0,max=0,imbalance=0;
        for(int i=0;i<net.length;i++) {
            double div=net[i]/g.volume[i];squares+=div*div;max=Math.max(max,Math.abs(div));
        }
        for(int f=0;f<velocity.length;f++) {
            double flux=(boundary.fixed[f]?boundary.velocity[f]:velocity[f])*g.area[f];
            if(g.high[f]<0) { imbalance+=flux; } if(g.low[f]<0) { imbalance-=flux; }
        }
        if(!Double.isFinite(max) || !Double.isFinite(squares)) { status=Status.NON_FINITE; }
        return new Quality(status,iteration,initial,residual,net.length==0?0:Math.sqrt(squares/net.length),max,imbalance);
    }
    private static double norm(double[] values) { return values.length==0?0:Math.sqrt(dot(values,values)/values.length); }
    private static double dot(double[] a,double[] b) { double sum=0;for(int i=0;i<a.length;i++) { sum+=a[i]*b[i]; }return sum; }
}
