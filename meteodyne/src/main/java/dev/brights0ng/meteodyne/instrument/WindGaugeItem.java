// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** A compass-like downwind arrow and cup anemometer, with server-owned wind readings. */
public final class WindGaugeItem extends Item {
    public static final int INTERVAL = 20;

    public WindGaugeItem(Properties properties) {
        super(properties);
    }

    /** Updating the instrument is not equipping a different item. Keep normal slot and other component changes. */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || changedBeyondReading(oldStack, newStack);
    }

    @Override
    public boolean shouldCauseBlockBreakReset(ItemStack oldStack, ItemStack newStack) {
        return changedBeyondReading(oldStack, newStack);
    }

    private static boolean changedBeyondReading(ItemStack oldStack, ItemStack newStack) {
        if (oldStack == newStack) { return false; }
        if (!ItemStack.isSameItem(oldStack, newStack) || oldStack.getCount() != newStack.getCount()) { return true; }
        ItemStack before = oldStack.copy(), after = newStack.copy();
        before.remove(InstrumentContent.WIND_READING.get());
        after.remove(InstrumentContent.WIND_READING.get());
        return !ItemStack.isSameItemSameComponents(before, after);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!(level instanceof ServerLevel server) || !(entity instanceof Player player)
                || (slot >= 9 && stack != player.getOffhandItem())) {
            return;
        }
        WindReading previous = stack.get(InstrumentContent.WIND_READING.get());
        long now = level.getGameTime();
        if (previous != null && previous.usable(level.dimension().location().toString(), now)
                && now - previous.time() < INTERVAL) {
            return;
        }
        WindReading reading = InstrumentReadings.wind(server, player.position().add(0, 0.9, 0));
        if (reading != null) { stack.set(InstrumentContent.WIND_READING.get(), reading); }
        else { stack.remove(InstrumentContent.WIND_READING.get()); }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.meteodyne.wind_gauge").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.meteodyne.wind_gauge.scale").withStyle(ChatFormatting.DARK_GRAY));
    }
}
