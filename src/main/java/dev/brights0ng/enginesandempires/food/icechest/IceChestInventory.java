package dev.brights0ng.enginesandempires.food.icechest;

import java.util.Map;

import javax.annotation.Nullable;

import dev.brights0ng.enginesandempires.food.SpoilageConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The ice chest's ten slots: slot {@link #ICE} takes only coolants (see {@link Coolants}), and slots 1 to 9 only food.
 *
 * <p>Every read or change first settles the chest's cooling ({@link IceChestBlockEntity#settle}), so whatever looks at the
 * food, whether a player, a hopper or a Create funnel, sees it as it is now. Without an owner (the client's copy in the menu)
 * it is a plain handler with the same slot rules.
 */
public class IceChestInventory extends ItemStackHandler {

    public static final int ICE = 0;
    public static final int FIRST_FOOD = 1;
    public static final int SIZE = 10;

    @Nullable
    private final IceChestBlockEntity owner;

    public IceChestInventory(@Nullable IceChestBlockEntity owner) {
        super(SIZE);
        this.owner = owner;
    }

    /** How long one of this item cools the chest, in ticks; 0 if it is not a coolant. */
    public static long coolingTicks(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        Long ticks = coolants().get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        return ticks == null ? 0 : ticks;
    }

    public static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }

    // The coolants are read from the config; parsing the list on every look would be wasteful, so it is kept until it changes
    private static Object cachedFrom;
    private static Map<String, Long> cached = Map.of();

    private static synchronized Map<String, Long> coolants() {
        Object key = SpoilageConfig.SPEC.isLoaded() ? java.util.List.of(SpoilageConfig.COOLANTS.get(), SpoilageConfig.ICE_TICKS.get()) : "defaults";
        if (!key.equals(cachedFrom)) {
            cached = SpoilageConfig.coolants();
            cachedFrom = key;
        }
        return cached;
    }

    /** The stack in a slot without settling first (for the settling itself). */
    ItemStack raw(int slot) {
        return stacks.get(slot);
    }

    private void settle() {
        if (owner != null) {
            owner.settle();
        }
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return slot == ICE ? coolingTicks(stack) > 0 : isFood(stack);
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        settle();
        return super.getStackInSlot(slot);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        settle();
        super.setStackInSlot(slot, stack);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        settle();
        return super.insertItem(slot, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        settle();
        return super.extractItem(slot, amount, simulate);
    }

    @Override
    protected void onContentsChanged(int slot) {
        if (owner != null) {
            owner.setChanged();
        }
    }
}
