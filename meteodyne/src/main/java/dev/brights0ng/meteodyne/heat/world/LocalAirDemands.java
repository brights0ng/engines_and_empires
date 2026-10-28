// Written by ChatGPT.
// Updated by Claude: kept placed-source demands while the source is unlit.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import static dev.brights0ng.meteodyne.heat.world.LocalAirDemandRegistry.*;
import dev.brights0ng.meteodyne.instrument.SensorBlock;
import dev.brights0ng.meteodyne.instrument.TemperatureSensorBlock;
import dev.brights0ng.meteodyne.instrument.WindSensorBlock;
import dev.brights0ng.meteodyne.ships.Ships;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** World-space producer adapter only. Does not load chunks, allocate grids, or route consumer readings. */
final class LocalAirDemands {
    // Provisional demand-retention interval; not a simulation radius or a production coverage guarantee.
    static final int GRACE_TICKS=100;
    final LocalAirDemandRegistry registry=new LocalAirDemandRegistry(GRACE_TICKS);
    private final ServerLevel level;
    private final Map<Long,BlockState> sensors=new LinkedHashMap<>();
    private long lastSources=-1,material=-1;
    LocalAirDemands(ServerLevel level) { this.level=level; }
    private static Key key(Kind kind,BlockPos pos) { return new Key(kind,Long.toString(pos.asLong())); }
    private static Cell cell(BlockPos pos) { return new Cell(pos.getX(),pos.getY(),pos.getZ()); }
    void sensor(BlockPos pos,BlockState state) {
        if(!(state.getBlock() instanceof SensorBlock) && !sensors.containsKey(pos.asLong())) return;
        sensors.remove(pos.asLong());
        if(state.getBlock() instanceof SensorBlock) sensors.put(pos.asLong(),state);
        // Reconcile below so orientation/kind changes retain their previous sample cell once.
        refreshSensors();
    }
    private void refreshSensors() {
        long now=level.getGameTime();registry.advance(now);
        if(!dev.brights0ng.meteodyne.rain.WeatherConfig.heat().enabled()) { registry.clear();return; }
        var temperatures=new ArrayList<Request>();var winds=new ArrayList<Request>();
        var iterator=sensors.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();var pos=BlockPos.of(entry.getKey());
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null) {
                registry.forget(key(Kind.TEMPERATURE_SENSOR,pos));registry.forget(key(Kind.WIND_SENSOR,pos));continue;
            }
            var current=chunk.getBlockState(pos);
            if(!(current.getBlock() instanceof SensorBlock)) {
                registry.forget(key(Kind.TEMPERATURE_SENSOR,pos));registry.forget(key(Kind.WIND_SENSOR,pos));iterator.remove();continue;
            }
            entry.setValue(current);
            if(Ships.get().inPlot(level,pos)) continue; // Plot-frame planning belongs to the ship integration gate.
            var state=entry.getValue();boolean wind=state.getBlock() instanceof WindSensorBlock;
            var kind=wind?Kind.WIND_SENSOR:Kind.TEMPERATURE_SENSOR;
            var sample=pos.relative(state.getValue(TemperatureSensorBlock.FACING));
            // A housing can be loaded at a chunk edge while its sampling cell is unavailable.
            if(level.getChunkSource().getChunkNow(sample.getX()>>4,sample.getZ()>>4)==null) { registry.forget(key(kind,pos));continue; }
            var request=new Request(key(kind,pos),cell(sample),!wind,wind);
            (wind?winds:temperatures).add(request);
        }
        registry.reconcile(Kind.TEMPERATURE_SENSOR,temperatures);registry.reconcile(Kind.WIND_SENSOR,winds);
    }
    void sourcesChanged() { lastSources=-1; }
    void players(Collection<net.minecraft.server.level.ServerPlayer> current) {
        registry.advance(level.getGameTime());
        var players=new ArrayList<Request>();
        for(var player:current) if(player.serverLevel()==level && !player.isSpectator() && player.isAlive() && !player.hasDisconnected()) {
            var pos=BlockPos.containing(player.position().add(0,.9,0));
            if(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)!=null)
                players.add(new Request(new Key(Kind.PLAYER,player.getUUID().toString()),cell(pos),true,true));
        }
        registry.reconcile(Kind.PLAYER,players);
    }
    void refresh(java.util.function.Supplier<List<BlockPos>> sources,boolean force) {
        players(level.players());
        if(force || lastSources<0 || level.getGameTime()-lastSources>=20 || material!=HeatTables.revision()) {
            lastSources=level.getGameTime();material=HeatTables.revision();var requests=new ArrayList<Request>();
            for(var pos:sources.get()) {
                var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);if(chunk==null || Ships.get().inPlot(level,pos)) continue;
                // Any state of a placed source block, lit or not, so toggling it keeps one demand and its domain.
                if(HeatTables.airSourceCandidate(chunk.getBlockState(pos).getBlock()))
                    requests.add(new Request(key(Kind.PLACED_SOURCE,pos),cell(pos),true,true));
            }
            registry.reconcile(Kind.PLACED_SOURCE,requests);refreshSensors();
        }
    }
    void unload(int x,int z) {
        registry.advance(level.getGameTime());
        sensors.keySet().removeIf(p->{ var pos=BlockPos.of(p);if(pos.getX()>>4!=x || pos.getZ()>>4!=z) return false;
            registry.forget(key(Kind.TEMPERATURE_SENSOR,pos));registry.forget(key(Kind.WIND_SENSOR,pos));return true; });
        registry.unloadChunk(x,z);
    }
    void clear() { registry.advance(level.getGameTime());registry.clear();lastSources=-1; }
}
