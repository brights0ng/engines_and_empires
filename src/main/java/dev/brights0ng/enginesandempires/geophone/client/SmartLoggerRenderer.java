package dev.brights0ng.enginesandempires.geophone.client;

import org.joml.Quaternionf;
import org.joml.Matrix4f;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.brights0ng.enginesandempires.geophone.LogbookEntry;
import dev.brights0ng.enginesandempires.geophone.LoggerDisplay;
import dev.brights0ng.enginesandempires.geophone.ReaderReading;
import dev.brights0ng.enginesandempires.geophone.ReadingOres;
import dev.brights0ng.enginesandempires.geophone.SmartLoggerBlock;
import dev.brights0ng.enginesandempires.geophone.SmartLoggerBlockEntity;
import dev.brights0ng.enginesandempires.frontier.map.TierGrid;
import dev.brights0ng.enginesandempires.frontier.map.TierMapPayloads;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The smart logger's block entity renderer.
 *
 * <p>Every quarter: its cog, whenever Flywheel is off (with Flywheel on, it is an instance, see {@link ThumperClient}).
 *
 * <p>The master quarter, always: the two bronze buttons on the bar, each sunk half a pixel while pressed; a small indicator
 * in front of each; the dock on the back half of the bar, with the portable record display lying in it if one is docked (its
 * own item model, so its lamps show there too); and, while the display is on, the map. The map is drawn flat on the screen glass (which looks the same on
 * or off) in plain coloured quads, glowing (full brightness), from the logger's synced readings and view. Positions come from
 * {@link LoggerDisplay}, the same place the block works out what a click hit.
 *
 * <p>What the map shows:
 * <ul>
 *   <li>A faint grey grid, lined up with the world, a power of two blocks apart, and a red tick on the screen's north edge.</li>
 *   <li>The logger itself, as a small white cross.</li>
 *   <li>Each reading in this dimension: a small dot in its ore's colour ({@link ReadingOres}), fainter if the reading has no
 *       height; around it, once zoomed in far enough to be bigger than the dot, a faint square as big as the error its
 *       confidence allows. The reading the goggles are calling out ({@link SmartLoggerHover}) is ringed in white.</li>
 *   <li>The zoom level, as a row of pips along the back edge.</li>
 * </ul>
 */
public class SmartLoggerRenderer extends KineticBlockEntityRenderer<SmartLoggerBlockEntity> {

    private static final float PIXEL = 1.0F / 16;

    /** How far each layer of the map sits above the one below, so none share a plane. */
    private static final float LAYER = 0.0006F;

    /** The grid's lines: faint grey, a little brighter where it crosses the world's zero. */
    private static final int GRID = 0x22FFFFFF;
    private static final int GRID_ORIGIN = 0x44FFFFFF;

    public SmartLoggerRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRenderOffScreen(SmartLoggerBlockEntity be) {
        return true;
    }

    @Override
    protected void renderSafe(SmartLoggerBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        Level level = be.getLevel();
        if (level == null) {
            return;
        }
        if (!VisualizationManager.supportsVisualization(level)) {
            renderRotatingBuffer(be, CachedBuffers.partial(AllPartialModels.SHAFTLESS_COGWHEEL, be.getBlockState()), ms,
                    buffer.getBuffer(RenderType.solid()), light);
        }
        if (!be.isMaster()) {
            return;
        }
        Layout layout = new Layout(be);
        renderButtons(be, layout, ms, buffer, level);
        VertexConsumer glow = buffer.getBuffer(RenderType.textBackground());
        int barLight = LevelRenderer.getLightColor(level, be.getBlockPos().relative(layout.right()).above());
        renderIndicators(be, layout, ms.last(), glow, LevelRenderer.getLightColor(level, be.getBlockPos().above()));
        renderDock(be, layout, ms, buffer, glow, barLight, level);
        if (be.displayOn()) {
            renderMap(be, layout, ms.last(), glow, level, buffer);
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
            quad(pose, glow, a[0], a[1], b[0], b[1], y, colour, lit ? LightTexture.FULL_BRIGHT : blockLight);
        }
    }

    // ---- the dock ----

    /**
     * How big the docked display is drawn. It is a 3D model a block and a half across (see {@code PrdShape}), laid along the
     * bar, so it must be small to fit the dock.
     */
    private static final float DOCKED_SCALE = 0.5F;

    /** A dark recess on the back half of the bar, and the display lying in it, screen up, its length along the bar. */
    private static void renderDock(SmartLoggerBlockEntity be, Layout layout, PoseStack ms, MultiBufferSource buffer,
                                   VertexConsumer glow, int light, Level level) {
        float y = (float) LoggerDisplay.BAR_TOP + 0.002F;
        double inset = 1.5 * PIXEL;
        double[] a = layout.at(LoggerDisplay.SCREEN_WIDTH + inset, LoggerDisplay.DOCK_FROM_V + inset);
        double[] b = layout.at(LoggerDisplay.WIDTH - inset, LoggerDisplay.DEPTH - inset);
        quad(ms.last(), glow, a[0], a[1], b[0], b[1], y, 0xFF141518, light);

        ItemStack docked = be.docked();
        if (docked.isEmpty()) {
            return;
        }
        double[] centre = layout.at(LoggerDisplay.Button.centreU(), LoggerDisplay.DOCK_CENTRE_V);
        LoggerDisplay.Frame frame = layout.frame();
        ms.pushPose();
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

    private static void renderMap(SmartLoggerBlockEntity be, Layout layout, PoseStack.Pose pose, VertexConsumer glow, Level level,
                                  MultiBufferSource buffer) {
        LoggerDisplay.View view = be.view();
        double scale = view.blocksPerMetre();
        double[] c1 = layout.at(LoggerDisplay.BEZEL, LoggerDisplay.BEZEL);
        double[] c2 = layout.at(LoggerDisplay.SCREEN_WIDTH - LoggerDisplay.BEZEL, LoggerDisplay.DEPTH - LoggerDisplay.BEZEL);
        Screen screen = new Screen(Math.min(c1[0], c2[0]), Math.min(c1[1], c2[1]), Math.max(c1[0], c2[0]), Math.max(c1[1], c2[1]));
        double[] centre = layout.at(LoggerDisplay.SCREEN_CENTRE_U, LoggerDisplay.SCREEN_CENTRE_V);
        Projection map = new Projection(centre[0], centre[1], view.centreX(), view.centreZ(), scale);
        float y = (float) LoggerDisplay.SCREEN_Y + 0.002F;
        int full = LightTexture.FULL_BRIGHT;
        boolean tiers = TierOverlay.shows(level);

        // The tier overlay, under everything else: asked for while someone is near enough to see it.
        if (tiers) {
            Vec3 middle = be.middle();
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.distanceToSqr(middle) <= TIER_VIEW_DISTANCE * TIER_VIEW_DISTANCE) {
                TierOverlay.want(be.getBlockPos(), view.centreX(), view.centreZ(), 1.3 * scale);
            }
            renderTiers(screen, map, pose, glow, y);
        }

        // The glass itself stays as it is when off: the map is drawn straight on it. First, a faint grid.
        int spacing = LoggerDisplay.gridSpacing(view);
        double half = 0.2 * PIXEL;
        double westmost = map.worldX(screen.x1);
        double eastmost = map.worldX(screen.x2);
        for (long gx = (long) Math.ceil(westmost / spacing) * spacing, n = 0; gx <= eastmost && n < 96; gx += spacing, n++) {
            double x = map.x(gx);
            quad(pose, glow, x - half, screen.z1, x + half, screen.z2, y + LAYER, gx == 0 ? GRID_ORIGIN : GRID, full);
        }
        double northmost = map.worldZ(screen.z1);
        double southmost = map.worldZ(screen.z2);
        for (long gz = (long) Math.ceil(northmost / spacing) * spacing, n = 0; gz <= southmost && n < 96; gz += spacing, n++) {
            double z = map.z(gz);
            quad(pose, glow, screen.x1, z - half, screen.x2, z + half, y + LAYER, gz == 0 ? GRID_ORIGIN : GRID, full);
        }

        // North.
        double middleX = (screen.x1 + screen.x2) / 2;
        quad(pose, glow, middleX - PIXEL, screen.z1, middleX + PIXEL, screen.z1 + 1.5 * PIXEL, y + 4 * LAYER, 0xFFE04848, full);

        // The readings, each in its ore's colour: first every blur (as big as the error its confidence allows, so it only
        // shows once zoomed in far enough to matter), then every dot, so no blur covers another reading's dot.
        String dimension = level.dimension().location().toString();
        for (LogbookEntry entry : be.records().entries()) {
            ReaderReading reading = entry.reading();
            if (!reading.dimension().equals(dimension)) {
                continue;
            }
            double x = map.x(reading.x() + 0.5);
            double z = map.z(reading.z() + 0.5);
            double blur = LoggerDisplay.blurBlocks(reading.confidence()) / scale;
            if (blur <= LoggerDisplay.MARKER_SIZE) {
                continue;
            }
            screen.fill(pose, glow, x - blur, z - blur, x + blur, z + blur, y + 2 * LAYER,
                    (ReadingOres.colour(reading.ore()) & 0x00FFFFFF) | 0x1C000000);
        }
        int hovered = SmartLoggerHover.hoveredIndex(be);
        int selected = SmartLoggerControls.selected(be);
        List<LogbookEntry> entries = be.records().entries();
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
            float dotY = y + 3 * LAYER;
            screen.fill(pose, glow, x - dot, z - dot, x + dot, z + dot, dotY, colour);
            if (entries.get(i).number() == selected) {
                // A wider amber ring around the one this player has selected.
                ring(screen, pose, glow, x, z, dot + 1.1 * PIXEL, 0.3 * PIXEL, y + 4 * LAYER, 0xFFF2B742);
            }
            if (i == hovered) {
                // A white ring around the one the goggles are calling out.
                ring(screen, pose, glow, x, z, dot + 0.6 * PIXEL, 0.2 * PIXEL, y + 5 * LAYER, 0xFFFFFFFF);
            }
        }

        // The logger.
        Vec3 here = be.middle();
        double lx = map.x(here.x);
        double lz = map.z(here.z);
        float crossY = y + 4 * LAYER;
        screen.fill(pose, glow, lx - PIXEL, lz - 0.2 * PIXEL, lx + PIXEL, lz + 0.2 * PIXEL, crossY, 0xFFE8ECEA);
        screen.fill(pose, glow, lx - 0.2 * PIXEL, lz - PIXEL, lx + 0.2 * PIXEL, lz + PIXEL, crossY, 0xFFE8ECEA);

        // The zoom pips, along the back edge from the left.
        for (int i = 0; i <= LoggerDisplay.MAX_ZOOM; i++) {
            double u = LoggerDisplay.BEZEL + PIXEL + i * 1.5 * PIXEL;
            double v = LoggerDisplay.DEPTH - LoggerDisplay.BEZEL - 1.5 * PIXEL;
            double[] a = layout.at(u, v);
            double[] b = layout.at(u + PIXEL, v + PIXEL);
            quad(pose, glow, a[0], a[1], b[0], b[1], y + 4 * LAYER, i <= view.zoom() ? 0xFFF2B742 : 0x40F2B742, full);
        }

        // Last, as the text switches buffers: the legend.
        if (tiers) {
            renderLegend(layout, pose, glow, buffer, y);
        }
    }

    /** How near a player must be to a logger for it to keep its tier overlay up to date for them. */
    private static final double TIER_VIEW_DISTANCE = 32;
    /** How thick a tint's outline is. */
    private static final double TIER_EDGE = 0.3 * PIXEL;

    /** Every tinted chunk column on the screen, and the incursions under way. */
    private static void renderTiers(Screen screen, Projection map, PoseStack.Pose pose, VertexConsumer glow, float y) {
        int minX = (int) Math.floor(Math.min(map.worldX(screen.x1), map.worldX(screen.x2))) >> 4;
        int maxX = (int) Math.floor(Math.max(map.worldX(screen.x1), map.worldX(screen.x2))) >> 4;
        int minZ = (int) Math.floor(Math.min(map.worldZ(screen.z1), map.worldZ(screen.z2))) >> 4;
        int maxZ = (int) Math.floor(Math.max(map.worldZ(screen.z1), map.worldZ(screen.z2))) >> 4;
        float fillY = y + 0.4F * LAYER;
        // Outlines above the grid (at 1 layer), so one on a grid line still shows; the blurs start at 2.
        float edgeY = y + 1.5F * LAYER;
        TierOverlay.visit(minX, minZ, maxX, maxZ, (cx, cz, tier, edges) -> {
            double x1 = map.x(cx * 16.0);
            double x2 = map.x(cx * 16.0 + 16);
            double z1 = map.z(cz * 16.0);
            double z2 = map.z(cz * 16.0 + 16);
            double ax = Math.min(x1, x2);
            double bx = Math.max(x1, x2);
            double az = Math.min(z1, z2);
            double bz = Math.max(z1, z2);
            screen.fill(pose, glow, ax, az, bx, bz, fillY, TierOverlay.fill(tier));
            int edge = TierOverlay.edge(tier);
            double e = Math.min(TIER_EDGE, (bx - ax) / 4);
            if ((edges & TierGrid.NORTH) != 0) {
                screen.fill(pose, glow, ax, az, bx, az + e, edgeY, edge);
            }
            if ((edges & TierGrid.SOUTH) != 0) {
                screen.fill(pose, glow, ax, bz - e, bx, bz, edgeY, edge);
            }
            if ((edges & TierGrid.WEST) != 0) {
                screen.fill(pose, glow, ax, az, ax + e, bz, edgeY, edge);
            }
            if ((edges & TierGrid.EAST) != 0) {
                screen.fill(pose, glow, bx - e, az, bx, bz, edgeY, edge);
            }
        });
        float markerY = y + 3.5F * LAYER;
        for (TierMapPayloads.Marker marker : TierOverlay.markers()) {
            double x = map.x(marker.x() + 0.5);
            double z = map.z(marker.z() + 0.5);
            if (!screen.contains(x, z)) {
                continue;
            }
            double size = (marker.kind() == 1 ? 1.6 : 1.2) * PIXEL;
            diamond(pose, glow, x, z, size + 0.35 * PIXEL, markerY, TierOverlay.MARKER_OUTLINE);
            diamond(pose, glow, x, z, size, markerY + 0.2F * LAYER, TierOverlay.MARKER);
        }
    }

    /** A diamond (a square on its corner), {@code r} from its middle to each point, facing up. */
    private static void diamond(PoseStack.Pose pose, VertexConsumer consumer, double x, double z, double r, float y, int argb) {
        int light = LightTexture.FULL_BRIGHT;
        consumer.addVertex(pose, (float) x, y, (float) (z - r)).setColor(argb).setLight(light);
        consumer.addVertex(pose, (float) (x - r), y, (float) z).setColor(argb).setLight(light);
        consumer.addVertex(pose, (float) x, y, (float) (z + r)).setColor(argb).setLight(light);
        consumer.addVertex(pose, (float) (x + r), y, (float) z).setColor(argb).setLight(light);
    }

    /** The legend's size: a font pixel, in metres of the top; a row; and the box. */
    private static final float LEGEND_FONT = 0.0075F;
    private static final double LEGEND_ROW = 10 * LEGEND_FONT;
    private static final double LEGEND_WIDTH = 0.52;

    /**
     * The legend in the back-right corner of the screen, read from the front: a swatch and a name for each tier that is
     * tinted, and the incursion marker.
     */
    private static void renderLegend(Layout layout, PoseStack.Pose pose, VertexConsumer glow, MultiBufferSource buffer, float y) {
        int rows = TierOverlay.LEGEND_KEYS.length;
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
            if (i < rows - 1) {
                double[] s1 = layout.at(u - swatch / 2, v - swatch / 2);
                double[] s2 = layout.at(u + swatch / 2, v + swatch / 2);
                quad(pose, glow, s1[0], s1[1], s2[0], s2[1], boxY + LAYER, TierOverlay.legendColour(i), LightTexture.FULL_BRIGHT);
            } else {
                double[] c = layout.at(u, v);
                diamond(pose, glow, c[0], c[1], swatch * 0.6, boxY + LAYER, TierOverlay.MARKER);
            }
        }
        // The text: font x along the top to the right, font y towards the front, so it reads from where the logger is worked.
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
            font.drawInBatch(TierOverlay.legendName(i), 0, 0, 0xFFE8ECEA, false, matrix, buffer, Font.DisplayMode.NORMAL, 0,
                    LightTexture.FULL_BRIGHT);
        }
    }

    /** A square ring around a dot, cut to the screen. */
    private static void ring(Screen screen, PoseStack.Pose pose, VertexConsumer glow, double x, double z, double ring, double edge,
                             float y, int argb) {
        screen.fill(pose, glow, x - ring, z - ring, x + ring, z - ring + edge, y, argb);
        screen.fill(pose, glow, x - ring, z + ring - edge, x + ring, z + ring, y, argb);
        screen.fill(pose, glow, x - ring, z - ring, x - ring + edge, z + ring, y, argb);
        screen.fill(pose, glow, x + ring - edge, z - ring, x + ring, z + ring, y, argb);
    }

    /** Where world positions land on the screen, relative to the master block, and back. */
    private record Projection(double screenX, double screenZ, double worldX, double worldZ, double scale) {
        double x(double world) {
            return screenX + (world - worldX) / scale;
        }

        double z(double world) {
            return screenZ + (world - worldZ) / scale;
        }

        double worldX(double x) {
            return worldX + (x - screenX) * scale;
        }

        double worldZ(double z) {
            return worldZ + (z - screenZ) * scale;
        }
    }

    /** The visible screen, relative to the master block. Everything on the map is cut to it. */
    private record Screen(double x1, double z1, double x2, double z2) {
        boolean contains(double x, double z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }

        void fill(PoseStack.Pose pose, VertexConsumer consumer, double ax, double az, double bx, double bz, float y, int argb) {
            double cx1 = Math.max(x1, Math.min(ax, bx));
            double cz1 = Math.max(z1, Math.min(az, bz));
            double cx2 = Math.min(x2, Math.max(ax, bx));
            double cz2 = Math.min(z2, Math.max(az, bz));
            if (cx1 < cx2 && cz1 < cz2) {
                quad(pose, consumer, cx1, cz1, cx2, cz2, y, argb, LightTexture.FULL_BRIGHT);
            }
        }
    }

    /** A flat rectangle facing up, between two corners in any order. */
    private static void quad(PoseStack.Pose pose, VertexConsumer consumer, double ax, double az, double bx, double bz,
                             float y, int argb, int light) {
        float x1 = (float) Math.min(ax, bx);
        float x2 = (float) Math.max(ax, bx);
        float z1 = (float) Math.min(az, bz);
        float z2 = (float) Math.max(az, bz);
        consumer.addVertex(pose, x1, y, z1).setColor(argb).setLight(light);
        consumer.addVertex(pose, x1, y, z2).setColor(argb).setLight(light);
        consumer.addVertex(pose, x2, y, z2).setColor(argb).setLight(light);
        consumer.addVertex(pose, x2, y, z1).setColor(argb).setLight(light);
    }
}
