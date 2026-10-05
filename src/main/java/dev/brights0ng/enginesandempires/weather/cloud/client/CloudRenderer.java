package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.io.IOException;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the voxel clouds: one opaque pass into the main framebuffer, right after solid terrain, so terrain and clouds
 * hide each other correctly and water draws over them.
 *
 * <p>Each cloud's mesh is drawn at PA's latest centre moved along its velocity to this frame, so clouds glide smoothly
 * between PA's once-a-second updates. Clouds outside the view, or past the draw distance, are skipped.
 *
 * <p>Vanilla's far clip plane is only 4x the render distance (768 blocks at 12 chunks), so while the renderer is on the
 * game's projection reaches out to the cloud draw distance instead (see {@code GameRendererCloudDepthMixin}). Depth
 * precision is set almost entirely by the near plane, so terrain doesn't lose any.
 */
public final class CloudRenderer {

    private static ShaderInstance shader;

    /**
     * Whether the voxel clouds replace vanilla's in this level: on, and the Overworld (where the pack's weather lives).
     * Until the weather simulation produces clouds (phase 4 of {@code claude/weather-backbone-plan.md}) the sky is empty.
     */
    public static boolean active(ClientLevel level) {
        return level != null && CloudConfig.enabled() && Level.OVERWORLD.equals(level.dimension());
    }

    /** How far the projection must reach while the clouds are drawn: the draw distance out, and the tallest top up. */
    public static float depthFar() {
        double out = CloudConfig.drawDistance() * 1.2 + 512;
        double up = CloudScale.MAX_TOP_Y + 512;
        return (float) Math.sqrt(out * out + up * up);
    }

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "clouds"),
                    DefaultVertexFormat.POSITION_COLOR), s -> shader = s);
        } catch (IOException e) {
            EnginesAndEmpiresMod.LOGGER.error("Clouds: couldn't load the cloud shader", e);
        }
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "fog_depth"),
                    DefaultVertexFormat.POSITION), FoggedTerrainDepth::setShader);
        } catch (IOException e) {
            EnginesAndEmpiresMod.LOGGER.error("Clouds: couldn't load the fogged-terrain depth shader", e);
        }
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double time = level.getGameTime() + partial;
        Vec3 camera = event.getCamera().getPosition();
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            if (active(level)) {
                // Terrain the fog has fully hidden mustn't hide the clouds behind it.
                FoggedTerrainDepth.apply(event.getModelViewMatrix(), event.getProjectionMatrix());
                drawClouds(level, event.getModelViewMatrix(), event.getProjectionMatrix(), event.getFrustum(), camera,
                        time, partial);
            }
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            CloudOutlines.render(camera, time);
        }
    }

    private static void drawClouds(ClientLevel level, Matrix4f modelView, Matrix4f projection, Frustum frustum,
                                   Vec3 camera, double time, float partial) {
        if (shader == null) {
            return;
        }
        Vec3 tint = tint(level, partial);
        float drawDistance = CloudConfig.drawDistance();
        shader.safeGetUniform("CloudTint").set((float) tint.x, (float) tint.y, (float) tint.z);
        if (dev.brights0ng.enginesandempires.weather.fog.client.FogEffects.insideCloud() != null) {
            // Inside a cloud its faces (holes, the far wall) fade into the fog like everything else, in every
            // direction: the game's fog, which the in-cloud fog has just set.
            shader.safeGetUniform("CloudFogStart").set(RenderSystem.getShaderFogStart());
            shader.safeGetUniform("CloudFogEnd").set(Math.max(RenderSystem.getShaderFogEnd(), 0.5f));
            shader.safeGetUniform("CloudFogSphere").set(1f);
        } else {
            shader.safeGetUniform("CloudFogStart").set(drawDistance * 0.45f);
            shader.safeGetUniform("CloudFogEnd").set(drawDistance);
            shader.safeGetUniform("CloudFogSphere").set(0f);
        }
        shader.safeGetUniform("Flash").set(0f);

        // One shader setup for all sections; per formation only the offset changes.
        Uniform offset = shader.getUniform("CloudOffset");
        if (offset == null) {
            return;
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        // Back faces are drawn too: they're hidden behind the front faces anyway, and wherever the mesh leaves a
        // sub-pixel crack (T-junctions the seam overlap can't reach) they fill it with cloud instead of sky.
        RenderSystem.disableCull();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        for (CloudMeshes.Drawable d : CloudMeshes.drawables(time)) {
            double x = d.x();
            double z = d.z();
            boolean offsetSet = false;
            for (CloudMeshes.Section s : d.sections()) {
                CloudVoxelizer.Result r = s.result;
                AABB box = new AABB(x + r.minX(), r.minY(), z + r.minZ(), x + r.maxX(), r.maxY(), z + r.maxZ());
                if (!frustum.isVisible(box)) {
                    continue;
                }
                if (!offsetSet) {
                    offset.set((float) (x - camera.x), (float) -camera.y, (float) (z - camera.z));
                    offset.upload();
                    offsetSet = true;
                }
                s.buffer.bind();
                s.buffer.draw();
            }
        }
        VertexBuffer.unbind();
        shader.clear();
        RenderSystem.enableCull();
    }

    /**
     * The sky's light on the clouds: vanilla's day-night curve for cloud colour, but with only a mild dimming in rain
     * and thunder. Vanilla greys clouds out heavily in bad weather, which on top of the storms' own darkness turned
     * their bases black; here a storm's darkness comes from the storm itself.
     */
    public static Vec3 tint(ClientLevel level, float partial) {
        float day = level.getTimeOfDay(partial);
        double light = Math.max(0, Math.min(1, Math.cos(day * Math.PI * 2) * 2 + 0.5));
        double dim = 1 - 0.15 * level.getRainLevel(partial) - 0.1 * level.getThunderLevel(partial);
        return new Vec3((light * 0.9 + 0.1) * dim, (light * 0.9 + 0.1) * dim, (light * 0.85 + 0.15) * dim);
    }

    private CloudRenderer() {
    }
}
