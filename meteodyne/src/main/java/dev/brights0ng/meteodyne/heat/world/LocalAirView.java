// Written by ChatGPT.
// Updated by ChatGPT: added automatic-domain viewer and held thermometer source selection.
// Updated by ChatGPT: explained coverage checks and rejection in the live readout.
// Updated by ChatGPT: retried guarded publications each tick without letting missing notices consume the field-send cadence.
package dev.brights0ng.meteodyne.heat.world;

import java.util.*;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.instrument.InstrumentContent;
import dev.brights0ng.meteodyne.instrument.InstrumentReading;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;


/** Ephemeral operator trial. Persistent renderer samples are sent only to the requesting player. */
@EventBusSubscriber(modid=Meteodyne.MODID)
public final class LocalAirView {
    enum Mode { MANUAL, AUTOMATIC }
    private record Session(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,int maxAge,int spacing,Mode mode,LocalAirViewSchedule schedule) {
        Session(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,int maxAge,int spacing,Mode mode) {this(dimension,maxAge,spacing,mode,new LocalAirViewSchedule());}
    }
    record Update(Component caption,LocalAirViewNetwork.Payload payload) { }
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    static final int MAX_VIEWERS=4;

    static boolean enable(ServerPlayer player,int maxAge) {
        return enable(player,maxAge,Mode.MANUAL);
    }
    static boolean enable(ServerPlayer player,int maxAge,Mode mode) {
        if(maxAge<0 || maxAge>40) throw new IllegalArgumentException("Trial age must be 0..40 ticks");
        if(!player.hasPermissions(2) || HeatWorld.of(player.serverLevel())==null) return false;
        if(!SESSIONS.containsKey(player.getUUID()) && SESSIONS.size()>=MAX_VIEWERS) return false;
        var old=SESSIONS.get(player.getUUID());
        clearReadings(player);LocalAirViewNetwork.send(player,false,List.of());
        SESSIONS.put(player.getUUID(),new Session(player.level().dimension(),maxAge,old==null?1:old.spacing,Objects.requireNonNull(mode)));return true;
    }
    static boolean scale(ServerPlayer player,int blocks) {
        if(blocks<1 || blocks>32) throw new IllegalArgumentException("Lattice spacing must be 1..32 blocks");
        if(!active(player)) return false;
        var old=SESSIONS.get(player.getUUID());
        SESSIONS.put(player.getUUID(),new Session(old.dimension,old.maxAge,blocks,old.mode));
        LocalAirViewNetwork.send(player,false,List.of());return true;
    }
    static int spacing(ServerPlayer player) { return active(player)?SESSIONS.get(player.getUUID()).spacing:0; }
    static Mode mode(ServerPlayer player) { return active(player)?SESSIONS.get(player.getUUID()).mode:null; }
    /** One immutable, revision-guarded field per packet; no world queries per point. */
    static List<LocalAirViewNetwork.Point> samples(LocalAirRun.Frame frame,long now,int maxAge,int spacing) {
        return samples(frame,now,maxAge,spacing,false);
    }
    static List<LocalAirViewNetwork.Point> samples(LocalAirRun.Frame frame,long now,int maxAge,int spacing,boolean worldAnchored) {
        return LocalAirViewModel.samples(frame,now,maxAge,spacing,worldAnchored);
    }
    public static boolean active(Player player) {
        var session=SESSIONS.get(player.getUUID());
        return session!=null && player.hasPermissions(2) && session.dimension.equals(player.level().dimension());
    }
    /** Same body point as the ordinary thermometer; no shelter multiplier, weather fallback or radiant term. */
    public static LocalAirReading reading(ServerPlayer player) {
        return presentation(player).reading();
    }
    static LocalAirPresentation presentation(ServerPlayer player) {
        var at=player.position().add(0,0.9,0);var session=SESSIONS.get(player.getUUID());
        if(!active(player) || session==null) throw new IllegalStateException("No active local-air trial");
        return HeatWorld.of(player.serverLevel()).localAirPresentation(session.mode==Mode.AUTOMATIC,at.x,at.y,at.z,session.maxAge);
    }
    static LocalAirViewNetwork.Payload payload(ServerPlayer player) { return payload(player,SESSIONS.get(player.getUUID()),presentation(player)); }
    private static LocalAirViewNetwork.Payload payload(ServerPlayer player,Session session,LocalAirPresentation data) {
        return payload(player,session,data,true);
    }
    private static LocalAirViewNetwork.Payload payload(ServerPlayer player,Session session,LocalAirPresentation data,boolean sample) {
        long now=player.serverLevel().getGameTime();var frame=data.frame();
        var points=sample?samples(frame,now,session.maxAge,session.spacing,session.mode==Mode.AUTOMATIC):List.<LocalAirViewNetwork.Point>of();
        return new LocalAirViewNetwork.Payload(player.level().dimension().location().toString(),true,data.domainId(),data.retainHistory(),
                frame==null?0:LocalAirDiagnostics.age(frame,now),session.maxAge,points);
    }
    public static void updateThermometer(net.minecraft.world.item.ItemStack stack,ServerPlayer player) {
        var value=reading(player).currentSample();
        if(value.isPresent()) {
            var sample=value.get();stack.set(InstrumentContent.READING.get(),new InstrumentReading(
                    player.level().dimension().location().toString(),sample.throughTick(),(float)sample.temperature()));
        } else stack.remove(InstrumentContent.READING.get());
    }
    static void disable(ServerPlayer player) {
        if(SESSIONS.remove(player.getUUID())!=null) {
            LocalAirViewNetwork.send(player,false,List.of());clearReadings(player);player.displayClientMessage(Component.literal("Local-air trial off; thermometer restored to normal sampling."),true);
        }
    }
    private static void clearReadings(ServerPlayer player) {
        for(var stack:player.getInventory().items) if(stack.is(InstrumentContent.THERMOMETER.get())) stack.remove(InstrumentContent.READING.get());
        for(var stack:player.getInventory().offhand) if(stack.is(InstrumentContent.THERMOMETER.get())) stack.remove(InstrumentContent.READING.get());
    }
    @SubscribeEvent static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) disable(player);
    }
    @SubscribeEvent static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) disable(player);
    }
    @SubscribeEvent static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if(event.getEntity() instanceof ServerPlayer player) disable(player);
    }
    @SubscribeEvent static void stop(ServerStoppingEvent event) {
        for(var player:event.getServer().getPlayerList().getPlayers()) disable(player);
        SESSIONS.clear();
    }
    @SubscribeEvent static void tick(ServerTickEvent.Post event) {
        for(var player:event.getServer().getPlayerList().getPlayers()) {
            if(!SESSIONS.containsKey(player.getUUID())) continue;
            if(!active(player) || player.isDeadOrDying()) { disable(player);continue; }
            var update=update(player);
            if(update.caption()!=null) player.displayClientMessage(update.caption(),true);
            if(update.payload()!=null) LocalAirViewNetwork.send(player,update.payload());
        }
    }
    /** Production presentation path, also exercised by viewers that are not in the real player list in GameTests. */
    static Update update(ServerPlayer player) {
        var session=SESSIONS.get(player.getUUID());var data=presentation(player);var frame=data.frame();
        long now=player.serverLevel().getGameTime();
        boolean renderable=frame!=null && LocalAirDiagnostics.age(frame,now)<=session.maxAge;
        var decision=session.schedule.advance(now,new LocalAirViewSchedule.State(data.domainId(),data.retainHistory(),renderable,data.reading().status(),data.reading().reason()));
        Component caption=null;
        if(decision.caption()) {
            String quality=frame==null?"":String.format(Locale.ROOT," | div %.2g, pressure residual %.2g",frame.quality.maxDivergence(),frame.quality.maxPressureResidual());
            caption=Component.literal((session.mode==Mode.AUTOMATIC?"Automatic | ":"Manual | ")+LocalAirViewModel.label(data)+quality);
        }
        return new Update(caption,decision.packet()?payload(player,session,data,decision.field()):null);
    }
    private LocalAirView() { }
}
