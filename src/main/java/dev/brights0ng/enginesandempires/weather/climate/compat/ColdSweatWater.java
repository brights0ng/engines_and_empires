package dev.brights0ng.enginesandempires.weather.climate.compat;

import java.util.Optional;

import com.momosoftworks.coldsweat.api.temperature.modifier.WaterTempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.api.util.placement.Matcher;
import com.momosoftworks.coldsweat.api.util.placement.Placement;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.climate.RainSoak;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.rain.WeatherQueries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Water and ice with Cold Sweat (weather phase 8e-1, {@code claude/weather-backbone-phase8e.md}; Bright, 2026-10-15).
 *
 * <ul>
 *   <li><b>Rain soaks people.</b> Cold Sweat only starts a person's wetness in rain when vanilla's global
 *       {@code level.isRaining()} says so, which the pack holds clear in the Overworld; and then its wetness modifier
 *       only soaks by the same check. So in the Overworld the pack does the soaking: every 5 ticks (Cold Sweat's own
 *       cadence), for anyone out in what falls, it adds Cold Sweat's own {@code WaterTempModifier} if there isn't
 *       one and deepens its wetness by {@link RainSoak} (Cold Sweat's soak speed and cap, scaled by what falls).
 *       Cold Sweat still dries it. Server only; the client gets the modifier from the server.</li>
 *   <li><b>The pack owns freezing and melting</b> ({@code SurfaceWeather}), so Cold Sweat's custom water freezing
 *       (its {@code Biome.shouldFreeze} and {@code IceBlock.randomTick} replacements) is forced off, whatever its
 *       config file says: set again at server start, on every Cold Sweat config (re)load, and checked every five
 *       seconds, with a warning in the log the first time it has to.</li>
 * </ul>
 */
public final class ColdSweatWater {

    private static boolean warned;

    static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, ColdSweatWater::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, ColdSweatWater::onServerStarting);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, ColdSweatWater::onServerTick);
        modEventBus.addListener(EventPriority.LOWEST, ColdSweatWater::onConfigLoading);
        modEventBus.addListener(EventPriority.LOWEST, ColdSweatWater::onConfigReloading);
    }

    // ---- rain

    private static void onPlayerTick(PlayerTickEvent.Pre event) {
        Player player = event.getEntity();
        if (!(player.level() instanceof ServerLevel level) || player.tickCount % 5 != 0 || player.isSpectator()
                || player.isInWater() || !WeatherOwnership.owns(level)) {
            return;
        }
        soak(player, WeatherQueries.precipitationAt(level, player.blockPosition()));
    }

    /**
     * One update (5 ticks) of rain on {@code entity}: what falls ({@code here}) wets it by {@link RainSoak}. Returns
     * whether it got any wetter or was soaked at all (false: nothing falls, or it is immune to being wet).
     */
    public static boolean soak(LivingEntity entity, LocalWeather.Here here) {
        double factor = RainSoak.factor(here.falling() ? here.precip() : null, here.strength());
        if (factor <= 0) {
            return false;
        }
        Optional<WaterTempModifier> wet = Temperature.getModifier(entity, Temperature.Trait.WORLD, WaterTempModifier.class);
        if (wet.isEmpty()) {
            Temperature.addModifier(entity, new WaterTempModifier().tickRate(5), Temperature.Trait.WORLD,
                    Placement.LAST.noDuplicates(Matcher.SAME_CLASS));
            wet = Temperature.getModifier(entity, Temperature.Trait.WORLD, WaterTempModifier.class);
        }
        if (wet.isEmpty()) {
            return false;
        }
        WaterTempModifier modifier = wet.get();
        double now = modifier.getTemperature();
        double next = RainSoak.soak(now, factor, ConfigSettings.RAIN_SOAK_SPEED.get(), ConfigSettings.MAX_RAIN_SOAK.get(), 1);
        if (next != now) {
            modifier.setTemperature(next);
        }
        return true;
    }

    // ---- freezing

    private static void onServerStarting(ServerStartingEvent event) {
        forceFreezeOff();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 == 0) {
            forceFreezeOff();
        }
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        if (ColdSweatCompat.MOD_ID.equals(event.getConfig().getModId())) {
            forceFreezeOff();
        }
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (ColdSweatCompat.MOD_ID.equals(event.getConfig().getModId())) {
            forceFreezeOff();
        }
    }

    /** Cold Sweat's custom freezing off. Returns whether it had been on. */
    public static boolean forceFreezeOff() {
        try {
            if (!ConfigSettings.USE_CUSTOM_WATER_FREEZE_BEHAVIOR.get()) {
                return false;
            }
            ConfigSettings.USE_CUSTOM_WATER_FREEZE_BEHAVIOR.set(false);
            if (!warned) {
                warned = true;
                EnginesAndEmpires.LOGGER.warn("Cold Sweat's \"Custom Freezing Behavior\" is on in its world config; the pack owns "
                        + "freezing and melting, so it is forced off. Set it to false in coldsweat/world.toml to silence this.");
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            // Cold Sweat's config not loaded yet: the next call (config load, server start) does it
            return false;
        }
    }

    private ColdSweatWater() {
    }
}
