// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianFulfilmentMeters;
import dev.brights0ng.empire.rules.CivilianDerivedStats;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reusable Level-4 block-breaking primitive: faces {@code target} within {@link CivilianDerivedStats#blockReach()},
 * works for a duration scaled by labor speed and Fulfilment productivity, removes the block, collects loot table drops
 * into the civilian's pockets, applies exhaustion, and credits {@code workDoneTicks}.
 */
public final class BreakBlockAction implements CivilianAction {

    private static final double REACH_MARGIN = 0.85;

    private final BlockPos target;
    @Nullable
    private final Predicate<ItemStack> toolFilter;
    private final int baseTicks;
    private final float baseExhaustion;
    private final boolean heavyLabor;
    private final Predicate<BlockState> stillValid;

    private int ticks;
    private int durationTicks = 1;

    public BreakBlockAction(BlockPos target, @Nullable Predicate<ItemStack> toolFilter,
                            int baseTicks, float baseExhaustion, boolean heavyLabor,
                            Predicate<BlockState> stillValid) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.toolFilter = toolFilter;
        this.baseTicks = Math.max(1, baseTicks);
        this.baseExhaustion = Math.max(0.0F, baseExhaustion);
        this.heavyLabor = heavyLabor;
        this.stillValid = Objects.requireNonNull(stillValid, "stillValid");
    }

    @Override
    public String name() {
        return "break_block(" + target.toShortString() + ")";
    }

    @Override
    public void start(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        ticks = 0;
        civ.getNavigation().stop();
        if (toolFilter != null) {
            EquipAction.equipFromPockets(civ, toolFilter);
        } else {
            EquipAction.stowMainHand(civ);
        }
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
        BlockState state = level.getBlockState(target);
        if (!stillValid.test(state)) {
            return Status.FAILED;
        }
        if (toolFilter != null && !toolFilter.test(civ.getMainHandItem())) {
            return Status.FAILED;
        }

        ticks++;
        civ.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 30.0F, 30.0F);
        if (ticks == 1 || ticks % 8 == 0) {
            civ.swing(InteractionHand.MAIN_HAND);
            SoundType sound = state.getSoundType(level, target, civ);
            level.playSound(null, target, sound.getHitSound(), SoundSource.BLOCKS,
                    (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F);
        }
        civ.schedule().addWorkDoneTicks(1);

        if (ticks < durationTicks) {
            return Status.RUNNING;
        }

        ItemStack held = civ.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target), civ, held);
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, target, Block.getId(state));
        level.removeBlock(target, false);
        for (ItemStack drop : drops) {
            civ.storeOrDrop(drop);
        }
        if (toolFilter != null && !held.isEmpty() && held.isDamageableItem()) {
            held.hurtAndBreak(1, civ, EquipmentSlot.MAINHAND);
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
