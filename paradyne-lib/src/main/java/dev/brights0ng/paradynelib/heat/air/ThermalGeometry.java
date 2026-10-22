// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.ArrayList;
import java.util.Arrays;

/** Immutable thermal capacities and pairwise exchange links on the flow geometry.
 * Air has one state per connected pocket; solid material has one state per occupied block.
 * The explicit fluid-material constructor adds stationary fluid heat storage/exchange, without fluid motion.
 * The original constructor retains the historical no-fluid-state control; its omissions are queryable.
 * All capacity/power units follow AirParams. No legacy convection or exposed-loss terms are used.
 */
public final class ThermalGeometry {
    public record Coefficients(double airMix, double film, double wallK, double solidCap) {
        public Coefficients {
            if (!nonnegative(airMix) || !nonnegative(film) || !nonnegative(wallK)
                    || !Double.isFinite(solidCap) || solidCap <= 0) {
                throw new IllegalArgumentException("Invalid thermal coefficients");
            }
        }
        public static Coefficients legacyMaterials() {
            var p = AirParams.DEFAULT;
            return new Coefficients(p.airMix(), p.film(), p.wallK(), p.solidCap());
        }
    }
    private record Link(int a, int b, double conductance) { }
    final FlowGeometry flow;
    final int[] material, low, high;
    private final int[] fluid;
    private final int unrepresentedFluidCells;
    final double[] capacity, conductance;

    public ThermalGeometry(FlowGeometry flow, double[] relativeConductance, double[] relativeCapacity,
                           Coefficients coefficients) {
        this(flow,relativeConductance,relativeCapacity,null,null,coefficients,false);
    }

    /** Separate solid/fluid table values; capacity uses the same existing solidCap storage units.
     * One stationary fluid state per fluid-bearing block, independent of its solid and air states.
     * Outer material/fluid boundaries remain insulated. Arrays are consumed during construction only.
     */
    public ThermalGeometry(FlowGeometry flow, double[] relativeConductance, double[] relativeCapacity,
                           double[] fluidConductance, double[] fluidCapacity, Coefficients coefficients) {
        this(flow,relativeConductance,relativeCapacity,fluidConductance,fluidCapacity,coefficients,true);
    }

    private ThermalGeometry(FlowGeometry flow, double[] relativeConductance, double[] relativeCapacity,
                            double[] fluidConductance, double[] fluidCapacity, Coefficients coefficients,boolean includeFluid) {
        this.flow = flow;
        int cells = flow.shapes.length;
        if (relativeConductance.length != cells || relativeCapacity.length != cells) {
            throw new IllegalArgumentException("Material arrays must match block count");
        }
        material = new int[cells];
        if(includeFluid && (fluidConductance==null || fluidCapacity==null
                || fluidConductance.length!=cells || fluidCapacity.length!=cells)) {
            throw new IllegalArgumentException("Fluid material arrays must match block count");
        }
        fluid=includeFluid?new int[cells]:null;
        if(fluid!=null) { Arrays.fill(fluid,-1); }
        Arrays.fill(material, -1);
        int count = flow.nodes();
        for (int c = 0; c < cells; c++) {
            if (flow.solidVolume(c) > 0) {
                if (!nonnegative(relativeConductance[c]) || !Double.isFinite(relativeCapacity[c])
                        || relativeCapacity[c] <= 0) { throw new IllegalArgumentException("Invalid material"); }
                material[c] = count++;
            }
        }
        int omitted=0;
        for(int c=0;c<cells;c++) if(flow.shapes[c].fluidVolume()>0) {
            if(!includeFluid) { omitted++;continue; }
            if(!nonnegative(fluidConductance[c]) || !Double.isFinite(fluidCapacity[c]) || fluidCapacity[c]<=0) {
                throw new IllegalArgumentException("Invalid fluid material");
            }
            fluid[c]=count++;
        }
        unrepresentedFluidCells=omitted;
        capacity = new double[count];
        System.arraycopy(flow.volume, 0, capacity, 0, flow.nodes());
        double[] half = new double[cells];
        for (int c = 0; c < cells; c++) {
            if (material[c] < 0) { continue; }
            capacity[material[c]] = flow.solidVolume(c) * relativeCapacity[c] * coefficients.solidCap();
            if (!Double.isFinite(capacity[material[c]]) || capacity[material[c]] <= 0) {
                throw new IllegalArgumentException("Unrepresentable material capacity");
            }
            // Same half-block resistance as AirGrid, including its minimum material conductance.
            half[c] = coefficients.wallK() == 0 ? Double.POSITIVE_INFINITY
                    : .5 / (Math.max(1e-4, relativeConductance[c]) * coefficients.wallK());
        }
        double[] fluidHalf=includeFluid?new double[cells]:null;
        if(includeFluid) for(int c=0;c<cells;c++) if(fluid[c]>=0) {
            capacity[fluid[c]]=flow.shapes[c].fluidVolume()*fluidCapacity[c]*coefficients.solidCap();
            if(!Double.isFinite(capacity[fluid[c]]) || capacity[fluid[c]]<=0) {
                throw new IllegalArgumentException("Unrepresentable fluid capacity");
            }
            fluidHalf[c]=coefficients.wallK()==0?Double.POSITIVE_INFINITY
                    :.5/(Math.max(1e-4,fluidConductance[c])*coefficients.wallK());
        }
        var links = new ArrayList<Link>();
        for (int f = 0; f < flow.faces(); f++) {
            if (!flow.exterior(f)) {
                link(links, flow.low[f], flow.high[f], coefficients.airMix() * flow.area[f] / flow.distance[f]);
            }
        }
        for (int c = 0; c < cells; c++) {
            FlowShape shape = flow.shapes[c];
            for (int k = 0; k < shape.components(); k++) {
                if (material[c] >= 0) {
                    link(links, flow.starts[c] + k, material[c], shape.solidArea[k] / (coefficients.film() + half[c]));
                }
            }
            int x = c % flow.nx, y = (c / flow.nx) % flow.ny, z = c / (flow.nx * flow.ny);
            for (int axis = 0; axis < 3; axis++) {
                if ((axis == 0 && x + 1 == flow.nx) || (axis == 1 && y + 1 == flow.ny)
                        || (axis == 2 && z + 1 == flow.nz)) { continue; }
                int next = c + (axis == 0 ? 1 : axis == 1 ? flow.nx : flow.nx * flow.ny);
                if (material[c] < 0 && material[next] < 0) { continue; }
                FlowShape other = flow.shapes[next];
                if (shape.labels == null && other.labels == null) {
                    if (shape == FlowShape.FULL && other == FlowShape.FULL) {
                        link(links, material[c], material[next], 1 / (half[c] + half[next]));
                    } else if (shape == FlowShape.EMPTY && other == FlowShape.FULL) {
                        link(links, flow.starts[c], material[next], 1 / (coefficients.film() + half[next]));
                    } else if (shape == FlowShape.FULL && other == FlowShape.EMPTY) {
                        link(links, flow.starts[next], material[c], 1 / (coefficients.film() + half[c]));
                    }
                    continue;
                }
                // Aggregate all patches of each pocket before creating a link; no dynamic subcell state.
                double[] toOther = new double[shape.components()], toThis = new double[other.components()];
                double solidContact = 0;
                for (int v = 0; v < 16; v++) for (int u = 0; u < 16; u++) {
                    int a = shape.patch(axis, 1, u, v), b = other.patch(axis, 0, u, v);
                    if (a >= 0 && b == FlowShape.SOLID) { toOther[a] += 1.0 / 256; }
                    if (b >= 0 && a == FlowShape.SOLID) { toThis[b] += 1.0 / 256; }
                    if (a == FlowShape.SOLID && b == FlowShape.SOLID) { solidContact += 1.0 / 256; }
                }
                for (int k = 0; k < toOther.length; k++) {
                    if (toOther[k] > 0) { link(links, flow.starts[c] + k, material[next], toOther[k] / (coefficients.film() + half[next])); }
                }
                for (int k = 0; k < toThis.length; k++) {
                    if (toThis[k] > 0) { link(links, flow.starts[next] + k, material[c], toThis[k] / (coefficients.film() + half[c])); }
                }
                if (solidContact > 0) { link(links, material[c], material[next], solidContact / (half[c] + half[next])); }
            }
        }
        if(includeFluid) appendFluidLinks(links,half,fluidHalf,coefficients);
        low = new int[links.size()]; high = new int[links.size()]; conductance = new double[links.size()];
        for (int i = 0; i < links.size(); i++) {
            var l = links.get(i); low[i] = l.a; high[i] = l.b; conductance[i] = l.conductance;
        }
    }

    private void appendFluidLinks(ArrayList<Link> links,double[] solidHalf,double[] fluidHalf,Coefficients coefficients) {
        for(int c=0;c<flow.shapes.length;c++) {
            var shape=flow.shapes[c];
            if(fluid[c]>=0) {
                for(int k=0;k<shape.components();k++) {
                    link(links,flow.starts[c]+k,fluid[c],shape.fluidArea[k]/(coefficients.film()+fluidHalf[c]));
                }
                if(material[c]>=0) {
                    int contact=0;
                    for(int z=0;z<16;z++) for(int y=0;y<16;y++) for(int x=0;x<16;x++) {
                        int a=shape.label(x,y,z);
                        if(x<15 && solidFluidPair(a,shape.label(x+1,y,z))) { contact++; }
                        if(y<15 && solidFluidPair(a,shape.label(x,y+1,z))) { contact++; }
                        if(z<15 && solidFluidPair(a,shape.label(x,y,z+1))) { contact++; }
                    }
                    link(links,material[c],fluid[c],(contact/256.0)/(solidHalf[c]+fluidHalf[c]));
                }
            }
            int x=c%flow.nx,y=(c/flow.nx)%flow.ny,z=c/(flow.nx*flow.ny);
            for(int axis=0;axis<3;axis++) {
                if((axis==0 && x+1==flow.nx) || (axis==1 && y+1==flow.ny) || (axis==2 && z+1==flow.nz)) { continue; }
                int next=c+(axis==0?1:axis==1?flow.nx:flow.nx*flow.ny);
                if(fluid[c]<0 && fluid[next]<0) { continue; }
                var other=flow.shapes[next];
                double[] toOtherAir=new double[other.components()],toThisAir=new double[shape.components()];
                double fluidSolid=0,solidFluid=0,fluidFluid=0;
                int patches=shape.labels==null && other.labels==null?1:256;
                double area=1.0/patches;
                for(int patch=0;patch<patches;patch++) {
                    int a=shape.patch(axis,1,patch%16,patch/16),b=other.patch(axis,0,patch%16,patch/16);
                    if(a==FlowShape.FLUID && b>=0) { toOtherAir[b]+=area; }
                    if(b==FlowShape.FLUID && a>=0) { toThisAir[a]+=area; }
                    if(a==FlowShape.FLUID && b==FlowShape.SOLID) { fluidSolid+=area; }
                    if(a==FlowShape.SOLID && b==FlowShape.FLUID) { solidFluid+=area; }
                    if(a==FlowShape.FLUID && b==FlowShape.FLUID) { fluidFluid+=area; }
                }
                for(int k=0;k<toOtherAir.length;k++) if(toOtherAir[k]>0) {
                    link(links,fluid[c],flow.starts[next]+k,toOtherAir[k]/(coefficients.film()+fluidHalf[c]));
                }
                for(int k=0;k<toThisAir.length;k++) if(toThisAir[k]>0) {
                    link(links,flow.starts[c]+k,fluid[next],toThisAir[k]/(coefficients.film()+fluidHalf[next]));
                }
                if(fluidSolid>0) { link(links,fluid[c],material[next],fluidSolid/(fluidHalf[c]+solidHalf[next])); }
                if(solidFluid>0) { link(links,material[c],fluid[next],solidFluid/(solidHalf[c]+fluidHalf[next])); }
                if(fluidFluid>0) { link(links,fluid[c],fluid[next],fluidFluid/(fluidHalf[c]+fluidHalf[next])); }
            }
        }
    }

    private static boolean solidFluidPair(int a,int b) {
        return (a==FlowShape.SOLID && b==FlowShape.FLUID) || (b==FlowShape.SOLID && a==FlowShape.FLUID);
    }

    private static boolean nonnegative(double x) { return Double.isFinite(x) && x >= 0; }
    private static void link(ArrayList<Link> links, int a, int b, double g) {
        if (!nonnegative(g)) { throw new IllegalArgumentException("Unrepresentable conductance"); }
        if (g > 0) { links.add(new Link(a, b, g)); }
    }
    public int states() { return capacity.length; }
    public int airStates() { return flow.nodes(); }
    public int materialState(int cell) { return material[cell]; }
    public int fluidState(int cell) {
        if(cell<0 || cell>=flow.shapes.length) { throw new IndexOutOfBoundsException(); }
        return fluid==null?-1:fluid[cell];
    }
    /** Historical constructor diagnostics: such cells must not silently enter a fluid-capable prototype. */
    public int unrepresentedFluidCells() { return unrepresentedFluidCells; }
    public double capacity(int state) { return capacity[state]; }
}
