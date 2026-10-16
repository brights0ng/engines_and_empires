package dev.brights0ng.enginesandempires.weather.climate.compat;

import java.util.Set;

import com.momosoftworks.coldsweat.api.event.core.init.DefaultTempModifiersEvent;
import com.momosoftworks.coldsweat.api.event.core.registry.TempModifierRegisterEvent;
import com.momosoftworks.coldsweat.api.util.placement.Matcher;
import com.momosoftworks.coldsweat.api.util.placement.Placement;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.enginesandempires.weather.rain.ClientWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Cold Sweat's world temperature, from the pack's climate (weather phase 8d, {@code claude/weather-backbone-phase8d.md};
 * Bright, 2026-10-15).
 *
 * <p>In the Overworld the pack's air temperature ({@code weather/climate/Temperature}: biome climate, height, season,
 * air masses and fronts, day and night, underground) replaces Cold Sweat's own biome, elevation, cave-biome and
 * Serene Seasons terms in the {@code WORLD} stack: those modifiers are removed from every entity's default stack and
 * a single {@link ClimateTempModifier} goes first instead. Everything personal (armor, acclimation, items, blocks,
 * water, food, damage) stays Cold Sweat's. Other dimensions are untouched.
 *
 * <p>Uses Cold Sweat 2.4.3's {@code DefaultTempModifiersEvent} and {@code TempModifierRegisterEvent}. Only loaded
 * when Cold Sweat is installed (see {@link #registerIfLoaded}); nothing else in the mod names its classes.
 */
public final class ColdSweatCompat {

    public static final String MOD_ID = "cold_sweat";

    /** Our modifier's id in Cold Sweat's registry. */
    public static final ResourceLocation CLIMATE_ID = ResourceLocation.fromNamespaceAndPath(EnginesAndEmpires.MODID, "climate");

    /** Cold Sweat's own world terms that the pack's climate replaces (by registry id, so none needs its mod loaded). */
    static final Set<ResourceLocation> REPLACED = Set.of(
            ResourceLocation.fromNamespaceAndPath("cold_sweat", "biomes"),
            ResourceLocation.fromNamespaceAndPath("cold_sweat", "elevation"),
            ResourceLocation.fromNamespaceAndPath("cold_sweat", "cave_biomes"),
            // Shade is replaced by sun warmth: it's not that shade takes heat, the sun gives it (8e-2)
            ResourceLocation.fromNamespaceAndPath("cold_sweat", "shade"),
            ResourceLocation.fromNamespaceAndPath("sereneseasons", "season"),
            // Local warmth and cold come from the heat simulation (8f-3): rooms, air and radiant heat; on ships too
            // (8f-5)
            ResourceLocation.fromNamespaceAndPath("cold_sweat", "blocks"),
            ResourceLocation.fromNamespaceAndPath("cold_sweat", "sublevel_blocks"));

    /** Cold Sweat's temperature unit: 1 MC is 25 C (its comfort range 0.5-1.7 MC is 12.5-42.5 C). */
    public static final double C_PER_MC = 25.0;

    /** The comfortable range under every difficulty (Bright, 2026-10-15), C. */
    public static final double COMFORT_MIN_C = 10.0;
    public static final double COMFORT_MAX_C = 35.0;

    /** Hooks into Cold Sweat if it is present. Safe to call either way. */
    public static void registerIfLoaded(IEventBus modEventBus) {
        if (ModList.get().isLoaded(MOD_ID)) {
            Hook.register(modEventBus);
        }
    }

    /** Degrees C as Cold Sweat's units. */
    public static double toMc(double celsius) {
        return celsius / C_PER_MC;
    }

    /**
     * The air temperature at {@code pos} in {@code level}, C: the server's full answer, or on a client the synced grid
     * (no underground). NaN on a client that has not been synced yet.
     */
    public static double airTemperature(Level level, BlockPos pos) {
        if (level instanceof ServerLevel server) {
            return dev.brights0ng.enginesandempires.weather.climate.Temperature.at(server, pos);
        }
        return ClientWeather.temperature(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** Kept separate so that Cold Sweat's classes are only looked up once it is known to be there. */
    private static final class Hook {

        static void register(IEventBus modEventBus) {
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, Hook::onRegisterModifiers);
            // After Cold Sweat's own listener (HIGHEST), which fills the default stacks we then rewrite
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, Hook::onDefaultModifiers);
            // Once everything is constructed: the difficulty presets all get the pack's comfort range
            modEventBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(Hook::sameComfortRangeEverywhere));
            // 8e-1: rain soaks people, and Cold Sweat's own freezing and ice melting stay off
            ColdSweatWater.register(modEventBus);
            // 8f-3: the hearth family as heat simulation sources
            ColdSweatHearths.registerDrivers();
        }

        /**
         * Cold Sweat's difficulty slider writes a preset's comfort range (super easy 40-120 F ... hard 55-90 F) into
         * the settings when it is chosen. Every preset's range becomes {@link #COMFORT_MIN_C}-{@link #COMFORT_MAX_C}
         * instead, so the slider only changes the rest (rate, damage, ...). The presets are a private map per
         * difficulty in 2.4.3, replaced here by a copy; custom has none and keeps whatever the player sets.
         */
        static void sameComfortRangeEverywhere() {
            try {
                java.lang.reflect.Field field = ConfigSettings.Difficulty.class.getDeclaredField("settings");
                field.setAccessible(true);
                ResourceLocation minKey = ConfigSettings.getKey(ConfigSettings.MIN_TEMP);
                ResourceLocation maxKey = ConfigSettings.getKey(ConfigSettings.MAX_TEMP);
                double min = toMc(COMFORT_MIN_C);
                double max = toMc(COMFORT_MAX_C);
                for (ConfigSettings.Difficulty difficulty : ConfigSettings.Difficulty.values()) {
                    if (difficulty == ConfigSettings.Difficulty.CUSTOM) {
                        continue;
                    }
                    difficulty.getOrDefault(minKey, 0.0); // makes the preset build its map
                    @SuppressWarnings("unchecked")
                    java.util.Map<ResourceLocation, java.util.function.Supplier<?>> copy =
                            new java.util.HashMap<>((java.util.Map<ResourceLocation, java.util.function.Supplier<?>>) field.get(difficulty));
                    copy.put(minKey, () -> min);
                    copy.put(maxKey, () -> max);
                    field.set(difficulty, copy);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                EnginesAndEmpires.LOGGER.warn("Could not give Cold Sweat's difficulties one comfort range; its own presets stay", e);
            }
        }

        private static void onRegisterModifiers(TempModifierRegisterEvent event) {
            event.register(CLIMATE_ID, ClimateTempModifier::new);
        }

        private static void onDefaultModifiers(DefaultTempModifiersEvent event) {
            if (event.getEntity().level().dimension() != Level.OVERWORLD) {
                return;
            }
            // Only entities on the full stack; the simple per-species climate modifier needs none of this
            boolean replaced = event.getModifiers(com.momosoftworks.coldsweat.api.util.Temperature.Trait.WORLD)
                    .removeIf(mod -> REPLACED.contains(mod.getID()));
            if (replaced) {
                int rate = event.getEntity() instanceof net.minecraft.world.entity.player.Player ? 20 : 100;
                event.addModifier(com.momosoftworks.coldsweat.api.util.Temperature.Trait.WORLD,
                        new ClimateTempModifier().tickRate(rate), Placement.FIRST.noDuplicates(Matcher.SAME_CLASS));
            }
        }
    }

    private ColdSweatCompat() {
    }
}
