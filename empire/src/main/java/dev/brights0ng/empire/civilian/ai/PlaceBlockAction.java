// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.Objects;
import java.util.function.Function;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reusable Level-4 block-placing / seed-planting primitive: equips a matching item into {@code MAIN_HAND}, paces over
 * a duration scaled by labor speed, Fulfilment productivity, and occupational skill, validates {@code canSurvive},
 * places the block (or drops the item on a rookie fumble), consumes 1 item, applies skill-scaled exhaustion, awards
 * skill XP, and credits {@code workDoneTicks}.
 */
public final class PlaceBlockAction implements CivilianAction {

    private static final double REACH_MARGIN = 0.85;

    private final BlockPos target;
    private final Predicate<ItemStack> itemFilter;
    private final Function<ItemStack, BlockState> stateFactory;
    private final int baseTicks;
    private final float baseExhaustion;
    private final boolean heavyLabor;
    @Nullable
    private final CivilianSkill skill;
    private final double baseXp;

    private int ticks;
    private int durationTicks = 1;
    private boolean fumbled;

    public PlaceBlockAction(BlockPos target, Predicate<ItemStack> itemFilter,
                            Function<ItemStack, BlockState> stateFactory,
                            int baseTicks, float baseExhaustion, boolean heavyLabor) {
        this(target, itemFilter, stateFactory, baseTicks, baseExhaustion, heavyLabor, null, 0.0);
    }

    public PlaceBlockAction(BlockPos target, Predicate<ItemStack> itemFilter,
                            Function<ItemStack, BlockState> stateFactory,
                            int baseTicks, float baseExhaustion, boolean heavyLabor,
                            @Nullable CivilianSkill skill, double baseXp) {
        this.target = Objects.requireNonNull(target, "target").immutable();
        this.itemFilter = Objects.requireNonNull(itemFilter, "itemFilter");
        this.stateFactory = Objects.requireNonNull(stateFactory, "stateFactory");
        this.baseTicks = Math.max(1, baseTicks);
        this.baseExhaustion = Math.max(0.0F, baseExhaustion);
        this.heavyLabor = heavyLabor;
        this.skill = skill;
        this.baseXp = Math.max(0.0, baseXp);
    }

    @Override
    public String name() {
        return "place_block(" + target.toShortString() + ")";
    }

    /** True if a rookie mistake caused the civilian to drop the item on the ground instead of placing/planting it. */
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
        EquipAction.equipFromPockets(civ, itemFilter);
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

        double skillLevel = skill != null ? civ.skills().level(skill) : CivilianSkills.ROOKIE_SKILL_CEILING;
        fumbled = skill != null && civ.getRandom().nextDouble() < civ.currentFumbleChance(skill);
        if (fumbled) {
            // Non-destructive rookie fumble: drops 1 seed/item onto the ground instead of planting it.
            ItemStack dropped = held.copyWithCount(1);
            held.shrink(1);
            if (held.isEmpty()) {
                civ.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            Block.popResource(level, target, dropped);
        } else {
            level.setBlock(target, toPlace, Block.UPDATE_ALL);
            SoundType sound = toPlace.getSoundType(level, target, civ);
            level.playSound(null, target, sound.getPlaceSound(), SoundSource.BLOCKS,
                    (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
            held.shrink(1);
            if (held.isEmpty()) {
                civ.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
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
