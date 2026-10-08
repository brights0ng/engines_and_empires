package dev.brights0ng.enginesandempires.weather.surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.ships.ShipCover;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Hail on players and mobs (phase 5c of the weather backbone; Bright, 2026-10-08).
 *
 * <ul>
 *   <li>Every living thing out under hail (nothing with collision above it, leaves and glass included: the
 *       motion-blocking heightmap) is hit on average every {@link WeatherConfig#hailInterval} ticks.</li>
 *   <li>Anything worn on the head stops the hit and loses {@link #HELMET_WEAR} durability.</li>
 *   <li>Otherwise a flat {@link WeatherConfig#hailDamage} that ignores armour (the {@code hail} damage type is in
 *       {@code bypasses_armor} and {@code bypasses_enchantments}), with the normal hurt flash and sound, but never
 *       below 1 health.</li>
 * </ul>
 *
 * Runs once a second per level; whether it hails is asked once per chunk ({@link LocalWeather#at}).
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class HailDamage {

    /** Durability a helmet loses to a hailstone (Bright, 2026-10-08). */
    public static final int HELMET_WEAR = 3;
    private static final int PERIOD = 20;

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % PERIOD != 0
                || !WeatherOwnership.owns(level) || LocalWeather.clouds(level).isEmpty()) {
            return;
        }
        double chance = (double) PERIOD / WeatherConfig.hailInterval();
        Map<Long, Boolean> hailing = new HashMap<>();
        RandomSource random = level.random;
        List<LivingEntity> struck = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof LivingEntity living) || living instanceof ArmorStand || !living.isAlive()
                    || living.isSpectator() || !exposed(level, living)) {
                continue;
            }
            int cx = living.getBlockX() >> 4;
            int cz = living.getBlockZ() >> 4;
            boolean hail = hailing.computeIfAbsent(ChunkPos.asLong(cx, cz), k -> {
                LocalWeather.Here here = LocalWeather.at(level, (cx << 4) + 8, living.getY(), (cz << 4) + 8);
                return here.falling() && here.precip() == Precip.HAIL;
            });
            if (hail && random.nextDouble() < chance) {
                struck.add(living);
            }
        }
        // Hit after the sweep: hurting can make mods spawn or move things, which the entity list can't take mid-loop.
        for (LivingEntity living : struck) {
            hit(level, living);
        }
    }

    /**
     * Whether {@code living} is out under the sky: nothing with collision (leaves and glass included) above it.
     * (Measured from half a block above its feet, so standing on a slab still counts as out.) A ship overhead
     * shelters it too (phase 5d); standing on a deck in the open doesn't.
     */
    public static boolean exposed(ServerLevel level, LivingEntity living) {
        int roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING, living.getBlockX(), living.getBlockZ());
        return Mth.floor(living.getY() + 0.5) >= roof
                && !ShipCover.covered(level, living.getX(), living.getY() + 0.5, living.getZ());
    }

    /** One hailstone on {@code living}: a helmet takes the wear, or it takes the damage (never below 1 health). */
    public static void hit(ServerLevel level, LivingEntity living) {
        ItemStack head = living.getItemBySlot(EquipmentSlot.HEAD);
        if (!head.isEmpty()) {
            if (head.isDamageableItem()) {
                head.hurtAndBreak(HELMET_WEAR, living, EquipmentSlot.HEAD);
            }
            return;
        }
        float amount = (float) Math.min(WeatherConfig.hailDamage(), living.getHealth() - 1);
        if (amount <= 0) {
            return;
        }
        living.hurt(source(level), amount);
    }

    public static DamageSource source(ServerLevel level) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(SurfaceContent.HAIL));
    }

    private HailDamage() {
    }
}
