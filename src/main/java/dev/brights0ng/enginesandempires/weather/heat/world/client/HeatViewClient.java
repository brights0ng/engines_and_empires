package dev.brights0ng.enginesandempires.weather.heat.world.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.brights0ng.enginesandempires.weather.heat.world.HeatView;
import dev.brights0ng.paradynelib.ships.ShipCover;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3d;

/**
 * Draws the heat simulation's debug view (weather phase 8f-4; {@code /eae heat show}): a small cube, 2 pixels across,
 * at the centre of every air cell of every region the server sends (all within 128 blocks), coloured by how much
 * warmer or colder than the outside air it is, and a short arrow along that region's wind, as long as the share of the
 * wind the cell feels (none where it is fully sheltered; the ships' shelter measure, phase 8f-4).
 * Drawn every frame from the server's latest update (once a second) until the view is turned off.
 *
 * <p>Colours: pale near 0; warming through yellow (+5), orange (+15), red (+30) to magenta (+45 and more); cooling
 * from pale blue to deep blue (-20 and less).
 *
 * <p>Ships (8f-5): a region in a ship's plot is drawn where the ship is, through its pose as rendered this frame
 * (the wind arrows too, which come in the ship's frame).
 */
public final class HeatViewClient {

    /** Half the cube's size: 1 pixel. */
    private static final float HALF = 1 / 16f;
    /** The arrow's length in full wind, blocks. */
    private static final float ARROW = 0.35f;
    /** Wind below this, m/s, gets no arrow. */
    private static final float CALM = 0.1f;
    /** A share of the wind below this gets no arrow. */
    private static final float SHELTERED = 0.05f;

    /** The regions on show, by their origin. */
    private static final java.util.Map<Long, HeatView.Payload> REGIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A new update from the server (network thread). */
    public static void accept(HeatView.Payload payload) {
        if (payload.count() == HeatView.Payload.OFF_COUNT) {
            REGIONS.clear();
        } else if (payload.count() == HeatView.Payload.END_COUNT) {
            REGIONS.values().removeIf(r -> r.frame() != payload.frame());
        } else {
            REGIONS.put(net.minecraft.core.BlockPos.asLong(payload.ox(), payload.oy(), payload.oz()), payload);
        }
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || REGIONS.isEmpty()
                || Minecraft.getInstance().level == null) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        PoseStack.Pose pose = new PoseStack().last();
        for (HeatView.Payload p : REGIONS.values()) {
            if (p.count() > 0) {
                draw(p, cam, buffers, pose);
            }
        }
    }

    private static void draw(HeatView.Payload p, Vec3 cam, MultiBufferSource.BufferSource buffers, PoseStack.Pose pose) {
        byte[] cells = p.cells();
        Pose3dc ship = null;
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (ShipCover.inPlot(level, new net.minecraft.world.level.ChunkPos(p.ox() >> 4, p.oz() >> 4))) {
            SubLevel s = Sable.HELPER.getContaining(level, p.ox() >> 4, p.oz() >> 4);
            if (s == null) {
                return;
            }
            ship = s instanceof ClientSubLevel c ? c.renderPose() : s.logicalPose();
        }
        Vector3d at = new Vector3d();

        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        for (int i = 0; i < p.count(); i++) {
            int b = i * 6;
            centre(p, cells, b, ship, at);
            float x = (float) (at.x - cam.x);
            float y = (float) (at.y - cam.y);
            float z = (float) (at.z - cam.z);
            int rgb = colour(p.offset(i));
            cube(quads, pose, x, y, z, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        }
        buffers.endBatch(RenderType.debugQuads());

        float speed = (float) Math.hypot(p.windX(), p.windZ());
        if (speed < CALM) {
            return;
        }
        Vector3d dir = new Vector3d(p.windX() / speed, 0, p.windZ() / speed);
        if (ship != null) {
            ship.transformNormal(dir);
        }
        float ux = (float) dir.x;
        float uy = (float) dir.y;
        float uz = (float) dir.z;
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (int i = 0; i < p.count(); i++) {
            int b = i * 6;
            float share = p.windShare(i);
            if (share < SHELTERED) {
                continue;
            }
            centre(p, cells, b, ship, at);
            float x = (float) (at.x - cam.x);
            float y = (float) (at.y - cam.y);
            float z = (float) (at.z - cam.z);
            float length = ARROW * share;
            float tx = x + ux * length;
            float ty = y + uy * length;
            float tz = z + uz * length;
            line(lines, pose, x, y, z, tx, ty, tz);
            // The head: two short strokes back from the tip, 30 degrees either side
            float back = length * 0.35f;
            float cos = (float) Math.cos(Math.toRadians(150));
            float sin = (float) Math.sin(Math.toRadians(150));
            line(lines, pose, tx, ty, tz, tx + (ux * cos - uz * sin) * back, ty + uy * cos * back,
                    tz + (ux * sin + uz * cos) * back);
            line(lines, pose, tx, ty, tz, tx + (ux * cos + uz * sin) * back, ty + uy * cos * back,
                    tz + (-ux * sin + uz * cos) * back);
        }
        buffers.endBatch(RenderType.lines());
    }

    /** The middle of cell {@code b} in the world: through the ship's pose if it is a ship's. */
    private static void centre(HeatView.Payload p, byte[] cells, int b, Pose3dc ship, Vector3d out) {
        out.set(p.ox() + (cells[b] & 0xFF) + 0.5, p.oy() + (cells[b + 1] & 0xFF) + 0.5, p.oz() + (cells[b + 2] & 0xFF) + 0.5);
        if (ship != null) {
            ship.transformPosition(out);
        }
    }

    /** The colour of an offset, 0xRRGGBB. */
    static int colour(float offset) {
        if (offset >= 0) {
            float[][] stops = {{0, 255, 250, 220}, {5, 255, 225, 0}, {15, 255, 140, 0}, {30, 225, 30, 30}, {45, 200, 0, 200}};
            return ramp(stops, offset);
        }
        float[][] stops = {{0, 205, 230, 255}, {5, 110, 170, 255}, {20, 20, 50, 230}};
        return ramp(stops, -offset);
    }

    private static int ramp(float[][] stops, float v) {
        if (v <= stops[0][0]) {
            return pack(stops[0]);
        }
        for (int i = 1; i < stops.length; i++) {
            if (v <= stops[i][0]) {
                float t = (v - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]);
                return pack(new float[]{0,
                        stops[i - 1][1] + (stops[i][1] - stops[i - 1][1]) * t,
                        stops[i - 1][2] + (stops[i][2] - stops[i - 1][2]) * t,
                        stops[i - 1][3] + (stops[i][3] - stops[i - 1][3]) * t});
            }
        }
        return pack(stops[stops.length - 1]);
    }

    private static int pack(float[] s) {
        return (Math.round(s[1]) << 16) | (Math.round(s[2]) << 8) | Math.round(s[3]);
    }

    private static void cube(VertexConsumer q, PoseStack.Pose pose, float x, float y, float z, int r, int g, int b) {
        float x0 = x - HALF, x1 = x + HALF, y0 = y - HALF, y1 = y + HALF, z0 = z - HALF, z1 = z + HALF;
        quad(q, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, r, g, b);
        quad(q, pose, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, r, g, b);
        quad(q, pose, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, r, g, b);
        quad(q, pose, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r, g, b);
        quad(q, pose, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b);
        quad(q, pose, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, r, g, b);
    }

    private static void quad(VertexConsumer q, PoseStack.Pose pose,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int r, int g, int b) {
        q.addVertex(pose, ax, ay, az).setColor(r, g, b, 255);
        q.addVertex(pose, bx, by, bz).setColor(r, g, b, 255);
        q.addVertex(pose, cx, cy, cz).setColor(r, g, b, 255);
        q.addVertex(pose, dx, dy, dz).setColor(r, g, b, 255);
    }

    private static void line(VertexConsumer lines, PoseStack.Pose pose,
                             float x0, float y0, float z0, float x1, float y1, float z1) {
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
        lines.addVertex(pose, x0, y0, z0).setColor(240, 240, 240, 255).setNormal(pose, dx, dy, dz);
        lines.addVertex(pose, x1, y1, z1).setColor(240, 240, 240, 255).setNormal(pose, dx, dy, dz);
    }

    private HeatViewClient() {
    }
}
