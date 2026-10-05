package dev.brights0ng.enginesandempires.food.client;

import java.util.List;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.food.Cohort;
import dev.brights0ng.enginesandempires.food.FoodFreshness;
import dev.brights0ng.enginesandempires.food.FoodStage;
import dev.brights0ng.enginesandempires.food.SpoilClock;
import dev.brights0ng.enginesandempires.food.SpoilTimes;
import dev.brights0ng.enginesandempires.food.Spoilage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.brights0ng.enginesandempires.food.FoodTopPayload;

    /**
     * The client side of food spoilage: the client's clock, the freshness lines in food tooltips, shift+scroll to choose the
     * top group, and the top group in the hotbar's item name (see {@code SelectedFoodNameMixin}).
     */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class FoodClient {

    /** Gives {@link SpoilClock} the client's copy of the game time. */
    public static void init() {
        SpoilClock.setClient(() -> {
            ClientLevel level = Minecraft.getInstance().level;
            return level == null ? -1L : level.getGameTime();
        }, () -> Minecraft.getInstance().isSameThread());
    }

    /** The ice chest's screen. */
    @SubscribeEvent
    static void onRegisterScreens(net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.register(dev.brights0ng.enginesandempires.food.FoodContent.ICE_CHEST_MENU.get(), IceChestScreen::new);
    }

    /**
     * Each group of units in the stack, top first: "> Fresh x21", "Stale x9". The substages stay hidden. With Shift held, a
     * stack of more than one group also says how to choose the top one.
     */
    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!Spoilage.isSpoilable(stack) || Spoilage.stored(stack) == null) {
            return;
        }
        long now = SpoilClock.now();
        if (now < 0) {
            return;
        }
        SpoilTimes times = Spoilage.times();
        FoodFreshness view = Spoilage.view(stack, now);
        List<Cohort> cohorts = view.cohorts();
        List<Component> lines = event.getToolTip();
        int at = Math.min(1, lines.size());
        for (int step = 0; step < cohorts.size(); step++) {
            Cohort cohort = cohorts.get((view.top() + step) % cohorts.size());
            FoodStage stage = times.stage(now - cohort.born());
            Component name = Component.translatable("tooltip.engines_and_empires.food." + stage.id());
            String key = step == 0 && cohorts.size() > 1 ? "tooltip.engines_and_empires.food.top" : "tooltip.engines_and_empires.food.group";
            lines.add(at + step, Component.translatable(key, name, cohort.count()).withStyle(colour(stage)));
        }
        if (cohorts.size() > 1 && Screen.hasShiftDown()) {
            lines.add(at + cohorts.size(), Component.translatable("tooltip.engines_and_empires.food.scroll").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * Shift+scroll over a food stack of more than one group in any menu (bar the creative inventory, whose slots are the
     * client's own) asks the server to put the next group on top: up for staler, down for fresher. Only Shift+scroll is
     * taken, so plain scrolling (Mouse Tweaks and the like) is left alone.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen) || screen instanceof CreativeModeInventoryScreen
                || !Screen.hasShiftDown() || event.getScrollDeltaY() == 0) {
            return;
        }
        Slot slot = screen.getSlotUnderMouse();
        if (slot == null) {
            return;
        }
        ItemStack stack = slot.getItem();
        long now = SpoilClock.now();
        if (now < 0 || !Spoilage.isSpoilable(stack) || Spoilage.stored(stack) == null || Spoilage.view(stack, now).cohorts().size() < 2) {
            return;
        }
        PacketDistributor.sendToServer(new FoodTopPayload(screen.getMenu().containerId, slot.index, event.getScrollDeltaY() > 0));
        event.setCanceled(true);
    }

    /** The hotbar's item name for a food stack, with its top group added: "Bread (Fresh x21)". */
    public static Component withTopGroup(ItemStack stack, Component name) {
        if (!Spoilage.isSpoilable(stack) || Spoilage.stored(stack) == null) {
            return name;
        }
        long now = SpoilClock.now();
        if (now < 0) {
            return name;
        }
        Cohort top = Spoilage.view(stack, now).topCohort();
        if (top == null) {
            return name;
        }
        FoodStage stage = Spoilage.times().stage(now - top.born());
        MutableComponent tail = Component.translatable("hud.engines_and_empires.food.top",
                Component.translatable("tooltip.engines_and_empires.food." + stage.id()), top.count()).withStyle(colour(stage));
        return Component.empty().append(name).append(" ").append(tail);
    }

    private static ChatFormatting colour(FoodStage stage) {
        return switch (stage) {
            case FRESH -> ChatFormatting.GREEN;
            case RIPE -> ChatFormatting.YELLOW;
            case STALE -> ChatFormatting.GOLD;
            case ROTTING -> ChatFormatting.DARK_RED;
        };
    }

    private FoodClient() {
    }
}
