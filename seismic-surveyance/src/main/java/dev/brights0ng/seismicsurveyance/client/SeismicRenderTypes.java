// Written by Gemini.
package dev.brights0ng.seismicsurveyance.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Custom render types for Seismic Surveyance planar map and screen displays:
 * <ul>
 *   <li>{@code MAP_SURFACE}: for 2D layered map elements (backgrounds, fills, blurs, grids, outlines, pips, and tabs).
 *       Uses {@code sortOnUpload = false} to strictly preserve painter's submission order regardless of camera angle,
 *       and {@code writeMaskState = COLOR_WRITE} with {@code LEQUAL_DEPTH_TEST} so transparent layers blend smoothly
 *       over each other without writing depth or blinking/clipping due to camera distance sorting.
 *       Also uses {@code NO_CULL} to avoid any winding-based culling discrepancies.</li>
 * </ul>
 */
public final class SeismicRenderTypes {

    private SeismicRenderTypes() {
    }

    /**
     * Map surface render type for translucent, layered planar geometry.
     * Uses painter's order (no upload sort) and color-only write (depth test enabled, depth write disabled).
     */
    public static final RenderType MAP_SURFACE = RenderType.create(
            "seismic_map_surface",
            DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
            VertexFormat.Mode.QUADS,
            2048,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_TEXT_BACKGROUND_SHADER)
                    .setTextureState(RenderStateShard.NO_TEXTURE)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false)
    );
}
