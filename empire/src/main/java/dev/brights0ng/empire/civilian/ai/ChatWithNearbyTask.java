// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Level-3 socializing task: seeks out a nearby available citizen or online empire member and holds a conversation.
 */
public final class ChatWithNearbyTask implements CivilianTask {

    private static final double SEARCH_RADIUS = 12.0;

    @Nullable
    private UUID targetPartnerId;
    @Nullable
    private SocializeChatAction action;

    @Override
    public String name() {
        return "chat_nearby";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        Entity partner = findNearestSocialPartner(ctx);
        targetPartnerId = partner != null ? partner.getUUID() : null;
        return partner != null;
    }

    @Override
    public double baseWeight() {
        return 80.0;
    }

    @Override
    public double urgency(AiContext ctx) {
        return 1.0;
    }

    @Override
    public void start(AiContext ctx) {
        action = new SocializeChatAction(targetPartnerId);
        action.start(ctx);
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (action == null) {
            start(ctx);
        }
        return action.tick(ctx);
    }

    @Override
    public void stop(AiContext ctx) {
        if (action != null) {
            action.stop(ctx);
            action = null;
        }
    }

    @Override
    public boolean canYieldCleanly() {
        return action == null || action.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return action != null ? action.name() : "socialize_chat";
    }

    @Nullable
    public static Entity findNearestSocialPartner(AiContext ctx) {
        CivilianEntity civ = ctx.civilian();
        double bestD2 = SEARCH_RADIUS * SEARCH_RADIUS;
        Entity best = null;
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
            return best;
        }
        if (ctx.empire() != null) {
            for (Player player : ctx.level().players()) {
                if (!player.isSpectator() && player.isAlive() && ctx.empire().isMember(player.getUUID())) {
                    double d2 = civ.distanceToSqr(player);
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = player;
                    }
                }
            }
        }
        return best;
    }
}
