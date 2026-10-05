package dev.brights0ng.enginesandempires.weather.cloud.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

/**
 * Debug view: each cloud's outline as Project Atmosphere sends it. A ring at the base and one at the top, four posts
 * between them, and an arrow from the centre to where the cloud will be in 10 seconds. Coloured by storm tier: white
 * clear or cloudy, blue rain, yellow thunder, red severe or cyclone.
 *
 * <p>Toggled with {@code /eae clouds outlines} or the {@code debug.outlines} client config.
 */
public final class CloudOutlines {

    private static final int SEGMENTS = 48;

    public static void render(Vec3 camera, double time) {
        if (!CloudConfig.outlines() || CloudTracker.clouds().isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack.Pose pose = new PoseStack().last();
        for (CloudShape c : CloudTracker.clouds()) {
            int[] rgb = colour(c.stormTier());
            float x = (float) (CloudTracker.x(c, time) - camera.x);
            float z = (float) (CloudTracker.z(c, time) - camera.z);
            float base = (float) (c.baseY() - camera.y);
            float top = (float) (c.topY() - camera.y);
            float r = c.radius();
            ring(lines, pose, x, base, z, r, rgb);
            ring(lines, pose, x, top, z, r, rgb);
            for (int i = 0; i < 4; i++) {
                double a = i * Math.PI / 2;
                float px = x + (float) Math.cos(a) * r;
                float pz = z + (float) Math.sin(a) * r;
                line(lines, pose, px, base, pz, px, top, pz, rgb);
            }
            float mid = (base + top) / 2;
            line(lines, pose, x, mid, z, x + (float) (c.vx() * 200), mid, z + (float) (c.vz() * 200), rgb);
        }
        buffers.endBatch(RenderType.lines());
    }

    private static void ring(VertexConsumer lines, PoseStack.Pose pose, float cx, float y, float cz, float r, int[] rgb) {
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = i * 2 * Math.PI / SEGMENTS;
            double a1 = (i + 1) * 2 * Math.PI / SEGMENTS;
            line(lines, pose, cx + (float) Math.cos(a0) * r, y, cz + (float) Math.sin(a0) * r,
                    cx + (float) Math.cos(a1) * r, y, cz + (float) Math.sin(a1) * r, rgb);
        }
    }

    private static void line(VertexConsumer lines, PoseStack.Pose pose,
                             float x0, float y0, float z0, float x1, float y1, float z1, int[] rgb) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) {
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        lines.addVertex(pose, x0, y0, z0).setColor(rgb[0], rgb[1], rgb[2], 255).setNormal(pose, dx, dy, dz);
        lines.addVertex(pose, x1, y1, z1).setColor(rgb[0], rgb[1], rgb[2], 255).setNormal(pose, dx, dy, dz);
    }

    private static int[] colour(String tier) {
        return switch (tier) {
            case "RAIN_CORE" -> new int[]{80, 150, 255};
            case "THUNDER_CORE" -> new int[]{255, 220, 60};
            case "SEVERE_CORE", "CYCLONE_CORE" -> new int[]{255, 60, 60};
            default -> new int[]{255, 255, 255};
        };
    }

    private CloudOutlines() {
    }
}
