package dev.brights0ng.enginesandempires.food;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Food spoilage on item stacks: which stacks spoil, what they hold, and the hooks the item mixins call.
 *
 * <p>How stacking works. Vanilla only stacks items whose components are all equal, which is why most spoilage mods end up
 * with piles of the same food that will not combine. Here, food stacks are compared as if they had no freshness
 * ({@link #sameIgnoringFreshness}), so every mod that moves items will stack them. The freshness is then kept right in two
 * ways:
 * <ul>
 *   <li>Where items actually move (menu clicks, picking up, hoppers, item handlers, item entities merging, splitting a
 *       stack), the mixins record the food before and settle it after with {@link FreshnessLedger}: what leaves a stack is
 *       its top units, and they go where the units went.</li>
 *   <li>Anywhere else (code this mod does not know about), a count change is resolved the safe way ({@link #onSetCount}):
 *       units that leave are taken from the freshest, units that arrive join the stalest. An unknown route can make food
 *       staler than it should be, but never fresher.</li>
 * </ul>
 */
public final class Spoilage {

    /** Food that never spoils. Empty for now: Bright's rule (2026-09-27) is that everything edible spoils. */
    public static final TagKey<Item> NON_PERISHABLE =
            ItemTags.create(ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "non_perishable"));

    /** Food that is rotting from the moment it exists, whatever else would say (rotten flesh). */
    public static final TagKey<Item> ALWAYS_ROTTING =
            ItemTags.create(ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "always_rotting"));

    /** Whether this stack is food that spoils: anything edible, bar the {@link #NON_PERISHABLE} tag. */
    public static boolean isSpoilable(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD) && !stack.is(NON_PERISHABLE);
    }

    public static SpoilTimes times() {
        return SpoilageConfig.times();
    }

    private static DataComponentType<FoodFreshness> type() {
        return FoodContent.FRESHNESS.get();
    }

    /** The freshness stored on the stack, or null if it has none yet (not food, or not looked at yet). */
    @Nullable
    public static FoodFreshness stored(ItemStack stack) {
        return stack.get(type());
    }

    /**
     * What a food stack holds at {@code now}, settled and adding up to its count. A stack with nothing stored yet is all born
     * now.
     */
    public static FoodFreshness view(ItemStack stack, long now) {
        FoodFreshness stored = stored(stack);
        FoodFreshness view = stored == null ? FoodFreshness.born(now, stack.getCount()) : stored.reconcile(stack.getCount(), now, times());
        return forceRotting(stack, view, now);
    }

    public static void write(ItemStack stack, FoodFreshness freshness) {
        if (!stack.isEmpty() && !freshness.isEmpty()) {
            long now = stack.is(ALWAYS_ROTTING) ? SpoilClock.now() : -1;
            stack.set(type(), now < 0 ? freshness : forceRotting(stack, freshness, now));
        }
    }

    private static FoodFreshness forceRotting(ItemStack stack, FoodFreshness freshness, long now) {
        if (!stack.is(ALWAYS_ROTTING)) {
            return freshness;
        }
        SpoilTimes times = times();
        return freshness.atLeastAge(times.start(FoodStage.ROTTING), now, times);
    }

    /**
     * Stamps food that has not been stamped yet as being somewhere in {@code stage}, {@code fraction} (0 to 1) of the way
     * through it. Food already stamped is left alone. Returns whether it stamped.
     */
    public static boolean stampAs(ItemStack stack, FoodStage stage, double fraction) {
        if (!isSpoilable(stack) || stored(stack) != null) {
            return false;
        }
        long now = SpoilClock.now();
        if (now < 0) {
            return false;
        }
        write(stack, FoodFreshness.born(now - times().ageWithin(stage, fraction), stack.getCount()));
        return true;
    }

    /**
     * Stamps a food stack that has no freshness yet (as born now), and tidies one that has: settles it and makes it add up
     * to the count. Returns whether anything changed.
     */
    public static boolean normalize(ItemStack stack) {
        if (!isSpoilable(stack)) {
            return false;
        }
        long now = SpoilClock.now();
        if (now < 0) {
            return false;
        }
        FoodFreshness stored = stored(stack);
        FoodFreshness view = view(stack, now);
        if (view.equals(stored)) {
            return false;
        }
        write(stack, view);
        return true;
    }

    // ---- Hooks for ItemStackMixin ----

    /**
     * {@code ItemStack.setCount}: a count change nothing else has accounted for. Taken units come off the freshest, added
     * ones join the stalest (see the class notes). Hooked places settle the freshness before the count changes, so by the
     * time this runs the numbers already agree and it does nothing.
     */
    public static void onSetCount(ItemStack stack, int newCount) {
        if (newCount <= 0) {
            return;
        }
        FoodFreshness stored = stored(stack);
        if (stored == null || stored.total() == newCount) {
            return;
        }
        long now = SpoilClock.now();
        if (now < 0) {
            return;
        }
        stack.set(type(), stored.reconcile(newCount, now, times()));
    }

    /** Before {@code ItemStack.split}: what the stack holds, if it is stamped food. */
    @Nullable
    public static FoodFreshness beforeSplit(ItemStack stack) {
        if (!isSpoilable(stack) || stored(stack) == null) {
            return null;
        }
        long now = SpoilClock.now();
        return now < 0 ? null : view(stack, now);
    }

    /** After {@code ItemStack.split}: the part split off is the top units, and the stack keeps the rest. */
    public static void afterSplit(ItemStack stack, ItemStack taken, FoodFreshness before) {
        long now = SpoilClock.now();
        if (now < 0 || taken.isEmpty()) {
            return;
        }
        FoodFreshness.Split split = before.takeTop(taken.getCount(), now, times());
        write(taken, split.taken());
        write(stack, split.rest());
    }

    /**
     * After a unit of food is eaten: the eaten units came off the top, so the stack keeps the rest. Returns what was eaten
     * (empty if nothing was), for the stage effects.
     */
    public static FoodFreshness afterEating(ItemStack stack, FoodFreshness before, int eaten) {
        long now = SpoilClock.now();
        if (now < 0 || eaten <= 0) {
            return FoodFreshness.EMPTY;
        }
        FoodFreshness.Split split = before.takeTop(eaten, now, times());
        write(stack, split.rest());
        return split.taken();
    }

    /**
     * {@code ItemStack.isSameItemSameComponents}: food stacks that differ only in freshness count as the same, so they stack.
     * Null leaves it to vanilla.
     *
     * <p>On the server thread, if only one of the two has been stamped, the other is stamped here (as born now, which is what
     * an unstamped stack already counts as). That way a stack that has not been looked at yet cannot be merged into by some
     * route this mod does not know about while still counting as brand new.
     */
    @Nullable
    public static Boolean sameIgnoringFreshness(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty() || !a.is(b.getItem())) {
            return null;
        }
        DataComponentType<FoodFreshness> type = type();
        boolean hasA = a.has(type);
        boolean hasB = b.has(type);
        if ((!hasA && !hasB) || !isSpoilable(a)) {
            return null;
        }
        if (hasA != hasB && SpoilClock.onServerThread()) {
            normalize(hasA ? b : a);
        }
        return Objects.equals(withoutFreshness(a), withoutFreshness(b));
    }

    /**
     * {@code ItemStack.matches} (exactly equal, count included): freshness still counts here. Menus use it to decide what to
     * send to clients, so a freshness change alone must count as a change. Null leaves it to vanilla.
     */
    @Nullable
    public static Boolean exactlyEqual(ItemStack a, ItemStack b) {
        if (a == b || (a.isEmpty() && b.isEmpty())) {
            return null;
        }
        DataComponentType<FoodFreshness> type = type();
        if (!a.has(type) && !b.has(type)) {
            return null;
        }
        return a.getCount() == b.getCount() && a.is(b.getItem()) && Objects.equals(a.getComponents(), b.getComponents());
    }

    /** {@code ItemStack.hashItemAndComponents}, leaving freshness out to match {@link #sameIgnoringFreshness}. Null leaves it. */
    @Nullable
    public static Integer hashIgnoringFreshness(@Nullable ItemStack stack) {
        if (stack == null || !isSpoilable(stack)) {
            return null;
        }
        int hash = 31 + stack.getItem().hashCode();
        return 31 * hash + withoutFreshness(stack).hashCode();
    }

    private static DataComponentPatch withoutFreshness(ItemStack stack) {
        DataComponentType<FoodFreshness> type = type();
        return stack.getComponentsPatch().forget(t -> t == type);
    }

    /** Which food a stack is, for the ledger: food stacks that would stack together share a kind. Null for anything else. */
    @Nullable
    private static Object kind(ItemStack stack) {
        return isSpoilable(stack) ? new Kind(stack.getItem(), withoutFreshness(stack)) : null;
    }

    private record Kind(Item item, DataComponentPatch rest) {
    }

    /**
     * The food in some places (slots, the cursor, stacks handed in or out), recorded before an operation moves items about,
     * so that {@link #settle} can put the freshness right after it. See {@link FreshnessLedger}.
     */
    public static final class Places {

        /** The menu click being settled on this thread, if any, so food minted during it can be noted (see {@link #noteMinted}). */
        private static final ThreadLocal<Places> ACTIVE = new ThreadLocal<>();

        private final List<FreshnessLedger.Place> before;
        private final List<ItemStack> beforeStacks;
        private final boolean[] replaceable;
        private final List<FreshnessLedger.Place> minted = new ArrayList<>();
        private final long now;

        private Places(List<FreshnessLedger.Place> before, List<ItemStack> beforeStacks, boolean[] replaceable, long now) {
            this.before = before;
            this.beforeStacks = beforeStacks;
            this.replaceable = replaceable;
            this.now = now;
        }

        /** Records the food in these places, or returns null when there is none (or no clock), and so nothing to do. */
        @Nullable
        public static Places capture(List<ItemStack> stacks) {
            return capture(stacks, new boolean[stacks.size()]);
        }

        /**
         * As {@link #capture(List)}, marking the places whose stack is swapped for a new one when taken from (crafting and
         * trading result slots). If such a place holds a different stack afterwards, what it held counts as having all left,
         * and the new stack is left as it is.
         */
        @Nullable
        public static Places capture(List<ItemStack> stacks, boolean[] replaceable) {
            boolean anyFood = false;
            for (ItemStack stack : stacks) {
                if (isSpoilable(stack)) {
                    anyFood = true;
                    break;
                }
            }
            if (!anyFood) {
                return null;
            }
            long now = SpoilClock.now();
            if (now < 0) {
                return null;
            }
            List<FreshnessLedger.Place> before = new ArrayList<>(stacks.size());
            for (ItemStack stack : stacks) {
                Object kind = kind(stack);
                before.add(kind == null ? FreshnessLedger.Place.NOTHING
                        : new FreshnessLedger.Place(kind, stack.getCount(), view(stack, now)));
            }
            return new Places(before, List.copyOf(stacks), replaceable, now);
        }

        @Nullable
        public static Places capture(ItemStack... stacks) {
            return capture(List.of(stacks));
        }

        /** Every slot of a menu, then the cursor. Result slots are marked replaceable. */
        @Nullable
        public static Places captureMenu(AbstractContainerMenu menu) {
            boolean[] replaceable = new boolean[menu.slots.size() + 1];
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                replaceable[i] = slot instanceof ResultSlot || slot instanceof MerchantResultSlot;
            }
            return capture(menuStacks(menu), replaceable);
        }

        /**
         * Runs a menu click with these places active, so that food minted during it (a trade result refilled between
         * shift-click trades) is noted, and then settles.
         */
        public void runAndSettle(Runnable click, java.util.function.Supplier<List<ItemStack>> after) {
            Places outer = ACTIVE.get();
            ACTIVE.set(this);
            try {
                click.run();
            } finally {
                ACTIVE.set(outer);
            }
            settle(after.get());
        }

        /**
         * Food that just appeared in a place mid-click (a trade result being refilled). If it is then taken within the same
         * click, whoever took it gets its freshness rather than food born now.
         */
        public static void noteMinted(ItemStack stack) {
            Places active = ACTIVE.get();
            if (active == null) {
                return;
            }
            Object kind = kind(stack);
            if (kind != null && stored(stack) != null) {
                active.minted.add(new FreshnessLedger.Place(kind, stack.getCount(), view(stack, active.now)));
            }
        }

        public static List<ItemStack> menuStacks(AbstractContainerMenu menu) {
            List<ItemStack> stacks = new ArrayList<>(menu.slots.size() + 1);
            for (Slot slot : menu.slots) {
                stacks.add(slot.getItem());
            }
            stacks.add(menu.getCarried());
            return stacks;
        }

        /** Writes the right freshness onto what the same places hold now (same order as captured). */
        public void settle(List<ItemStack> after) {
            List<FreshnessLedger.Place> afterPlaces = new ArrayList<>(after.size());
            boolean[] skip = new boolean[after.size()];
            for (int i = 0; i < after.size(); i++) {
                ItemStack stack = after.get(i);
                Object kind = kind(stack);
                if (i < replaceable.length && replaceable[i] && stack != beforeStacks.get(i)) {
                    // A result slot refilled with a new stack: what it held has all gone, and the new stack stands on its own
                    skip[i] = true;
                    kind = null;
                }
                afterPlaces.add(kind == null ? FreshnessLedger.Place.NOTHING : new FreshnessLedger.Place(kind, stack.getCount(), FoodFreshness.EMPTY));
            }
            FoodFreshness[] result = FreshnessLedger.settle(before, afterPlaces, minted, now, times());
            for (int i = 0; i < result.length; i++) {
                if (result[i] != null && !skip[i]) {
                    write(after.get(i), result[i]);
                }
            }
        }

        public void settle(ItemStack... after) {
            settle(List.of(after));
        }
    }

    private Spoilage() {
    }
}
