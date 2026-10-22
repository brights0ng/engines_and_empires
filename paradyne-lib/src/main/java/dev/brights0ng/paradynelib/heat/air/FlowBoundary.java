// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

/** Prepared immutable exterior conditions: prescribed normal velocity OR open pressure correction, never both. */
public final class FlowBoundary {
    final FlowGeometry geometry;
    final boolean[] fixed;
    final double[] velocity;
    private FlowBoundary(Builder b) { geometry=b.geometry;fixed=b.fixed.clone();velocity=b.velocity.clone(); }
    public static Builder builder(FlowGeometry geometry) { return new Builder(geometry); }
    /** Prescribe incoming weather flow, leaving other exterior faces pressure-open. */
    public static FlowBoundary wind(FlowGeometry geometry,double x,double y,double z) {
        if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) { throw new IllegalArgumentException("Finite wind required"); }
        Builder b=builder(geometry);
        for(int f=0;f<geometry.faces();f++) {
            if(!geometry.exterior(f)) { continue; }
            double speed=geometry.axis[f]==0?x:geometry.axis[f]==1?y:z;
            if((geometry.low[f]<0 && speed>0) || (geometry.high[f]<0 && speed<0)) { b.prescribe(f,speed); }
        }
        return b.build();
    }
    public static final class Builder {
        final FlowGeometry geometry;final boolean[] fixed;final double[] velocity;
        Builder(FlowGeometry geometry) { this.geometry=geometry;fixed=new boolean[geometry.faces()];velocity=new double[geometry.faces()]; }
        public Builder prescribe(int face,double normalVelocity) {
            if(!geometry.exterior(face) || !Double.isFinite(normalVelocity)) { throw new IllegalArgumentException("Finite exterior face velocity required"); }
            fixed[face]=true;velocity[face]=normalVelocity;return this;
        }
        public FlowBoundary build() { return new FlowBoundary(this); }
    }
}
