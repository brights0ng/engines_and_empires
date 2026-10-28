// Written by Claude.
package dev.brights0ng.meteodyne.heat.world;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Debug-only scripted block and chunk churn for local-air soak baselines. Overworld only, server thread only.
 * Edits use {@link Block#UPDATE_CLIENTS} so no neighbor updates cascade; vanilla shape updates and random ticks still run.
 */
@EventBusSubscriber(modid=Meteodyne.MODID)
final class LocalAirSoak {
    static final int STATIONS=4,SPACING=48,CHURN_CHUNK_X=24,CHURN_CHUNK_Z=2;
    enum Mode { CALM, BUSY }
    enum Action { FURNACE, CAMPFIRE, CROP, LEAF, REDSTONE, CHUNK }

    /** Pure per-tick schedule; tick counts from soak start. */
    static final class Schedule {
        final Mode mode;
        private final Random crops;
        Schedule(Mode mode) { this(mode,42); }
        Schedule(Mode mode,long seed) { this.mode=mode;crops=new Random(seed); }
        EnumSet<Action> actions(long tick) {
            var out=EnumSet.noneOf(Action.class);
            if(mode==Mode.CALM) return out;
            if(tick%200==0) out.add(Action.FURNACE);
            if(tick%600==0) out.add(Action.CAMPFIRE);
            if(tick%20==0) out.add(Action.CROP);
            if(tick%40==0) out.add(Action.LEAF);
            if(tick%10==0) out.add(Action.REDSTONE);
            if(tick%100==0) out.add(Action.CHUNK);
            return out;
        }
        /** Next pseudo-random CROP target index in [0, count). */
        int nextCrop(int count) { return crops.nextInt(count); }
    }

    record Station(BlockPos surface,BlockPos campfire,BlockPos furnace,List<BlockPos> wheat,List<BlockPos> leaves,List<BlockPos> wires) { }
    private static final class State {
        final BlockPos origin;
        final List<Station> stations;
        final boolean forceChunks;
        Mode mode;
        Schedule schedule;
        boolean running,chunkChurn,redstoneOn;
        long ticks,chunkFlips;
        int leafCursor;
        final EnumMap<Action,Long> counts=new EnumMap<>(Action.class);
        State(BlockPos origin,List<Station> stations,boolean forceChunks) { this.origin=origin;this.stations=stations;this.forceChunks=forceChunks; }
    }
    private static State state;

    private LocalAirSoak() { }

    // ---- commands ----

    static int build(CommandSourceStack source) {
        var level=overworld(source);if(level==null) return 0;
        try {
            build(level,BlockPos.containing(source.getPosition()),STATIONS,true);
        } catch(IllegalStateException e) { source.sendFailure(Component.literal(e.getMessage()));return 0; }
        var s=state;
        source.sendSuccess(()->Component.literal("Soak stations built at "+s.stations.stream().map(st->st.surface().toShortString()).toList()+"; station chunks force-loaded. Next: /meteodyne heat air soak start calm"),false);
        return 1;
    }
    static int start(CommandSourceStack source,Mode mode) {
        var level=overworld(source);if(level==null) return 0;
        try { start(level,mode,true); }
        catch(IllegalStateException e) { source.sendFailure(Component.literal(e.getMessage()));return 0; }
        source.sendSuccess(()->Component.literal("Soak "+mode.name().toLowerCase(Locale.ROOT)+" started at "+state.origin.toShortString()+". Capture with /meteodyne heat benchmark start 1800."),false);
        return 1;
    }
    static int stop(CommandSourceStack source) {
        var level=overworld(source);if(level==null) return 0;
        if(state==null) { source.sendFailure(Component.literal("No soak stations built."));return 0; }
        stop(level);
        source.sendSuccess(()->Component.literal("Soak stopped; churn and station chunks unforced. "+summary()),false);
        return 1;
    }
    static int status(CommandSourceStack source) {
        if(overworld(source)==null) return 0;
        source.sendSuccess(()->Component.literal(state==null?"No soak stations built. Run /meteodyne heat air soak build.":summary()),false);
        return 1;
    }
    private static ServerLevel overworld(CommandSourceStack source) {
        var level=source.getLevel();
        if(level.dimension()!=Level.OVERWORLD) { source.sendFailure(Component.literal("The local-air soak runs in the Overworld only."));return null; }
        return level;
    }

    // ---- engine ----

    /** Builds stations S_i = origin + (48*i, 0, 0) on the WORLD_SURFACE heightmap; rebuilding the same origin reuses the stored surfaces. */
    static void build(ServerLevel level,BlockPos origin,int stations,boolean forceChunks) {
        if(state!=null && state.running) throw new IllegalStateException("Stop the soak before rebuilding.");
        var surfaces=new ArrayList<BlockPos>();
        boolean same=state!=null && state.origin.equals(origin) && state.stations.size()==stations;
        for(int i=0;i<stations;i++) {
            if(same) { surfaces.add(state.stations.get(i).surface());continue; }
            var at=origin.offset(SPACING*i,0,0);
            if(!level.hasChunk(at.getX()>>4,at.getZ()>>4)) throw new IllegalStateException("Station "+i+" chunk at "+at.toShortString()+" is not loaded.");
            surfaces.add(new BlockPos(at.getX(),level.getHeight(Heightmap.Types.WORLD_SURFACE,at.getX(),at.getZ()),at.getZ()));
        }
        for(var s:surfaces) if(!level.hasChunk(s.getX()>>4,s.getZ()>>4)) throw new IllegalStateException("Station chunk at "+s.toShortString()+" is not loaded.");
        if(state!=null && state.forceChunks) forceStations(level,state,false);
        var built=new ArrayList<Station>();
        for(var s:surfaces) built.add(place(level,s));
        state=new State(origin,List.copyOf(built),forceChunks);
        if(forceChunks) forceStations(level,state,true);
    }
    private static Station place(ServerLevel level,BlockPos s) {
        var campfire=s;var furnace=s.offset(3,0,0);
        set(level,campfire,Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT,true));
        set(level,furnace,Blocks.FURNACE.defaultBlockState());
        var wheat=new ArrayList<BlockPos>();
        for(int x=-8;x<=-3;x++) for(int z=-3;z<=2;z++) {
            set(level,s.offset(x,-1,z),Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE,7));
            var crop=s.offset(x,0,z);set(level,crop,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,0));wheat.add(crop);
        }
        for(int y=0;y<=2;y++) set(level,s.offset(0,y,6),Blocks.OAK_LOG.defaultBlockState());
        var leaves=new ArrayList<BlockPos>();
        for(int x=-1;x<=1;x++) for(int z=5;z<=7;z++) {
            var leaf=s.offset(x,3,z);leaves.add(leaf);
            set(level,leaf,Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,false).setValue(LeavesBlock.DISTANCE,1));
        }
        var wires=new ArrayList<BlockPos>();
        for(int x=-3;x<=2;x++) {
            set(level,s.offset(x,-1,-6),Blocks.STONE.defaultBlockState());
            var wire=s.offset(x,0,-6);set(level,wire,Blocks.REDSTONE_WIRE.defaultBlockState());wires.add(wire);
        }
        return new Station(s,campfire,furnace,List.copyOf(wheat),List.copyOf(leaves),List.copyOf(wires));
    }
    static void start(ServerLevel level,Mode mode,boolean chunkChurn) {
        var s=state;
        if(s==null) throw new IllegalStateException("No soak stations built. Run /meteodyne heat air soak build first.");
        forceChurn(level,s,false);
        if(s.forceChunks) forceStations(level,s,true);
        s.mode=mode;s.schedule=new Schedule(mode);s.chunkChurn=chunkChurn;s.running=true;
        s.ticks=0;s.chunkFlips=0;s.leafCursor=0;s.redstoneOn=false;s.counts.clear();
    }
    static void stop(ServerLevel level) {
        var s=state;if(s==null) return;
        s.running=false;forceChurn(level,s,false);
        if(s.forceChunks) forceStations(level,s,false);
    }
    static boolean active() { return state!=null && state.running; }
    static long count(Action action) { return state==null?0:state.counts.getOrDefault(action,0L); }
    static List<Station> stations() { return state==null?List.of():state.stations; }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if(event.getLevel() instanceof ServerLevel level && level.dimension()==Level.OVERWORLD && state!=null && state.running) tick(level,state);
    }
    @SubscribeEvent
    static void onServerStopping(ServerStoppingEvent event) {
        var level=event.getServer().overworld();
        if(state!=null && level!=null) stop(level);
        state=null;
    }
    private static void tick(ServerLevel level,State s) {
        for(var action:s.schedule.actions(s.ticks)) {
            switch(action) {
                case FURNACE -> { for(var st:s.stations) toggle(level,st.furnace(),BlockStateProperties.LIT); }
                case CAMPFIRE -> { for(var st:s.stations) toggle(level,st.campfire(),BlockStateProperties.LIT); }
                case CROP -> {
                    int per=s.stations.getFirst().wheat().size();int pick=s.schedule.nextCrop(per*s.stations.size());
                    var pos=s.stations.get(pick/per).wheat().get(pick%per);
                    var current=level.getBlockState(pos);
                    if(current.hasProperty(CropBlock.AGE)) set(level,pos,current.setValue(CropBlock.AGE,(current.getValue(CropBlock.AGE)+1)%8));
                }
                case LEAF -> {
                    int per=s.stations.getFirst().leaves().size();int pick=s.leafCursor++%(per*s.stations.size());
                    var pos=s.stations.get(pick/per).leaves().get(pick%per);var current=level.getBlockState(pos);
                    if(current.hasProperty(LeavesBlock.DISTANCE)) set(level,pos,current.setValue(LeavesBlock.DISTANCE,current.getValue(LeavesBlock.DISTANCE)==1?2:1));
                }
                case REDSTONE -> {
                    s.redstoneOn=!s.redstoneOn;
                    for(var st:s.stations) for(var pos:st.wires()) {
                        var current=level.getBlockState(pos);
                        if(current.hasProperty(RedStoneWireBlock.POWER)) set(level,pos,current.setValue(RedStoneWireBlock.POWER,s.redstoneOn?15:0));
                    }
                }
                case CHUNK -> {
                    if(!s.chunkChurn) break;
                    boolean first=s.chunkFlips++%2==0;var a=churnChunk(s,0);var b=churnChunk(s,CHURN_CHUNK_Z);
                    level.setChunkForced(a.x,a.z,first);level.setChunkForced(b.x,b.z,!first);
                }
            }
            s.counts.merge(action,1L,Long::sum);
        }
        s.ticks++;
    }
    private static void toggle(ServerLevel level,BlockPos pos,net.minecraft.world.level.block.state.properties.BooleanProperty property) {
        var current=level.getBlockState(pos);
        if(current.hasProperty(property)) set(level,pos,current.setValue(property,!current.getValue(property)));
    }
    private static void set(ServerLevel level,BlockPos pos,BlockState state) { level.setBlock(pos,state,Block.UPDATE_CLIENTS); }
    private static ChunkPos churnChunk(State s,int dz) {
        var origin=new ChunkPos(s.origin);return new ChunkPos(origin.x+CHURN_CHUNK_X,origin.z+dz);
    }
    private static void forceChurn(ServerLevel level,State s,boolean on) {
        for(int dz:new int[]{0,CHURN_CHUNK_Z}) { var c=churnChunk(s,dz);level.setChunkForced(c.x,c.z,on); }
    }
    private static void forceStations(ServerLevel level,State s,boolean on) {
        for(var st:s.stations) level.setChunkForced(st.surface().getX()>>4,st.surface().getZ()>>4,on);
    }

    // ---- reporting ----

    static Map<String,Object> report() {
        var s=state;var out=new LinkedHashMap<String,Object>();
        if(s==null) { out.put("built",false);return out; }
        out.put("writtenBy","Claude");
        out.put("mode",s.mode==null?"NONE":s.mode.name());out.put("running",s.running);
        out.put("origin",s.origin.toShortString());out.put("stations",s.stations.stream().map(st->st.surface().toShortString()).toList());
        out.put("ticksRun",s.ticks);out.put("chunkChurn",s.chunkChurn);
        var counts=new LinkedHashMap<String,Long>();for(var a:Action.values()) counts.put(a.name(),s.counts.getOrDefault(a,0L));
        out.put("actionCounts",counts);
        return out;
    }
    private static String summary() {
        var r=report();
        return "Soak "+r.get("mode")+(Boolean.TRUE.equals(r.get("running"))?" running":" idle")+" at "+r.get("origin")+"; ticks "+r.get("ticksRun")+"; actions "+r.get("actionCounts")+".";
    }
}
