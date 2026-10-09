package dev.brights0ng.enginesandempires.weather.cloud.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the high deck's see-through clouds (2026-10-07 evening, Bright: cirrostratus strips, cirrus wisps): one
 * translucent grid around the camera at the deck's height ({@link CloudVeils}), its pattern made by cloud_veil.fsh in
 * the deck's own frame, which drifts with the veil clouds' mean velocity (eased over about 30 s, as the clouds' own
 * motion). The grid is remade twice a second and moved on by the drift in between. Client thread.
 */
public final class CloudVeilRenderer {

    /** Cells along a side of the grid. */
    static final int CELLS = 64;
    /** Ticks between remaking the grid. */
    static final int REBUILD_TICKS = 10;
    /** How quickly the pattern's velocity follows the clouds' (share per tick: about 30 s to settle). */
    static final double EASE = 1 / 600.0;
    /** High clouds stay lit after sunset: they see the sun as if it were this much higher (sine of elevation). */
    static final double HIGH_LEAD = 0.06;

    private static ShaderInstance shader;
    private static VertexBuffer buffer;
    private static boolean any;
    private static double gridX0, gridZ0;
    /** The drift when the grid was made. */
    private static double builtDriftX, builtDriftZ;
    /** The deck's drift (blocks, since joining) and its velocity (blocks per tick). */
    private static double driftX, driftZ, velX, velZ;
    private static boolean velKnown;
    private static double windX = 1, windZ;

    static void setShader(ShaderInstance s) {
        shader = s;
    }

    /** Called once per client tick while the clouds are drawn. */
    public static void tick(ClientLevel level, Vec3 camera) {
        double time = level.getGameTime();
        double reach = CloudConfig.drawDistance();
        var clouds = CloudTracker.clouds();
        double[] v = CloudVeils.velocity(clouds, time, camera.x, camera.z, reach);
        if (v != null) {
            if (!velKnown) {
                velX = v[0];
                velZ = v[1];
                velKnown = true;
            } else {
                velX += (v[0] - velX) * EASE;
                velZ += (v[1] - velZ) * EASE;
            }
            double l = Math.hypot(velX, velZ);
            if (l > 1e-4) {
                windX = velX / l;
                windZ = velZ / l;
            }
        }
        driftX += velX;
        driftZ += velZ;
        if (level.getGameTime() % REBUILD_TICKS == 0 || buffer == null) {
            rebuild(CloudVeils.of(clouds, time, camera.x, camera.z, reach, CELLS));
        }
    }

    private static void rebuild(CloudVeils.Grid g) {
        any = g.any();
        if (!any) {
            return;
        }
        gridX0 = g.x0();
        gridZ0 = g.z0();
        builtDriftX = driftX;
        builtDriftZ = driftZ;
        try (ByteBufferBuilder memory = new ByteBufferBuilder(CELLS * CELLS * 4 * 16)) {
            BufferBuilder bb = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int n = g.n();
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    int a = g.index(i, j), b = g.index(i + 1, j), c = g.index(i + 1, j + 1), d = g.index(i, j + 1);
                    if (g.strips()[a] + g.strips()[b] + g.strips()[c] + g.strips()[d] + g.fibres()[a] + g.fibres()[b]
                            + g.fibres()[c] + g.fibres()[d] <= 0) {
                        continue;
                    }
                    vertex(bb, g, i, j, a);
                    vertex(bb, g, i + 1, j, b);
                    vertex(bb, g, i + 1, j + 1, c);
                    vertex(bb, g, i, j + 1, d);
                }
            }
            MeshData mesh = bb.build();
            if (mesh == null) {
                any = false;
                return;
            }
            if (buffer == null) {
                buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            }
            buffer.bind();
            buffer.upload(mesh);
            VertexBuffer.unbind();
        }
    }

    private static void vertex(BufferBuilder bb, CloudVeils.Grid g, int i, int j, int k) {
        bb.addVertex((float) (i * g.spacing()), g.height()[k], (float) (j * g.spacing()))
                .setColor(g.strips()[k], g.fibres()[k], 0f, 1f);
    }

    /** Draws the veils (after the voxel clouds: translucent, depth-tested, not written to depth). */
    public static void render(Matrix4f modelView, Matrix4f projection, Vec3 camera, float partial) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!any || shader == null || buffer == null || level == null) {
            return;
        }
        double dx = driftX + velX * partial - builtDriftX, dz = driftZ + velZ * partial - builtDriftZ;
        shader.safeGetUniform("GridOffset").set((float) (gridX0 + dx - camera.x), (float) -camera.y,
                (float) (gridZ0 + dz - camera.z));
        shader.safeGetUniform("PatternOrigin").set((float) (gridX0 - builtDriftX), (float) (gridZ0 - builtDriftZ));
        float day = level.getTimeOfDay(partial);
        double h = CloudColours.sunHeight(day);
        double dim = 1 - 0.15 * level.getRainLevel(partial) - 0.1 * level.getThunderLevel(partial);
        double[] sky = CloudColours.sky(h);
        double[] sun = CloudColours.sun(Math.min(1, h + HIGH_LEAD));
        shader.safeGetUniform("SkyColor").set((float) (sky[0] * dim), (float) (sky[1] * dim), (float) (sky[2] * dim));
        shader.safeGetUniform("SunColor").set((float) (sun[0] * dim), (float) (sun[1] * dim), (float) (sun[2] * dim));
        CloudLight light = CloudLight.at(day);
        shader.safeGetUniform("LightDir").set((float) light.x(), (float) light.y(), (float) light.z());
        shader.safeGetUniform("SunStrength").set((float) CloudLight.strengthForHeight(Math.min(1, h + HIGH_LEAD)));
        shader.safeGetUniform("WindDir").set((float) windX, (float) windZ);
        float far = CloudConfig.drawDistance();
        shader.safeGetUniform("FogStart").set(far * 0.45f);
        shader.safeGetUniform("FogEnd").set(far);
        shader.safeGetUniform("Opacity").set((float) CloudTuning.veilOpacity);
        float[] storm = dev.brights0ng.enginesandempires.weather.fog.client.FogEffects.cloudFog();
        if (storm != null) {
            shader.safeGetUniform("StormFog").set(storm[0], storm[1], storm[2]);
        } else {
            shader.safeGetUniform("StormFog").set(0f, 0f, 0f);
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        buffer.bind();
        buffer.draw();
        VertexBuffer.unbind();
        shader.clear();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** Forgets the grid (leaving a world, or the clouds switched off). */
    public static void clear() {
        any = false;
        velKnown = false;
        driftX = driftZ = velX = velZ = 0;
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }

    private CloudVeilRenderer() {
    }
}
