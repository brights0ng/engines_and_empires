// Edited by Gemini: added ORES/OIL/FRONTIER/EMPIRE tab rendering, multi-chunk shrinking reservoir polygon fill with CCW winding and clipping, and hover priority; smooth miter outline, ear-clipped interior triangulation, and z-fighting/layering fixes for grid lines and deposits; switched to RenderType.textBackground() to preserve painter's order and depth testing; moved zoom level pips to bottom left to prevent tab overlap; ensured frontier and empire boundary lines render on top of grid lines.
package dev.brights0ng.seismicsurveyance.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import dev.brights0ng.seismicsurveyance.LogbookEntry;
import dev.brights0ng.seismicsurveyance.LoggerDisplay;
import dev.brights0ng.seismicsurveyance.OilTiers;
import dev.brights0ng.seismicsurveyance.PrdItem;
import dev.brights0ng.seismicsurveyance.ReaderReading;
import dev.brights0ng.seismicsurveyance.ReadingOres;
import dev.brights0ng.seismicsurveyance.SmartLoggerBlock;
import dev.brights0ng.seismicsurveyance.SmartLoggerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the smart logger's top (master block only): the two buttons on the bar along the right-hand edge, with the little
 * light in front of each; the dock on the back half of the bar, with the portable record display lying in it if one is docked (its
 * screen off); and, while the display is on, the map.
 *
 * <h2>The map</h2>
 * The screen sits one pixel below the rim around it, and the map is drawn straight onto its glass. It shows:
 * <ul>
 *   <li>The tier overlay: tinted chunk columns under the grid, from the registered map layers (see {@link MapLayers}).</li>
 *   <li>A faint grid, showing blocks: power-of-two squares, sized to the zoom level.</li>
 *   <li>North: a small red marker at the edge of the screen, due north of the logger.</li>
 *   <li>The readings: each a coloured dot (see {@link ReadingOres}), plus a ring for the one selected by this player (amber)
 *       or hovered by their crosshair (white). Far enough in, a blurred square of the same colour spreads out to the reading's
 *       worst-case position (see {@link LoggerDisplay#blurBlocks}).</li>
 *   <li>The logger itself: a small cross at its place in the world.</li>
 *   <li>The zoom level: small pips along the front edge of the screen (bottom left), one lit per level.</li>
 *   <li>The tabs: clickable ORES and OIL tabs along the top edge of the map.</li>
 *   <li>The legend: a swatch and a name for each of the registered layers' rows, in the top right corner.</li>
 * </ul>
 *
 * <p>All lines and boxes are drawn in code, without textures, and unlit so the screen and buttons glow at their own brightness.
 *
 * <p>Only ever loaded on a client.
 */
public class SmartLoggerRenderer implements BlockEntityRenderer<SmartLoggerBlockEntity> {

    /** A block-model pixel, in metres of the top. */
    private static final double PIXEL = 1.0 / 16;

    /** A tiny lift between things drawn on top of each other, so they never z-fight. */
    private static final float LAYER = 0.0006F;

    /** Scale of the docked portable record display. */
    private static final float DOCKED_SCALE = 1.0F;

    // The colours of the screen, fully bright.
    private static final int GRID = 0x306C7870;
    private static final int GRID_ORIGIN = 0x608FA89A;

    public SmartLoggerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SmartLoggerBlockEntity be, float partialTick, PoseStack ms, MultiBufferSource buffer, int packedLight,
                       int packedOverlay) {
        Level level = be.getLevel();
        if (level == null) {
            return;
        }
        // Only the master quarter (the front left) draws anything: the whole top is rendered from there.
        if (!be.isMaster()) {
            return;
        }
        Layout layout = new Layout(be);
        renderButtons(be, layout, ms, buffer, level);
        VertexConsumer barGlow = buffer.getBuffer(RenderType.textBackground());
        int barLight = LevelRenderer.getLightColor(level, be.getBlockPos().relative(layout.right()).above());
        renderIndicators(be, layout, ms.last(), barGlow, LevelRenderer.getLightColor(level, be.getBlockPos().above()));
        renderDock(be, layout, ms, buffer, barGlow, barLight, level);
        if (be.displayOn()) {
            renderMap(be, layout, ms.last(), level, buffer);
        }
    }

    /** Where the desk's parts are, relative to the master block. */
    private record Layout(LoggerDisplay.Frame frame, double middleX, double middleZ, Direction right) {

        Layout(SmartLoggerBlockEntity be) {
            this(be.frame(), 0.5 + 0.5 * (be.frame().rightX() + be.frame().forwardX()),
                    0.5 + 0.5 * (be.frame().rightZ() + be.frame().forwardZ()), SmartLoggerBlock.right(be.facing()));
        }

        /** A point {u, v} on the top, relative to the master block, as {x, z}. */
        double[] at(double u, double v) {
            return frame.world(middleX, middleZ, u, v);
        }
    }

    // ---- the buttons ----

    private static void renderButtons(SmartLoggerBlockEntity be, Layout layout, PoseStack ms, MultiBufferSource buffer,
                                      Level level) {
        ModelBlockRenderer renderer = Minecraft.getInstance().getBlockRenderer().getModelRenderer();
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        int light = LevelRenderer.getLightColor(level, be.getBlockPos().relative(layout.right()).above());
        // The button model is six across along X: turn X to point along the bar's width, to the right.
        Quaternionf turn = new Quaternionf().rotationY((float) Math.atan2(-layout.right().getStepZ(), layout.right().getStepX()));
        long now = level.getGameTime();
        for (LoggerDisplay.Button button : LoggerDisplay.Button.values()) {
            double[] p = layout.at(LoggerDisplay.Button.centreU(), button.centreV());
            double sink = be.buttonDown(button, now) ? LoggerDisplay.BUTTON_HEIGHT - LoggerDisplay.BUTTON_PRESSED_HEIGHT : 0;
            ms.pushPose();
            ms.translate(p[0], LoggerDisplay.BAR_TOP - sink, p[1]);
            ms.mulPose(turn);
            ms.translate(-0.5, 0, -0.5);
            renderer.renderModel(ms.last(), consumer, null, ThumperClient.LOGGER_BUTTON.get(), 1.0F, 1.0F, 1.0F, light,
                    OverlayTexture.NO_OVERLAY);
            ms.popPose();
        }
    }

    /** A little light in front of each button: what it is for, and for the power button whether the display is on. */
    private static void renderIndicators(SmartLoggerBlockEntity be, Layout layout, PoseStack.Pose pose, VertexConsumer glow,
                                         int blockLight) {
        float y = (float) LoggerDisplay.BAR_TOP + 0.002F;
        for (LoggerDisplay.Button button : LoggerDisplay.Button.values()) {
            double u = LoggerDisplay.Button.centreU();
            double v = button.centreV() - LoggerDisplay.BUTTON_ALONG / 2 - 1.5 * PIXEL;
            int colour;
            boolean lit;
            switch (button) {
                case POWER -> {
                    lit = be.displayOn();
                    colour = lit ? 0xFF5CFF7A : 0xFF4A1A16;
                }
                default -> {
                    lit = true;
                    colour = 0xFFF2B742;
                }
            }
            double[] a = layout.at(u - 2 * PIXEL, v - 0.5 * PIXEL);
            double[] b = layout.at(u + 2 * PIXEL, v + 0.5 * PIXEL);
            int light = lit ? LightTexture.FULL_BRIGHT : blockLight;
            quad(pose, glow, a[0], a[1], b[0], b[1], y, colour, light);
        }
    }

    // ---- the dock ----

    private static void renderDock(SmartLoggerBlockEntity be, Layout layout, PoseStack ms, MultiBufferSource buffer,
                                   VertexConsumer glow, int barLight, Level level) {
        ItemStack docked = be.docked();
        double u = LoggerDisplay.Button.centreU();
        double v = LoggerDisplay.DOCK_CENTRE_V;
        double halfAlong = (LoggerDisplay.DEPTH - LoggerDisplay.DOCK_FROM_V) / 2 - 0.5 * PIXEL;
        double halfAcross = LoggerDisplay.BAR_WIDTH / 2 - PIXEL;
        double[] a = layout.at(u - halfAcross, v - halfAlong);
        double[] b = layout.at(u + halfAcross, v + halfAlong);
        float y = (float) LoggerDisplay.BAR_TOP + 0.002F;
        quad(ms.last(), glow, a[0], a[1], b[0], b[1], y, 0xFF1C1D21, barLight);

        if (docked.isEmpty() || !(docked.getItem() instanceof PrdItem)) {
            return;
        }

        LoggerDisplay.Frame frame = layout.frame();
        double[] centre = layout.at(u, v);
        ms.pushPose();
        int light = LevelRenderer.getLightColor(level, be.getBlockPos().relative(layout.right()).above());
        ms.translate(centre[0], y, centre[1]);
        // Turned so the display's top edge points away from the front, laid flat, face up, then turned a quarter in its own
        // plane so its long side runs along the bar.
        ms.mulPose(Axis.YP.rotation((float) Math.atan2(-frame.forwardX(), -frame.forwardZ()) - (float) Math.PI / 2));
        ms.mulPose(Axis.XP.rotationDegrees(0));
        ms.mulPose(Axis.ZP.rotationDegrees(90));
        ms.scale(DOCKED_SCALE, DOCKED_SCALE, DOCKED_SCALE);
        Minecraft.getInstance().getItemRenderer().renderStatic(docked, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                ms, buffer, level, 0);
        ms.popPose();
    }

    // ---- the map ----

    private static void renderMap(SmartLoggerBlockEntity be, Layout layout, PoseStack.Pose pose, Level level,
                                  MultiBufferSource buffer) {
        VertexConsumer glow = buffer.getBuffer(RenderType.textBackground());
        LoggerDisplay.View view = be.view();
        double scale = view.blocksPerMetre();
        double[] c1 = layout.at(LoggerDisplay.BEZEL, LoggerDisplay.BEZEL);
        double[] c2 = layout.at(LoggerDisplay.SCREEN_WIDTH - LoggerDisplay.BEZEL, LoggerDisplay.DEPTH - LoggerDisplay.BEZEL);
        Screen screen = new Screen(Math.min(c1[0], c2[0]), Math.min(c1[1], c2[1]), Math.max(c1[0], c2[0]), Math.max(c1[1], c2[1]));
        double[] centre = layout.at(LoggerDisplay.SCREEN_CENTRE_U, LoggerDisplay.SCREEN_CENTRE_V);
        Projection map = new Projection(centre[0], centre[1], view.centreX(), view.centreZ(), scale);
        float y = (float) LoggerDisplay.SCREEN_Y + 0.002F;
        int full = LightTexture.FULL_BRIGHT;
        List<MapLayer> layers = MapLayers.showing(level, be.activeTab());

        // The map's layer fills (the tier overlay), under everything else: asked for while someone is near enough to see them.
        for (MapLayer layer : layers) {
            Vec3 middle = be.middle();
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.distanceToSqr(middle) <= TIER_VIEW_DISTANCE * TIER_VIEW_DISTANCE) {
                layer.want(be.getBlockPos(), view.centreX(), view.centreZ(), 1.3 * scale);
            }
            renderLayerFills(screen, map, pose, glow, y, layer);
        }

        // 1. The readings for the current active tab (ORES or OIL): deposit fills and uncertainty blurs
        // are drawn first under the grid lines. Each entry receives a slight deterministic offset to eliminate
        // z-fighting between overlapping deposits.
        String dimension = level.dimension().location().toString();
        int hovered = SmartLoggerHover.hoveredIndex(be);
        int selected = SmartLoggerControls.selected(be);
        List<LogbookEntry> entries = be.currentRecords().entries();

        if (be.activeTab() == LoggerDisplay.Tab.OIL) {
            // Oil deposits cover multi-chunk shrinking reservoirs rendered as organic polygons.
            for (int i = 0; i < entries.size(); i++) {
                LogbookEntry entry = entries.get(i);
                ReaderReading reading = entry.reading();
                if (!reading.dimension().equals(dimension)) {
                    continue;
                }
                float entryOffset = (i % 32) * 0.02F * LAYER;
                float fillY = y + 1.2F * LAYER + entryOffset;

                if (!reading.ore().contains(":")) {
                    int cx = reading.deposit() != 0 ? (int) reading.deposit() : (reading.x() >> 4);
                    int cz = reading.deposit() != 0 ? (int) (reading.deposit() >>> 32) : (reading.z() >> 4);
                    double x1 = map.x(cx * 16.0);
                    double z1 = map.z(cz * 16.0);
                    double x2 = map.x((cx + 1) * 16.0);
                    double z2 = map.z((cz + 1) * 16.0);
                    int colour = ReadingOres.colour(reading.ore());
                    int fill = (colour & 0x00FFFFFF) | 0x4D000000;
                    screen.fill(pose, glow, x1, z1, x2, z2, fillY, fill);
                    continue;
                }
                double[][] poly = OilTiers.getPolygon(reading);
                if (poly.length < 3) {
                    continue;
                }

                double[][] screenPts = new double[poly.length][2];
                double minSX = Double.MAX_VALUE, maxSX = -Double.MAX_VALUE;
                double minSZ = Double.MAX_VALUE, maxSZ = -Double.MAX_VALUE;
                for (int p = 0; p < poly.length; p++) {
                    double sx = map.x(poly[p][0]);
                    double sz = map.z(poly[p][1]);
                    screenPts[p][0] = sx;
                    screenPts[p][1] = sz;
                    minSX = Math.min(minSX, sx);
                    maxSX = Math.max(maxSX, sx);
                    minSZ = Math.min(minSZ, sz);
                    maxSZ = Math.max(maxSZ, sz);
                }

                if (maxSX < screen.x1 || minSX > screen.x2 || maxSZ < screen.z1 || minSZ > screen.z2) {
                    continue;
                }

                int colour = ReadingOres.colour(reading.ore());
                int fill = (colour & 0x00FFFFFF) | 0x4D000000;
                int fullLight = LightTexture.FULL_BRIGHT;

                // Fill: Triangulate into interior triangles using ear clipping to prevent leaking out of concave regions
                List<double[][]> triangles = MapPolygonRenderer.triangulate(screenPts);
                for (double[][] tri : triangles) {
                    if (Math.max(tri[0][0], Math.max(tri[1][0], tri[2][0])) < screen.x1
                            || Math.min(tri[0][0], Math.min(tri[1][0], tri[2][0])) > screen.x2
                            || Math.max(tri[0][1], Math.max(tri[1][1], tri[2][1])) < screen.z1
                            || Math.min(tri[0][1], Math.min(tri[1][1], tri[2][1])) > screen.z2) {
                        continue;
                    }
                    renderClippedConvexPoly(screen, pose, glow, List.of(tri[0], tri[1], tri[2]), fillY, fill, fullLight);
                }
            }
        } else if (be.activeTab() == LoggerDisplay.Tab.ORES) {
            for (int i = 0; i < entries.size(); i++) {
                ReaderReading reading = entries.get(i).reading();
                if (!reading.dimension().equals(dimension)) {
                    continue;
                }
                double x = map.x(reading.x() + 0.5);
                double z = map.z(reading.z() + 0.5);
                double blur = LoggerDisplay.blurBlocks(reading.confidence()) / scale;
                if (blur <= LoggerDisplay.MARKER_SIZE) {
                    continue;
                }
                float entryOffset = (i % 32) * 0.02F * LAYER;
                float blurY = y + 1.2F * LAYER + entryOffset;
                screen.fill(pose, glow, x - blur, z - blur, x + blur, z + blur, blurY,
                        (ReadingOres.colour(reading.ore()) & 0x00FFFFFF) | 0x1C000000);
            }
        }

        // 2. The grid lines: drawn over deposit fills and blurs, with horizontal, vertical, and origin lines
        // at distinct depths so they never z-fight at intersections.
        int spacing = LoggerDisplay.gridSpacing(view);
        double half = 0.2 * PIXEL;
        double northmost = map.worldZ(screen.z1);
        double southmost = map.worldZ(screen.z2);
        for (long gz = (long) Math.ceil(northmost / spacing) * spacing, n = 0; gz <= southmost && n < 96; gz += spacing, n++) {
            double z = map.z(gz);
            float gridY = gz == 0 ? y + 2.4F * LAYER : y + 2.2F * LAYER;
            quad(pose, glow, screen.x1, z - half, screen.x2, z + half, gridY, gz == 0 ? GRID_ORIGIN : GRID, full);
        }
        double westmost = map.worldX(screen.x1);
        double eastmost = map.worldX(screen.x2);
        for (long gx = (long) Math.ceil(westmost / spacing) * spacing, n = 0; gx <= eastmost && n < 96; gx += spacing, n++) {
            double x = map.x(gx);
            float gridY = gx == 0 ? y + 2.45F * LAYER : y + 2.3F * LAYER;
            quad(pose, glow, x - half, screen.z1, x + half, screen.z2, gridY, gx == 0 ? GRID_ORIGIN : GRID, full);
        }

        // 3. Boundary lines (frontier & empire chunk outlines): drawn cleanly over the grid lines.
        for (MapLayer layer : layers) {
            renderLayerBorders(screen, map, pose, glow, y, layer);
        }

        // 4. Deposit outlines, center dots, and rings: drawn cleanly over the grid lines and boundary lines.
        if (be.activeTab() == LoggerDisplay.Tab.OIL) {
            for (int i = 0; i < entries.size(); i++) {
                LogbookEntry entry = entries.get(i);
                ReaderReading reading = entry.reading();
                if (!reading.dimension().equals(dimension)) {
                    continue;
                }
                int colour = ReadingOres.colour(reading.ore());
                int border = (colour & 0x00FFFFFF) | 0xC0000000;
                float entryOffset = (i % 32) * 0.01F * LAYER;
                float borderY = y + 2.8F * LAYER + entryOffset;
                double edge = 0.4 * PIXEL;
                if (i == hovered) {
                    border = 0xFFFFFFFF;
                    borderY = y + 4.5F * LAYER;
                    edge = 0.6 * PIXEL;
                } else if (entry.number() == selected) {
                    border = 0xFFF2B742;
                    borderY = y + 4.0F * LAYER;
                    edge = 0.6 * PIXEL;
                }

                if (!reading.ore().contains(":")) {
                    int cx = reading.deposit() != 0 ? (int) reading.deposit() : (reading.x() >> 4);
                    int cz = reading.deposit() != 0 ? (int) (reading.deposit() >>> 32) : (reading.z() >> 4);
                    double x1 = map.x(cx * 16.0);
                    double z1 = map.z(cz * 16.0);
                    double x2 = map.x((cx + 1) * 16.0);
                    double z2 = map.z((cz + 1) * 16.0);
                    double borderEdge = 0.25 * PIXEL;
                    screen.fill(pose, glow, x1, z1, x2, z1 + borderEdge, borderY, border);
                    screen.fill(pose, glow, x1, z2 - borderEdge, x2, z2, borderY, border);
                    screen.fill(pose, glow, x1, z1, x1 + borderEdge, z2, borderY, border);
                    screen.fill(pose, glow, x2 - borderEdge, z1, x2, z2, borderY, border);
                    continue;
                }

                double[][] poly = OilTiers.getPolygon(reading);
                if (poly.length < 3) {
                    continue;
                }

                double[][] screenPts = new double[poly.length][2];
                double minSX = Double.MAX_VALUE, maxSX = -Double.MAX_VALUE;
                double minSZ = Double.MAX_VALUE, maxSZ = -Double.MAX_VALUE;
                for (int p = 0; p < poly.length; p++) {
                    double sx = map.x(poly[p][0]);
                    double sz = map.z(poly[p][1]);
                    screenPts[p][0] = sx;
                    screenPts[p][1] = sz;
                    minSX = Math.min(minSX, sx);
                    maxSX = Math.max(maxSX, sx);
                    minSZ = Math.min(minSZ, sz);
                    maxSZ = Math.max(maxSZ, sz);
                }

                if (maxSX < screen.x1 || minSX > screen.x2 || maxSZ < screen.z1 || minSZ > screen.z2) {
                    continue;
                }

                int fullLight = LightTexture.FULL_BRIGHT;
                double[][][] ribbon = MapPolygonRenderer.strokeRibbon(screenPts, edge * 0.5);
                for (double[][] quad : ribbon) {
                    double qMaxX = Math.max(Math.max(quad[0][0], quad[1][0]), Math.max(quad[2][0], quad[3][0]));
                    double qMinX = Math.min(Math.min(quad[0][0], quad[1][0]), Math.min(quad[2][0], quad[3][0]));
                    double qMaxZ = Math.max(Math.max(quad[0][1], quad[1][1]), Math.max(quad[2][1], quad[3][1]));
                    double qMinZ = Math.min(Math.min(quad[0][1], quad[1][1]), Math.min(quad[2][1], quad[3][1]));
                    if (qMaxX < screen.x1 || qMinX > screen.x2 || qMaxZ < screen.z1 || qMinZ > screen.z2) {
                        continue;
                    }
                    renderClippedConvexPoly(screen, pose, glow, List.of(quad[0], quad[1], quad[2], quad[3]), borderY, border, fullLight);
                }
            }
        } else if (be.activeTab() == LoggerDisplay.Tab.ORES) {
            for (int i = 0; i < entries.size(); i++) {
                ReaderReading reading = entries.get(i).reading();
                if (!reading.dimension().equals(dimension)) {
                    continue;
                }
                double x = map.x(reading.x() + 0.5);
                double z = map.z(reading.z() + 0.5);
                if (!screen.contains(x, z)) {
                    continue;
                }
                // A reading with no height is drawn fainter: it is only known across the ground.
                int colour = ReadingOres.colour(reading.ore());
                if (!reading.hasHeight()) {
                    colour = (colour & 0x00FFFFFF) | 0x99000000;
                }
                double dot = LoggerDisplay.MARKER_SIZE / 2;
                float entryOffset = (i % 32) * 0.01F * LAYER;
                float dotY = y + 2.8F * LAYER + entryOffset;
                screen.fill(pose, glow, x - dot, z - dot, x + dot, z + dot, dotY, colour);
                if (entries.get(i).number() == selected) {
                    // A wider amber ring around the one this player has selected.
                    ring(screen, pose, glow, x, z, dot + 1.1 * PIXEL, 0.3 * PIXEL, y + 4.0F * LAYER, 0xFFF2B742);
                }
                if (i == hovered) {
                    // A white ring around the one the goggles are calling out.
                    ring(screen, pose, glow, x, z, dot + 0.6 * PIXEL, 0.2 * PIXEL, y + 4.5F * LAYER, 0xFFFFFFFF);
                }
            }
        }

        // Layer markers (incursions, bonfires): drawn over deposits.
        for (MapLayer layer : layers) {
            renderLayerMarkers(screen, map, pose, glow, y, layer);
        }

        // North.
        double middleX = (screen.x1 + screen.x2) / 2;
        quad(pose, glow, middleX - PIXEL, screen.z1, middleX + PIXEL, screen.z1 + 1.5 * PIXEL, y + 4.0F * LAYER, 0xFFE04848, full);

        // The logger.
        Vec3 here = be.middle();
        double lx = map.x(here.x);
        double lz = map.z(here.z);
        float crossY = y + 4 * LAYER;
        screen.fill(pose, glow, lx - PIXEL, lz - 0.2 * PIXEL, lx + PIXEL, lz + 0.2 * PIXEL, crossY, 0xFFE8ECEA);
        screen.fill(pose, glow, lx - 0.2 * PIXEL, lz - PIXEL, lx + 0.2 * PIXEL, lz + PIXEL, crossY, 0xFFE8ECEA);

        // The zoom pips, along the front edge (bottom left).
        for (int i = 0; i <= LoggerDisplay.MAX_ZOOM; i++) {
            double u = LoggerDisplay.BEZEL + PIXEL + i * 1.5 * PIXEL;
            double v = LoggerDisplay.BEZEL + 0.5 * PIXEL;
            double[] a = layout.at(u, v);
            double[] b = layout.at(u + PIXEL, v + PIXEL);
            quad(pose, glow, a[0], a[1], b[0], b[1], y + 4 * LAYER, i <= view.zoom() ? 0xFFF2B742 : 0x40F2B742, full);
        }

        // 1. Quads for tabs and legend: written to glow BEFORE switching buffers to font text.
        renderTabQuads(be, layout, pose, glow, y);
        List<MapLayer.LegendRow> legend = MapLayers.legend(layers);
        if (!legend.isEmpty()) {
            renderLegendQuads(layout, pose, glow, y, legend);
        }

        // 2. Text for tabs and legend: switches buffers, ending the glow buffer.
        renderTabsText(be, layout, pose, buffer, y);
        if (!legend.isEmpty()) {
            renderLegendText(layout, pose, buffer, y, legend);
        }
    }

    /** The tab backgrounds and borders along the top of the map. Draws all quads to glow before buffer switching. */
    private static void renderTabQuads(SmartLoggerBlockEntity be, Layout layout, PoseStack.Pose pose, VertexConsumer glow, float y) {
        double top = LoggerDisplay.DEPTH - LoggerDisplay.BEZEL - 0.01;
        double bottom = top - LoggerDisplay.TAB_HEIGHT;
        double mid = LoggerDisplay.SCREEN_CENTRE_U;
        float tabY = y + 5.5F * LAYER;

        for (LoggerDisplay.Tab tab : LoggerDisplay.Tab.values()) {
            boolean active = be.activeTab() == tab;
            double left = LoggerDisplay.tabLeft(tab);
            double right = LoggerDisplay.tabRight(tab);

            double[] a = layout.at(left, bottom);
            double[] b = layout.at(right, top);
            int bg = active ? 0xD05A4614 : 0xD016181C;
            quad(pose, glow, a[0], a[1], b[0], b[1], tabY, bg, LightTexture.FULL_BRIGHT);

            // Tab border / top highlight.
            int edgeColor = active ? 0xFFF2B742 : 0xFF353840;
            double[] edgeA = layout.at(left, top - 0.008);
            double[] edgeB = layout.at(right, top);
            quad(pose, glow, edgeA[0], edgeA[1], edgeB[0], edgeB[1], tabY + LAYER, edgeColor, LightTexture.FULL_BRIGHT);
        }
    }

    /** The tab labels. Drawn after all glow quads are finished. */
    private static void renderTabsText(SmartLoggerBlockEntity be, Layout layout, PoseStack.Pose pose,
                                       MultiBufferSource buffer, float y) {
        double top = LoggerDisplay.DEPTH - LoggerDisplay.BEZEL - 0.01;
        double bottom = top - LoggerDisplay.TAB_HEIGHT;
        double mid = LoggerDisplay.SCREEN_CENTRE_U;
        float tabY = y + 5.5F * LAYER;

        double[] origin = layout.at(0, 0);
        double[] alongU = layout.at(1, 0);
        double[] alongV = layout.at(0, 1);
        float ux = (float) (alongU[0] - origin[0]);
        float uz = (float) (alongU[1] - origin[1]);
        float vx = (float) (alongV[0] - origin[0]);
        float vz = (float) (alongV[1] - origin[1]);
        Font font = Minecraft.getInstance().font;
        float fontScale = 0.0068F;

        for (LoggerDisplay.Tab tab : LoggerDisplay.Tab.values()) {
            boolean active = be.activeTab() == tab;
            double left = LoggerDisplay.tabLeft(tab);
            double right = LoggerDisplay.tabRight(tab);

            Component text = Component.translatable("seismic_surveyance.smart_logger.tab_" + tab.name().toLowerCase(Locale.ROOT));
            int textWidth = font.width(text);
            double textCenterU = (left + right) / 2.0;
            double textU = textCenterU - (textWidth * fontScale) / 2.0;
            double textV = (top + bottom) / 2.0 + 3.5 * fontScale;

            double[] at = layout.at(textU, textV);
            Matrix4f matrix = new Matrix4f(pose.pose())
                    .translate((float) at[0], tabY + 2 * LAYER, (float) at[1])
                    .mul(new Matrix4f(ux, 0, uz, 0, -vx, 0, -vz, 0, 0, 1, 0, 0, 0, 0, 0, 1))
                    .scale(fontScale, fontScale, fontScale);
            font.drawInBatch(text, 0, 0, active ? 0xFFF2B742 : 0xFF8A8D95, false, matrix, buffer, Font.DisplayMode.NORMAL, 0,
                    LightTexture.FULL_BRIGHT);
        }
    }

    /** How near a player must be to a logger for it to keep its layers (the tier overlay) up to date for them. */
    private static final double TIER_VIEW_DISTANCE = 32;
    /** How thick a tint's outline is. */
    private static final double TIER_EDGE = 0.3 * PIXEL;

    /** A layer's tinted chunk columns on the screen (fills under deposits and grid lines). */
    private static void renderLayerFills(Screen screen, Projection map, PoseStack.Pose pose, VertexConsumer glow, float y,
                                         MapLayer layer) {
        int minX = (int) Math.floor(Math.min(map.worldX(screen.x1), map.worldX(screen.x2))) >> 4;
        int maxX = (int) Math.floor(Math.max(map.worldX(screen.x1), map.worldX(screen.x2))) >> 4;
        int minZ = (int) Math.floor(Math.min(map.worldZ(screen.z1), map.worldZ(screen.z2))) >> 4;
        int maxZ = (int) Math.floor(Math.max(map.worldZ(screen.z1), map.worldZ(screen.z2))) >> 4;
        layer.columns(minX, minZ, maxX, maxZ, (cx, cz, fill, edge, edges) -> {
            double x1 = map.x(cx << 4);
            double x2 = map.x((cx + 1) << 4);
            double z1 = map.z(cz << 4);
            double z2 = map.z((cz + 1) << 4);
            screen.fill(pose, glow, x1, z1, x2, z2, y + 0.5F * LAYER, fill);
        });
    }

    /** A layer's boundary outlines: drawn cleanly on top of grid lines (y + 2.45F * LAYER) at y + 2.6F * LAYER. */
    private static void renderLayerBorders(Screen screen, Projection map, PoseStack.Pose pose, VertexConsumer glow, float y,
                                           MapLayer layer) {
        int minX = (int) Math.floor(Math.min(map.worldX(screen.x1), map.worldX(screen.x2))) >> 4;
        int maxX = (int) Math.floor(Math.max(map.worldX(screen.x1), map.worldX(screen.x2))) >> 4;
        int minZ = (int) Math.floor(Math.min(map.worldZ(screen.z1), map.worldZ(screen.z2))) >> 4;
        int maxZ = (int) Math.floor(Math.max(map.worldZ(screen.z1), map.worldZ(screen.z2))) >> 4;
        float borderY = y + 2.6F * LAYER;
        layer.columns(minX, minZ, maxX, maxZ, (cx, cz, fill, edge, edges) -> {
            if (edges == 0) {
                return;
            }
            double x1 = map.x(cx << 4);
            double x2 = map.x((cx + 1) << 4);
            double z1 = map.z(cz << 4);
            double z2 = map.z((cz + 1) << 4);
            if ((edges & MapLayer.NORTH) != 0) {
                screen.fill(pose, glow, x1, z1 - TIER_EDGE, x2, z1 + TIER_EDGE, borderY, edge);
            }
            if ((edges & MapLayer.SOUTH) != 0) {
                screen.fill(pose, glow, x1, z2 - TIER_EDGE, x2, z2 + TIER_EDGE, borderY, edge);
            }
            if ((edges & MapLayer.WEST) != 0) {
                screen.fill(pose, glow, x1 - TIER_EDGE, z1, x1 + TIER_EDGE, z2, borderY, edge);
            }
            if ((edges & MapLayer.EAST) != 0) {
                screen.fill(pose, glow, x2 - TIER_EDGE, z1, x2 + TIER_EDGE, z2, borderY, edge);
            }
        });
    }

    /** A layer's markers (the incursions under way and active bonfires). */
    private static void renderLayerMarkers(Screen screen, Projection map, PoseStack.Pose pose, VertexConsumer glow, float y,
                                           MapLayer layer) {
        for (MapLayer.Marker marker : layer.markers()) {
            double x = map.x(marker.x() + 0.5);
            double z = map.z(marker.z() + 0.5);
            if (!screen.contains(x, z)) {
                continue;
            }
            double size = (marker.large() ? 1.6 : 1.0) * LoggerDisplay.MARKER_SIZE;
            diamond(pose, glow, x, z, size + 0.2 * PIXEL, y + 3.4F * LAYER, marker.outline());
            diamond(pose, glow, x, z, size, y + 3.6F * LAYER, marker.colour());
        }
    }

    /** The legend's size: a font pixel, in metres of the top; a row; and the box. */
    private static final float LEGEND_FONT = 0.0075F;
    private static final double LEGEND_ROW = 10 * LEGEND_FONT;
    private static final double LEGEND_WIDTH = 0.52;

    /**
     * The legend box and swatches in the back-right corner of the screen.
     */
    private static void renderLegendQuads(Layout layout, PoseStack.Pose pose, VertexConsumer glow, float y,
                                          List<MapLayer.LegendRow> legend) {
        int rows = legend.size();
        double right = LoggerDisplay.SCREEN_WIDTH - LoggerDisplay.BEZEL - PIXEL;
        double back = LoggerDisplay.DEPTH - LoggerDisplay.BEZEL - PIXEL;
        double left = right - LEGEND_WIDTH;
        double front = back - rows * LEGEND_ROW - 2 * PIXEL;
        double[] a = layout.at(left, front);
        double[] b = layout.at(right, back);
        float boxY = y + 5.5F * LAYER;
        quad(pose, glow, a[0], a[1], b[0], b[1], boxY, 0xD00B0F10, LightTexture.FULL_BRIGHT);
        double swatch = 0.045;
        for (int i = 0; i < rows; i++) {
            double rowBack = back - PIXEL - i * LEGEND_ROW;
            double v = rowBack - LEGEND_ROW / 2;
            double u = left + PIXEL + swatch / 2;
            if (!legend.get(i).marker()) {
                double[] s1 = layout.at(u - swatch / 2, v - swatch / 2);
                double[] s2 = layout.at(u + swatch / 2, v + swatch / 2);
                quad(pose, glow, s1[0], s1[1], s2[0], s2[1], boxY + LAYER, legend.get(i).colour(), LightTexture.FULL_BRIGHT);
            } else {
                double[] c = layout.at(u, v);
                diamond(pose, glow, c[0], c[1], swatch * 0.6, boxY + LAYER, legend.get(i).colour());
            }
        }
    }

    /**
     * The legend text, drawn after all glow quads are finished.
     */
    private static void renderLegendText(Layout layout, PoseStack.Pose pose, MultiBufferSource buffer, float y,
                                         List<MapLayer.LegendRow> legend) {
        int rows = legend.size();
        double right = LoggerDisplay.SCREEN_WIDTH - LoggerDisplay.BEZEL - PIXEL;
        double back = LoggerDisplay.DEPTH - LoggerDisplay.BEZEL - PIXEL;
        double left = right - LEGEND_WIDTH;
        float boxY = y + 5.5F * LAYER;
        double swatch = 0.045;

        double[] origin = layout.at(0, 0);
        double[] alongU = layout.at(1, 0);
        double[] alongV = layout.at(0, 1);
        float ux = (float) (alongU[0] - origin[0]);
        float uz = (float) (alongU[1] - origin[1]);
        float vx = (float) (alongV[0] - origin[0]);
        float vz = (float) (alongV[1] - origin[1]);
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < rows; i++) {
            double rowBack = back - PIXEL - i * LEGEND_ROW;
            double[] at = layout.at(left + 2 * PIXEL + swatch, rowBack - 1.5 * LEGEND_FONT);
            Matrix4f matrix = new Matrix4f(pose.pose())
                    .translate((float) at[0], boxY + 2 * LAYER, (float) at[1])
                    .mul(new Matrix4f(ux, 0, uz, 0, -vx, 0, -vz, 0, 0, 1, 0, 0, 0, 0, 0, 1))
                    .scale(LEGEND_FONT, LEGEND_FONT, LEGEND_FONT);
            font.drawInBatch(legend.get(i).name(), 0, 0, 0xFFE8ECEA, false, matrix, buffer, Font.DisplayMode.NORMAL, 0,
                    LightTexture.FULL_BRIGHT);
        }
    }

    /** A line between two screen points with given width, cut to the screen. */
    private static void line(Screen screen, PoseStack.Pose pose, VertexConsumer glow,
                             double x1, double z1, double x2, double z2, float y, int colour, double width) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            return;
        }
        double nx = (-dz / len) * (width * 0.5);
        double nz = (dx / len) * (width * 0.5);

        float cx1 = (float) Math.max(screen.x1, Math.min(screen.x2, x1 - nx));
        float cz1 = (float) Math.max(screen.z1, Math.min(screen.z2, z1 - nz));
        float cx2 = (float) Math.max(screen.x1, Math.min(screen.x2, x1 + nx));
        float cz2 = (float) Math.max(screen.z1, Math.min(screen.z2, z1 + nz));
        float cx3 = (float) Math.max(screen.x1, Math.min(screen.x2, x2 + nx));
        float cz3 = (float) Math.max(screen.z1, Math.min(screen.z2, z2 + nz));
        float cx4 = (float) Math.max(screen.x1, Math.min(screen.x2, x2 - nx));
        float cz4 = (float) Math.max(screen.z1, Math.min(screen.z2, z2 - nz));

        int light = LightTexture.FULL_BRIGHT;
        vertex(pose, glow, cx1, y, cz1, colour, light);
        vertex(pose, glow, cx2, y, cz2, colour, light);
        vertex(pose, glow, cx3, y, cz3, colour, light);
        vertex(pose, glow, cx4, y, cz4, colour, light);
    }

    /** A square ring around a dot, cut to the screen. */
    private static void ring(Screen screen, PoseStack.Pose pose, VertexConsumer glow, double x, double z, double radius,
                             double thickness, float y, int colour) {
        double r1 = radius - thickness / 2;
        double r2 = radius + thickness / 2;
        screen.fill(pose, glow, x - r2, z - r2, x + r2, z - r1, y, colour);
        screen.fill(pose, glow, x - r2, z + r1, x + r2, z + r2, y, colour);
        screen.fill(pose, glow, x - r2, z - r1, x - r1, z + r1, y, colour);
        screen.fill(pose, glow, x + r1, z - r1, x + r2, z + r1, y, colour);
    }

    /** Cuts a polygon to one side of a line x = at (axis 0) or z = at (axis 1). */
    private static List<double[]> clip(List<double[]> points, int axis, double at, boolean keepGreater) {
        List<double[]> result = new ArrayList<>(points.size() + 2);
        int n = points.size();
        for (int i = 0; i < n; i++) {
            double[] p = points.get(i);
            double[] q = points.get((i + 1) % n);
            boolean pIn = keepGreater ? p[axis] >= at : p[axis] <= at;
            boolean qIn = keepGreater ? q[axis] >= at : q[axis] <= at;
            if (pIn) {
                result.add(p);
            }
            if (pIn != qIn) {
                double diff = q[axis] - p[axis];
                if (Math.abs(diff) > 1e-9) {
                    double t = (at - p[axis]) / diff;
                    double other = p[1 - axis] + (q[1 - axis] - p[1 - axis]) * t;
                    result.add(axis == 0 ? new double[]{at, other} : new double[]{other, at});
                }
            }
        }
        return result;
    }

    /**
     * Clips a convex polygon (triangle or stroke quad) to the screen bounds and emits fan quads facing upward (+Y).
     */
    private static void renderClippedConvexPoly(Screen screen, PoseStack.Pose pose, VertexConsumer glow,
                                                List<double[]> pts, float y, int colour, int light) {
        pts = clip(pts, 0, screen.x1, true);
        pts = clip(pts, 0, screen.x2, false);
        pts = clip(pts, 1, screen.z1, true);
        pts = clip(pts, 1, screen.z2, false);
        int n = pts.size();
        if (n < 3) {
            return;
        }
        double area = 0;
        for (int p = 0; p < n; p++) {
            double[] a = pts.get(p);
            double[] b = pts.get((p + 1) % n);
            area += a[0] * b[1] - b[0] * a[1];
        }
        if (area > 0) {
            List<double[]> turned = new ArrayList<>(n);
            turned.add(pts.get(0));
            for (int p = n - 1; p >= 1; p--) {
                turned.add(pts.get(p));
            }
            pts = turned;
        }
        for (int p = 1; p + 1 < n; p += 2) {
            double[] a = pts.get(0);
            double[] b = pts.get(p);
            double[] c = pts.get(p + 1);
            double[] d = (p + 2 < n) ? pts.get(p + 2) : c;
            vertex(pose, glow, (float) a[0], y, (float) a[1], colour, light);
            vertex(pose, glow, (float) b[0], y, (float) b[1], colour, light);
            vertex(pose, glow, (float) c[0], y, (float) c[1], colour, light);
            vertex(pose, glow, (float) d[0], y, (float) d[1], colour, light);
        }
    }

    /** A quad across the ground between two world points, unlit. */
    private static void quad(PoseStack.Pose pose, VertexConsumer glow, double x1, double z1, double x2, double z2, float y,
                             int colour, int light) {
        float xMin = (float) Math.min(x1, x2);
        float xMax = (float) Math.max(x1, x2);
        float zMin = (float) Math.min(z1, z2);
        float zMax = (float) Math.max(z1, z2);
        vertex(pose, glow, xMin, y, zMin, colour, light);
        vertex(pose, glow, xMin, y, zMax, colour, light);
        vertex(pose, glow, xMax, y, zMax, colour, light);
        vertex(pose, glow, xMax, y, zMin, colour, light);
    }

    /** A diamond centered at (x, z), radius r from centre to points. */
    private static void diamond(PoseStack.Pose pose, VertexConsumer glow, double x, double z, double r, float y, int colour) {
        float cx = (float) x;
        float cz = (float) z;
        float radius = (float) r;
        int light = LightTexture.FULL_BRIGHT;
        vertex(pose, glow, cx, y, cz - radius, colour, light);
        vertex(pose, glow, cx - radius, y, cz, colour, light);
        vertex(pose, glow, cx, y, cz + radius, colour, light);
        vertex(pose, glow, cx + radius, y, cz, colour, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, float x, float y, float z, int colour, int light) {
        consumer.addVertex(pose.pose(), x, y, z)
                .setColor(colour)
                .setLight(light);
    }

    /** A rectangular area of the screen, clipping what is drawn into it. Coordinates in the world. */
    private record Screen(double x1, double z1, double x2, double z2) {

        boolean contains(double x, double z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }

        void fill(PoseStack.Pose pose, VertexConsumer glow, double fx1, double fz1, double fx2, double fz2, float y, int colour) {
            double cx1 = Math.max(x1, Math.min(fx1, fx2));
            double cx2 = Math.min(x2, Math.max(fx1, fx2));
            double cz1 = Math.max(z1, Math.min(fz1, fz2));
            double cz2 = Math.min(z2, Math.max(fz1, fz2));
            if (cx2 > cx1 && cz2 > cz1) {
                quad(pose, glow, cx1, cz1, cx2, cz2, y, colour, LightTexture.FULL_BRIGHT);
            }
        }
    }

    /** Maps world positions to where they land on the screen, in world units from the master block. */
    private record Projection(double screenX, double screenZ, double worldX, double worldZ, double scale) {

        double x(double worldX) {
            return screenX + (worldX - this.worldX) / scale;
        }

        double z(double worldZ) {
            return screenZ + (worldZ - this.worldZ) / scale;
        }

        double worldX(double screenX) {
            return this.worldX + (screenX - this.screenX) * scale;
        }

        double worldZ(double screenZ) {
            return this.worldZ + (screenZ - this.screenZ) * scale;
        }
    }
}
