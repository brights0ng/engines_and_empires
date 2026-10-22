// Written by ChatGPT.
package dev.brights0ng.paradynelib.heat.air;

import java.util.Arrays;

/** Immutable, reusable static geometry bake; the 16-cubed samples are not a dynamic fluid grid.
 * Air components connect only across sample faces. Coordinates and boxes use sixteenths of a block.
 * Solid and fluid occupancy are distinct; both exclude air. World-context shape capture belongs to callers.
 */
public final class FlowShape {
    public static final int RESOLUTION = 16;
    static final int SOLID = -1, FLUID = -2;
    private static final int COUNT = 4096;
    public static final FlowShape EMPTY = new FlowShape(0);
    public static final FlowShape FULL = new FlowShape(SOLID);
    public static final FlowShape WATER = new FlowShape(FLUID);
    final int[] labels;
    final int uniform;
    final double[] volume, cx, cy, cz, solidArea, fluidArea;
    final double solidVolume, fluidVolume;

    private FlowShape(int uniform) {
        this.uniform = uniform; labels = null;
        volume = uniform == 0 ? new double[]{1} : new double[0];
        cx = uniform == 0 ? new double[]{0.5} : new double[0]; cy = cx; cz = cx;
        solidArea = new double[volume.length]; fluidArea = new double[volume.length];
        solidVolume = uniform == SOLID ? 1 : 0; fluidVolume = uniform == FLUID ? 1 : 0;
    }

    private FlowShape(int[] occupancy) {
        labels = occupancy.clone(); uniform = Integer.MIN_VALUE;
        int solids = 0, fluids = 0, components = 0;
        for (int i = 0; i < COUNT; i++) {
            if (labels[i] == SOLID) { solids++; }
            else if (labels[i] == FLUID) { fluids++; }
            else { labels[i] = -3; }
        }
        int[] queue = new int[COUNT];
        for (int seed = 0; seed < COUNT; seed++) {
            if (labels[seed] != -3) { continue; }
            int read = 0, write = 0; queue[write++] = seed; labels[seed] = components;
            while (read < write) {
                int i = queue[read++], x = i & 15, y = (i >> 4) & 15, z = i >> 8;
                if (x > 0 && labels[i - 1] == -3) { labels[i - 1] = components; queue[write++] = i - 1; }
                if (x < 15 && labels[i + 1] == -3) { labels[i + 1] = components; queue[write++] = i + 1; }
                if (y > 0 && labels[i - 16] == -3) { labels[i - 16] = components; queue[write++] = i - 16; }
                if (y < 15 && labels[i + 16] == -3) { labels[i + 16] = components; queue[write++] = i + 16; }
                if (z > 0 && labels[i - 256] == -3) { labels[i - 256] = components; queue[write++] = i - 256; }
                if (z < 15 && labels[i + 256] == -3) { labels[i + 256] = components; queue[write++] = i + 256; }
            }
            components++;
        }
        volume = new double[components]; cx = new double[components]; cy = new double[components]; cz = new double[components];
        solidArea = new double[components]; fluidArea = new double[components];
        for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int i = index(x,y,z), c = labels[i];
            if (c < 0) { continue; }
            volume[c]++; cx[c] += (x + 0.5)/16; cy[c] += (y + 0.5)/16; cz[c] += (z + 0.5)/16;
            if (x > 0) { surface(c,labels[i-1]); } if (x < 15) { surface(c,labels[i+1]); }
            if (y > 0) { surface(c,labels[i-16]); } if (y < 15) { surface(c,labels[i+16]); }
            if (z > 0) { surface(c,labels[i-256]); } if (z < 15) { surface(c,labels[i+256]); }
        }
        for (int c = 0; c < components; c++) {
            cx[c] /= volume[c]; cy[c] /= volume[c]; cz[c] /= volume[c]; volume[c] /= COUNT;
        }
        solidVolume = solids/(double)COUNT; fluidVolume = fluids/(double)COUNT;
    }

    private void surface(int component, int neighbor) {
        if (neighbor == SOLID) { solidArea[component] += 1.0/256; }
        if (neighbor == FLUID) { fluidArea[component] += 1.0/256; }
    }

    static int index(int x,int y,int z) { return (z*16+y)*16+x; }
    int label(int x,int y,int z) { return labels == null ? uniform : labels[index(x,y,z)]; }
    int patch(int axis,int side,int a,int b) {
        int edge = side == 0 ? 0 : 15;
        return axis == 0 ? label(edge,a,b) : axis == 1 ? label(a,edge,b) : label(a,b,edge);
    }
    public int components() { return volume.length; }
    public double airVolume(int component) { return volume[component]; }
    public double solidVolume() { return solidVolume; }
    public double fluidVolume() { return fluidVolume; }

    public static final class Builder {
        private final int[] occupancy = new int[COUNT];
        public Builder solidBox(int x0,int y0,int z0,int x1,int y1,int z1) { return box(x0,y0,z0,x1,y1,z1,SOLID); }
        public Builder fluidBox(int x0,int y0,int z0,int x1,int y1,int z1) { return box(x0,y0,z0,x1,y1,z1,FLUID); }
        private Builder box(int x0,int y0,int z0,int x1,int y1,int z1,int kind) {
            if (x0<0 || y0<0 || z0<0 || x1>16 || y1>16 || z1>16 || x1<x0 || y1<y0 || z1<z0) {
                throw new IllegalArgumentException("Shape boxes must lie in 0..16");
            }
            for(int z=z0;z<z1;z++) for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) {
                int i=index(x,y,z);
                if (kind == SOLID || occupancy[i] != SOLID) { occupancy[i]=kind; }
            }
            return this;
        }
        public FlowShape build() {
            int first=occupancy[0];
            if (Arrays.stream(occupancy).allMatch(v->v==first)) { return first==SOLID?FULL:first==FLUID?WATER:EMPTY; }
            return new FlowShape(occupancy);
        }
    }
}
