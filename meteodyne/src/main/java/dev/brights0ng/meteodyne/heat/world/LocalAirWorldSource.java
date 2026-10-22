// Written by ChatGPT.
package dev.brights0ng.meteodyne.heat.world;

import java.util.IdentityHashMap;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Server-thread geometry/material extraction only; no chunk loads and no world objects in output.
 * Revision supplier covers geometry/chunk/context edits; separately verified source-only events retain geometry.
 */
final class LocalAirWorldSource implements LocalAirCoverage.World {
    private static final java.util.concurrent.atomic.AtomicLong NEXT_CONTEXT=new java.util.concurrent.atomic.AtomicLong();
    private final long contextId;
    private final ServerLevel level;
    private final LongSupplier physicalRevision;
    private final LocalAirDependencies dependencies;
    private final java.util.ArrayList<LocalAirWorldSource> histories=new java.util.ArrayList<>();
    private java.util.HashMap<Long,BlockState> overlay;
    private boolean released,invalid;
    private final LoadedView view=new LoadedView();
    private final IdentityHashMap<BlockState,LocalAirGeometryCapture.Shape> dryShapes=new IdentityHashMap<>();
    LocalAirWorldSource(ServerLevel level,LongSupplier physicalRevision) {
        this(level,physicalRevision,null);
    }
    LocalAirWorldSource(ServerLevel level,LongSupplier physicalRevision,LocalAirDependencies dependencies) {
        this(level,physicalRevision,dependencies,NEXT_CONTEXT.incrementAndGet());
    }
    private LocalAirWorldSource(ServerLevel level,LongSupplier physicalRevision,LocalAirDependencies dependencies,long contextId) {
        this.level=level;this.physicalRevision=physicalRevision;this.dependencies=dependencies;this.contextId=contextId;
    }
    @Override public long geometryRevision() { return invalid?Long.MIN_VALUE:physicalRevision.getAsLong(); }
    @Override public LocalAirCoverage.History freeze() {
        long revision=geometryRevision();
        var past=new LocalAirWorldSource(level,()->revision,dependencies,contextId);
        past.overlay=new java.util.HashMap<>();histories.add(past);
        if(dependencies!=null) dependencies.historyReads(true);
        return new LocalAirCoverage.History() {
            public LocalAirCoverage.World world() { return past; }
            public void close() {
                histories.remove(past);past.released=true;past.overlay.clear();
                if(dependencies!=null) dependencies.historyReads(!histories.isEmpty());
            }
        };
    }
    /** Called after the block write but before any further sliced read. Remember every changed position:
     * a later historical shape can start reading context outside the currently known dependency footprint. */
    boolean remember(BlockPos pos,BlockState before) {
        for(var past:histories) {
            if(before==null || !past.overlay.containsKey(pos.asLong()) && past.overlay.size()>=256) {
                for(var history:histories) history.invalid=true;
                return false;
            }
            past.overlay.putIfAbsent(pos.asLong(),before);
        }
        return true;
    }
    boolean hasHistory() { return !histories.isEmpty(); }
    @Override public long contextId() { return contextId; }
    @Override public int minBuildY() { return level.getMinBuildHeight(); }
    @Override public int maxBuildY() { return level.getMaxBuildHeight(); }
    @Override public long materialRevision() { return HeatTables.revision(); }
    @Override public LocalAirGeometryCapture.Footprint lastReads() {
        return new LocalAirGeometryCapture.Footprint(java.util.Arrays.copyOf(view.positions,view.reads));
    }
    @Override public LocalAirGeometryCapture.Cell sample(int x,int y,int z) {
        if(!level.getServer().isSameThread()) { throw new IllegalStateException("World capture requires server thread"); }
        if(released || invalid) throw unsupported("Historical view no longer available");
        view.reads=0;var pos=new BlockPos(x,y,z);var state=view.getBlockState(pos);var fluid=state.getFluidState();
        var shape=fluid.isEmpty() && !state.getBlock().hasDynamicShape()?dryShapes.get(state):null;
        if(shape==null) {
            // Snow's collision shape omits the lowest layer; its physical occupancy does not.
            var boxes=(state.getBlock() instanceof SnowLayerBlock?state.getShape(view,pos):state.getCollisionShape(view,pos)).toAabbs();
            if(boxes.size()>64) { throw unsupported("Collision shape exceeds box budget"); }
            int[] solid=new int[boxes.size()*6];int at=0;
            for(var box:boxes) {
                double[] c={box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ};
                for(double v:c) if(!Double.isFinite(v) || v<0 || v>1) { throw unsupported("Out-of-cell collision shape needs material ownership mapping"); }
                for(int axis=0;axis<3;axis++) {
                    solid[at+axis]=sampleIndex(c[axis]);solid[at+axis+3]=sampleIndex(c[axis+3]);
                    if(solid[at+axis]>=solid[at+axis+3]) { throw unsupported("Solid feature is below sampled resolution"); }
                }
                at+=6;
            }
            int[] liquid=new int[0];
            if(!fluid.isEmpty()) {
                float height=fluid.getHeight(view,pos);
                if(!Float.isFinite(height) || height<=0 || height>1) { throw unsupported("Unsupported fluid height"); }
                int top=sampleIndex(height);
                if(top==0) { throw unsupported("Fluid feature is below sampled resolution"); }
                liquid=new int[]{0,0,0,16,top,16};
            }
            shape=new LocalAirGeometryCapture.Shape(solid,liquid);
            if(fluid.isEmpty() && !state.getBlock().hasDynamicShape()) { dryShapes.put(state,shape); }
        }
        InsulationData material=HeatTables.insulation(state.getBlock());
        InsulationData liquid=fluid.isEmpty()?InsulationData.STONE:HeatTables.insulation(fluid.createLegacyBlock().getBlock());
        if(!HeatTables.driven(state).isEmpty()) throw unsupported("Driven source capture requires a separate source-update contract");
        var variant=HeatTables.power(state);
        var heat=variant==null || !variant.inAir()?null:new LocalAirGeometryCapture.Heat(
                dev.brights0ng.paradynelib.heat.air.AirParams.DEFAULT.gridPower(variant.power(),variant.radiant()),
                HeatTables.absoluteLimit(state,variant),variant.maxOffset(),variant.perFace());
        return new LocalAirGeometryCapture.Cell(shape,material.conductance(),material.capacity(),liquid.conductance(),liquid.capacity(),heat);
    }
    private static int sampleIndex(double coordinate) { return Math.max(0,Math.min(16,(int)Math.ceil(coordinate*16-.5))); }
    private static LocalAirGeometryCapture.Unavailable unsupported(String reason) {
        return new LocalAirGeometryCapture.Unavailable(LocalAirGeometryCapture.Status.UNSUPPORTED_GEOMETRY,reason);
    }
    private final class LoadedView implements BlockGetter {
        int reads;
        final long[] positions=new long[128];
        @Override public BlockState getBlockState(BlockPos pos) {
            if(dependencies!=null) dependencies.read(pos.getX(),pos.getY(),pos.getZ());
            if(++reads>128) { throw unsupported("Shape context exceeds read budget"); }
            positions[reads-1]=pos.asLong();
            if(pos.getY()<getMinBuildHeight() || pos.getY()>=getMinBuildHeight()+getHeight()) {
                throw new LocalAirGeometryCapture.Unavailable(LocalAirGeometryCapture.Status.MISSING_GEOMETRY,"Outside world height");
            }
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null) { throw new LocalAirGeometryCapture.Unavailable(LocalAirGeometryCapture.Status.MISSING_GEOMETRY,"Chunk not loaded"); }
            return overlay!=null && overlay.containsKey(pos.asLong())?overlay.get(pos.asLong()):chunk.getBlockState(pos);
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { throw unsupported("Block-entity-dependent collision shape"); }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    }
}
