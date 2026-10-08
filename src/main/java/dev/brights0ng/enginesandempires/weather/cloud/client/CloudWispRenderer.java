package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the wisps ({@link CloudWisps}) of the cumulus near the camera: soft translucent sprites, haze turned to face
 * the camera and shreds hanging upright, sorted far to near and blended over the clouds. Client (render thread).
 */
public final class CloudWispRenderer {

    /** The most wisps drawn in all. */
    static final int MAX_WISPS = 3000;

    private static final Map<CloudMeshes.Formation, Entry> CLOUDS = new IdentityHashMap<>();
    private static long seeds = 0x5EEDL;

    private static DynamicTexture texture;
    private static ResourceLocation textureId;
    private static VertexBuffer buffer;

    private record Entry(UUID frame, CloudWisps wisps) {
    }

    /** Once a client tick: wisps on the cumulus within range, new ones in, old ones out. */
    public static void tick(double time, Vec3 camera) {
        if (!CloudTuning.wisps || CloudTuning.wispDensity <= 0) {
            CLOUDS.clear();
            return;
        }
        List<CloudMeshes.Formation> live = CloudMeshes.live();
        java.util.Set<CloudMeshes.Formation> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        int total = 0;
        for (Entry e : CLOUDS.values()) {
            total += e.wisps.wisps.size();
        }
        for (CloudMeshes.Formation fe : live) {
            CloudField f = fe.liveField;
            if (!f.heap) {
                continue;
            }
            double ax = CloudTracker.drawnX(fe.liveFrame, time);
            double az = CloudTracker.drawnZ(fe.liveFrame, time);
            if (!Double.isFinite(ax) || !Double.isFinite(az)) {
                continue;
            }
            double[] b = f.bounds();
            double cx = camera.x - ax, cz = camera.z - az;
            double gx = Math.max(0, Math.max(b[0] - cx, cx - b[1]));
            double gy = Math.max(0, Math.max(b[2] - camera.y, camera.y - b[3]));
            double gz = Math.max(0, Math.max(b[4] - cz, cz - b[5]));
            if (gx * gx + gy * gy + gz * gz > CloudTuning.wispDistance * CloudTuning.wispDistance) {
                continue;
            }
            seen.add(fe);
            Entry e = CLOUDS.get(fe);
            if (e == null || !e.frame.equals(fe.liveFrame)) {
                if (e != null) {
                    total -= e.wisps.wisps.size();
                }
                e = new Entry(fe.liveFrame, new CloudWisps(seeds++));
                CLOUDS.put(fe, e);
            }
            int before = e.wisps.wisps.size();
            e.wisps.tick(f, fe.liveTime, time, Math.max(0, MAX_WISPS - total));
            total += e.wisps.wisps.size() - before;
        }
        CLOUDS.keySet().retainAll(seen);
    }

    /** Draws the wisps (after the clouds), at time {@code time} (partial ticks included). */
    public static void render(Matrix4f modelView, Matrix4f projection, Vec3 camera, double time) {
        if (CLOUDS.isEmpty()) {
            return;
        }
        if (!ensureTexture()) {
            return;
        }
        ClientLevelColours colours = ClientLevelColours.now();
        record Drawn(double x, double y, double z, double d2, CloudWisps.Wisp w) {
        }
        List<Drawn> drawn = new ArrayList<>();
        for (Map.Entry<CloudMeshes.Formation, Entry> me : CLOUDS.entrySet()) {
            double ax = CloudTracker.drawnX(me.getValue().frame, time);
            double az = CloudTracker.drawnZ(me.getValue().frame, time);
            if (!Double.isFinite(ax)) {
                continue;
            }
            for (CloudWisps.Wisp w : me.getValue().wisps.wisps) {
                double[] p = w.at(time);
                double x = ax + p[0] - camera.x, y = p[1] - camera.y, z = az + p[2] - camera.z;
                drawn.add(new Drawn(x, y, z, x * x + y * y + z * z, w));
            }
        }
        if (drawn.isEmpty()) {
            return;
        }
        drawn.sort((a, b) -> Double.compare(b.d2, a.d2));

        Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f left = cam.getLeftVector();
        Vector3f up = cam.getUpVector();
        // Fogged like the clouds (CloudRenderer): inside a cloud by the game's fog, in every direction (Bright,
        // 2026-10-07: wisps showed through the in-cloud fog); otherwise by the clouds' own distance fog, horizontally.
        boolean inside = dev.brights0ng.enginesandempires.weather.fog.client.FogEffects.insideCloud() != null;
        float fogStart = inside ? RenderSystem.getShaderFogStart() : CloudConfig.drawDistance() * 0.45f;
        float fogEnd = inside ? Math.max(RenderSystem.getShaderFogEnd(), 0.5f) : CloudConfig.drawDistance();
        float[] fog = RenderSystem.getShaderFogColor();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (Drawn d : drawn) {
            CloudWisps.Wisp w = d.w;
            double alpha = w.alpha(time);
            double dist = inside ? Math.sqrt(d.d2) : Math.hypot(d.x, d.z);
            double fogged = Math.max(0, Math.min(1, (dist - fogStart) / Math.max(1e-3, fogEnd - fogStart)));
            alpha *= 1 - fogged;
            if (alpha <= 0.004) {
                continue;
            }
            // Lit like the cloud's vertices, live (CloudShading, clouds.fsh).
            double[] parts = w.parts(colours.light.x(), colours.light.y(), colours.light.z(), colours.light.strength());
            double skyPart = parts[0], sunPart = parts[1];
            float r = (float) mixFog(Math.min(1, skyPart * colours.sky[0] + sunPart * colours.sun[0]), fog[0], fogged);
            float g = (float) mixFog(Math.min(1, skyPart * colours.sky[1] + sunPart * colours.sun[1]), fog[1], fogged);
            float bl = (float) mixFog(Math.min(1, skyPart * colours.sky[2] + sunPart * colours.sun[2]), fog[2], fogged);
            float a = (float) Math.min(1, alpha);
            if (w.kind == CloudWisps.HAZE) {
                double c = Math.cos(w.turn), s = Math.sin(w.turn);
                // Two in-view axes, turned.
                double ux = (left.x() * c + up.x() * s) * w.size, uy = (left.y() * c + up.y() * s) * w.size,
                        uz = (left.z() * c + up.z() * s) * w.size;
                double vx = (-left.x() * s + up.x() * c) * w.size, vy = (-left.y() * s + up.y() * c) * w.size,
                        vz = (-left.z() * s + up.z() * c) * w.size;
                quad(bb, d.x, d.y, d.z, ux, uy, uz, vx, vy, vz, 0f, 0.5f, r, g, bl, a);
            } else {
                // Upright, turned toward the camera about the vertical.
                double hl = Math.hypot(d.x, d.z);
                double sx = hl < 1e-6 ? 1 : -d.z / hl, sz = hl < 1e-6 ? 0 : d.x / hl;
                double ux = sx * w.size, uz = sz * w.size;
                double vy = w.size * w.stretch;
                quad(bb, d.x, d.y, d.z, ux, 0, uz, 0, vy, 0, 0.5f, 1f, r, g, bl, a);
            }
        }
        MeshData mesh = bb.build();
        if (mesh == null) {
            return;
        }
        ShaderInstance shader = GameRenderer.getPositionTexColorShader();
        if (shader == null) {
            mesh.close();
            return;
        }
        if (buffer == null) {
            buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        RenderSystem.setShaderTexture(0, textureId);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        buffer.bind();
        buffer.upload(mesh);
        buffer.drawWithShader(modelView, projection, shader);
        VertexBuffer.unbind();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void quad(BufferBuilder bb, double cx, double cy, double cz, double ux, double uy, double uz,
                             double vx, double vy, double vz, float u0, float u1, float r, float g, float b, float a) {
        bb.addVertex((float) (cx - ux - vx), (float) (cy - uy - vy), (float) (cz - uz - vz)).setUv(u0, 1).setColor(r, g, b, a);
        bb.addVertex((float) (cx + ux - vx), (float) (cy + uy - vy), (float) (cz + uz - vz)).setUv(u1, 1).setColor(r, g, b, a);
        bb.addVertex((float) (cx + ux + vx), (float) (cy + uy + vy), (float) (cz + uz + vz)).setUv(u1, 0).setColor(r, g, b, a);
        bb.addVertex((float) (cx - ux + vx), (float) (cy - uy + vy), (float) (cz - uz + vz)).setUv(u0, 0).setColor(r, g, b, a);
    }

    private static double mixFog(double c, float fog, double f) {
        return c + (fog - c) * f;
    }

    /** The wisp texture: soft haze on the left half, a torn, fibrous shred on the right. Made once, in code. */
    private static boolean ensureTexture() {
        if (textureId != null) {
            return true;
        }
        try {
            int[][] alpha = WispTexture.make(WispTexture.SIZE);
            int w = alpha[0].length, h = alpha.length;
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // ABGR; white, so the channel order only matters for alpha.
                    img.setPixelRGBA(x, y, (alpha[y][x] << 24) | 0x00FFFFFF);
                }
            }
            texture = new DynamicTexture(img);
            texture.setFilter(true, false);
            textureId = ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "dynamic/cloud_wisps");
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            return true;
        } catch (RuntimeException ex) {
            EnginesAndEmpiresMod.LOGGER.warn("Clouds: couldn't make the wisp texture", ex);
            CloudTuning.wisps = false;
            return false;
        }
    }

    /** Forgets every wisp (leaving a world). */
    public static void clear() {
        CLOUDS.clear();
    }

    /** The light's colours and strength now, as the cloud shader has them (CloudColours with rain's dimming). */
    private record ClientLevelColours(double[] sky, double[] sun, CloudLight light) {
        static ClientLevelColours now() {
            var level = Minecraft.getInstance().level;
            if (level == null) {
                return new ClientLevelColours(new double[]{1, 1, 1}, new double[]{1, 1, 1}, CloudLight.at(0));
            }
            float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
            float day = level.getTimeOfDay(partial);
            double h = CloudColours.sunHeight(day);
            double dim = 1 - 0.15 * level.getRainLevel(partial) - 0.1 * level.getThunderLevel(partial);
            double[] sky = CloudColours.sky(h), sun = CloudColours.sun(h);
            for (int k = 0; k < 3; k++) {
                sky[k] *= dim;
                sun[k] *= dim;
            }
            return new ClientLevelColours(sky, sun, CloudLight.at(day));
        }
    }

    private CloudWispRenderer() {
    }
}
