// Extracted from TalcBorderRenderer (written by Gemini, rewritten by Claude); generalized for shared boundary and region rendering by Gemini.
package dev.brights0ng.empire.client;

import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Shared in-world renderer for {@link VeilMesh}es (both empire claim boundaries and 3D regions).
 * Draws chalk ribbons and shimmering translucent veils through {@link EmpireRenderTypes#BORDER_VEIL} with
 * unified distance fade, chalk opacity, and veil shimmer animation so visual changes apply identically to both.
 */
public final class VeilMeshRenderer {

    /** Lines and veils fade out between these horizontal distances from the camera. */
    public static final float FADE_START = 55.0F;
    public static final float FADE_END = 80.0F;
    /** Base alpha of chalk ribbon quads before distance fade. */
    public static final int CHALK_ALPHA = 225;
    /** Peak base alpha of shimmering veil quads before wave modulation and distance fade. */
    public static final float VEIL_BASE_ALPHA = 105.0F;
    /** Animation time scale applied to (gameTime + partialTick). */
    public static final float TIME_SCALE = 0.06F;

    /** Maps a mesh quad style id to chalk RGB ({@code out[0..2]}) and veil RGB ({@code out[3..5]}). */
    @FunctionalInterface
    public interface StylePalette {
        void colors(int style, int[] out);
    }

    /** Animation time parameter derived from world game time and partial tick. */
    public static float animationTime(long gameTime, RenderLevelStageEvent event) {
        return (gameTime + event.getPartialTick().getGameTimeDeltaPartialTick(false)) * TIME_SCALE;
    }

    /** Draws a single {@link VeilMesh} using {@link EmpireRenderTypes#BORDER_VEIL}. */
    public static void draw(RenderLevelStageEvent event, Minecraft mc, VeilMesh mesh, float time, StylePalette palette) {
        if (mesh == null || mesh.quadCount() == 0) {
            return;
        }
        draw(event, mc, List.of(mesh), time, palette);
    }

    /** Draws a list of {@link VeilMesh}es in a single {@link EmpireRenderTypes#BORDER_VEIL} batch. */
    public static void draw(RenderLevelStageEvent event, Minecraft mc, List<VeilMesh> meshes, float time,
                            StylePalette palette) {
        if (meshes.isEmpty()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(EmpireRenderTypes.BORDER_VEIL);

        int[] color = new int[6];
        float[] fade = new float[4];
        boolean drewAny = false;

        for (VeilMesh m : meshes) {
            if (m == null || m.quadCount() == 0) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(m.originX() - cam.x, -cam.y, m.originZ() - cam.z);
            Matrix4f matrix = poseStack.last().pose();

            // Camera position relative to the mesh origin, so distances stay in small floats.
            float camX = (float) (cam.x - m.originX());
            float camZ = (float) (cam.z - m.originZ());

            for (int q = 0; q < m.quadCount(); q++) {
                float maxFade = 0.0F;
                for (int v = 0; v < 4; v++) {
                    float dx = m.x(q, v) - camX;
                    float dz = m.z(q, v) - camZ;
                    float d = Mth.sqrt(dx * dx + dz * dz);
                    fade[v] = 1.0F - Mth.clamp((d - FADE_START) / (FADE_END - FADE_START), 0.0F, 1.0F);
                    maxFade = Math.max(maxFade, fade[v]);
                }
                if (maxFade <= 0.0F) {
                    continue;
                }
                palette.colors(m.style(q), color);
                int kind = m.kind(q);
                drewAny = true;
                for (int v = 0; v < 4; v++) {
                    float x = m.x(q, v);
                    float y = m.y(q, v);
                    float z = m.z(q, v);
                    if (kind == VeilMesh.CHALK) {
                        int alpha = (int) (CHALK_ALPHA * fade[v]);
                        consumer.addVertex(matrix, x, y, z).setColor(color[0], color[1], color[2], alpha);
                    } else {
                        int alpha;
                        if (kind == VeilMesh.VEIL_GRADIENT && (v == 1 || v == 2)) {
                            alpha = 0;
                        } else {
                            float wave = Mth.sin((x + m.originX() + y + z + m.originZ()) * 0.4F + time * 3.0F);
                            alpha = (int) (VEIL_BASE_ALPHA * (0.75F + 0.25F * wave) * fade[v]);
                        }
                        consumer.addVertex(matrix, x, y, z).setColor(color[3], color[4], color[5], alpha);
                    }
                }
            }
            poseStack.popPose();
        }

        if (drewAny) {
            buffers.endBatch(EmpireRenderTypes.BORDER_VEIL);
        }
    }

    private VeilMeshRenderer() {
    }
}
