// Written by Claude (GreedyFluids M7d, the debug view; Bright, 2026-11-15); edited by Claude (M8b b4: secondaries).
package dev.brights0ng.meteodyne.airflow.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.brights0ng.meteodyne.airflow.AirflowView;
import dev.brights0ng.meteodyne.heat.world.client.HeatViewClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws airflow's debug view ({@code /meteodyne airflow show}; {@link AirflowView}), from the server's latest update
 * (once a second) until the view is turned off:
 * <ul>
 *   <li><b>Tiles:</b> each tile's outline, coloured by state: green running, cyan running beside a player's tile (a
 *       secondary, M8b b4), grey running with no player in it (its grace period), yellow reading its sections or
 *       building, red failed.</li>
 *   <li><b>Air:</b> the legacy heat view's cubes ({@link HeatViewClient#colour}: pale near the background, yellow to
 *       magenta warmer, blue colder) at each cell sent, and a white arrow along the air's velocity there, 0.4 blocks
 *       at 1 m/s or more, shorter below, none under 0.02 m/s.</li>
 * </ul>
 */
public final class AirflowViewClient {

    private static final float ARROW = 0.4f;
    private static final float FULL_SPEED = 1f;
    private static final float CALM = 0.02f;

    /** A tile on show: its latest frame's parts. */
    private record Tile(int frame, List<AirflowView.Payload> parts) {
    }

    private static final Map<Integer, Tile> TILES = new ConcurrentHashMap<>();

    /** A message from the server. */
    public static void accept(AirflowView.Payload p) {
        switch (p.kind()) {
            case AirflowView.Payload.OFF -> TILES.clear();
            case AirflowView.Payload.END -> TILES.values().removeIf(t -> t.frame() != p.frame());
            default -> TILES.compute(p.id(), (id, old) -> {
                Tile t = old != null && old.frame() == p.frame() ? old
                        : new Tile(p.frame(), java.util.Collections.synchronizedList(new ArrayList<>()));
                t.parts().add(p);
                return t;
            });
        }
    }

    /** Forgets everything shown (logging out). */
    public static void clear() {
        TILES.clear();
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || TILES.isEmpty()
                || Minecraft.getInstance().level == null) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        PoseStack.Pose pose = new PoseStack().last();
        List<AirflowView.Payload> parts = new ArrayList<>();
        for (Tile t : TILES.values()) {
            synchronized (t.parts()) {
                parts.addAll(t.parts());
            }
        }

        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        for (AirflowView.Payload p : parts) {
            for (int i = 0; i < p.count(); i++) {
                int rgb = HeatViewClient.colour(p.offset(i));
                HeatViewClient.cube(quads, pose, (float) (p.ox() + p.dx(i) + 0.5 - cam.x),
                        (float) (p.oy() + p.dy(i) + 0.5 - cam.y), (float) (p.oz() + p.dz(i) + 0.5 - cam.z),
                        (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            }
        }
        buffers.endBatch(RenderType.debugQuads());

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (AirflowView.Payload p : parts) {
            for (int i = 0; i < p.count(); i++) {
                float u = p.velocity(i, 0), v = p.velocity(i, 1), w = p.velocity(i, 2);
                float speed = (float) Math.sqrt(u * u + v * v + w * w);
                if (speed < CALM) {
                    continue;
                }
                float length = ARROW * Math.min(1, speed / FULL_SPEED) / speed;
                float x = (float) (p.ox() + p.dx(i) + 0.5 - cam.x), y = (float) (p.oy() + p.dy(i) + 0.5 - cam.y);
                float z = (float) (p.oz() + p.dz(i) + 0.5 - cam.z);
                HeatViewClient.line(lines, pose, x, y, z, x + u * length, y + v * length, z + w * length);
            }
        }
        java.util.Set<Integer> outlined = new java.util.HashSet<>();
        for (AirflowView.Payload p : parts) {
            if (outlined.add(p.id())) {
                outline(lines, pose, p, cam);
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    private static void outline(VertexConsumer lines, PoseStack.Pose pose, AirflowView.Payload p, Vec3 cam) {
        int state = p.state() & ~(AirflowView.EMPTY | AirflowView.SECONDARY);
        boolean empty = (p.state() & AirflowView.EMPTY) != 0;
        boolean secondary = (p.state() & AirflowView.SECONDARY) != 0;
        int rgb = switch (state) {
            case AirflowView.RUNNING -> empty ? 0x9A9A9A : secondary ? 0x40C8E8 : 0x40E040;
            case AirflowView.FAILED -> 0xFF3030;
            default -> 0xFFD020;
        };
        float x0 = (float) (p.ox() - cam.x), y0 = (float) (p.oy() - cam.y), z0 = (float) (p.oz() - cam.z);
        float x1 = x0 + p.sx(), y1 = y0 + p.sy(), z1 = z0 + p.sz();
        float[][] edges = {
                {x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
        for (float[] e : edges) {
            float dx = e[3] - e[0], dy = e[4] - e[1], dz = e[5] - e[2];
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            lines.addVertex(pose, e[0], e[1], e[2]).setColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255)
                    .setNormal(pose, dx / len, dy / len, dz / len);
            lines.addVertex(pose, e[3], e[4], e[5]).setColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255)
                    .setNormal(pose, dx / len, dy / len, dz / len);
        }
    }

    private AirflowViewClient() {
    }
}
