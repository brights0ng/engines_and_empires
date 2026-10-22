// Written by Gemini; edited by Claude (polygon-offset layering for the talc lines).
package dev.brights0ng.empire.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Custom render types for Empire world and boundary rendering:
 * <ul>
 *   <li>{@code BORDER_VEIL}: For in-world talc lines and shimmering light veils at Empire perimeter boundaries.
 *       Uses untextured POSITION_COLOR with translucent blending, LEQUAL depth testing, no face culling,
 *       and color-only write mask (disabling depth writes so overlapping translucent sheets blend smoothly).
 *       Polygon-offset layering pulls the quads toward the camera by a slope-scaled depth bias, so lines lying on
 *       block faces win the depth test at any distance and grazing angle, where a fixed geometric offset fails.</li>
 * </ul>
 */
public final class EmpireRenderTypes {

    private EmpireRenderTypes() {
    }

    public static final RenderType BORDER_VEIL = RenderType.create(
            "empire_border_veil",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            2048,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTextureState(RenderStateShard.NO_TEXTURE)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
                    .createCompositeState(false)
    );
}
