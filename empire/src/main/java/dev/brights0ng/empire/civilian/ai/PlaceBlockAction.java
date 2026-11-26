// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianFulfilmentMeters;
import dev.brights0ng.empire.rules.CivilianDerivedStats;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reusable Level-4 block-placing / seed-planting primitive: equips a matching item into {@code MAIN_HAND}, paces over
 * a duration scaled by labor speed and Fulfilment productivity, validates {@code canSurvive}, places the block,
 * consumes 1 item, applies exhaustion, and credits {@code workDoneTicks}.
 */
public final class PlaceBlockAction implements CivilianAction {

    private static final double REACH_MARGIN = 0.85;

    private final BlockPos target;
    private final Predicate<ItemStack> itemFilter;
    private final Function<ItemStack, BlockState> stateFactory;
    private final int baseTicks;
    private final float baseExhaustion;
    private final boolean heavyLabor;

    private int ticks;
    private int durationTicks = 1;

    public PlaceBlockAction(BlockPos target, Predicate<ItemStack> itemFilter,
                            Function<ItemStack, BlockState> stateFactory,
                            int baseTicks, float baseExhaustion, boolean heavyLabor) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.itemFilter = Objects.requireNonNull(itemFilter, "itemFilter");
        this.stateFactory = Objects.requireNonNull(stateFactory, "stateFactory");
        this.baseTicks = Math.max(1, baseTicks);
        this.baseExhaustion = Math.max(0.0F, baseExhaustion);
        this.heavyLabor = heavyLabor;
    }

    @Override
    public String name() {
        return "place_block(" + target.toShortString() + ")";
    }

    @Override
    public void start(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        ticks = 0;
        civ.getNavigation().stop();
        EquipAction.equipFromPockets(civ, itemFilter);
        CivilianDerivedStats stats = civ.derivedStats();
        double laborSpeed = heavyLabor ? stats.heavyLaborSpeedMultiplier() : stats.fineWorkSpeedMultiplier();
        double productivity = CivilianFulfilmentMeters.evaluate(ctx.level(), civ).productivity();
        durationTicks = FarmerUtility.scaledWorkTicks(baseTicks, laborSpeed, productivity);
    }

    @Override
    public Status tick(AiContext ctx) {
        ServerLevel level = ctx.level();
        CivilianEntity civ = ctx.civilian();
        if (!level.hasChunkAt(target)) {
            return Status.FAILED;
        }
        double maxReach = civ.derivedStats().blockReach() + REACH_MARGIN;
        if (!target.closerToCenterThan(civ.position(), maxReach)) {
            return Status.FAILED;
        }
        ItemStack held = civ.getMainHandItem();
        if (held.isEmpty() || !itemFilter.test(held)) {
            if (!EquipAction.equipFromPockets(civ, itemFilter)) {
                return Status.FAILED;
            }
            held = civ.getMainHandItem();
        }
        if (!level.getBlockState(target).canBeReplaced()) {
            return Status.FAILED;
        }
        BlockState toPlace = stateFactory.apply(held);
        if (toPlace == null || !toPlace.canSurvive(level, target)) {
            return Status.FAILED;
        }

        ticks++;
        civ.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 30.0F, 30.0F);
        if (ticks == 1) {
            civ.swing(InteractionHand.MAIN_HAND);
        }
        civ.schedule().addWorkDoneTicks(1);

        if (ticks < durationTicks) {
            return Status.RUNNING;
        }

        level.setBlock(target, toPlace, Block.UPDATE_ALL);
        SoundType sound = toPlace.getSoundType(level, target, civ);
        level.playSound(null, target, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        held.shrink(1);
        if (held.isEmpty()) {
            civ.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        if (baseExhaustion > 0.0F) {
            civ.food().addExhaustion(baseExhaustion * (float) civ.derivedStats().activityExhaustionMultiplier());
        }
        civ.syncNeedsToRecord();
        return Status.SUCCEEDED;
    }

    @Override
    public boolean canYieldCleanly() {
        return ticks >= durationTicks;
    }
}
