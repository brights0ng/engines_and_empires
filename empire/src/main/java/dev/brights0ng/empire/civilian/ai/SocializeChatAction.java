// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Level-4 socializing primitive: approaches a nearby citizen or empire member, faces them, initiates a conversation
 * when available, and credits active socializing time toward today's social quota.
 */
public final class SocializeChatAction implements CivilianAction {

    private static final double APPROACH_DIST = 3.2;
    private static final double SEARCH_RADIUS = 12.0;
    private static final int PULSE_TICKS = 60;
    private static final int REPATH_INTERVAL = 20;

    @Nullable
    private UUID partnerEntityId;
    private int ticks;
    private int repathTicks;

    public SocializeChatAction(@Nullable UUID partnerEntityId) {
        this.partnerEntityId = partnerEntityId;
    }

    @Override
    public String name() {
        return "socialize_chat";
    }

    @Override
    public void start(AiContext ctx) {
        ticks = 0;
        repathTicks = 0;
    }

    @Override
    public Status tick(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        ticks++;

        Entity partner = resolvePartner(ctx);
        if (partner == null) {
            // Still count mild lingering social time if at a gathering spot with no active partner yet, then finish pulse
            if (ticks >= PULSE_TICKS) {
                return Status.SUCCEEDED;
            }
            return Status.RUNNING;
        }

        double distSq = civ.distanceToSqr(partner);
        if (distSq > APPROACH_DIST * APPROACH_DIST) {
            repathTicks++;
            if (repathTicks >= REPATH_INTERVAL || civ.getNavigation().isDone()) {
                repathTicks = 0;
                civ.getNavigation().moveTo(partner, MoveToAction.DEFAULT_WALK_SPEED);
            }
            if (ticks >= PULSE_TICKS * 2) {
                return Status.FAILED;
            }
            return Status.RUNNING;
        }

        civ.getNavigation().stop();
        civ.getLookControl().setLookAt(partner, 30.0F, 30.0F);
        civ.schedule().addSocialDoneTicks(1);

        if (partner instanceof CivilianEntity other && civ.talkPartner() == null) {
            civ.tryStartConversationWith(other);
        } else if (partner instanceof Player player) {
            civ.fulfilment().recordSocialInteraction(player.getUUID(), civ.food().activeTicks());
        }

        if (ticks >= PULSE_TICKS && civ.talkPartner() == null) {
            return Status.SUCCEEDED;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(AiContext ctx) {
        ctx.civilian().getNavigation().stop();
    }

    @Override
    public boolean canYieldCleanly() {
        return ticks >= 20;
    }

    @Nullable
    private Entity resolvePartner(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        if (partnerEntityId != null) {
            Entity e = ctx.level().getEntity(partnerEntityId);
            if (e != null && e.isAlive() && !e.isRemoved()
                    && civ.distanceToSqr(e) <= SEARCH_RADIUS * SEARCH_RADIUS) {
                if (e instanceof CivilianEntity other && (other.isLyingDown() || other.status() == CivilianEntity.Status.LEAVING)) {
                    partnerEntityId = null;
                } else {
                    return e;
                }
            } else {
                partnerEntityId = null;
            }
        }
        // Fallback: find closest live non-leaving civilian nearby
        CivilianEntity best = null;
        double bestD2 = SEARCH_RADIUS * SEARCH_RADIUS;
        for (CivilianEntity other : ctx.level().getEntitiesOfClass(
                CivilianEntity.class, civ.getBoundingBox().inflate(SEARCH_RADIUS))) {
            if (other != civ && other.isAlive() && !other.isLyingDown()
                    && other.status() != CivilianEntity.Status.LEAVING) {
                double d2 = civ.distanceToSqr(other);
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = other;
                }
            }
        }
        if (best != null) {
            partnerEntityId = best.getUUID();
        }
        return best;
    }
}
