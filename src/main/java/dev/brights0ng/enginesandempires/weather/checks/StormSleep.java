package dev.brights0ng.enginesandempires.weather.checks;

import java.util.List;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;

/**
 * Sleep and the localized weather (weather phase 6a; Bright, 2026-10-08).
 *
 * <ul>
 *   <li><b>Sleeping in a daytime thunderstorm:</b> vanilla allows it whenever the (global) thunder darkens the sky, which
 *       is never here. A thunderstorm over the bed now allows it, and lets the player stay asleep while it lasts.</li>
 *   <li><b>The night skip fast-forwards the weather</b> by the skipped time ({@link WeatherSim#advance}, as
 *       {@code /eae weather step}). The simulation runs on game time, which sleeping doesn't move, while the daily
 *       heating reads the time of day, which it does; without this a night's storms would sit frozen into the
 *       morning.</li>
 * </ul>
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class StormSleep {

    @SubscribeEvent
    static void onCanSleep(CanPlayerSleepEvent event) {
        ServerPlayer player = event.getEntity();
        if (event.getProblem() != Player.BedSleepingProblem.NOT_POSSIBLE_NOW
                || !WeatherOwnership.owns(player.level()) || !WeatherQueries.thunderOver(player.level(), event.getPos())) {
            return;
        }
        // Vanilla stops at "not now" before its monster check, so do that check here.
        event.setProblem(!player.isCreative() && monstersNear(player, event.getPos())
                ? Player.BedSleepingProblem.NOT_SAFE : null);
    }

    @SubscribeEvent
    static void onContinueSleeping(CanContinueSleepingEvent event) {
        LivingEntity sleeper = event.getEntity();
        Level level = sleeper.level();
        if (event.getProblem() != Player.BedSleepingProblem.NOT_POSSIBLE_NOW || level.isClientSide()
                || !WeatherOwnership.owns(level)) {
            return;
        }
        BlockPos bed = sleeper.getSleepingPos().orElse(sleeper.blockPosition());
        if (WeatherQueries.thunderOver(level, bed)) {
            event.setContinueSleeping(true);
        }
    }

    /** Lowest priority, so any other mod's change to the wake-up time is already in. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onSleepFinished(SleepFinishedTimeEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !WeatherOwnership.owns(level)) {
            return;
        }
        long skipped = event.getNewTime() - level.getDayTime();
        if (skipped > 0) {
            fastForward(level, skipped);
        }
    }

    /** Runs the level's weather {@code ticks} ahead (the night skip; public for the game tests). */
    public static void fastForward(ServerLevel level, long ticks) {
        long started = System.nanoTime();
        WeatherSim.of(level).advance(ticks);
        EnginesAndEmpiresMod.LOGGER.info("Weather: the night skip ran the weather {} ticks ahead in {} ms", ticks,
                (System.nanoTime() - started) / 1_000_000);
    }

    /** Vanilla's sleeping check: a monster within 8 blocks across and 5 up or down that keeps the player awake. */
    private static boolean monstersNear(ServerPlayer player, BlockPos bed) {
        Vec3 v = Vec3.atBottomCenterOf(bed);
        List<Monster> near = player.level().getEntitiesOfClass(Monster.class,
                new AABB(v.x - 8, v.y - 5, v.z - 8, v.x + 8, v.y + 5, v.z + 8),
                m -> m.isPreventingPlayerRest(player));
        return !near.isEmpty();
    }

    private StormSleep() {
    }
}
