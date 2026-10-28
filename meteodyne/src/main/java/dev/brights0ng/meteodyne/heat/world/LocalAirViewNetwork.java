// Written by ChatGPT.
// Updated by ChatGPT: versioned ownership, history and field-age metadata in the view payload.
package dev.brights0ng.meteodyne.heat.world;

import java.util.List;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import dev.brights0ng.meteodyne.Meteodyne;

@EventBusSubscriber(modid=Meteodyne.MODID)
public final class LocalAirViewNetwork {
    // 32,768 * 28 bytes plus header stays below the clientbound 1 MiB payload limit.
    static final int MAX_POINTS=LocalAirCommand.CELL_CAP;
    public record Point(int x,int y,int z,float offset,float vx,float vy,float vz) { }
    public record Payload(String dimension,boolean enabled,long domainId,boolean retainHistory,long ageTicks,int maxAgeTicks,List<Point> points) implements CustomPacketPayload {
        public Payload(String dimension,boolean enabled,List<Point> points) {this(dimension,enabled,0,true,0,40,points);}
        public LocalAirViewCache.Frame cacheFrame() {return new LocalAirViewCache.Frame(enabled,domainId,retainHistory,ageTicks,maxAgeTicks,points);}
        public Payload {
            points=List.copyOf(points);
            if(points.size()>MAX_POINTS) throw new IllegalArgumentException("Too many view samples");
            if(ageTicks<0 || maxAgeTicks<0 || maxAgeTicks>40) throw new IllegalArgumentException("Invalid view age");
        }
        public static final Type<Payload> TYPE=new Type<>(Meteodyne.id("local_air_view"));
        public static final StreamCodec<ByteBuf,Payload> CODEC=StreamCodec.of((b,p)->{
            ByteBufCodecs.STRING_UTF8.encode(b,p.dimension);b.writeBoolean(p.enabled);b.writeLong(p.domainId);b.writeBoolean(p.retainHistory);b.writeLong(p.ageTicks);b.writeInt(p.maxAgeTicks);b.writeInt(p.points.size());
            for(var s:p.points) { b.writeInt(s.x);b.writeInt(s.y);b.writeInt(s.z);b.writeFloat(s.offset);b.writeFloat(s.vx);b.writeFloat(s.vy);b.writeFloat(s.vz); }
        },b->{
            String dimension=ByteBufCodecs.STRING_UTF8.decode(b);boolean enabled=b.readBoolean();long domain=b.readLong();boolean history=b.readBoolean();long age=b.readLong();int maxAge=b.readInt();int n=b.readInt();
            if(n<0 || n>MAX_POINTS) throw new IllegalArgumentException("Invalid view sample count");
            var points=new java.util.ArrayList<Point>(n);
            for(int i=0;i<n;i++) points.add(new Point(b.readInt(),b.readInt(),b.readInt(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat()));
            return new Payload(dimension,enabled,domain,history,age,maxAge,points);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    @SubscribeEvent static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("4").optional().playToClient(Payload.TYPE,Payload.CODEC,
                (payload,context)->context.enqueueWork(()->dev.brights0ng.meteodyne.heat.world.client.LocalAirViewClient.accept(payload)));
    }
    static void send(ServerPlayer player,boolean enabled,List<Point> points) {
        send(player,new Payload(player.level().dimension().location().toString(),enabled,points));
    }
    static void send(ServerPlayer player,Payload payload) {
        if(player.connection!=null && player.connection.hasChannel(Payload.TYPE))
            PacketDistributor.sendToPlayer(player,payload);
    }
    private LocalAirViewNetwork() { }
}
