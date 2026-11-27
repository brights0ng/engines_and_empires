// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.Objects;
import java.util.function.ToIntFunction;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianFulfilmentMeters;
import dev.brights0ng.empire.rules.CivilianSkill;
import dev.brights0ng.empire.rules.CivilianSkills;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Reusable Level-4 container-transfer primitive backed by NeoForge's {@link Capabilities.ItemHandler#BLOCK}.
 * Works identically with Chests (including double chests), Trapped Chests, Barrels, and Create Item Vaults.
 *
 * <p>Paced over a short duration scaled by {@code routingEfficiencyMultiplier}, Fulfilment {@code productivity}, and
 * {@link CivilianSkill#LOGISTICS}, playing open/close sounds and chest lid animations where supported, spilling 1–2
 * stackable items on a rookie fumble, and awarding Logistics XP on completion.
 */
public final class TransferItemsAction implements CivilianAction {

    public enum Mode {
        DEPOSIT,
        WITHDRAW
    }

    private static final double REACH_MARGIN = 1.1;

    private final BlockPos storagePos;
    private final Mode mode;
    private final ToIntFunction<ItemStack> transferQuota;
    private final int baseTicks;
    @Nullable
    private final CivilianSkill skill;

    private int ticks;
    private int durationTicks = 1;
    private boolean openedContainer;
    private int movedItems;
    private boolean fumbled;

    /**
     * @param transferQuota given a stack, returns how many items from that stack should be deposited (for
     *                      {@link Mode#DEPOSIT}) or withdrawn (for {@link Mode#WITHDRAW}); {@code <= 0} skips it.
     */
    public TransferItemsAction(BlockPos storagePos, Mode mode, ToIntFunction<ItemStack> transferQuota, int baseTicks) {
        this(storagePos, mode, transferQuota, baseTicks, CivilianSkill.LOGISTICS);
    }

    public TransferItemsAction(BlockPos storagePos, Mode mode, ToIntFunction<ItemStack> transferQuota, int baseTicks,
                               @Nullable CivilianSkill skill) {
        this.storagePos = Objects.requireNonNull(storagePos, "storagePos").immutable();
        this.mode = Objects.requireNonNull(mode, "mode");
        this.transferQuota = Objects.requireNonNull(transferQuota, "transferQuota");
        this.baseTicks = Math.max(1, baseTicks);
        this.skill = skill;
    }

    public int movedItems() {
        return movedItems;
    }

    /** True if a rookie Logistics mistake spilled 1–2 stackable items onto the floor next to the container. */
    public boolean fumbled() {
        return fumbled;
    }

    public int durationTicks() {
        return durationTicks;
    }

    @Override
    public String name() {
        return (mode == Mode.DEPOSIT ? "deposit_items(" : "withdraw_items(") + storagePos.toShortString() + ")";
    }

    @Override
    public void start(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        ticks = 0;
        movedItems = 0;
        openedContainer = false;
        fumbled = false;
        civ.getNavigation().stop();
        double routing = civ.derivedStats().routingEfficiencyMultiplier();
        double productivity = CivilianFulfilmentMeters.evaluate(ctx.level(), civ).productivity();
        double skillSpeed = skill != null ? CivilianSkills.speedMultiplier(civ.skills().level(skill)) : 1.0;
        durationTicks = FarmerUtility.scaledWorkTicks(baseTicks, routing, productivity, skillSpeed);

        ServerLevel level = ctx.level();
        if (level.hasChunkAt(storagePos)) {
            signalContainerOpen(level, storagePos, true);
            openedContainer = true;
        }
    }

    @Override
    public Status tick(AiContext ctx) {
        ServerLevel level = ctx.level();
        CivilianEntity civ = ctx.civilian();
        if (!level.hasChunkAt(storagePos)) {
            return Status.FAILED;
        }
        double maxReach = civ.derivedStats().blockReach() + REACH_MARGIN;
        if (!storagePos.closerToCenterThan(civ.position(), maxReach)) {
            return Status.FAILED;
        }

        ticks++;
        civ.getLookControl().setLookAt(
                storagePos.getX() + 0.5, storagePos.getY() + 0.5, storagePos.getZ() + 0.5, 30.0F, 30.0F);
        if (ticks == 1) {
            civ.swing(InteractionHand.MAIN_HAND);
        }
        civ.schedule().addWorkDoneTicks(1);

        if (ticks < durationTicks) {
            return Status.RUNNING;
        }

        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, storagePos, null);
        if (handler == null) {
            closeIfOpen(level);
            return Status.FAILED;
        }

        boolean attemptFumble = skill != null && civ.getRandom().nextDouble() < civ.currentFumbleChance(skill);
        if (mode == Mode.DEPOSIT) {
            // Stow main hand into pockets first so held tools/produce are deposited cleanly too.
            EquipAction.stowMainHand(civ);
            movedItems = executeDeposit(level, civ, handler, attemptFumble);
        } else {
            movedItems = executeWithdraw(level, civ, handler, attemptFumble);
        }
        closeIfOpen(level);
        if (movedItems > 0) {
            if (skill != null) {
                civ.awardSkillActionXp(level, skill, CivilianSkills.transferBaseXp(movedItems));
            } else {
                civ.syncNeedsToRecord();
            }
            return Status.SUCCEEDED;
        }
        return Status.FAILED;
    }

    @Override
    public void stop(AiContext ctx) {
        closeIfOpen(ctx.level());
    }

    @Override
    public boolean canYieldCleanly() {
        return ticks >= durationTicks;
    }

    private int executeDeposit(ServerLevel level, CivilianEntity civ, IItemHandler handler, boolean attemptFumble) {
        int totalMoved = 0;
        SimpleContainer inv = civ.inventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int wantToDeposit = Math.min(stack.getCount(), transferQuota.applyAsInt(stack));
            if (wantToDeposit <= 0) {
                continue;
            }
            int moved = depositStack(level, civ, handler, stack, wantToDeposit, attemptFumble);
            if (moved > 0) {
                if (stack.isEmpty()) {
                    inv.setItem(slot, ItemStack.EMPTY);
                }
                totalMoved += moved;
            }
        }
        // Also check main hand in case inventory was too full to stow before deposit.
        ItemStack main = civ.getMainHandItem();
        if (!main.isEmpty() && !civ.isUsingItem()) {
            int wantToDeposit = Math.min(main.getCount(), transferQuota.applyAsInt(main));
            if (wantToDeposit > 0) {
                int moved = depositStack(level, civ, handler, main, wantToDeposit, attemptFumble);
                if (moved > 0) {
                    if (main.isEmpty()) {
                        civ.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    }
                    totalMoved += moved;
                }
            }
        }
        return totalMoved;
    }

    private int depositStack(ServerLevel level, CivilianEntity civ, IItemHandler handler,
                             ItemStack stack, int wantToDeposit, boolean attemptFumble) {
        int spillCount = 0;
        if (attemptFumble && !fumbled && isSpillable(stack)) {
            spillCount = Math.min(wantToDeposit >= 4 ? 2 : 1, wantToDeposit);
        }
        int insertTarget = wantToDeposit - spillCount;
        int inserted = 0;
        if (insertTarget > 0) {
            ItemStack toInsert = stack.copyWithCount(insertTarget);
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(handler, toInsert, false);
            inserted = insertTarget - remainder.getCount();
        }
        // Only spill if the container actually accepted something (or the whole 1-item batch was spillable & fittable).
        int spilled = 0;
        if (spillCount > 0 && (inserted > 0 || canInsertAny(handler, stack))) {
            Block.popResource(level, civ.blockPosition(), stack.copyWithCount(spillCount));
            spilled = spillCount;
            fumbled = true;
        }
        int removed = inserted + spilled;
        if (removed > 0) {
            stack.shrink(removed);
        }
        return removed;
    }

    private int executeWithdraw(ServerLevel level, CivilianEntity civ, IItemHandler handler, boolean attemptFumble) {
        int totalMoved = 0;
        SimpleContainer inv = civ.inventory();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                continue;
            }
            int want = Math.min(inSlot.getCount(), transferQuota.applyAsInt(inSlot));
            if (want <= 0) {
                continue;
            }
            int canFit = availableSpaceFor(inv, inSlot);
            int toTake = Math.min(want, canFit);
            if (toTake <= 0) {
                continue;
            }
            ItemStack extracted = handler.extractItem(slot, toTake, false);
            if (!extracted.isEmpty()) {
                int spilled = 0;
                if (attemptFumble && !fumbled && isSpillable(extracted)) {
                    spilled = Math.min(extracted.getCount() >= 4 ? 2 : 1, extracted.getCount());
                    Block.popResource(level, civ.blockPosition(), extracted.split(spilled));
                    fumbled = true;
                }
                int stored = 0;
                if (!extracted.isEmpty()) {
                    ItemStack leftover = inv.addItem(extracted);
                    stored = extracted.getCount() - leftover.getCount();
                    if (!leftover.isEmpty()) {
                        ItemHandlerHelper.insertItemStacked(handler, leftover, false);
                    }
                }
                totalMoved += stored + spilled;
            }
        }
        return totalMoved;
    }

    /** Only stackable, non-damageable items (produce, seeds, materials) can spill on a Logistics fumble — never tools. */
    private static boolean isSpillable(ItemStack stack) {
        return !stack.isEmpty() && stack.getMaxStackSize() > 1 && !stack.isDamageableItem();
    }

    /** Counts how many more items of {@code sample}'s type can fit into {@code inv}. */
    public static int availableSpaceFor(SimpleContainer inv, ItemStack sample) {
        if (sample.isEmpty()) {
            return 0;
        }
        int maxStack = sample.getMaxStackSize();
        int space = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack cur = inv.getItem(i);
            if (cur.isEmpty()) {
                space += maxStack;
            } else if (ItemStack.isSameItemSameComponents(cur, sample) && cur.getCount() < maxStack) {
                space += (maxStack - cur.getCount());
            }
        }
        return space;
    }

    /** True if {@code handler} can accept at least 1 item of {@code sample} (simulated without modifying storage). */
    public static boolean canInsertAny(IItemHandler handler, ItemStack sample) {
        if (sample.isEmpty()) {
            return false;
        }
        ItemStack one = sample.copyWithCount(1);
        return ItemHandlerHelper.insertItemStacked(handler, one, true).isEmpty();
    }

    private void closeIfOpen(ServerLevel level) {
        if (openedContainer) {
            openedContainer = false;
            if (level.hasChunkAt(storagePos)) {
                signalContainerOpen(level, storagePos, false);
            }
        }
    }

    private static void signalContainerOpen(ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ChestBlockEntity) {
            level.blockEvent(pos, state.getBlock(), 1, open ? 1 : 0);
            level.playSound(null, pos, open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE,
                    SoundSource.BLOCKS, 0.35F, level.random.nextFloat() * 0.1F + 0.9F);
        } else if (state.getBlock() instanceof BarrelBlock) {
            level.playSound(null, pos, open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE,
                    SoundSource.BLOCKS, 0.35F, level.random.nextFloat() * 0.1F + 0.9F);
        }
    }
}
