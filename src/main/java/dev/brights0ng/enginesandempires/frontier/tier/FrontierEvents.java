package dev.brights0ng.enginesandempires.frontier.tier;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.FrontierTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Feeds the world into {@link FrontierLevel}: levels and chunks loading, time passing, and people being about. (Block
 * changes come in through {@code LevelChunkFrontierMixin}, since events miss explosions, pistons and Create contraptions.)
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class FrontierEvents {

    /** How often people are counted, in ticks. Each counting adds this many ticks of inhabited time. */
    public static final int INHABIT_INTERVAL = 20;

    @SubscribeEvent
    static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FrontierLevels.attach(level);
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FrontierLevels.detach(level);
        }
    }

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        FrontierLevel frontier = FrontierLevels.of(level);
        if (frontier == null) {
            return;
        }
        if (level.getServer().isSameThread()) {
            frontier.onChunkLoad(chunk);
        } else {
            // Handled on the server thread, if the chunk is still there by then.
            level.getServer().execute(() -> {
                if (level.getChunkSource().getChunkNow(chunk.getPos().x, chunk.getPos().z) == chunk) {
                    frontier.onChunkLoad(chunk);
                }
            });
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk
                && level.getServer().isSameThread()) {
            FrontierLevel frontier = FrontierLevels.of(level);
            if (frontier != null) {
                frontier.onChunkUnload(chunk);
            }
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        FrontierLevel frontier = FrontierLevels.of(event.getLevel());
        if (frontier == null) {
            return;
        }
        frontier.tick();
        ServerLevel level = frontier.level();
        if (level.getGameTime() % INHABIT_INTERVAL == 0) {
            for (ServerPlayer player : level.players()) {
                if (!player.isSpectator()) {
                    frontier.inhabit(player.blockPosition(), INHABIT_INTERVAL, false);
                }
            }
        }
    }

    /** Villagers, colonists and illagers (the inhabitants tag) make land inhabited as they go about, as players do. */
    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity.tickCount % INHABIT_INTERVAL != 0 || entity instanceof Player || !isInhabitant(entity)) {
            return;
        }
        FrontierLevel frontier = FrontierLevels.of(entity.level());
        if (frontier != null) {
            frontier.inhabit(entity.blockPosition(), INHABIT_INTERVAL, true);
        }
    }

    /**
     * A resident loading in (or spawning) makes its land count as lived in straight away. This is what keeps villages and
     * colonies Settled while no player is around: nothing ticks in unloaded chunks, so without it they would be found faded.
     */
    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        FrontierLevel guarded = FrontierLevels.of(event.getLevel());
        if (guarded != null) {
            guarded.guards().onJoin(entity);
        }
        if (entity instanceof Player || !isInhabitant(entity)) {
            return;
        }
        FrontierLevel frontier = FrontierLevels.of(event.getLevel());
        if (frontier != null) {
            frontier.residentArrived(entity.blockPosition());
        }
    }

    @SubscribeEvent
    static void onEntityLeave(EntityLeaveLevelEvent event) {
        FrontierLevel frontier = FrontierLevels.of(event.getLevel());
        if (frontier != null) {
            frontier.guards().onLeave(event.getEntity());
        }
    }

    private static boolean isInhabitant(Entity entity) {
        return entity.getType().is(FrontierTags.INHABITANTS);
    }

    private FrontierEvents() {
    }
}
