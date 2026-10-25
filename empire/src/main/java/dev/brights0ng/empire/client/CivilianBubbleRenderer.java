// Written by Claude.
package dev.brights0ng.empire.client;

import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianSpeechStore;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * A civilian's speech bubble: its line on a partly transparent dark background above the name tag, with a thin bar
 * along the bottom that shrinks as the line's time runs out. Fades in and out. The bubble's bottom edge sits at the
 * anchor, so longer lines grow upwards, away from the name.
 */
public final class CivilianBubbleRenderer {

    /** Line wrap width in font pixels (provisional). */
    public static final int BUBBLE_WRAP = 120;
    /** Bubbles further than this from the camera are not drawn (provisional). */
    public static final double BUBBLE_RENDER_DIST = 24.0;
    /** Background and timer bar colours, ARGB (provisional). */
    public static final int BUBBLE_BG = 0x99101010;
    public static final int BUBBLE_BAR = 0xFFE0B050;

    private static final int MAX_LINES = 4;
    private static final int PADDING = 3;
    private static final int BAR_HEIGHT = 2;
    private static final float FADE_IN = 4.0F;
    private static final float FADE_OUT = 6.0F;
    private static final double ANCHOR_ABOVE_HEAD = 0.85;
    private static final float SCALE = 0.025F;
    // Vanilla puts a name tag's background 0.01 behind its text in this local space.
    private static final float Z_BACKGROUND = 0.01F;
    private static final float Z_BAR = 0.005F;
    private static final float Z_TEXT = 0.0F;
    /** Font draws any alpha below 4 as opaque. */
    private static final int MIN_TEXT_ALPHA = 4;

    /** How long a line stays up, in ticks. */
    static int duration(String text) {
        return Mth.clamp(50 + 2 * text.length(), 60, 140);
    }

    static void render(CivilianEntity civilian, CivilianSpeechStore.Bubble bubble, EntityRenderDispatcher dispatcher,
                       Font font, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        Component text = Component.translatable(bubble.key(), bubble.args().toArray());
        int duration = duration(text.getString());
        float age = Math.max(0.0F, civilian.level().getGameTime() - bubble.startGameTime() + partialTick);
        if (age >= duration) {
            CivilianSpeechStore.remove(civilian.getId());
            return;
        }
        if (civilian.isInvisible() || dispatcher.distanceToSqr(civilian) > BUBBLE_RENDER_DIST * BUBBLE_RENDER_DIST) {
            return;
        }

        List<FormattedCharSequence> lines = font.split(text, BUBBLE_WRAP);
        if (lines.size() > MAX_LINES) {
            lines = lines.subList(0, MAX_LINES);
        }
        int textWidth = 0;
        for (FormattedCharSequence line : lines) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        float width = textWidth + 2 * PADDING;
        float height = lines.size() * font.lineHeight + 2 * PADDING;
        float left = -width / 2.0F;
        float right = width / 2.0F;
        float top = -height;

        float fade = Mth.clamp(Math.min(age / FADE_IN, (duration - age) / FADE_OUT), 0.0F, 1.0F);
        float remaining = (duration - age) / duration;

        poseStack.pushPose();
        poseStack.translate(0.0, civilian.getBbHeight() + ANCHOR_ABOVE_HEAD, 0.0);
        poseStack.mulPose(dispatcher.cameraOrientation());
        poseStack.scale(SCALE, -SCALE, SCALE);
        Matrix4f matrix = poseStack.last().pose();

        VertexConsumer quads = buffers.getBuffer(RenderType.textBackground());
        quad(quads, matrix, left, top, right, 0.0F, Z_BACKGROUND, withAlpha(BUBBLE_BG, fade), light);
        quad(quads, matrix, left, -BAR_HEIGHT, left + width * remaining, 0.0F, Z_BAR, withAlpha(BUBBLE_BAR, fade), light);

        int textColor = (Math.max(MIN_TEXT_ALPHA, Math.round(255 * fade)) << 24) | 0xFFFFFF;
        float y = top + PADDING;
        for (FormattedCharSequence line : lines) {
            font.drawInBatch(line, -font.width(line) / 2.0F, y, textColor, false, matrix, buffers,
                    Font.DisplayMode.NORMAL, 0, light);
            y += font.lineHeight;
        }
        poseStack.popPose();
    }

    private static int withAlpha(int argb, float fade) {
        int alpha = Math.round((argb >>> 24) * fade);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    /** A flat quad, wound like the font's own background quads. */
    private static void quad(VertexConsumer buffer, Matrix4f matrix, float x0, float y0, float x1, float y1, float z,
                             int argb, int light) {
        buffer.addVertex(matrix, x0, y1, z).setColor(argb).setLight(light);
        buffer.addVertex(matrix, x1, y1, z).setColor(argb).setLight(light);
        buffer.addVertex(matrix, x1, y0, z).setColor(argb).setLight(light);
        buffer.addVertex(matrix, x0, y0, z).setColor(argb).setLight(light);
    }

    private CivilianBubbleRenderer() {
    }
}
