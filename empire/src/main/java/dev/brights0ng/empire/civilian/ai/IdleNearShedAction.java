// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.region.FarmRegionScanner;
import dev.brights0ng.empire.rules.ChunkKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Level-4 Farmer shed-idling action: loiters naturally within a 5–6 block radius of the farm's seed shed while on
 * duty (crediting {@code workDoneTicks}), alternating between relaxed pauses (looking around or glancing at nearby
 * players/civilians), short strolls on non-farmland ground, and walking over to chat with nearby civilians.
 *
 * <p>Stays {@link Status#RUNNING} continuously so {@link CivilianSlotRunner}'s periodic work recheck interval
 * ({@link dev.brights0ng.empire.rules.FarmerUtility#IDLE_WORK_RECHECK_TICKS}) governs when the farmer checks for new
 * field work.
 */
public final class IdleNearShedAction implements CivilianAction {

    /** Maximum horizontal wander radius around the shed stand spot (5–6 blocks around the shed). */
    public static final double LOITER_RADIUS = 5.5;
    /** Maximum distance from the seed shed block itself for chosen stroll waypoints. */
    public static final double MAX_SHED_DIST = 6.0;

    private static final double STROLL_SPEED = 0.48;
    private static final double STROLL_MIN_RADIUS = 2.0;
    private static final double STROLL_MIN_STEP_SQ = 1.5 * 1.5;
    private static final double STROLL_REACH = 1.35;
    private static final int STROLL_TIMEOUT_TICKS = 120;
    private static final int STROLL_PICK_ATTEMPTS = 16;

    private static final int PAUSE_MIN_TICKS = 50;
    private static final int PAUSE_MAX_TICKS = 130;
    private static final int LOOK_CHANGE_MIN_TICKS = 35;
    private static final int LOOK_CHANGE_MAX_TICKS = 80;
    private static final double LOOK_ENTITY_RADIUS = 6.0;
    private static final double LOOK_ENTITY_CHANCE = 0.60;

    private static final double CHAT_SEARCH_RADIUS = 6.5;
    private static final double CHAT_APPROACH_DIST = 3.2;
    private static final int CHAT_APPROACH_TIMEOUT_TICKS = 100;
    private static final int CHAT_REPATH_INTERVAL = 20;
    private static final double CHAT_START_CHANCE = 0.45;
    private static final double STROLL_CHANCE = 0.55;
    private static final int CHAT_COOLDOWN_MIN_TICKS = 400;
    private static final int CHAT_COOLDOWN_MAX_TICKS = 800;
    private static final int CHAT_RETRY_COOLDOWN_TICKS = 140;

    private enum Mode {
        PAUSE,
        STROLL,
        APPROACH_CHAT
    }

    private final BlockPos standSpot;
    private final BlockPos shedFocus;

    private Mode mode = Mode.PAUSE;
    private int pauseRemaining;
    private int lookChangeRemaining;
    @Nullable
    private UUID lookEntityId;
    @Nullable
    private Vec3 lookPoint;
    @Nullable
    private MoveToAction strollAction;
    @Nullable
    private UUID chatPartnerId;
    private int chatApproachTicks;
    private int chatRepathTicks;
    private int chatCooldownTicks;

    public IdleNearShedAction(BlockPos standSpot, BlockPos shedFocus) {
        this.standSpot = standSpot.immutable();
        this.shedFocus = shedFocus.immutable();
    }

    @Override
    public String name() {
        return switch (mode) {
            case PAUSE -> "idle_near_shed(look)";
            case STROLL -> "idle_near_shed(stroll)";
            case APPROACH_CHAT -> "idle_near_shed(chat)";
        };
    }

    @Override
    public void start(AiContext ctx) {
        chatCooldownTicks = 0;
        enterPause(ctx);
    }

    @Override
    public Status tick(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        civ.schedule().addWorkDoneTicks(1);
        if (chatCooldownTicks > 0) {
            chatCooldownTicks--;
        }

        switch (mode) {
            case PAUSE -> tickPause(ctx);
            case STROLL -> tickStroll(ctx);
            case APPROACH_CHAT -> tickApproachChat(ctx);
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(AiContext ctx) {
        if (strollAction != null) {
            strollAction.stop(ctx);
            strollAction = null;
        }
        chatPartnerId = null;
        lookEntityId = null;
        lookPoint = null;
        ctx.civilian().getNavigation().stop();
    }

    @Override
    public boolean canYieldCleanly() {
        return true;
    }

    private void enterPause(AiContext ctx) {
        if (strollAction != null) {
            strollAction.stop(ctx);
            strollAction = null;
        }
        chatPartnerId = null;
        mode = Mode.PAUSE;
        CivilianEntity civ = ctx.civilian();
        civ.getNavigation().stop();
        pauseRemaining = randomRange(civ.getRandom(), PAUSE_MIN_TICKS, PAUSE_MAX_TICKS);
        lookChangeRemaining = 0;
    }

    private void tickPause(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        if (lookChangeRemaining <= 0) {
            pickLookTarget(ctx);
            lookChangeRemaining = randomRange(civ.getRandom(), LOOK_CHANGE_MIN_TICKS, LOOK_CHANGE_MAX_TICKS);
        } else {
            lookChangeRemaining--;
        }
        applyLook(ctx);

        pauseRemaining--;
        if (pauseRemaining <= 0) {
            chooseNextBeat(ctx);
        }
    }

    private void chooseNextBeat(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        RandomSource rng = civ.getRandom();

        // Keep gently leashed near the seed shed if pushed or wandered out beyond the loiter radius.
        double distSq = standSpot.distToCenterSqr(civ.position());
        if (distSq > (LOITER_RADIUS + 0.5) * (LOITER_RADIUS + 0.5)) {
            startStroll(ctx, standSpot);
            return;
        }

        if (chatCooldownTicks <= 0) {
            CivilianEntity partner = findChatPartner(ctx);
            if (partner != null && rng.nextDouble() < CHAT_START_CHANCE) {
                mode = Mode.APPROACH_CHAT;
                chatPartnerId = partner.getUUID();
                chatApproachTicks = 0;
                chatRepathTicks = CHAT_REPATH_INTERVAL;
                return;
            }
        }

        if (rng.nextDouble() < STROLL_CHANCE) {
            BlockPos dest = pickStrollTarget(ctx);
            if (dest != null) {
                startStroll(ctx, dest);
                return;
            }
        }

        enterPause(ctx);
    }

    private void startStroll(AiContext ctx, BlockPos target) {
        mode = Mode.STROLL;
        lookEntityId = null;
        lookPoint = null;
        strollAction = new MoveToAction(target, STROLL_REACH, STROLL_SPEED, null, null, STROLL_TIMEOUT_TICKS);
        strollAction.start(ctx);
    }

    private void tickStroll(AiContext ctx) {
        if (strollAction == null) {
            enterPause(ctx);
            return;
        }
        Status status = strollAction.tick(ctx);
        if (status != Status.RUNNING) {
            strollAction.stop(ctx);
            strollAction = null;
            enterPause(ctx);
        }
    }

    private void tickApproachChat(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        chatApproachTicks++;
        CivilianEntity partner = resolveChatPartner(ctx);
        if (partner == null || chatApproachTicks >= CHAT_APPROACH_TIMEOUT_TICKS) {
            chatCooldownTicks = CHAT_RETRY_COOLDOWN_TICKS;
            enterPause(ctx);
            return;
        }

        double distSq = civ.distanceToSqr(partner);
        if (distSq <= CHAT_APPROACH_DIST * CHAT_APPROACH_DIST) {
            civ.getNavigation().stop();
            civ.getLookControl().setLookAt(partner, 25.0F, 25.0F);
            boolean started = civ.tryStartConversationWith(partner);
            chatCooldownTicks = started
                    ? randomRange(civ.getRandom(), CHAT_COOLDOWN_MIN_TICKS, CHAT_COOLDOWN_MAX_TICKS)
                    : CHAT_RETRY_COOLDOWN_TICKS;
            enterPause(ctx);
            return;
        }

        chatRepathTicks++;
        if (chatRepathTicks >= CHAT_REPATH_INTERVAL || civ.getNavigation().isDone()) {
            chatRepathTicks = 0;
            civ.getNavigation().moveTo(partner, STROLL_SPEED);
        }
    }

    private void pickLookTarget(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        RandomSource rng = civ.getRandom();
        Entity person = findNearbyPersonToLookAt(ctx);
        if (person != null && rng.nextDouble() < LOOK_ENTITY_CHANCE) {
            lookEntityId = person.getUUID();
            lookPoint = null;
            return;
        }
        lookEntityId = null;
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double dist = 4.0 + rng.nextDouble() * 2.5;
        double dy = (rng.nextDouble() - 0.55) * 0.45;
        lookPoint = new Vec3(
                civ.getX() + Math.cos(angle) * dist,
                civ.getEyeY() + dy,
                civ.getZ() + Math.sin(angle) * dist);
    }

    private void applyLook(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        if (lookEntityId != null) {
            Entity target = ctx.level().getEntity(lookEntityId);
            double maxDistSq = (LOOK_ENTITY_RADIUS + 1.5) * (LOOK_ENTITY_RADIUS + 1.5);
            if (target != null && target.isAlive() && !target.isRemoved() && civ.distanceToSqr(target) <= maxDistSq) {
                civ.getLookControl().setLookAt(target, 20.0F, 20.0F);
                return;
            }
            lookEntityId = null;
            lookChangeRemaining = 0;
        }
        if (lookPoint != null) {
            civ.getLookControl().setLookAt(lookPoint.x, lookPoint.y, lookPoint.z, 15.0F, 15.0F);
        }
    }

    @Nullable
    private Entity findNearbyPersonToLookAt(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        double bestDistSq = LOOK_ENTITY_RADIUS * LOOK_ENTITY_RADIUS;
        Entity best = null;
        for (Player player : ctx.level().players()) {
            if (!player.isSpectator() && player.isAlive()) {
                double d2 = civ.distanceToSqr(player);
                if (d2 < bestDistSq && civ.hasLineOfSight(player)) {
                    bestDistSq = d2;
                    best = player;
                }
            }
        }
        for (CivilianEntity other : ctx.level().getEntitiesOfClass(
                CivilianEntity.class, civ.getBoundingBox().inflate(LOOK_ENTITY_RADIUS))) {
            if (other != civ && other.isAlive() && !other.isLyingDown()
                    && other.status() != CivilianEntity.Status.LEAVING) {
                double d2 = civ.distanceToSqr(other);
                if (d2 < bestDistSq && civ.hasLineOfSight(other)) {
                    bestDistSq = d2;
                    best = other;
                }
            }
        }
        return best;
    }

    @Nullable
    private CivilianEntity findChatPartner(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        double maxFarmerDistSq = CHAT_SEARCH_RADIUS * CHAT_SEARCH_RADIUS;
        double maxShedDistSq = (LOITER_RADIUS + 1.5) * (LOITER_RADIUS + 1.5);
        CivilianEntity best = null;
        double bestDistSq = maxFarmerDistSq;
        for (CivilianEntity other : ctx.level().getEntitiesOfClass(
                CivilianEntity.class, civ.getBoundingBox().inflate(CHAT_SEARCH_RADIUS))) {
            if (!isEligibleChatPartner(civ, other, maxShedDistSq)) {
                continue;
            }
            double d2 = civ.distanceToSqr(other);
            if (d2 < bestDistSq && civ.hasLineOfSight(other)) {
                bestDistSq = d2;
                best = other;
            }
        }
        return best;
    }

    @Nullable
    private CivilianEntity resolveChatPartner(AiContext ctx) {
        if (chatPartnerId == null) {
            return null;
        }
        Entity entity = ctx.level().getEntity(chatPartnerId);
        if (!(entity instanceof CivilianEntity other)) {
            return null;
        }
        double maxShedDistSq = (LOITER_RADIUS + 2.0) * (LOITER_RADIUS + 2.0);
        if (!isEligibleChatPartner(ctx.civilian(), other, maxShedDistSq)) {
            return null;
        }
        return other;
    }

    private boolean isEligibleChatPartner(CivilianEntity civ, CivilianEntity other, double maxShedDistSq) {
        if (other == civ || !other.isAlive() || other.isRemoved() || other.isLyingDown()) {
            return false;
        }
        if (other.status() == CivilianEntity.Status.LEAVING
                || other.talkPartner() != null
                || other.dialoguePlayer() != null) {
            return false;
        }
        return standSpot.distToCenterSqr(other.position()) <= maxShedDistSq;
    }

    @Nullable
    private BlockPos pickStrollTarget(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        ServerLevel level = ctx.level();
        EmpireRecord empire = ctx.empire();
        RegionRecord farm = resolveFarm(ctx);
        RandomSource rng = civ.getRandom();
        BlockPos.MutableBlockPos cand = new BlockPos.MutableBlockPos();
        double maxShedDistSq = MAX_SHED_DIST * MAX_SHED_DIST;

        for (int attempt = 0; attempt < STROLL_PICK_ATTEMPTS; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double radius = STROLL_MIN_RADIUS + rng.nextDouble() * (LOITER_RADIUS - STROLL_MIN_RADIUS);
            int nx = standSpot.getX() + (int) Math.round(Math.cos(angle) * radius);
            int nz = standSpot.getZ() + (int) Math.round(Math.sin(angle) * radius);

            if (empire != null && !empire.state().staked().contains(ChunkKeys.pack(nx >> 4, nz >> 4))) {
                continue;
            }
            if (!level.hasChunk(nx >> 4, nz >> 4)) {
                continue;
            }

            for (int dy : new int[] {0, -1, 1, -2, 2}) {
                cand.set(nx, standSpot.getY() + dy, nz);
                if (cand.distSqr(shedFocus) > maxShedDistSq) {
                    continue;
                }
                if (cand.distToCenterSqr(civ.position()) < STROLL_MIN_STEP_SQ) {
                    continue;
                }
                if (!CivilianBeds.isStandable(level, cand)) {
                    continue;
                }
                if (isFieldOrCrop(level, farm, cand)) {
                    continue;
                }
                return cand.immutable();
            }
        }
        return null;
    }

    @Nullable
    private static RegionRecord resolveFarm(AiContext ctx) {
        WorkAssignment work = ctx.civilian().workAssignment();
        if (work == null || work.workplaceRegionId() == null) {
            return null;
        }
        return ctx.data().region(work.workplaceRegionId());
    }

    private static boolean isFieldOrCrop(ServerLevel level, @Nullable RegionRecord farm, BlockPos pos) {
        if (FarmRegionScanner.isFarmland(level.getBlockState(pos.below()))
                || FarmCrops.isBasicCrop(level.getBlockState(pos))) {
            return true;
        }
        if (farm != null) {
            RegionRecord.RoomRecord roomAtFeet = farm.roomAt(pos);
            if (roomAtFeet != null && roomAtFeet.isField()) {
                return true;
            }
            RegionRecord.RoomRecord roomBelow = farm.roomAt(pos.below());
            if (roomBelow != null && roomBelow.isField()) {
                return true;
            }
        }
        return false;
    }

    private static int randomRange(RandomSource rng, int minInclusive, int maxInclusive) {
        return minInclusive + rng.nextInt(Math.max(1, maxInclusive - minInclusive + 1));
    }
}
