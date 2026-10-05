package dev.brights0ng.enginesandempires.geophone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;

import org.joml.Matrix4f;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.LoggerDisplay;
import dev.brights0ng.enginesandempires.geophone.PrdItem;
import dev.brights0ng.enginesandempires.geophone.PrdShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * How the portable record display is held, on this client.
 *
 * <p>Normally it is carried by its handle, in one hand: that is just the item model's hand views (see {@code PrdDataGen}).
 * While it is being worked ({@link PrdSession}), it is held up in front of the eyes in both hands instead. The change is
 * animated: {@link #raise} runs from 0 (carried) to 1 (held up) over {@link #RAISE_TICKS} ticks, and back when it is put
 * down. In first person, over the first half the carried display drops out of view, and over the second half it comes
 * up in both hands, the way a map is swapped to two hands. Whatever is in the other hand is hidden until it is put down
 * again.
 *
 * <p>In third person the player's arms take {@link PrdArmPose#RAISED}, and {@link PrdItemRenderer} moves the display from
 * the fist to between the hands. Only this client knows it is being worked, so other players still see it carried.
 *
 * <p>Held up in first person, where it is drawn is handed to {@link PrdSession#capture}, every frame, so the cursor can find
 * its buttons and glass.
 *
 * <p>Every number that places the display or the arms is a constant here, to be tuned by eye.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class PrdPoses {

    // ---- tuning: first person, held up ----

    /** How long raising or lowering it takes. */
    static final float RAISE_TICKS = 6;
    /** Where the display's middle is, held up: right, up, and away from the eyes (blocks). */
    static final float RAISED_X = 0;
    static final float RAISED_Y = -0.06F;
    static final float RAISED_Z = -0.72F;
    /** How big it is, held up: one model pixel is this sixteenth of a block. */
    static final float RAISED_SCALE = 0.7F;
    /** How far it is tipped back, top away from the eyes (degrees). */
    static final float RAISED_TILT = -8;
    /** How far each hand is moved out from where a two-handed map is held, to reach the display's sides (blocks). */
    static final float RIGHT_HAND_OUT = 0.076F;
    static final float LEFT_HAND_OUT = 0.104F;
    /**
     * How much of the game's walking bob the raised display keeps: a hint of it, so it still feels carried, but little enough
     * that the screen can be read on the move. (The world still bobs as usual; only the hands and display are steadied.)
     */
    static final float BOB_KEPT = 0.15F;

    // ---- tuning: third person, held up ----

    /** Where the display's middle is, from the right fist: in (towards the left hand), forward along the forearm, and up (pixels). */
    static final float[] RAISED_3P_OFFSET = {-1.7F, 1.5F, 0.5F};
    static final float RAISED_3P_SCALE = 0.4F;

    // ---- state ----

    private static float raise;
    private static float raiseO;
    /** The hand the raised display is in. */
    private static InteractionHand hand = InteractionHand.MAIN_HAND;
    /** The entity being drawn right now, so the renderer knows whose display it is drawing in third person. */
    private static LivingEntity drawing;

    /** How far the display is raised, 0 to 1, this frame. */
    public static float raise(float partialTick) {
        return Mth.lerp(partialTick, raiseO, raise);
    }

    /** The hand the raised display is in. */
    public static InteractionHand hand() {
        return hand;
    }

    /** The entity whose hands are being drawn now, if any. */
    static LivingEntity drawing() {
        return drawing;
    }

    /** Whether this entity's display, in this hand, is held up in both hands now (in third person). */
    static boolean raisedInThirdPerson(LivingEntity entity, InteractionHand inHand) {
        Minecraft minecraft = Minecraft.getInstance();
        return entity == minecraft.player && inHand == hand
                && raise(minecraft.getTimer().getGameTimeDeltaPartialTick(false)) >= 0.5F;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        raiseO = raise;
        LocalPlayer player = minecraft.player;
        if (PrdSession.active()) {
            hand = PrdSession.hand();
        }
        if (player == null || !(player.getItemInHand(hand).getItem() instanceof PrdItem)) {
            raise = 0;
            raiseO = 0;
            return;
        }
        float target = PrdSession.active() ? 1 : 0;
        float step = 1 / RAISE_TICKS;
        raise = raise < target ? Math.min(target, raise + step) : Math.max(target, raise - step);
    }

    // ---- whose hands ----

    @SubscribeEvent
    static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        drawing = event.getEntity();
    }

    @SubscribeEvent
    static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        drawing = null;
    }

    // ---- first person ----

    @SubscribeEvent
    static void onRenderHand(RenderHandEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        float r = raise(event.getPartialTick());
        if (player == null || r <= 0) {
            return;
        }
        if (event.getHand() != hand) {
            event.setCanceled(true); // that hand is busy holding the display
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof PrdItem)) {
            return;
        }
        event.setCanceled(true);
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource buffer = event.getMultiBufferSource();
        int light = event.getPackedLight();
        if (r < 0.5F) {
            // Lowering the carried display out of sight.
            float lowered = Math.min(1, event.getEquipProgress() + smooth(r * 2));
            float side = arm == HumanoidArm.RIGHT ? 1 : -1;
            poseStack.pushPose();
            poseStack.translate(side * 0.56F, -0.52F + lowered * -0.6F, -0.72F);
            minecraft.getEntityRenderDispatcher().getItemInHandRenderer().renderItem(player, stack,
                    arm == HumanoidArm.RIGHT ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                    arm == HumanoidArm.LEFT, poseStack, buffer, light);
            poseStack.popPose();
        } else {
            // Bringing it up in both hands.
            renderRaised(poseStack, buffer, light, player, stack, 1 - smooth((r - 0.5F) * 2), event.getPartialTick());
        }
    }

    /** Eases in and out. */
    private static float smooth(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * The display held up in both hands, {@code drop} (0 to 1) of the way down out of view. Built like the game's own
     * two-handed map: the hands are posed as they hold a map, moved out to the display's sides.
     */
    private static void renderRaised(PoseStack poseStack, MultiBufferSource buffer, int light, LocalPlayer player, ItemStack stack,
                                     float drop, float partialTick) {
        poseStack.pushPose();
        steadyBob(poseStack, player, partialTick);
        poseStack.translate(RAISED_X, RAISED_Y + drop * -1.2F, RAISED_Z);
        poseStack.mulPose(Axis.XP.rotationDegrees(RAISED_TILT));
        if (!player.isInvisible()) {
            holdingHand(poseStack, buffer, light, player, HumanoidArm.RIGHT, RIGHT_HAND_OUT);
            holdingHand(poseStack, buffer, light, player, HumanoidArm.LEFT, LEFT_HAND_OUT);
        }
        float scale = RAISED_SCALE;
        poseStack.scale(scale, scale, scale);
        poseStack.translate(-PrdShape.CENTRE_X / 16, -PrdShape.CENTRE_Y / 16, -PrdShape.CENTRE_Z / 16);
        // Model pixels to the screen, exactly as drawn: the hands' projection, the view, then this pose.
        PrdSession.capture(new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix())
                .mul(poseStack.last().pose()).scale(1 / 16F));
        PrdItemRenderer.renderDisplay(stack, poseStack, buffer, light, OverlayTexture.NO_OVERLAY, player, true);
        poseStack.popPose();
    }

    /**
     * Takes most of the walking bob back out of the hands. Before the hands are drawn, the game has bobbed them
     * ({@code GameRenderer#bobView}: a sway, a dip and two small tilts, all scaled by how fast the player is walking), then
     * turned them a little after the view ({@code ItemInHandRenderer#renderHandsWithItems}, the lag behind the camera). This
     * undoes the lag and the bob, puts back {@link #BOB_KEPT} of the bob, then puts the lag back as it was.
     */
    private static void steadyBob(PoseStack poseStack, LocalPlayer player, float partialTick) {
        if (!Minecraft.getInstance().options.bobView().get()) {
            return;
        }
        float lagX = (player.getViewXRot(partialTick) - Mth.lerp(partialTick, player.xBobO, player.xBob)) * 0.1F;
        float lagY = (player.getViewYRot(partialTick) - Mth.lerp(partialTick, player.yBobO, player.yBob)) * 0.1F;
        poseStack.mulPose(Axis.YP.rotationDegrees(-lagY));
        poseStack.mulPose(Axis.XP.rotationDegrees(-lagX));

        float walked = -(player.walkDist + (player.walkDist - player.walkDistO) * partialTick);
        float bob = Mth.lerp(partialTick, player.oBob, player.bob);
        float sway = Mth.sin(walked * (float) Math.PI) * bob * 0.5F;
        float dip = -Math.abs(Mth.cos(walked * (float) Math.PI) * bob);
        float roll = Mth.sin(walked * (float) Math.PI) * bob * 3.0F;
        float nod = Math.abs(Mth.cos(walked * (float) Math.PI - 0.2F) * bob) * 5.0F;
        // Undo the game's bob, in reverse order...
        poseStack.mulPose(Axis.XP.rotationDegrees(-nod));
        poseStack.mulPose(Axis.ZP.rotationDegrees(-roll));
        poseStack.translate(-sway, -dip, 0);
        // ...and put a little of it back.
        poseStack.translate(sway * BOB_KEPT, dip * BOB_KEPT, 0);
        poseStack.mulPose(Axis.ZP.rotationDegrees(roll * BOB_KEPT));
        poseStack.mulPose(Axis.XP.rotationDegrees(nod * BOB_KEPT));

        poseStack.mulPose(Axis.XP.rotationDegrees(lagX));
        poseStack.mulPose(Axis.YP.rotationDegrees(lagY));
    }

    /** One hand holding the display's side: the game's map hand (see {@code ItemInHandRenderer#renderMapHand}), moved out. */
    private static void holdingHand(PoseStack poseStack, MultiBufferSource buffer, int light, AbstractClientPlayer player,
                                    HumanoidArm side, float out) {
        PlayerRenderer renderer = (PlayerRenderer) Minecraft.getInstance().getEntityRenderDispatcher().<AbstractClientPlayer>getRenderer(player);
        float f = side == HumanoidArm.RIGHT ? 1 : -1;
        poseStack.pushPose();
        poseStack.translate(f * out, 0, 0);
        poseStack.mulPose(Axis.YP.rotationDegrees(90));
        poseStack.mulPose(Axis.YP.rotationDegrees(92));
        poseStack.mulPose(Axis.XP.rotationDegrees(45));
        poseStack.mulPose(Axis.ZP.rotationDegrees(f * -41));
        poseStack.translate(f * 0.3F, -1.1F, 0.45F);
        if (side == HumanoidArm.RIGHT) {
            renderer.renderRightHand(poseStack, buffer, light, player);
        } else {
            renderer.renderLeftHand(poseStack, buffer, light, player);
        }
        poseStack.popPose();
    }

    private PrdPoses() {
    }
}
