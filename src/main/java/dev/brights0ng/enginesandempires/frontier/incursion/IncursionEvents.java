package dev.brights0ng.enginesandempires.frontier.incursion;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.FrontierTags;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevels;
import dev.brights0ng.enginesandempires.frontier.tier.TierParams;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/** Keeps incursions going: time passing, their mobs joining and leaving, and what those mobs kill. */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class IncursionEvents {

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Incursions.of(level).tick();
            if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                NightHold.of(level).tick(level);
            }
        }
    }

    /** No sleeping through an incursion, or through a night the hearts are holding. */
    @SubscribeEvent
    static void onCanSleep(net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        if (NightHold.of(level).held() > 0 || !Incursions.of(level).active().isEmpty()) {
            event.setProblem(Player.BedSleepingProblem.NOT_SAFE);
        }
    }

    /** A Warden always leaves its heart behind. */
    @SubscribeEvent
    static void onDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.monster.warden.Warden warden
                && warden.level() instanceof ServerLevel) {
            event.getDrops().add(new net.minecraft.world.entity.item.ItemEntity(warden.level(), warden.getX(), warden.getY() + 0.5,
                    warden.getZ(), new net.minecraft.world.item.ItemStack(dev.brights0ng.enginesandempires.frontier.FrontierContent.SQUELCHING_HEART.get())));
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Incursions.forget(level);
        }
    }

    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof Mob mob
                && mob.getTags().contains(Incursion.TAG)) {
            Incursions.of(level).onMobJoin(mob);
        }
    }

    /** A retreating mob is gone once no player is near (a Warden, once it has dug back down). */
    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity.tickCount % 20 != 0 || !(entity instanceof Mob mob) || !(entity.level() instanceof ServerLevel level)
                || !IncursionGoals.isRetreating(mob)) {
            return;
        }
        if (IncursionGoals.shouldVanish(level, mob)) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(mob.blockPosition().below())),
                    mob.getX(), mob.getY() + 0.2, mob.getZ(), 20, 0.4, 0.1, 0.4, 0.1);
            mob.discard();
        }
    }

    /** Incursion mobs break no blocks, as storms don't. */
    @SubscribeEvent
    static void onGriefing(EntityMobGriefingEvent event) {
        if (event.getEntity() != null && event.getEntity().getTags().contains(Incursion.TAG)) {
            event.setCanGrief(false);
        }
    }

    /**
     * A villager, pillager or colonist killed by an incursion costs the land around it a day of inhabited time. So does a
     * player, once per player per incursion.
     */
    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || !(event.getSource().getEntity() instanceof Mob killer)) {
            return;
        }
        Incursion incursion = Incursions.of(level).forMob(killer);
        if (incursion == null) {
            return;
        }
        boolean counts = victim instanceof Player player ? incursion.firstKillOf(player.getUUID())
                : victim.getType().is(FrontierTags.INHABITANTS);
        FrontierLevel frontier = FrontierLevels.of(level);
        if (counts && frontier != null) {
            frontier.removeHabitation(victim.blockPosition(), TierParams.DAY);
            Incursions.of(level).data().markChanged();
        }
    }

    /** Registers the loud machines' data map (mod bus; added in the mod class). */
    public static void registerDataMaps(RegisterDataMapTypesEvent event) {
        event.register(LoudMachines.DATA_MAP);
    }

    @SubscribeEvent
    static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        LoudMachines.clearCache();
    }

    private IncursionEvents() {
    }
}
