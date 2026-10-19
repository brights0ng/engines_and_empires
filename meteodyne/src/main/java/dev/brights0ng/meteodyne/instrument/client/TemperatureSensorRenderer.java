// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.instrument.SensorShape;
import dev.brights0ng.meteodyne.instrument.TemperatureSensorBlock;
import dev.brights0ng.meteodyne.instrument.TemperatureSensorBlockEntity;
import dev.brights0ng.meteodyne.instrument.ThermometerItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** The liquid column is independent of the baked housing and of the sensor's redstone calibration. */
public final class TemperatureSensorRenderer implements BlockEntityRenderer<TemperatureSensorBlockEntity> {
    private static final ResourceLocation WHITE = Meteodyne.id("textures/block/instrument_white.png");
    public TemperatureSensorRenderer(BlockEntityRendererProvider.Context context) { }

    @Override
    public void render(TemperatureSensorBlockEntity sensor, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int light, int overlay) {
        SensorShape.Box head = SensorShape.head(sensor.getBlockState().getValue(TemperatureSensorBlock.FACE));
        poses.pushPose();
        poses.translate(0.5, 0, 0.5);
        poses.mulPose(Axis.YP.rotationDegrees(180 - sensor.getBlockState().getValue(TemperatureSensorBlock.FACING).toYRot()));
        poses.translate(-0.5, 0, -0.5);
        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        double reading = sensor.visualReading(partialTick);
        if (Double.isFinite(reading)) {
            double top = SensorShape.TUBE_BOTTOM - ThermometerItem.DIAL.fraction(reading) * (SensorShape.TUBE_BOTTOM - SensorShape.TUBE_TOP);
            rectangle(out, poses, head, SensorShape.TUBE_LEFT, top, SensorShape.TUBE_RIGHT, SensorShape.TUBE_BOTTOM, 0xFFBD3B35, light, overlay);
            rectangle(out, poses, head, SensorShape.BULB_LEFT, SensorShape.BULB_TOP, SensorShape.BULB_RIGHT, SensorShape.BULB_BOTTOM, 0xFFBD3B35, light, overlay);
        } else {
            rectangle(out, poses, head, 12, 15, 20, 17, 0xFF777E82, light, overlay);
        }
        poses.popPose();
    }

    private static void rectangle(VertexConsumer out, PoseStack poses, SensorShape.Box head, double left, double top,
                                  double right, double bottom, int color, int light, int overlay) {
        float x0 = SensorShape.faceX(head, left), x1 = SensorShape.faceX(head, right);
        float y0 = SensorShape.faceY(head, bottom), y1 = SensorShape.faceY(head, top);
        float z = (float) ((head.z0() - 0.02) / 16);
        PoseStack.Pose pose = poses.last();
        vertex(out, pose, x0, y0, z, color, light, overlay, 0, 1);
        vertex(out, pose, x1, y0, z, color, light, overlay, 1, 1);
        vertex(out, pose, x1, y1, z, color, light, overlay, 1, 0);
        vertex(out, pose, x0, y1, z, color, light, overlay, 0, 0);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z,
                               int color, int light, int overlay, float u, float v) {
        out.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, -1);
    }
}
