// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianFulfilmentMeters;
import dev.brights0ng.empire.rules.CivilianDerivedStats;
import dev.brights0ng.empire.rules.CivilianSkill;
import dev.brights0ng.empire.rules.CivilianSkills;
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
 * works for a duration scaled by labor speed, Fulfilment productivity, and occupational skill, removes the block,
 * applies produce yield / rookie fumble / tool durability / exhaustion scaling, awards skill XP, and credits
 * {@code workDoneTicks}.
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
    @Nullable
    private final CivilianSkill skill;
    private final double baseXp;
    private final boolean cropYieldBonus;

    private int ticks;
    private int durationTicks = 1;
    private boolean droppedOverflow;
    private boolean fumbled;

    public BreakBlockAction(BlockPos target, @Nullable Predicate<ItemStack> toolFilter,
                            int baseTicks, float baseExhaustion, boolean heavyLabor,
                            Predicate<BlockState> stillValid) {
        this(target, toolFilter, baseTicks, baseExhaustion, heavyLabor, stillValid, null, 0.0, false);
    }

    public BreakBlockAction(BlockPos target, @Nullable Predicate<ItemStack> toolFilter,
                            int baseTicks, float baseExhaustion, boolean heavyLabor,
                            Predicate<BlockState> stillValid,
                            @Nullable CivilianSkill skill, double baseXp, boolean cropYieldBonus) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.toolFilter = toolFilter;
        this.baseTicks = Math.max(1, baseTicks);
        this.baseExhaustion = Math.max(0.0F, baseExhaustion);
        this.heavyLabor = heavyLabor;
        this.stillValid = Objects.requireNonNull(stillValid, "stillValid");
        this.skill = skill;
        this.baseXp = Math.max(0.0, baseXp);
        this.cropYieldBonus = cropYieldBonus;
    }

    @Override
    public String name() {
        return "break_block(" + target.toShortString() + ")";
    }

    /** True if breaking this block had to drop overflow onto the ground because pockets had no available space. */
    public boolean droppedOverflow() {
        return droppedOverflow;
    }

    /** True if a rookie mistake caused harvested drops to spill onto the ground instead of going into pockets. */
    public boolean fumbled() {
        return fumbled;
    }

    public int durationTicks() {
        return durationTicks;
    }

    @Override
    public void start(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        ticks = 0;
        droppedOverflow = false;
        fumbled = false;
        civ.getNavigation().stop();
        if (toolFilter != null) {
            EquipAction.equipFromPockets(civ, toolFilter);
        } else {
            EquipAction.stowMainHand(civ);
        }
        CivilianDerivedStats stats = civ.derivedStats();
        double laborSpeed = heavyLabor ? stats.heavyLaborSpeedMultiplier() : stats.fineWorkSpeedMultiplier();
        double productivity = CivilianFulfilmentMeters.evaluate(ctx.level(), civ).productivity();
        double skillSpeed = skill != null ? CivilianSkills.speedMultiplier(civ.skills().level(skill)) : 1.0;
        durationTicks = FarmerUtility.scaledWorkTicks(baseTicks, laborSpeed, productivity, skillSpeed);
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

        double skillLevel = skill != null ? civ.skills().level(skill) : CivilianSkills.ROOKIE_SKILL_CEILING;
        ItemStack held = civ.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target), civ, held);
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, target, Block.getId(state));
        level.removeBlock(target, false);

        if (skill != null && cropYieldBonus && skillLevel > 0.0) {
            for (ItemStack drop : drops) {
                if (!drop.isEmpty() && FarmCrops.isCropProduceDrop(drop, drops)) {
                    int scaled = CivilianSkills.rollScaledYield(drop.getCount(), skillLevel, civ.getRandom()::nextLong);
                    drop.setCount(Math.min(scaled, drop.getMaxStackSize()));
                }
            }
        }

        fumbled = skill != null && civ.getRandom().nextDouble() < civ.currentFumbleChance(skill);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            if (fumbled) {
                // Rookie fumble: drop straight to the ground (distinct from pocket overflow).
                Block.popResource(level, target, drop);
            } else {
                if (TransferItemsAction.availableSpaceFor(civ.inventory(), drop) < drop.getCount()) {
                    droppedOverflow = true;
                }
                civ.storeOrDrop(drop);
            }
        }
        if (toolFilter != null && !held.isEmpty() && held.isDamageableItem()) {
            double durChance = skill != null ? CivilianSkills.toolDurabilityChance(skillLevel) : 1.0;
            if (durChance >= 1.0 || civ.getRandom().nextDouble() < durChance) {
                held.hurtAndBreak(1, civ, EquipmentSlot.MAINHAND);
            }
        }
        if (baseExhaustion > 0.0F) {
            double skillExh = skill != null ? CivilianSkills.exhaustionMultiplier(skillLevel) : 1.0;
            civ.food().addExhaustion((float) (baseExhaustion * civ.derivedStats().activityExhaustionMultiplier() * skillExh));
        }
        if (skill != null && baseXp > 0.0) {
            civ.awardSkillActionXp(level, skill, baseXp);
        } else {
            civ.syncNeedsToRecord();
        }
        return Status.SUCCEEDED;
    }

    @Override
    public boolean canYieldCleanly() {
        return ticks >= durationTicks;
    }
}
