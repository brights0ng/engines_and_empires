package dev.brights0ng.enginesandempires.weather.cloud.client;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;

/**
 * Lets clouds show over terrain the fog has fully hidden (Bright, 2026-10-04: a fogged-out hill in front of a cloud
 * cut a hole in it). The hill is drawn in pure fog colour, but its depth still hides the cloud behind it. So, just
 * before the clouds draw, every pixel of solid terrain at or past the fog's end is pushed to the far plane in the depth
 * buffer: the clouds then draw there as if the hill weren't, which is what one sees through fog that thick. Terrain
 * the fog only partly hides still hides the cloud (Bright, 2026-10-04: blending clouds through half-fogged terrain
 * made blocks look see-through; the fog standing out is preferred).
 *
 * <p>Cost per frame: one depth copy (the depth can't be read while it is written) and one full-screen pass that only
 * writes depth. Render thread.
 */
final class FoggedTerrainDepth {

    private static ShaderInstance shader;
    private static TextureTarget copy;

    static void setShader(ShaderInstance s) {
        shader = s;
    }

    /** Pushes fully fogged solid terrain back; call right after solid terrain, before the clouds. */
    static void apply(Matrix4f modelView, Matrix4f projection) {
        if (shader == null) {
            return;
        }
        float fogEnd = RenderSystem.getShaderFogEnd();
        if (!(fogEnd > 0) || fogEnd > 1e6f) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (copy == null) {
            copy = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        } else if (copy.width != main.width || copy.height != main.height) {
            copy.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        // A depth copy needs the same depth format: match the main image if a mod gave it a stencil.
        if (main.isStencilEnabled() && !copy.isStencilEnabled()) {
            copy.enableStencil();
        }
        copy.copyDepthFrom(main);
        main.bindWrite(false);

        shader.setSampler("DepthSampler", copy.getDepthTextureId());
        shader.safeGetUniform("InvViewProj").set(new Matrix4f(projection).mul(modelView).invert());
        shader.safeGetUniform("TerrainFogEnd").set(fogEnd);
        shader.safeGetUniform("TerrainFogShape").set(RenderSystem.getShaderFogShape().getIndex());

        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> shader);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableCull();
    }

    private FoggedTerrainDepth() {
    }
}
