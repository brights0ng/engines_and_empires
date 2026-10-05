package dev.brights0ng.enginesandempires.geophone.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.PrdItem;
import dev.brights0ng.enginesandempires.geophone.PrdSignal;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * Client-side registration for the portable record display, and the parts of working it that belong to the game rather
 * than to the display: the key to look around, walking while it is up, and keeping the game's own uses of the mouse out of
 * the way. Only ever loaded on a client.
 *
 * <p>The item is drawn by {@link PrdItemRenderer} (its item model's parent is {@code builtin/entity}, which hands the drawing
 * to the renderer registered here), which lights the lamp {@link #light} names: the last flash the display carries
 * ({@link PrdSignal}), or else the green lamp, steady, while it is being worked. How it is held is {@link PrdPoses}; working
 * it is {@link PrdSession}.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class PrdClient {

    /**
     * Held while working the display, lets go of the cursor so the mouse turns the view, the way Create Aeronautics does when
     * steering; let go, and the cursor is back. Tab by default. It only means anything while the display is up, so it is
     * given the GUI context, which keeps it from being shown as clashing with the player list, also on Tab.
     */
    public static final KeyMapping LOOK = new KeyMapping("key.engines_and_empires.prd_look", KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_TAB, "key.categories.engines_and_empires");

    @SubscribeEvent
    static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(LOOK);
    }

    @SubscribeEvent
    static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(SeismicContent.PRD_MENU.get(), PrdScreen::new);
    }

    /**
     * The item's own renderer, and its arm pose while held up. The renderer is made the first time it is asked for, once the
     * game's model and renderer sets exist.
     */
    @SubscribeEvent
    static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new PrdItemRenderer();
                }
                return renderer;
            }

            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return PrdPoses.raisedInThirdPerson(entity, hand) ? PrdArmPose.RAISED.getValue() : null;
            }
        }, SeismicContent.PORTABLE_RECORD_DISPLAY.get());
    }

    /** Which lamp a display is showing right now: a flash if one is playing, else green while it is being worked. */
    public static PrdSignal.Light light(ItemStack stack, LivingEntity holder) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = minecraft.level == null ? 0 : minecraft.level.getGameTime();
        PrdSignal.Light flash = PrdItem.flashing(stack, now);
        if (flash != PrdSignal.Light.NONE) {
            return flash;
        }
        if ((holder == null || holder == minecraft.player) && PrdSession.shows(stack)) {
            return PrdSignal.Light.GREEN;
        }
        return PrdSignal.Light.NONE;
    }

    /** Raises the display in this hand, to work it. */
    public static void openMap(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && !PrdSession.active()) {
            PrdSession.start(hand);
        }
    }

    // ---- while it is up ----

    /**
     * Keeps the session honest, every tick: it ends if the display leaves the hand, or if something else closes its screen.
     * Holding {@link #LOOK} takes the screen away (so the mouse turns the view again), and letting go brings it back.
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!PrdSession.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !(PrdSession.display().getItem() instanceof PrdItem)) {
            PrdSession.end();
            return;
        }
        PrdSession.tick();
        boolean lookHeld = !PrdSession.typing() && held(minecraft.getWindow().getWindow(), LOOK);
        if (PrdSession.looking()) {
            if (minecraft.screen != null) {
                PrdSession.end(); // something else opened over it (chat, the pause menu...)
            } else if (!lookHeld) {
                PrdSession.setLooking(false);
                minecraft.setScreen(new PrdDeviceScreen());
            }
        } else if (!(minecraft.screen instanceof PrdDeviceScreen)) {
            PrdSession.end();
        } else if (lookHeld) {
            PrdSession.setLooking(true);
            minecraft.setScreen(null); // the mouse is the game's again, to look around
        }
    }

    /** While looking around, the mouse buttons do not attack, mine or use anything: the hands are full. */
    @SubscribeEvent
    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (PrdSession.active()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /**
     * While looking around, the hands are still full: the hotbar keys, dropping and swapping hands do nothing, and the
     * inventory key puts the display down (without opening the inventory), as it does with the cursor out. Their presses
     * are used up here, before the game gets to them.
     */
    @SubscribeEvent
    static void onClientTickPre(ClientTickEvent.Pre event) {
        if (!PrdSession.active() || !PrdSession.looking()) {
            return;
        }
        Options options = Minecraft.getInstance().options;
        for (KeyMapping slot : options.keyHotbarSlots) {
            while (slot.consumeClick()) {
                // used up
            }
        }
        while (options.keyDrop.consumeClick() || options.keySwapOffhand.consumeClick()) {
            // used up
        }
        boolean inventory = false;
        while (options.keyInventory.consumeClick()) {
            inventory = true;
        }
        if (inventory) {
            PrdSession.end();
        }
    }

    /** Nor does the mouse wheel change the hotbar slot while looking around. */
    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (PrdSession.active() && PrdSession.looking()) {
            event.setCanceled(true);
        }
    }

    /** The look key is Tab, which also shows the player list: not while the display is up. */
    @SubscribeEvent
    static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (PrdSession.active() && event.getName().equals(VanillaGuiLayers.TAB_LIST)) {
            event.setCanceled(true);
        }
    }

    /**
     * Walking while it is up. The movement keys only count as held while no screen is open (NeoForge gives them an "in game"
     * context), so the player's input is worked out again here, straight from the keyboard, while the display's screen is
     * open: forwards, backwards, sideways, jumping, sneaking, and sprinting. Not while typing a name, when those keys are
     * letters.
     */
    @SubscribeEvent
    static void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof PrdDeviceScreen) || !(event.getEntity() instanceof LocalPlayer player)) {
            return;
        }
        Input input = event.getInput();
        if (PrdSession.typing()) {
            input.up = input.down = input.left = input.right = input.jumping = input.shiftKeyDown = false;
            input.forwardImpulse = 0;
            input.leftImpulse = 0;
            return;
        }
        long window = minecraft.getWindow().getWindow();
        input.up = held(window, minecraft.options.keyUp);
        input.down = held(window, minecraft.options.keyDown);
        input.left = held(window, minecraft.options.keyLeft);
        input.right = held(window, minecraft.options.keyRight);
        input.jumping = held(window, minecraft.options.keyJump);
        input.shiftKeyDown = held(window, minecraft.options.keyShift);
        input.forwardImpulse = input.up == input.down ? 0.0F : (input.up ? 1.0F : -1.0F);
        input.leftImpulse = input.left == input.right ? 0.0F : (input.left ? 1.0F : -1.0F);
        if (input.shiftKeyDown) {
            float slow = (float) player.getAttributeValue(Attributes.SNEAKING_SPEED);
            input.forwardImpulse *= slow;
            input.leftImpulse *= slow;
        }
        if (held(window, minecraft.options.keySprint) && input.up && !input.shiftKeyDown && !player.isSprinting()
                && player.getFoodData().getFoodLevel() > 6 && !player.isUsingItem()) {
            player.setSprinting(true);
        }
    }

    /** Whether a key mapping's key or mouse button is held down right now, whatever screen is open. */
    static boolean held(long window, KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        if (key.getValue() == InputConstants.UNKNOWN.getValue()) {
            return false;
        }
        return switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(window, key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }

    private PrdClient() {
    }
}
