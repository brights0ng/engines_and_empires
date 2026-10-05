package dev.brights0ng.enginesandempires.geophone.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.LogbookEntry;
import dev.brights0ng.enginesandempires.geophone.LoggerDisplay;
import dev.brights0ng.enginesandempires.geophone.LoggerMapInput;
import dev.brights0ng.enginesandempires.geophone.LoggerMapPayloads;
import dev.brights0ng.enginesandempires.geophone.ReaderReading;
import dev.brights0ng.enginesandempires.geophone.SmartLoggerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Working a smart logger's map, on the client, where the pointer is known:
 * <ul>
 *   <li><b>Right-click</b> a reading's dot to select it; right-click it again to send it to a docked portable record display,
 *       or, with none docked, to unselect it. Right-clicking empty map clears the selection. The selection is this player's
 *       own, and is never sent anywhere.</li>
 *   <li><b>Sneak + right-click</b> anywhere on the map recentres it on the logger, keeping the zoom.</li>
 *   <li><b>Left-click and drag</b> pans the map: the spot grabbed follows the crosshair. While the display is on, left-clicking
 *       the map never starts breaking the logger.</li>
 *   <li><b>Scroll</b> zooms in or out about the spot under the crosshair, instead of changing the hotbar slot.</li>
 * </ul>
 * The view is shared by everyone looking at the logger, so every change to it is sent to the server
 * ({@link LoggerMapPayloads}); it is shown straight away here, without waiting for the server's copy to come back.
 *
 * <p>Only ever loaded on a client.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class SmartLoggerControls {

    /** This player's selection on each logger, by where it is: the selected entry's number. */
    private static final Map<String, Integer> SELECTED = new HashMap<>();

    /** The drag in progress: which logger, and the world point that was grabbed. */
    private static SmartLoggerBlockEntity dragging;
    private static double grabX;
    private static double grabZ;

    /** A view waiting to be sent: at most one message a tick, however fast the frames come. */
    private static SmartLoggerBlockEntity unsent;

    // ---- the selection ----

    private static String key(SmartLoggerBlockEntity logger) {
        return logger.getLevel() == null ? "" : logger.getLevel().dimension().location() + "@" + logger.getBlockPos().asLong();
    }

    /** The number of the entry this player has selected on this logger, or -1 (also if it has since been deleted). */
    public static int selected(SmartLoggerBlockEntity logger) {
        Integer number = SELECTED.get(key(logger));
        if (number == null) {
            return -1;
        }
        if (logger.records().indexOfNumber(number) < 0) {
            SELECTED.remove(key(logger));
            return -1;
        }
        return number;
    }

    /** The entry this player has selected on this logger, or null. */
    public static LogbookEntry selectedEntry(SmartLoggerBlockEntity logger) {
        int number = selected(logger);
        return number < 0 ? null : logger.records().get(logger.records().indexOfNumber(number));
    }

    // ---- where the crosshair is ----

    /** The spot on a logger's map screen the crosshair is on, with its display on, or null. */
    private static LoggerMapInput.Spot aimedAtMap() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        LoggerMapInput.Spot spot = LoggerMapInput.spot(minecraft.level, hit.getBlockPos(), hit.getDirection(), hit.getLocation());
        return spot != null && spot.onScreen() && spot.logger().displayOn() ? spot : null;
    }

    /**
     * Where the player's line of sight meets the plane of a logger's screen, as {u, v}, or null if they are looking away from
     * it. Unlike the crosshair's block, this keeps working past the edges of the screen, so a drag does not stop there.
     */
    private static double[] onScreenPlane(SmartLoggerBlockEntity logger, float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        double planeY = logger.getBlockPos().getY() + LoggerDisplay.SCREEN_Y;
        if (Math.abs(look.y) < 1e-4) {
            return null;
        }
        double t = (planeY - eye.y) / look.y;
        if (t <= 0 || t > 16) {
            return null;
        }
        Vec3 at = eye.add(look.scale(t));
        Vec3 middle = logger.middle();
        return logger.frame().local(middle.x, middle.z, at.x, at.z);
    }

    // ---- changing the view ----

    private static void setView(SmartLoggerBlockEntity logger, LoggerDisplay.View view) {
        logger.setViewLocally(view);
        unsent = logger;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (unsent != null) {
            LoggerDisplay.View view = unsent.view();
            PacketDistributor.sendToServer(new LoggerMapPayloads.View(unsent.getBlockPos(), view.centreX(), view.centreZ(), view.zoom()));
            unsent = null;
        }
    }

    // ---- right-click: select, send, recentre ----

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide) {
            return;
        }
        LoggerMapInput.Spot spot = LoggerMapInput.spot(event.getLevel(), event.getPos(), event.getFace(),
                event.getHitVec().getLocation());
        if (spot == null || !spot.onScreen()) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            return;
        }
        rightClick(spot, event.getEntity().isShiftKeyDown());
    }

    private static void rightClick(LoggerMapInput.Spot spot, boolean sneaking) {
        SmartLoggerBlockEntity logger = spot.logger();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (!logger.displayOn()) {
            player.displayClientMessage(Component.translatable("message.engines_and_empires.smart_logger.display_off"), true);
            return;
        }
        if (sneaking) {
            Vec3 middle = logger.middle();
            setView(logger, LoggerDisplay.recentre(logger.view(), middle.x, middle.z));
            player.playSound(SoundEvents.COMPARATOR_CLICK, 0.4F, 0.8F);
            return;
        }
        List<ReaderReading> readings = logger.records().entries().stream().map(LogbookEntry::reading).toList();
        int index = LoggerDisplay.hovered(logger.view(), logger.frame(), spot.u(), spot.v(), readings,
                player.level().dimension().location().toString());
        String key = key(logger);
        if (index < 0) {
            if (SELECTED.remove(key) != null) {
                player.playSound(SoundEvents.COMPARATOR_CLICK, 0.3F, 0.7F);
            }
            return;
        }
        int number = logger.records().get(index).number();
        if (selected(logger) == number) {
            SELECTED.remove(key);
            if (!logger.docked().isEmpty()) {
                PacketDistributor.sendToServer(new LoggerMapPayloads.Send(logger.getBlockPos(), number));
            } else {
                player.playSound(SoundEvents.COMPARATOR_CLICK, 0.3F, 0.7F);
            }
        } else {
            SELECTED.put(key, number);
            player.playSound(SoundEvents.COMPARATOR_CLICK, 0.4F, 1.3F);
        }
    }

    // ---- left-click: drag, never break ----

    /** A left-click on the map, with its display on, grabs it instead of hitting the block; so does holding it down. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        if (dragging != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        LoggerMapInput.Spot spot = aimedAtMap();
        if (spot == null) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        SmartLoggerBlockEntity logger = spot.logger();
        double[] grabbed = LoggerDisplay.toWorld(logger.view(), logger.frame(), spot.u(), spot.v());
        dragging = logger;
        grabX = grabbed[0];
        grabZ = grabbed[1];
    }

    /** Every frame of a drag, the grabbed spot is moved to where the player now looks. */
    @SubscribeEvent
    static void onRenderFrame(RenderFrameEvent.Pre event) {
        if (dragging == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || !minecraft.options.keyAttack.isDown() || dragging.isRemoved()
                || !dragging.displayOn() || minecraft.level != dragging.getLevel()) {
            dragging = null;
            return;
        }
        double[] at = onScreenPlane(dragging, event.getPartialTick().getGameTimeDeltaPartialTick(true));
        if (at == null) {
            return;
        }
        LoggerDisplay.View view = dragging.view();
        double[] now = LoggerDisplay.toWorld(view, dragging.frame(), at[0], at[1]);
        LoggerDisplay.View moved = LoggerDisplay.drag(view, grabX, grabZ, now[0], now[1]);
        if (Math.abs(moved.centreX() - view.centreX()) > 1e-3 || Math.abs(moved.centreZ() - view.centreZ()) > 1e-3) {
            setView(dragging, moved);
        }
    }

    // ---- scroll: zoom ----

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || event.getScrollDeltaY() == 0) {
            return;
        }
        LoggerMapInput.Spot spot = aimedAtMap();
        if (spot == null) {
            return;
        }
        event.setCanceled(true);
        SmartLoggerBlockEntity logger = spot.logger();
        LoggerDisplay.View view = logger.view();
        double[] under = LoggerDisplay.toWorld(view, logger.frame(), spot.u(), spot.v());
        LoggerDisplay.View zoomed = LoggerDisplay.zoomAbout(view, under[0], under[1], event.getScrollDeltaY() > 0 ? 1 : -1);
        if (!zoomed.equals(view)) {
            setView(logger, zoomed);
            if (minecraft.player != null) {
                minecraft.player.playSound(SoundEvents.COMPARATOR_CLICK, 0.3F, zoomed.zoom() > view.zoom() ? 1.1F : 0.8F);
            }
        }
    }

    private SmartLoggerControls() {
    }
}
