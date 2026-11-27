// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.Objects;
import java.util.function.BiPredicate;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reusable Level-4 block-interaction primitive (e.g. hoeing soil into farmland, toggling stations, harvesting bushes).
 * Equips an optional required tool, faces {@code target} within {@link CivilianDerivedStats#blockReach()}, paces over
 * a speed/productivity/skill-scaled duration, runs the {@link BlockInteraction} (or whiffs on a rookie fumble), applies
 * skill-scaled tool durability and exhaustion, awards skill XP, and credits {@code workDoneTicks}.
 */
public final class InteractBlockAction implements CivilianAction {

    @FunctionalInterface
    public interface BlockInteraction {
        boolean execute(ServerLevel level, CivilianEntity civ, BlockPos target, ItemStack heldTool);
    }

    private static final double REACH_MARGIN = 0.85;

    private final String actionName;
    private final BlockPos target;
    @Nullable
    private final Predicate<ItemStack> toolFilter;
    private final BiPredicate<ServerLevel, BlockPos> stillValid;
    private final BlockInteraction interaction;
    private final int baseTicks;
    private final float baseExhaustion;
    private final boolean heavyLabor;
    @Nullable
    private final CivilianSkill skill;
    private final double baseXp;
    private final boolean damageHeldTool;

    private int ticks;
    private int durationTicks = 1;
    private boolean fumbled;

    public InteractBlockAction(String actionName, BlockPos target, @Nullable Predicate<ItemStack> toolFilter,
                               BiPredicate<ServerLevel, BlockPos> stillValid, BlockInteraction interaction,
                               int baseTicks, float baseExhaustion, boolean heavyLabor) {
        this(actionName, target, toolFilter, stillValid, interaction, baseTicks, baseExhaustion, heavyLabor,
                null, 0.0, false);
    }

    public InteractBlockAction(String actionName, BlockPos target, @Nullable Predicate<ItemStack> toolFilter,
                               BiPredicate<ServerLevel, BlockPos> stillValid, BlockInteraction interaction,
                               int baseTicks, float baseExhaustion, boolean heavyLabor,
                               @Nullable CivilianSkill skill, double baseXp, boolean damageHeldTool) {
        this.actionName = Objects.requireNonNull(actionName, "actionName");
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.toolFilter = toolFilter;
        this.stillValid = Objects.requireNonNull(stillValid, "stillValid");
        this.interaction = Objects.requireNonNull(interaction, "interaction");
        this.baseTicks = Math.max(1, baseTicks);
        this.baseExhaustion = Math.max(0.0F, baseExhaustion);
        this.heavyLabor = heavyLabor;
        this.skill = skill;
        this.baseXp = Math.max(0.0, baseXp);
        this.damageHeldTool = damageHeldTool;
    }

    /** Factory specialization: tills dirt/grass/path/coarse-dirt into farmland using a held hoe (Cultivation skill). */
    public static InteractBlockAction till(BlockPos target) {
        return new InteractBlockAction(
                "interact_till",
                target,
                FarmCrops::isHoe,
                FarmCrops::isTillableSoil,
                (level, civ, pos, hoe) -> {
                    if (!FarmCrops.isTillableSoil(level, pos) || !FarmCrops.isHoe(hoe)) {
                        return false;
                    }
                    BlockState cur = level.getBlockState(pos);
                    BlockState next = cur.is(Blocks.COARSE_DIRT)
                            ? Blocks.DIRT.defaultBlockState()
                            : Blocks.FARMLAND.defaultBlockState();
                    level.setBlock(pos, next, Block.UPDATE_ALL);
                    level.playSound(null, pos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F, 1.0F);
                    return true;
                },
                FarmerUtility.TILL_BASE_TICKS,
                FarmerUtility.TILL_EXHAUSTION,
                true,
                CivilianSkill.CULTIVATION,
                CivilianSkills.TILL_SOIL_BASE_XP,
                true
        );
    }

    @Override
    public String name() {
        return actionName + "(" + target.toShortString() + ")";
    }

    /** True if a rookie mistake caused this interaction swing to whiff without changing the block. */
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
        fumbled = false;
        civ.getNavigation().stop();
        if (toolFilter != null) {
            EquipAction.equipFromPockets(civ, toolFilter);
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
        if (!stillValid.test(level, target)) {
            return Status.FAILED;
        }
        ItemStack held = civ.getMainHandItem();
        if (toolFilter != null && !toolFilter.test(held)) {
            if (!EquipAction.equipFromPockets(civ, toolFilter)) {
                return Status.FAILED;
            }
            held = civ.getMainHandItem();
        }

        ticks++;
        civ.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 30.0F, 30.0F);
        if (ticks == 1 || ticks % 8 == 0) {
            civ.swing(InteractionHand.MAIN_HAND);
        }
        civ.schedule().addWorkDoneTicks(1);

        if (ticks < durationTicks) {
            return Status.RUNNING;
        }

        double skillLevel = skill != null ? civ.skills().level(skill) : CivilianSkills.ROOKIE_SKILL_CEILING;
        fumbled = skill != null && civ.getRandom().nextDouble() < civ.currentFumbleChance(skill);
        if (!fumbled) {
            boolean ok = interaction.execute(level, civ, target, held);
            if (!ok) {
                return Status.FAILED;
            }
        }
        if (damageHeldTool && toolFilter != null && !held.isEmpty() && held.isDamageableItem()) {
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
            double xp = fumbled ? baseXp * CivilianSkills.FUMBLE_ATTEMPT_XP_FACTOR : baseXp;
            civ.awardSkillActionXp(level, skill, xp);
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
