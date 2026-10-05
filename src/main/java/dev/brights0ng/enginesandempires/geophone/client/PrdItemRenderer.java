package dev.brights0ng.enginesandempires.geophone.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.Logbook;
import dev.brights0ng.enginesandempires.geophone.LogbookEntry;
import dev.brights0ng.enginesandempires.geophone.LoggerDisplay;
import dev.brights0ng.enginesandempires.geophone.PrdDisplay;
import dev.brights0ng.enginesandempires.geophone.PrdItem;
import dev.brights0ng.enginesandempires.geophone.PrdShape;
import dev.brights0ng.enginesandempires.geophone.PrdSignal;
import dev.brights0ng.enginesandempires.geophone.ReaderReading;
import dev.brights0ng.enginesandempires.geophone.ReadingOres;
import dev.brights0ng.enginesandempires.frontier.map.TierGrid;
import dev.brights0ng.enginesandempires.frontier.map.TierMapPayloads;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the portable record display, wherever it is: in the inventory, on the ground, in an item frame, docked in a smart
 * logger, and in hand. Its item model's parent is {@code builtin/entity}, which hands all of that to this renderer (see
 * {@code PrdClient}, where it is registered); the game has already placed it for the view by then, from the item model's
 * {@code display} block.
 *
 * <p>The layers:
 * <ol>
 *   <li>The display itself: the baked model {@code item/portable_record_display_body} (see {@code PrdDataGen}), whose
 *       screen shows a still picture of a map.</li>
 *   <li>Its buttons, drawn apart so that they can be pushed in: the five down the side and the eject button.</li>
 *   <li>On the glass, in hand: while it is being worked ({@link PrdSession}), its two pages (the map, or the records list),
 *       with the tabs along the top and the strip along the bottom; otherwise, the live map, turned so that up is the way
 *       its holder faces.</li>
 *   <li>The lit lamp, if any ({@link PrdClient#light}).</li>
 * </ol>
 * The glass and the lamp are plain coloured shapes and text at full brightness, like the smart logger's map. Everything is
 * laid out in model pixels, from {@link PrdShape}.
 *
 * <p>Every shape on the glass is opaque. Faint things (the grid, a reading's blur) are given the colour they would have over
 * the dark glass, rather than drawn see-through: a see-through shape still writes depth, and drawn before the model it hides
 * whatever is behind it, punching a hole through the whole display.
 */
public class PrdItemRenderer extends BlockEntityWithoutLevelRenderer {

    /** The display's models. Flywheel partial models load themselves, as long as they exist before models load: see {@link #init}. */
    public static final PartialModel BODY = PartialModel.of(model("portable_record_display_body"));
    public static final PartialModel BUTTON = PartialModel.of(model("portable_record_display_button"));
    public static final PartialModel EJECT = PartialModel.of(model("portable_record_display_eject"));

    private static ResourceLocation model(String name) {
        return ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "item/" + name);
    }

    /** Loads this class, and with it the models above, early enough for them to be baked. */
    public static void init() {
    }

    public PrdItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffer, int light,
                             int overlay) {
        Minecraft minecraft = Minecraft.getInstance();
        LivingEntity holder = null;
        boolean thirdPerson = context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        if (context.firstPerson()) {
            holder = minecraft.player;
        } else if (thirdPerson) {
            holder = PrdPoses.drawing();
        }
        if (thirdPerson && holder != null) {
            InteractionHand hand = holder.getMainHandItem() == stack ? InteractionHand.MAIN_HAND
                    : holder.getOffhandItem() == stack ? InteractionHand.OFF_HAND : null;
            if (hand != null && PrdPoses.raisedInThirdPerson(holder, hand)) {
                poseStack.pushPose();
                fromFistToBetweenHands(poseStack, context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND);
                renderDisplay(stack, poseStack, buffer, light, overlay, holder, true);
                poseStack.popPose();
                return;
            }
        }
        renderDisplay(stack, poseStack, buffer, light, overlay, holder, holder != null);
    }

    /**
     * In third person, held up in both hands: undoes the carry (the item model's third-person view, see
     * {@link PrdShape#CARRY_ROTATION}) to get back to the fist, then puts the display between the hands, its screen facing
     * back up the arms, towards the face.
     */
    private static void fromFistToBetweenHands(PoseStack poseStack, boolean leftHand) {
        float mirror = leftHand ? -1 : 1;
        float[] r = PrdShape.CARRY_ROTATION;
        float[] t = PrdShape.CARRY_TRANSLATION;
        float s = PrdShape.CARRY_SCALE;
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.scale(1 / s, 1 / s, 1 / s);
        poseStack.mulPose(new Quaternionf().rotationXYZ(r[0] * Mth.DEG_TO_RAD, mirror * r[1] * Mth.DEG_TO_RAD,
                mirror * r[2] * Mth.DEG_TO_RAD).conjugate());
        poseStack.translate(-mirror * t[0] / 16, -t[1] / 16, -t[2] / 16);

        float[] o = PrdPoses.RAISED_3P_OFFSET;
        poseStack.translate(mirror * o[0] / 16, o[1] / 16, o[2] / 16);
        float scale = PrdPoses.RAISED_3P_SCALE;
        poseStack.scale(scale, scale, scale);
        poseStack.translate(-PrdShape.CENTRE_X / 16, -PrdShape.CENTRE_Y / 16, -PrdShape.CENTRE_Z / 16);
    }

    /**
     * The display, in model space (one block is 16 model pixels, from 0 to 1), with what is on its glass if {@code live} and
     * it has a holder.
     */
    public static void renderDisplay(ItemStack stack, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay,
                                     @Nullable LivingEntity holder, boolean live) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean worked = live && holder != null && holder == minecraft.player && PrdSession.shows(stack);
        VertexConsumer solid = buffer.getBuffer(Sheets.cutoutBlockSheet());
        quads(BODY.get(), poseStack.last(), solid, light, overlay);
        for (int i = 0; i < PrdShape.BUTTONS.length; i++) {
            float depth = worked ? PrdSession.pressDepth(i) : 0;
            poseStack.pushPose();
            poseStack.translate(0, -i * PrdShape.BUTTON_STEP / 16, -depth / 16);
            quads(BUTTON.get(), poseStack.last(), solid, light, overlay);
            poseStack.popPose();
        }
        poseStack.pushPose();
        poseStack.translate(0, 0, -(worked ? PrdSession.pressDepth(PrdSession.EJECT) : 0) / 16);
        quads(EJECT.get(), poseStack.last(), solid, light, overlay);
        poseStack.popPose();
        drawButtonWords(poseStack, buffer, light, worked);

        poseStack.pushPose();
        poseStack.scale(1 / 16F, 1 / 16F, 1 / 16F);
        Canvas canvas = new Canvas(poseStack.last(), buffer);
        if (worked) {
            drawWorking(canvas, stack, holder, minecraft.getTimer().getGameTimeDeltaPartialTick(false));
        } else if (live && holder != null && stack.getItem() instanceof PrdItem) {
            drawCarried(canvas, stack, holder, minecraft.getTimer().getGameTimeDeltaPartialTick(false));
        }
        drawLamps(canvas, PrdClient.light(stack, holder));
        poseStack.popPose();
    }

    private static void quads(BakedModel model, PoseStack.Pose pose, VertexConsumer out, int light, int overlay) {
        RandomSource random = RandomSource.create();
        for (Direction direction : Direction.values()) {
            random.setSeed(42L);
            for (BakedQuad quad : model.getQuads(null, direction, random)) {
                out.putBulkData(pose, quad, 1, 1, 1, 1, light, overlay);
            }
        }
        random.setSeed(42L);
        for (BakedQuad quad : model.getQuads(null, null, random)) {
            out.putBulkData(pose, quad, 1, 1, 1, 1, light, overlay);
        }
    }

    // ---- the words on the buttons ----

    /** The ink the buttons' words are engraved in. */
    private static final int ENGRAVED = 0xFF2A1E10;
    /** How much of a button's face its word may take up, across and down. */
    private static final float WORD_ACROSS = 0.84F;
    private static final float WORD_DOWN = 0.6F;

    private static Component buttonWord(String button) {
        return Component.translatable("gui.engines_and_empires.portable_record_display.button." + button);
    }

    /**
     * Each button's word on its face, moving in with it. The side buttons all share one size of lettering, the biggest at
     * which the longest word still fits; the eject button, being smaller, has its own.
     */
    private static void drawButtonWords(PoseStack poseStack, MultiBufferSource buffer, int light, boolean worked) {
        Font font = Minecraft.getInstance().font;
        PrdShape.Box first = PrdShape.button(0);
        float size = Float.MAX_VALUE;
        for (String button : PrdShape.BUTTONS) {
            size = Math.min(size, fit(font, buttonWord(button), first));
        }
        for (int i = 0; i < PrdShape.BUTTONS.length; i++) {
            float depth = worked ? PrdSession.pressDepth(i) : 0;
            engrave(poseStack, buffer, light, font, buttonWord(PrdShape.BUTTONS[i]), PrdShape.button(i), depth, size);
        }
        PrdShape.Box eject = PrdShape.eject();
        Component word = buttonWord("eject");
        engrave(poseStack, buffer, light, font, word, eject, worked ? PrdSession.pressDepth(PrdSession.EJECT) : 0,
                fit(font, word, eject));
    }

    /** The size of a font pixel, in model pixels, at which a word fills its button face as far as it may. */
    private static float fit(Font font, Component word, PrdShape.Box box) {
        float across = (box.x2() - box.x1()) * WORD_ACROSS / Math.max(1, font.width(word));
        float down = (box.y2() - box.y1()) * WORD_DOWN / 7;
        return Math.min(across, down);
    }

    /** A word written in the middle of a button's front face, {@code depth} pushed in. */
    private static void engrave(PoseStack poseStack, MultiBufferSource buffer, int light, Font font, Component word,
                                PrdShape.Box box, float depth, float size) {
        float x = (box.x1() + box.x2()) / 2;
        float y = (box.y1() + box.y2()) / 2;
        float z = box.z2() - depth + 0.01F;
        Matrix4f matrix = new Matrix4f(poseStack.last().pose())
                .translate(x / 16, y / 16, z / 16)
                .scale(size / 16, -size / 16, size / 16);
        font.drawInBatch(word, -font.width(word) / 2F, -3.5F, ENGRAVED, false, matrix, buffer, Font.DisplayMode.NORMAL, 0, light);
    }

    // ---- colours ----

    private static final int BACKGROUND = 0xFF0B0F10;
    private static final int GRID = overGlass(0x30FFFFFF);
    private static final int GRID_ORIGIN = overGlass(0x55FFFFFF);
    private static final int AMBER = 0xFFF2B742;
    private static final int AMBER_DIM = overGlass(0x40F2B742);
    private static final int WHITE = 0xFFE8ECEA;
    private static final int DIM = 0xFF6A6D75;
    private static final int RED = 0xFFE05A4A;
    private static final int NORTH = 0xFFE04848;
    private static final int BAR = 0xFF15181B;
    private static final int BAR_EDGE = 0xFF2A2E33;
    private static final int TAB_ON = 0xFF3A2E12;
    private static final int ROW_SELECTED = 0xFF5A4614;
    private static final int ROW_HOVERED = 0xFF2B2F37;
    private static final int ROW_EVEN = 0xFF16181C;
    private static final int ROW_ODD = 0xFF1C1F24;
    /** The size of a reading's dot, in the map screen's pixels: a little bigger than on the screen, as the glass is small. */
    private static final double DOT = 4;

    /** A see-through colour made solid: the colour it shows over the dark glass. */
    static int overGlass(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255F;
        int r = Math.round(((argb >> 16) & 0xFF) * a + ((BACKGROUND >> 16) & 0xFF) * (1 - a));
        int g = Math.round(((argb >> 8) & 0xFF) * a + ((BACKGROUND >> 8) & 0xFF) * (1 - a));
        int b = Math.round((argb & 0xFF) * a + (BACKGROUND & 0xFF) * (1 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---- carried: the live map ----

    private static void drawCarried(Canvas canvas, ItemStack stack, LivingEntity holder, float partialTick) {
        double x = Mth.lerp(partialTick, holder.xo, holder.getX());
        double z = Mth.lerp(partialTick, holder.zo, holder.getZ());
        float facing = holder.getViewYRot(partialTick);
        drawMap(canvas, PrdItem.records(stack), holder, partialTick, new LoggerDisplay.View(x, z, PrdSession.zoom()), facing,
                -1, -1, false);
    }

    /**
     * The map: the grid lined up with the world, the readings, the holder's arrow, north, and the zoom. {@code framed} while
     * it is being worked, when the tabs and the strip cover the top and bottom of the glass, and the zoom sits above the strip.
     */
    private static void drawMap(Canvas canvas, Logbook records, LivingEntity holder, float partialTick, LoggerDisplay.View view,
                                float yaw, int selected, int hovered, boolean framed) {
        double holderX = Mth.lerp(partialTick, holder.xo, holder.getX());
        double holderZ = Mth.lerp(partialTick, holder.zo, holder.getZ());
        float facing = holder.getViewYRot(partialTick);
        String dimension = holder.level().dimension().location().toString();
        canvas.rect(PrdShape.SCREEN_X1, PrdShape.SCREEN_Y1, PrdShape.SCREEN_X2, PrdShape.SCREEN_Y2, BACKGROUND, 0);

        double k = PrdDisplay.pixelsPerBlock(view);
        int spacing = LoggerDisplay.gridSpacing(view);
        double reach = PrdShape.GLASS_WIDTH * 0.75 / k;
        boolean tiers = TierOverlay.shows(holder.level());
        if (tiers) {
            if (holder == Minecraft.getInstance().player) {
                TierOverlay.want("portable_record_display", view.centreX(), view.centreZ(), reach);
            }
            drawTiers(canvas, view, yaw, reach);
        }
        for (long gx = (long) Math.ceil((view.centreX() - reach) / spacing) * spacing, n = 0;
             gx <= view.centreX() + reach && n < 128; gx += spacing, n++) {
            canvas.line(view, yaw, gx, view.centreZ() - reach, gx, view.centreZ() + reach, gx == 0 ? GRID_ORIGIN : GRID, 1);
        }
        for (long gz = (long) Math.ceil((view.centreZ() - reach) / spacing) * spacing, n = 0;
             gz <= view.centreZ() + reach && n < 128; gz += spacing, n++) {
            canvas.line(view, yaw, view.centreX() - reach, gz, view.centreX() + reach, gz, gz == 0 ? GRID_ORIGIN : GRID, 1);
        }

        // The readings: every blur first, then every dot.
        List<LogbookEntry> entries = records.entries();
        for (LogbookEntry entry : entries) {
            ReaderReading reading = entry.reading();
            if (!reading.dimension().equals(dimension)) {
                continue;
            }
            double blur = LoggerDisplay.blurBlocks(reading.confidence());
            if (blur * k <= DOT / 2) {
                continue;
            }
            double x = reading.x() + 0.5;
            double z = reading.z() + 0.5;
            canvas.world(view, yaw, new double[][]{{x - blur, z - blur}, {x - blur, z + blur}, {x + blur, z + blur}, {x + blur, z - blur}},
                    overGlass((ReadingOres.colour(reading.ore()) & 0x00FFFFFF) | 0x1C000000), 2);
        }
        for (LogbookEntry entry : entries) {
            ReaderReading reading = entry.reading();
            if (!reading.dimension().equals(dimension)) {
                continue;
            }
            double[] at = PrdDisplay.toScreen(view, yaw, reading.x() + 0.5, reading.z() + 0.5);
            int colour = ReadingOres.colour(reading.ore()) | 0xFF000000;
            if (!reading.hasHeight()) {
                colour = overGlass((colour & 0x00FFFFFF) | 0x99000000);
            }
            canvas.square(at, DOT / 2, colour, 3);
            if (entry.number() == selected) {
                canvas.ring(at, DOT / 2 + 2.5, 1.2, AMBER, 4);
            } else if (entry.number() == hovered) {
                canvas.ring(at, DOT / 2 + 1.5, 0.8, WHITE, 4);
            }
        }
        if (tiers) {
            for (TierMapPayloads.Marker marker : TierOverlay.markers()) {
                double[] m = PrdDisplay.toScreen(view, yaw, marker.x() + 0.5, marker.z() + 0.5);
                double size = marker.kind() == 1 ? 5.5 : 4.5;
                canvas.map(diamond(m, size + 1.5), TierOverlay.MARKER_OUTLINE, 3.4F);
                canvas.map(diamond(m, size), TierOverlay.MARKER, 3.6F);
            }
            drawLegend(canvas, framed);
        }

        // Whoever holds it: a white arrow, pointing the way they face.
        double[] at = PrdDisplay.toScreen(view, yaw, holderX, holderZ);
        double[] forward = PrdDisplay.forward(facing);
        double[] d = PrdDisplay.turn(yaw, forward[0], forward[1]);
        double px = -d[1];
        double py = d[0];
        canvas.map(new double[][]{
                {at[0] + d[0] * 6, at[1] + d[1] * 6},
                {at[0] - d[0] * 4 + px * 4.5, at[1] - d[1] * 4 + py * 4.5},
                {at[0] - d[0] * 1.5, at[1] - d[1] * 1.5},
                {at[0] - d[0] * 4 - px * 4.5, at[1] - d[1] * 4 - py * 4.5}}, WHITE, 5);

        // North: a red marker at the edge of what shows (inside the tabs and strip, when framed), due north of the arrow.
        double[] north = PrdDisplay.turn(yaw, 0, -1);
        double margin = 5;
        double top = -PrdShape.GLASS_HEIGHT / 2.0 + margin + (framed ? PrdShape.TAB_HEIGHT : 0);
        double bottom = PrdShape.GLASS_HEIGHT / 2.0 - margin - (framed ? PrdShape.STRIP_HEIGHT : 0);
        double[] mark = PrdDisplay.edgeAlong(at[0], at[1], north[0], north[1], -PrdShape.GLASS_WIDTH / 2.0 + margin, top,
                PrdShape.GLASS_WIDTH / 2.0 - margin, bottom);
        canvas.square(mark, 3, NORTH, 5);

        // The zoom, as a row of pips along the bottom from the left.
        float pipY = PrdShape.GLASS_HEIGHT - 5 - (framed ? PrdShape.STRIP_HEIGHT : 0);
        for (int i = 0; i <= LoggerDisplay.MAX_ZOOM; i++) {
            float x = 5 + i * 4.5F;
            canvas.page(x, pipY, x + 2.5F, pipY + 2.5F, i <= view.zoom() ? AMBER : AMBER_DIM, 5);
        }
    }

    // ---- being worked: the pages ----

    /** A diamond (a square on its corner) about a point on the map, {@code r} map pixels to each point. */
    private static double[][] diamond(double[] at, double r) {
        return new double[][]{{at[0], at[1] - r}, {at[0] + r, at[1]}, {at[0], at[1] + r}, {at[0] - r, at[1]}};
    }

    /**
     * The tier overlay: every tinted chunk column within {@code reach} blocks of the map's middle, a very faint fill and an
     * outline where it meets a lower tier, under the grid. Made solid over the glass, like every faint thing here.
     */
    private static void drawTiers(Canvas canvas, LoggerDisplay.View view, float yaw, double reach) {
        int minX = (int) Math.floor(view.centreX() - reach) >> 4;
        int maxX = (int) Math.floor(view.centreX() + reach) >> 4;
        int minZ = (int) Math.floor(view.centreZ() - reach) >> 4;
        int maxZ = (int) Math.floor(view.centreZ() + reach) >> 4;
        TierOverlay.visit(minX, minZ, maxX, maxZ, (cx, cz, tier, edges) -> {
            double x1 = cx * 16.0;
            double z1 = cz * 16.0;
            double x2 = x1 + 16;
            double z2 = z1 + 16;
            canvas.world(view, yaw, new double[][]{{x1, z1}, {x1, z2}, {x2, z2}, {x2, z1}}, overGlass(TierOverlay.fill(tier)), 0.4F);
            // Outlines above the grid (layer 1), so one on a grid line still shows; the blurs are at 2.
            int edge = overGlass(TierOverlay.edge(tier));
            if ((edges & TierGrid.NORTH) != 0) {
                canvas.line(view, yaw, x1, z1, x2, z1, edge, 1.5F);
            }
            if ((edges & TierGrid.SOUTH) != 0) {
                canvas.line(view, yaw, x1, z2, x2, z2, edge, 1.5F);
            }
            if ((edges & TierGrid.WEST) != 0) {
                canvas.line(view, yaw, x1, z1, x1, z2, edge, 1.5F);
            }
            if ((edges & TierGrid.EAST) != 0) {
                canvas.line(view, yaw, x2, z1, x2, z2, edge, 1.5F);
            }
        });
    }

    /** How big the legend's lettering is: a font pixel, in glass pixels. */
    private static final float LEGEND_TEXT = 0.6F;

    /** The legend, in the top right (below the tabs, while it is being worked): the tinted tiers and the incursion marker. */
    private static void drawLegend(Canvas canvas, boolean framed) {
        Font font = canvas.font();
        int rows = TierOverlay.LEGEND_KEYS.length;
        float row = 10 * LEGEND_TEXT;
        float widest = 0;
        for (int i = 0; i < rows; i++) {
            widest = Math.max(widest, font.width(TierOverlay.legendName(i)) * LEGEND_TEXT);
        }
        float width = widest + 10;
        float right = PrdShape.GLASS_WIDTH - 2;
        float left = right - width;
        float top = 2 + (framed ? PrdShape.TAB_HEIGHT : 0);
        canvas.page(left, top, right, top + rows * row + 2, overGlass(0xD0000000), 6);
        for (int i = 0; i < rows; i++) {
            float y = top + 1 + i * row;
            if (i < rows - 1) {
                canvas.page(left + 2, y + 1.2F, left + 5.5F, y + 4.7F, TierOverlay.legendColour(i), 7);
            } else {
                float cx = left + 3.75F;
                float cy = y + 2.95F;
                canvas.page(cx - 1.2F, cy - 1.2F, cx + 1.2F, cy + 1.2F, TierOverlay.MARKER, 7);
                canvas.page(cx - 0.5F, cy - 1.8F, cx + 0.5F, cy + 1.8F, TierOverlay.MARKER, 7);
                canvas.page(cx - 1.8F, cy - 0.5F, cx + 1.8F, cy + 0.5F, TierOverlay.MARKER, 7);
            }
            canvas.text(TierOverlay.legendName(i), left + 7.5F, y + 0.6F, WHITE, LEGEND_TEXT);
        }
    }

    private static void drawWorking(Canvas canvas, ItemStack stack, LivingEntity holder, float partialTick) {
        Logbook records = PrdItem.records(stack);
        // Turned with the player, so the arrow always points up and the map (north and all) turns under it.
        float facing = holder.getViewYRot(partialTick);
        LoggerDisplay.View view = PrdSession.following()
                ? new LoggerDisplay.View(Mth.lerp(partialTick, holder.xo, holder.getX()), Mth.lerp(partialTick, holder.zo, holder.getZ()),
                PrdSession.zoom())
                : PrdSession.view();
        if (PrdSession.page() == PrdSession.Page.MAP) {
            drawMap(canvas, records, holder, partialTick, view, facing, PrdSession.selected(), PrdSession.hovered(), true);
        } else {
            drawRecords(canvas, records);
        }
        drawTabs(canvas, records);
        drawStrip(canvas, records);
    }

    private static void drawTabs(Canvas canvas, Logbook records) {
        canvas.page(0, 0, PrdShape.GLASS_WIDTH, PrdShape.TAB_HEIGHT, BAR, 6);
        canvas.page(0, PrdShape.TAB_HEIGHT - 1, PrdShape.GLASS_WIDTH, PrdShape.TAB_HEIGHT, BAR_EDGE, 7);
        for (PrdSession.Page page : PrdSession.Page.values()) {
            int[] tab = PrdGlass.tab(page);
            boolean on = page == PrdSession.page();
            if (on) {
                canvas.page(tab[0], 0, tab[1], PrdShape.TAB_HEIGHT, TAB_ON, 7);
            }
            canvas.text(PrdGlass.label(page), tab[0] + PrdGlass.TAB_PAD, 2, on ? AMBER : DIM);
        }
        String count = records.size() + "/" + records.capacity();
        canvas.text(Component.literal(count), PrdShape.GLASS_WIDTH - 3 - canvas.font().width(count), 2, records.isFull() ? RED : WHITE);
    }

    private static void drawRecords(Canvas canvas, Logbook records) {
        canvas.rect(PrdShape.SCREEN_X1, PrdShape.SCREEN_Y1, PrdShape.SCREEN_X2, PrdShape.SCREEN_Y2, BACKGROUND, 0);
        Font font = canvas.font();
        int scroll = PrdSession.scroll();
        int top = PrdShape.TAB_HEIGHT + 1;
        if (records.size() == 0) {
            Component empty = Component.translatable("gui.engines_and_empires.portable_record_display.empty");
            canvas.text(empty, (PrdShape.GLASS_WIDTH - font.width(empty)) / 2F, top + 30, DIM);
            return;
        }
        boolean scrolls = records.size() > PrdShape.ROWS;
        int right = PrdShape.GLASS_WIDTH - (scrolls ? 5 : 2);
        for (int row = 0; row < PrdShape.ROWS; row++) {
            int index = scroll + row;
            if (!records.has(index)) {
                break;
            }
            LogbookEntry entry = records.get(index);
            ReaderReading reading = entry.reading();
            int y = top + row * PrdShape.ROW_HEIGHT;
            int background = entry.number() == PrdSession.selected() ? ROW_SELECTED
                    : entry.number() == PrdSession.hovered() ? ROW_HOVERED : index % 2 == 0 ? ROW_EVEN : ROW_ODD;
            canvas.page(2, y, right, y + PrdShape.ROW_HEIGHT, background, 1);
            canvas.page(4, y + 2, 9, y + 7, ReadingOres.colour(reading.ore()) | 0xFF000000, 2);
            String where = font.plainSubstrByWidth(ReadingBoardScreen.describe(reading).getString(), 64);
            int whereWidth = font.width(where);
            canvas.text(Component.literal(where), right - 2 - whereWidth, y + 1, AMBER);
            String name = font.plainSubstrByWidth(PrdGlass.name(entry), right - 2 - whereWidth - 4 - 12);
            canvas.text(Component.literal(name), 12, y + 1, entry.number() == PrdSession.selected() ? WHITE : AMBER);
        }
        if (scrolls) {
            float track = PrdShape.ROWS * PrdShape.ROW_HEIGHT;
            float thumb = Math.max(4, track * PrdShape.ROWS / records.size());
            float thumbTop = top + (track - thumb) * scroll / Math.max(1, records.size() - PrdShape.ROWS);
            canvas.page(PrdShape.GLASS_WIDTH - 4, top, PrdShape.GLASS_WIDTH - 2, top + track, ROW_EVEN, 1);
            canvas.page(PrdShape.GLASS_WIDTH - 4, thumbTop, PrdShape.GLASS_WIDTH - 2, thumbTop + thumb, AMBER_DIM, 2);
        }
    }

    /** The strip along the bottom: a name being typed, a message, the reading under the cursor or selected, or a hint. */
    private static void drawStrip(Canvas canvas, Logbook records) {
        int top = PrdShape.GLASS_HEIGHT - PrdShape.STRIP_HEIGHT;
        canvas.page(0, top, PrdShape.GLASS_WIDTH, PrdShape.GLASS_HEIGHT, BAR, 6);
        canvas.page(0, top, PrdShape.GLASS_WIDTH, top + 1, BAR_EDGE, 7);
        Font font = canvas.font();
        int line1 = top + 2;
        int line2 = top + 11;
        int width = PrdShape.GLASS_WIDTH - 6;
        if (PrdSession.typing()) {
            boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
            String shown = PrdSession.typed() + (blink ? "_" : " ");
            String fitted = font.plainSubstrByWidth(shown, width - font.width("Name: "), true);
            canvas.text(Component.translatable("gui.engines_and_empires.portable_record_display.name_prompt", fitted), 3, line1, WHITE);
            canvas.text(Component.translatable("gui.engines_and_empires.portable_record_display.name_help"), 3, line2, DIM);
            return;
        }
        Component notice = PrdSession.notice();
        if (notice != null) {
            canvas.text(Component.literal(font.plainSubstrByWidth(notice.getString(), width)), 3, line1, AMBER);
            return;
        }
        int number = PrdSession.hovered() >= 0 ? PrdSession.hovered() : PrdSession.selected();
        int row = number < 0 ? -1 : records.indexOfNumber(number);
        if (row < 0) {
            String hint = PrdSession.page() == PrdSession.Page.MAP ? "gui.engines_and_empires.portable_record_display.select_hint"
                    : "gui.engines_and_empires.portable_record_display.records_hint";
            canvas.text(Component.literal(font.plainSubstrByWidth(Component.translatable(hint).getString(), width)), 3, line1 + 4, DIM);
            return;
        }
        LogbookEntry entry = records.get(row);
        ReaderReading reading = entry.reading();
        canvas.page(3, line1 + 1, 8, line1 + 6, ReadingOres.colour(reading.ore()) | 0xFF000000, 8);
        String oreName = ReadingOres.colouredName(reading.ore()).getString();
        int oreWidth = font.width(oreName);
        canvas.text(Component.literal(oreName), PrdShape.GLASS_WIDTH - 3 - oreWidth, line1, DIM);
        canvas.text(Component.literal(font.plainSubstrByWidth(PrdGlass.name(entry), width - 10 - oreWidth - 4)), 11, line1,
                number == PrdSession.selected() ? AMBER : WHITE);
        String at = reading.x() + ", " + (reading.hasHeight() ? String.valueOf(reading.y()) : "?") + ", " + reading.z();
        String where = font.plainSubstrByWidth(ReadingBoardScreen.describe(reading).getString(), 70);
        canvas.text(Component.literal(where), PrdShape.GLASS_WIDTH - 3 - font.width(where), line2, AMBER);
        canvas.text(Component.literal(font.plainSubstrByWidth(at, width - font.width(where) - 4)), 3, line2, WHITE);
    }

    // ---- the lamps ----

    private static final int[] LAMP_ON = {0xFFFF4A3A, 0xFFFFD23A, 0xFF5CFF7A};

    private static void drawLamps(Canvas canvas, PrdSignal.Light lit) {
        int lamp = switch (lit) {
            case RED -> 0;
            case YELLOW -> 1;
            case GREEN -> 2;
            case NONE -> -1;
        };
        if (lamp < 0) {
            return;
        }
        canvas.cube(PrdShape.lamp(lamp), 0.06F, LAMP_ON[lamp]);
    }

    // ---- drawing, in model pixels ----

    /**
     * Flat shapes and text on the glass, and boxes, in plain colour at full brightness. Shapes on the glass are given in the
     * map screen's pixels about its middle ({@code x} right, {@code y} down, as {@link PrdDisplay} works), in glass pixels
     * from its top left ({@link #page}, {@link #text}), or in model pixels, and are cut to the glass.
     */
    private record Canvas(PoseStack.Pose pose, MultiBufferSource buffers) {

        /** How far above the glass the map is drawn, and each layer above the last, so none share a plane. */
        private static final float LIFT = 0.012F;
        private static final float LAYER = 0.004F;
        /** Text sits above every shape. */
        private static final int TEXT_LAYER = 10;

        /**
         * Where plain shapes go. Asked for afresh for every shape: drawing text in between switches the buffer source to the
         * font's buffer, which finishes this one, and a finished one cannot be written to.
         */
        private VertexConsumer out() {
            return buffers.getBuffer(RenderType.textBackground());
        }

        Font font() {
            return Minecraft.getInstance().font;
        }

        /** A shape given in world (X, Z). */
        void world(LoggerDisplay.View view, double yaw, double[][] points, int argb, float layer) {
            double[][] map = new double[points.length][];
            for (int i = 0; i < points.length; i++) {
                map[i] = PrdDisplay.toScreen(view, yaw, points[i][0], points[i][1]);
            }
            map(map, argb, layer);
        }

        /** A line one map pixel wide between two world points. */
        void line(LoggerDisplay.View view, double yaw, double ax, double az, double bx, double bz, int argb, float layer) {
            double[] a = PrdDisplay.toScreen(view, yaw, ax, az);
            double[] b = PrdDisplay.toScreen(view, yaw, bx, bz);
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            double length = Math.sqrt(dx * dx + dy * dy);
            if (length < 1e-3) {
                return;
            }
            double nx = -dy / length * 0.5;
            double ny = dx / length * 0.5;
            map(new double[][]{{a[0] + nx, a[1] + ny}, {b[0] + nx, b[1] + ny}, {b[0] - nx, b[1] - ny}, {a[0] - nx, a[1] - ny}}, argb, layer);
        }

        /** A square, {@code half} map pixels either side of a point on the map. */
        void square(double[] at, double half, int argb, int layer) {
            map(new double[][]{{at[0] - half, at[1] - half}, {at[0] + half, at[1] - half}, {at[0] + half, at[1] + half},
                    {at[0] - half, at[1] + half}}, argb, layer);
        }

        /** A square ring round a point on the map. */
        void ring(double[] at, double ring, double edge, int argb, int layer) {
            double x = at[0];
            double y = at[1];
            map(new double[][]{{x - ring, y - ring}, {x + ring, y - ring}, {x + ring, y - ring + edge}, {x - ring, y - ring + edge}}, argb, layer);
            map(new double[][]{{x - ring, y + ring - edge}, {x + ring, y + ring - edge}, {x + ring, y + ring}, {x - ring, y + ring}}, argb, layer);
            map(new double[][]{{x - ring, y - ring}, {x - ring + edge, y - ring}, {x - ring + edge, y + ring}, {x - ring, y + ring}}, argb, layer);
            map(new double[][]{{x + ring - edge, y - ring}, {x + ring, y - ring}, {x + ring, y + ring}, {x + ring - edge, y + ring}}, argb, layer);
        }

        /** A shape given in map pixels about the middle of the glass. */
        void map(double[][] points, int argb, float layer) {
            float scale = 1 / PrdShape.MAP_PIXELS_PER_MODEL_PIXEL;
            List<float[]> model = new ArrayList<>(points.length);
            for (double[] p : points) {
                model.add(new float[]{PrdShape.screenCentreX() + (float) p[0] * scale, PrdShape.screenCentreY() - (float) p[1] * scale});
            }
            glass(model, argb, layer);
        }

        /** A rectangle in glass pixels, from the glass's top left. */
        void page(float x1, float y1, float x2, float y2, int argb, int layer) {
            float scale = 1 / PrdShape.MAP_PIXELS_PER_MODEL_PIXEL;
            rect(PrdShape.SCREEN_X1 + x1 * scale, PrdShape.SCREEN_Y2 - y2 * scale, PrdShape.SCREEN_X1 + x2 * scale,
                    PrdShape.SCREEN_Y2 - y1 * scale, argb, layer);
        }

        /** A rectangle in model pixels. */
        void rect(float x1, float y1, float x2, float y2, int argb, int layer) {
            glass(new ArrayList<>(List.of(new float[]{x1, y1}, new float[]{x2, y1}, new float[]{x2, y2}, new float[]{x1, y2})), argb, layer);
        }

        /**
         * A line of text, its top left at glass pixel ({@code x}, {@code y}), one font pixel to a glass pixel. The glass's
         * down is the font's down, as for a sign: flipping y also keeps the glyphs facing out.
         */
        void text(Component text, float x, float y, int argb) {
            text(text, x, y, argb, 1.0F);
        }

        /** A line of text, {@code size} glass pixels to a font pixel. */
        void text(Component text, float x, float y, int argb, float size) {
            float scale = 1 / PrdShape.MAP_PIXELS_PER_MODEL_PIXEL;
            Matrix4f matrix = new Matrix4f(pose.pose())
                    .translate(PrdShape.SCREEN_X1, PrdShape.SCREEN_Y2, PrdShape.SCREEN_Z + LIFT + TEXT_LAYER * LAYER)
                    .scale(scale, -scale, scale)
                    .translate(x, y, 0)
                    .scale(size, size, 1);
            font().drawInBatch(text, 0, 0, argb, false, matrix, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        }

        /** A shape in model pixels, cut to the glass and drawn just above it, facing out. */
        private void glass(List<float[]> points, int argb, float layer) {
            points = clip(points, 0, PrdShape.SCREEN_X1, false);
            points = clip(points, 0, PrdShape.SCREEN_X2, true);
            points = clip(points, 1, PrdShape.SCREEN_Y1, false);
            points = clip(points, 1, PrdShape.SCREEN_Y2, true);
            int n = points.size();
            if (n < 3) {
                return;
            }
            // Anticlockwise, seen from the front, or it faces away and is culled. The first point is kept first: a shape is
            // drawn as a fan from it, which the arrow needs (its tip sees every other corner).
            double area = 0;
            for (int i = 0; i < n; i++) {
                float[] p = points.get(i);
                float[] q = points.get((i + 1) % n);
                area += p[0] * q[1] - q[0] * p[1];
            }
            if (area < 0) {
                List<float[]> turned = new ArrayList<>(n);
                turned.add(points.get(0));
                for (int i = n - 1; i >= 1; i--) {
                    turned.add(points.get(i));
                }
                points = turned;
            }
            float z = PrdShape.SCREEN_Z + LIFT + layer * LAYER;
            Matrix4f matrix = pose.pose();
            VertexConsumer out = out();
            for (int i = 1; i + 1 < n; i += 2) {
                float[] a = points.get(0);
                float[] b = points.get(i);
                float[] c = points.get(i + 1);
                float[] d = i + 2 < n ? points.get(i + 2) : c;
                vertex(out, matrix, a[0], a[1], z, argb);
                vertex(out, matrix, b[0], b[1], z, argb);
                vertex(out, matrix, c[0], c[1], z, argb);
                vertex(out, matrix, d[0], d[1], z, argb);
            }
        }

        /** Cuts a shape to one side of a line {@code x = at} ({@code axis} 0) or {@code y = at} (1): below it, or above. */
        private static List<float[]> clip(List<float[]> points, int axis, float at, boolean keepBelow) {
            List<float[]> result = new ArrayList<>(points.size() + 2);
            int n = points.size();
            for (int i = 0; i < n; i++) {
                float[] p = points.get(i);
                float[] q = points.get((i + 1) % n);
                boolean pIn = keepBelow ? p[axis] <= at : p[axis] >= at;
                boolean qIn = keepBelow ? q[axis] <= at : q[axis] >= at;
                if (pIn) {
                    result.add(p);
                }
                if (pIn != qIn) {
                    float t = (at - p[axis]) / (q[axis] - p[axis]);
                    result.add(new float[]{p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t});
                }
            }
            return result;
        }

        /** A box {@code grow} model pixels bigger all round than {x1, y1, z1, x2, y2, z2}, every face facing out. */
        void cube(float[] box, float grow, int argb) {
            float x1 = box[0] - grow;
            float y1 = box[1] - grow;
            float z1 = box[2] - grow;
            float x2 = box[3] + grow;
            float y2 = box[4] + grow;
            float z2 = box[5] + grow;
            Matrix4f m = pose.pose();
            VertexConsumer out = out();
            // South (+z), north (-z), east (+x), west (-x), up (+y), down (-y): each anticlockwise seen from outside.
            quad(out, m, argb, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2);
            quad(out, m, argb, x2, y1, z1, x1, y1, z1, x1, y2, z1, x2, y2, z1);
            quad(out, m, argb, x2, y1, z2, x2, y1, z1, x2, y2, z1, x2, y2, z2);
            quad(out, m, argb, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1);
            quad(out, m, argb, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1);
            quad(out, m, argb, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
        }

        private static void quad(VertexConsumer out, Matrix4f m, int argb, float... corners) {
            for (int i = 0; i < 12; i += 3) {
                vertex(out, m, corners[i], corners[i + 1], corners[i + 2], argb);
            }
        }

        private static void vertex(VertexConsumer out, Matrix4f matrix, float x, float y, float z, int argb) {
            out.addVertex(matrix, x, y, z).setColor(argb).setLight(LightTexture.FULL_BRIGHT);
        }
    }
}
