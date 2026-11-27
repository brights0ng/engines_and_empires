// Written by Gemini.
package dev.brights0ng.empire.client;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.record.MotionRecorderNetwork;
import dev.brights0ng.empire.record.MotionTraceJson;
import dev.brights0ng.empire.record.MotionTraceJson.SummaryTracker;
import dev.brights0ng.empire.record.MotionTraceJson.TickFrame;
import dev.brights0ng.empire.record.RecordPayloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Client-side player movement, camera, input, and action recorder controlled by {@code /empire debug record ...}.
 * Writes a streaming JSONL trace under {@code <gameDir>/empire-recordings/} combining 20 Hz client telemetry,
 * millisecond-precision mouse/interact/hotbar/screen events, and server-confirmed world mutation events.
 */
public final class MotionRecorderClient {

    private static final String RECORDINGS_DIR = "empire-recordings";
    private static final int FLUSH_INTERVAL_TICKS = 40;

    @Nullable
    private static RecordingSession active;

    private static final class RecordingSession {
        final String label;
        final Path filePath;
        final BufferedWriter writer;
        final long startNanos;
        final SummaryTracker summary = new SummaryTracker();

        int tickSeq;
        int ticksSinceFlush;
        Vec3 prevPos;
        float prevYaw;
        float prevPitch;
        int prevSlot;
        @Nullable
        String prevScreen;
        long screenOpenedMs;
        long lastGameTime;

        RecordingSession(String label, Path filePath, BufferedWriter writer, long startNanos,
                         LocalPlayer player, long gameTime) {
            this.label = label;
            this.filePath = filePath;
            this.writer = writer;
            this.startNanos = startNanos;
            this.prevPos = player.position();
            this.prevYaw = player.getYRot();
            this.prevPitch = player.getXRot();
            this.prevSlot = player.getInventory().selected;
            this.lastGameTime = gameTime;
        }

        long elapsedMs() {
            return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        }
    }

    /** Registers client payload hooks and NeoForge client event listeners. */
    public static void register() {
        MotionRecorderNetwork.registerClientHandlers(
                MotionRecorderClient::onControl,
                MotionRecorderClient::onServerEvent);
        NeoForge.EVENT_BUS.addListener(MotionRecorderClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(MotionRecorderClient::onMouseButton);
        NeoForge.EVENT_BUS.addListener(MotionRecorderClient::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(MotionRecorderClient::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(MotionRecorderClient::onRenderGui);
        NeoForge.EVENT_BUS.addListener(MotionRecorderClient::onLoggingOut);
    }

    private static void onControl(RecordPayloads.Control payload) {
        switch (payload.action()) {
            case START -> startSession(payload.arg());
            case STOP -> stopSession("command", true);
            case STATUS -> reportStatus();
            case MARK -> recordMarker(payload.arg());
        }
    }

    private static void onServerEvent(RecordPayloads.ServerEvent payload) {
        RecordingSession session = active;
        if (session == null) {
            return;
        }
        session.lastGameTime = payload.gameTime();
        writeEvent(session, payload.gameTime(), "server", payload.kind(), payload.payloadJson());
    }

    private static void startSession(String rawLabel) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        if (active != null) {
            stopSession("restarted", true);
        }

        String label = MotionTraceJson.sanitizeLabel(rawLabel);
        Instant now = Instant.now();
        Path dir = FMLPaths.GAMEDIR.get().resolve(RECORDINGS_DIR);
        Path file = dir.resolve(MotionTraceJson.buildFilename(now, label));

        try {
            Files.createDirectories(dir);
            BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            RecordingSession session = new RecordingSession(label, file, writer, System.nanoTime(),
                    player, level.getGameTime());

            String gameMode = mc.gameMode != null ? mc.gameMode.getPlayerMode().getName() : "unknown";
            String meta = MotionTraceJson.buildMetaLine(
                    level.getGameTime(),
                    now,
                    label,
                    player.getScoreboardName(),
                    player.getUUID().toString(),
                    level.dimension().location().toString(),
                    gameMode,
                    player.getX(),
                    player.getY(),
                    player.getZ());
            writer.write(meta);
            writer.newLine();
            writer.flush();

            active = session;
            chat(Component.literal("[Empire Record] Started: " + file.getFileName()
                            + " (use /empire debug record stop to finish)")
                    .withStyle(ChatFormatting.GREEN));
        } catch (IOException e) {
            Empire.LOGGER.error("Failed to start motion recording at {}", file, e);
            chat(Component.literal("[Empire Record] Failed to open file: " + e.getMessage())
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static void stopSession(String reason, boolean announce) {
        RecordingSession session = active;
        active = null;
        if (session == null) {
            if (announce) {
                chat(Component.literal("[Empire Record] No active recording.").withStyle(ChatFormatting.YELLOW));
            }
            return;
        }

        long durationMs = session.elapsedMs();
        Minecraft mc = Minecraft.getInstance();
        long gt = mc.level != null ? mc.level.getGameTime() : session.lastGameTime;

        if (session.prevScreen != null) {
            long openDuration = Math.max(0L, durationMs - session.screenOpenedMs);
            String closeJson = "{\"screen\":" + MotionTraceJson.jsonString(session.prevScreen)
                    + ",\"durationMs\":" + openDuration + "}";
            writeEvent(session, gt, "client", "screen_close", closeJson);
            session.prevScreen = null;
        }

        try {
            session.writer.write(session.summary.buildSummaryLine(durationMs, gt, reason));
            session.writer.newLine();
            session.writer.flush();
            session.writer.close();
        } catch (IOException e) {
            Empire.LOGGER.error("Failed to finalize motion recording {}", session.filePath, e);
        }

        if (announce) {
            String msg = String.format(Locale.ROOT,
                    "[Empire Record] Saved %s (%s, %d ticks, %d events, %.1f%% idle, %.1f blocks)",
                    session.filePath.toAbsolutePath(),
                    formatDuration(durationMs),
                    session.summary.ticks(),
                    session.summary.totalEvents(),
                    session.summary.idleFraction() * 100.0,
                    session.summary.horizontalDistance());
            chat(Component.literal(msg).withStyle(ChatFormatting.GREEN));
        }
    }

    private static void reportStatus() {
        RecordingSession session = active;
        if (session == null) {
            chat(Component.literal("[Empire Record] Idle.").withStyle(ChatFormatting.GRAY));
            return;
        }
        String msg = String.format(Locale.ROOT,
                "[Empire Record] Active: %s (%s, %d ticks, %d events)",
                session.filePath.getFileName(),
                formatDuration(session.elapsedMs()),
                session.summary.ticks(),
                session.summary.totalEvents());
        chat(Component.literal(msg).withStyle(ChatFormatting.AQUA));
    }

    private static void recordMarker(String note) {
        RecordingSession session = active;
        if (session == null) {
            chat(Component.literal("[Empire Record] No active recording to mark.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        long gt = mc.level != null ? mc.level.getGameTime() : session.lastGameTime;
        writeEvent(session, gt, "client", "marker", "{\"note\":" + MotionTraceJson.jsonString(note) + "}");
        chat(Component.literal("[Empire Record] Marker: " + note).withStyle(ChatFormatting.AQUA));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        RecordingSession session = active;
        if (session == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.isPaused()) {
            return;
        }

        long ms = session.elapsedMs();
        long gt = level.getGameTime();
        session.lastGameTime = gt;

        // Track hotbar switches
        int slot = player.getInventory().selected;
        ItemStack held = player.getMainHandItem();
        String heldId = itemId(held);
        if (slot != session.prevSlot) {
            String json = "{\"from\":" + session.prevSlot
                    + ",\"to\":" + slot
                    + ",\"item\":" + MotionTraceJson.jsonString(heldId)
                    + ",\"count\":" + held.getCount() + "}";
            writeEvent(session, gt, "client", "hotbar_switch", json);
            session.prevSlot = slot;
        }

        // Track GUI screen open/close transitions (chests, inventory, pause, etc.)
        String screenName = describeScreen(mc.screen);
        if (screenName != null && !screenName.equals(session.prevScreen)) {
            if (session.prevScreen != null) {
                long openDur = Math.max(0L, ms - session.screenOpenedMs);
                writeEvent(session, gt, "client", "screen_close",
                        "{\"screen\":" + MotionTraceJson.jsonString(session.prevScreen)
                                + ",\"durationMs\":" + openDur + "}");
            }
            session.prevScreen = screenName;
            session.screenOpenedMs = ms;
            String title = mc.screen != null ? mc.screen.getTitle().getString() : "";
            writeEvent(session, gt, "client", "screen_open",
                    "{\"screen\":" + MotionTraceJson.jsonString(screenName)
                            + ",\"title\":" + MotionTraceJson.jsonString(title) + "}");
        } else if (screenName == null && session.prevScreen != null) {
            long openDur = Math.max(0L, ms - session.screenOpenedMs);
            writeEvent(session, gt, "client", "screen_close",
                    "{\"screen\":" + MotionTraceJson.jsonString(session.prevScreen)
                            + ",\"durationMs\":" + openDur + "}");
            session.prevScreen = null;
        }

        Vec3 pos = player.position();
        double vx = pos.x - session.prevPos.x;
        double vy = pos.y - session.prevPos.y;
        double vz = pos.z - session.prevPos.z;
        double speedH = Math.hypot(vx, vz);
        session.prevPos = pos;

        float yaw = player.getYRot();
        float pitch = player.getXRot();
        float dyaw = MotionTraceJson.wrapAngleDelta(yaw, session.prevYaw);
        float dpitch = pitch - session.prevPitch;
        session.prevYaw = yaw;
        session.prevPitch = pitch;

        Input input = player.input;
        float forward = input != null ? input.forwardImpulse : 0.0F;
        float strafe = input != null ? input.leftImpulse : 0.0F;
        boolean jumping = input != null && input.jumping;
        boolean sneaking = (input != null && input.shiftKeyDown) || player.isShiftKeyDown();
        boolean sprinting = player.isSprinting();
        boolean onGround = player.onGround();
        boolean atkDown = mc.options.keyAttack.isDown();
        boolean useDown = mc.options.keyUse.isDown();

        String aimJson = describeHitResult(mc.hitResult, level, player);

        TickFrame frame = new TickFrame(
                ms, gt, session.tickSeq++,
                pos.x, pos.y, pos.z,
                vx, vy, vz, speedH,
                yaw, pitch, dyaw, dpitch,
                forward, strafe,
                jumping, sneaking, sprinting, onGround,
                atkDown, useDown,
                slot, heldId, held.getCount(),
                screenName, aimJson);

        session.summary.recordTick(speedH, sprinting, sneaking, jumping, screenName != null);
        writeRawLine(session, MotionTraceJson.buildTickLine(frame));

        session.ticksSinceFlush++;
        if (session.ticksSinceFlush >= FLUSH_INTERVAL_TICKS) {
            session.ticksSinceFlush = 0;
            flushQuietly(session);
        }
    }

    private static void onMouseButton(InputEvent.MouseButton.Post event) {
        RecordingSession session = active;
        if (session == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.screen != null) {
            return;
        }
        int button = event.getButton();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return;
        }
        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) {
            return;
        }
        String btnName = button == GLFW.GLFW_MOUSE_BUTTON_LEFT ? "left" : "right";
        String actName = action == GLFW.GLFW_PRESS ? "press" : "release";
        String aimJson = describeHitResult(mc.hitResult, level, player);
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"button\":").append(MotionTraceJson.jsonString(btnName))
                .append(",\"action\":").append(MotionTraceJson.jsonString(actName));
        if (aimJson != null) {
            sb.append(",\"aim\":").append(aimJson);
        }
        sb.append('}');
        writeEvent(session, level.getGameTime(), "client", "mouse_button", sb.toString());
    }

    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        RecordingSession session = active;
        if (session == null || !event.getLevel().isClientSide()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getEntity() != mc.player) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getLevel().getBlockState(pos);
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"pos\":[").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(']')
                .append(",\"face\":").append(MotionTraceJson.jsonString(
                        event.getFace() != null ? event.getFace().getSerializedName() : ""))
                .append(",\"action\":").append(MotionTraceJson.jsonString(event.getAction().name().toLowerCase(Locale.ROOT)))
                .append(",\"block\":").append(MotionTraceJson.jsonString(blockId(state.getBlock())));
        appendCropFields(sb, state);
        sb.append(",\"item\":").append(MotionTraceJson.jsonString(itemId(event.getItemStack())))
                .append(",\"dist\":").append(MotionTraceJson.fmt(
                        mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)), 3))
                .append('}');
        writeEvent(session, event.getLevel().getGameTime(), "client", "left_click_block", sb.toString());
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        RecordingSession session = active;
        if (session == null || !event.getLevel().isClientSide()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getEntity() != mc.player) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getLevel().getBlockState(pos);
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"pos\":[").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(']')
                .append(",\"face\":").append(MotionTraceJson.jsonString(
                        event.getFace() != null ? event.getFace().getSerializedName() : ""))
                .append(",\"hand\":").append(MotionTraceJson.jsonString(event.getHand().name().toLowerCase(Locale.ROOT)))
                .append(",\"block\":").append(MotionTraceJson.jsonString(blockId(state.getBlock())));
        appendCropFields(sb, state);
        sb.append(",\"item\":").append(MotionTraceJson.jsonString(itemId(event.getItemStack())))
                .append(",\"dist\":").append(MotionTraceJson.fmt(
                        mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)), 3))
                .append('}');
        writeEvent(session, event.getLevel().getGameTime(), "client", "right_click_block", sb.toString());
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        RecordingSession session = active;
        if (session == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.font == null) {
            return;
        }

        long elapsedMs = session.elapsedMs();
        String text = "REC " + formatDuration(elapsedMs)
                + (session.label.isEmpty() ? "" : " [" + session.label + "]");
        int textWidth = mc.font.width(text);
        int x = 8;
        int y = 8;
        int dotSpace = 10;
        int padX = 5;
        int padY = 3;
        int width = padX * 2 + dotSpace + textWidth;
        int height = padY * 2 + mc.font.lineHeight;

        GuiGraphics gfx = event.getGuiGraphics();
        gfx.fill(x, y, x + width, y + height, 0xAA101010);

        boolean dotOn = ((elapsedMs / 500L) & 1L) == 0L;
        int dotColor = dotOn ? 0xFFE53935 : 0xFF6B1E1C;
        int dotX = x + padX;
        int dotY = y + padY + 1;
        gfx.fill(dotX, dotY, dotX + 6, dotY + 6, dotColor);

        gfx.drawString(mc.font, text, x + padX + dotSpace, y + padY, 0xFFF5F5F5, true);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (active != null) {
            stopSession("disconnect", false);
        }
    }

    private static void writeEvent(RecordingSession session, long gameTime, String source, String kind, String dataJson) {
        session.summary.recordEvent(kind);
        writeRawLine(session, MotionTraceJson.buildEventLine(session.elapsedMs(), gameTime, source, kind, dataJson));
    }

    private static void writeRawLine(RecordingSession session, String line) {
        try {
            session.writer.write(line);
            session.writer.newLine();
        } catch (IOException e) {
            Empire.LOGGER.error("Error writing motion trace line to {}", session.filePath, e);
            stopSession("io_error", true);
        }
    }

    private static void flushQuietly(RecordingSession session) {
        try {
            session.writer.flush();
        } catch (IOException e) {
            Empire.LOGGER.error("Error flushing motion trace {}", session.filePath, e);
        }
    }

    @Nullable
    private static String describeHitResult(@Nullable HitResult hit, ClientLevel level, LocalPlayer player) {
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        double dist = player.getEyePosition().distanceTo(hit.getLocation());
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            BlockState state = level.getBlockState(pos);
            StringBuilder sb = new StringBuilder(128);
            sb.append("{\"kind\":\"block\",\"pos\":[").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(']')
                    .append(",\"face\":").append(MotionTraceJson.jsonString(bhr.getDirection().getSerializedName()))
                    .append(",\"block\":").append(MotionTraceJson.jsonString(blockId(state.getBlock())));
            appendCropFields(sb, state);
            sb.append(",\"dist\":").append(MotionTraceJson.fmt(dist, 3)).append('}');
            return sb.toString();
        }
        if (hit instanceof EntityHitResult ehr && hit.getType() == HitResult.Type.ENTITY) {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(ehr.getEntity().getType());
            return "{\"kind\":\"entity\",\"entity\":" + MotionTraceJson.jsonString(key != null ? key.toString() : "")
                    + ",\"dist\":" + MotionTraceJson.fmt(dist, 3) + "}";
        }
        return null;
    }

    private static void appendCropFields(StringBuilder sb, BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            sb.append(",\"cropAge\":").append(crop.getAge(state))
                    .append(",\"mature\":").append(crop.isMaxAge(state));
        }
    }

    @Nullable
    private static String describeScreen(@Nullable Screen screen) {
        return screen != null ? screen.getClass().getSimpleName() : null;
    }

    private static String blockId(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null ? key.toString() : "";
    }

    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null ? key.toString() : "";
    }

    private static String formatDuration(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", min, sec);
    }

    private static void chat(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(message, false);
        }
    }

    private MotionRecorderClient() {
    }
}
