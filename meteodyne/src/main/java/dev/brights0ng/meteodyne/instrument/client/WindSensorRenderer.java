// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.instrument.*;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** A downwind arrow on the dial and actual open cup geometry rotating with measured wind speed. */
public final class WindSensorRenderer implements BlockEntityRenderer<WindSensorBlockEntity> {
    public WindSensorRenderer(BlockEntityRendererProvider.Context context) { }
    @Override public void render(WindSensorBlockEntity sensor, float partial, PoseStack poses,
            MultiBufferSource buffers, int light, int overlay) {
        var face = sensor.getBlockState().getValue(SensorBlock.FACE);
        poses.pushPose();
        poses.translate(0.5, 0, 0.5);
        poses.mulPose(Axis.YP.rotationDegrees(180 - sensor.getBlockState().getValue(SensorBlock.FACING).toYRot()));
        poses.translate(-0.5, 0, -0.5);
        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(Meteodyne.id("textures/block/instrument_white.png")));
        var head = WindSensorShape.head(face);
        double angle = sensor.angle(partial);
        if (Double.isFinite(angle)) {
            double a = Math.toRadians(angle), sin = Math.sin(a), cos = Math.cos(a);
            double[][] points = {{0,-2.3},{-1,0.2},{0,-0.3},{1,0.2}};
            float[][] vertices = new float[4][3];
            for (int i = 0; i < 4; i++) {
                double x = points[i][0] * cos - points[i][1] * sin;
                double y = points[i][0] * sin + points[i][1] * cos;
                vertices[i] = new float[]{(float)((head.x1() - 3 - x) / 16),
                        (float)((head.y1() - 3 - y) / 16), (float)((head.z0() - 0.02) / 16)};
            }
            quad(out, poses.last(), vertices, 0xFFBD3B35, light, overlay, 0, 0, -1);
        } else {
            box(out, poses.last(), new SensorShape.Box(7, head.y0() + 2.7, head.z0() - 0.03,
                    9, head.y0() + 3.3, head.z0() - 0.01), 0xFF777E82, light, overlay);
        }
        poses.translate(0.5, 0, 0.5);
        poses.mulPose(Axis.YP.rotationDegrees((float) sensor.rotor(partial)));
        poses.translate(-0.5, 0, -0.5);
        for (var box : WindSensorShape.rotorBoxes(face)) { box(out, poses.last(), box, 0xFFAD8848, light, overlay); }
        poses.popPose();
    }
    private static void box(VertexConsumer out, PoseStack.Pose pose, SensorShape.Box b, int color, int light, int overlay) {
        float x0=(float)b.x0()/16, x1=(float)b.x1()/16, y0=(float)b.y0()/16, y1=(float)b.y1()/16, z0=(float)b.z0()/16, z1=(float)b.z1()/16;
        quad(out,pose,new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0}},color,light,overlay,0,0,-1);
        quad(out,pose,new float[][]{{x1,y0,z1},{x0,y0,z1},{x0,y1,z1},{x1,y1,z1}},color,light,overlay,0,0,1);
        quad(out,pose,new float[][]{{x0,y0,z1},{x0,y0,z0},{x0,y1,z0},{x0,y1,z1}},color,light,overlay,-1,0,0);
        quad(out,pose,new float[][]{{x1,y0,z0},{x1,y0,z1},{x1,y1,z1},{x1,y1,z0}},color,light,overlay,1,0,0);
        quad(out,pose,new float[][]{{x0,y1,z0},{x1,y1,z0},{x1,y1,z1},{x0,y1,z1}},0xFFD4B570,light,overlay,0,1,0);
        quad(out,pose,new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y0,z0},{x0,y0,z0}},color,light,overlay,0,-1,0);
    }
    private static void quad(VertexConsumer out, PoseStack.Pose pose, float[][] vertices, int color, int light, int overlay,
            float nx, float ny, float nz) {
        for (int i=0; i<4; i++) {
            float[] v=vertices[i];
            out.addVertex(pose,v[0],v[1],v[2]).setColor(color).setUv(i==1||i==2?1:0,i<2?1:0)
                    .setOverlay(overlay).setLight(light).setNormal(pose,nx,ny,nz);
        }
    }
}
