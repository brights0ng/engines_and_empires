// Written by ChatGPT; reuses the existing HeatViewClient cube, colour and line renderer.
// Updated by ChatGPT: cleared obsolete owners and aged received fields using server sample age.
package dev.brights0ng.meteodyne.heat.world.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.brights0ng.meteodyne.heat.world.LocalAirViewNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Retains the last diagnostic frame until switched off; unavailable/aged data is drawn grey. */
public final class LocalAirViewClient {
    private static final dev.brights0ng.meteodyne.heat.world.LocalAirViewCache cache=new dev.brights0ng.meteodyne.heat.world.LocalAirViewCache();
    private static net.minecraft.client.multiplayer.ClientLevel owner;
    public static void clear() { cache.clear();owner=null; }
    public static void accept(LocalAirViewNetwork.Payload payload) {
        var level=Minecraft.getInstance().level;
        if(level!=owner) cache.clear();
        owner=level;
        if(level==null || !level.dimension().location().toString().equals(payload.dimension())) { cache.clear();return; }
        cache.accept(payload.cacheFrame(),level.getGameTime());
    }
    public static void render(RenderLevelStageEvent event) {
        var level=Minecraft.getInstance().level;
        if(level!=owner) { cache.clear();owner=level;return; }
        var points=cache.points();
        if(level==null || points.isEmpty() || event.getStage()!=RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        var camera=event.getCamera().getPosition();var buffers=Minecraft.getInstance().renderBuffers().bufferSource();
        var pose=new PoseStack().last();boolean fresh=cache.current(level.getGameTime());
        var quads=buffers.getBuffer(RenderType.debugQuads());
        for(var p:points) {
            int rgb=fresh?HeatViewClient.colour(p.offset()):0x808080;
            HeatViewClient.cube(quads,pose,(float)(p.x()+0.5-camera.x),(float)(p.y()+0.5-camera.y),(float)(p.z()+0.5-camera.z),rgb>>16&255,rgb>>8&255,rgb&255);
        }
        buffers.endBatch(RenderType.debugQuads());
        // Historical glyphs are cubes only. Never present an unavailable vector as current flow.
        if(!fresh) return;
        var lines=buffers.getBuffer(RenderType.lines());
        for(var p:points) {
            double speed=Math.sqrt(p.vx()*p.vx()+p.vy()*p.vy()+p.vz()*p.vz());if(speed<1e-6) continue;
            float ux=(float)(p.vx()/speed),uy=(float)(p.vy()/speed),uz=(float)(p.vz()/speed);
            float length=(float)Math.min(0.8,speed*0.2),ex=ux*length,ey=uy*length,ez=uz*length;
            float sx,sy,sz;
            if(Math.abs(uy)>0.9) { sx=0;sy=uz;sz=-uy; } else { sx=-uz;sy=0;sz=ux; }
            float sideScale=(float)(length*0.2/Math.sqrt(sx*sx+sy*sy+sz*sz));sx*=sideScale;sy*=sideScale;sz*=sideScale;
            float x=(float)(p.x()+0.5-camera.x),y=(float)(p.y()+0.5-camera.y),z=(float)(p.z()+0.5-camera.z);
            HeatViewClient.line(lines,pose,x,y,z,x+ex,y+ey,z+ez);
            HeatViewClient.line(lines,pose,x+ex,y+ey,z+ez,x+ex*.7f+sx,y+ey*.7f+sy,z+ez*.7f+sz);
            HeatViewClient.line(lines,pose,x+ex,y+ey,z+ez,x+ex*.7f-sx,y+ey*.7f-sy,z+ez*.7f-sz);
        }
        buffers.endBatch(RenderType.lines());
    }
    private LocalAirViewClient() { }
}
