package dev.brights0ng.enginesandempires.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.momosoftworks.coldsweat.api.event.core.init.DefaultTempModifiersEvent;
import com.momosoftworks.coldsweat.api.temperature.modifier.TempModifier;
import dev.brights0ng.enginesandempires.EnginesAndEmpires;
import dev.brights0ng.enginesandempires.weather.climate.Temperature;
import dev.brights0ng.enginesandempires.weather.climate.compat.ClimateTempModifier;
import dev.brights0ng.enginesandempires.weather.climate.compat.ColdSweatCompat;
import dev.brights0ng.enginesandempires.weather.climate.compat.ColdSweatWater;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.rain.Precip;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 8d: Cold Sweat's world temperature from the pack's climate ({@code weather/climate/compat/ColdSweatCompat}).
 * The dev runs always have Cold Sweat.
 */
@GameTestHolder(EnginesAndEmpires.MODID)
@PrefixGameTestTemplate(false)
public final class ColdSweatGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final com.momosoftworks.coldsweat.api.util.Temperature.Trait WORLD =
            com.momosoftworks.coldsweat.api.util.Temperature.Trait.WORLD;

    private static Player player(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setNoGravity(true);
        player.setInvulnerable(true);
        player.setPos(helper.absoluteVec(new Vec3(1.5, 3, 1.5)));
        return player;
    }

    /** The ids in an entity's default WORLD stack, after Cold Sweat's own defaults and the pack's rewrite. */
    private static List<String> worldStack(net.minecraft.world.entity.LivingEntity entity) {
        DefaultTempModifiersEvent event = new DefaultTempModifiersEvent(entity);
        // Start from a clean stack: the event copies the entity's own lists, and other tests may have filled them
        event.getModifiers(WORLD).clear();
        NeoForge.EVENT_BUS.post(event);
        return event.getModifiers(WORLD).stream().map(TempModifier::getID).map(String::valueOf).toList();
    }

    @GameTest(template = SCRATCH)
    public static void coldSweatIsLoaded(GameTestHelper helper) {
        helper.assertTrue(net.neoforged.fml.ModList.get().isLoaded(ColdSweatCompat.MOD_ID), "Cold Sweat should be in the dev runtime");
        helper.assertTrue(com.momosoftworks.coldsweat.api.registry.TempModifierRegistry.containsKey(ColdSweatCompat.CLIMATE_ID),
                "the pack's climate modifier should be registered with Cold Sweat");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void theOverworldGetsTheClimateInPlaceOfColdSweatsWorldTerms(GameTestHelper helper) {
        List<String> ids = worldStack(player(helper));
        String climate = ColdSweatCompat.CLIMATE_ID.toString();
        helper.assertTrue(ids.stream().filter(climate::equals).count() == 1, "exactly one climate modifier: " + ids);
        helper.assertTrue(ids.get(0).equals(climate), "the climate goes first: " + ids);
        for (String gone : List.of("cold_sweat:biomes", "cold_sweat:elevation", "cold_sweat:cave_biomes", "cold_sweat:shade",
                "sereneseasons:season", "cold_sweat:blocks", "cold_sweat:sublevel_blocks")) {
            helper.assertFalse(ids.contains(gone), gone + " should be replaced: " + ids);
        }
        // Block warmth comes from the heat simulation now (8f-3), on ships too (8f-5)
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void otherDimensionsAreUntouched(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "the test server should have the Nether");
        List<String> ids = worldStack(FakePlayerFactory.get(nether, new GameProfile(UUID.randomUUID(), "eae-nether")));
        helper.assertFalse(ids.contains(ColdSweatCompat.CLIMATE_ID.toString()), "no pack climate in the Nether: " + ids);
        helper.assertTrue(ids.contains("cold_sweat:biomes"), "Cold Sweat's own biome term stays in the Nether: " + ids);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void theModifierReadsThePacksAirTemperature(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player player = player(helper);
        BlockPos pos = player.blockPosition();
        double expected = Temperature.at(level, pos) / 25.0;
        double viaColdSweat = com.momosoftworks.coldsweat.api.util.Temperature.convert(Temperature.at(level, pos),
                com.momosoftworks.coldsweat.api.util.Temperature.Units.C, com.momosoftworks.coldsweat.api.util.Temperature.Units.MC, true);
        helper.assertTrue(Math.abs(expected - viaColdSweat) < 1e-9, "1 MC is 25 C: " + expected + " vs " + viaColdSweat);

        ClimateTempModifier modifier = new ClimateTempModifier();
        // A real player also feels the wind, the humidity and the sun on top of the air (8e-2); a dummy asking what a block is like gets the air alone
        double air = Temperature.at(level, pos);
        expected += dev.brights0ng.enginesandempires.weather.climate.PersonClimate.offsetC(
                dev.brights0ng.enginesandempires.weather.climate.PersonClimate.sample(level, player), air) / 25.0;
        double out = modifier.update(0.0, player, WORLD);
        helper.assertTrue(Math.abs(out - expected) < 1e-9, "the modifier adds the pack's air temperature: " + out + " vs " + expected);
        // It adds to what is already there, as Cold Sweat's own biome term does
        double onTop = new ClimateTempModifier().update(0.4, player, WORLD);
        helper.assertTrue(Math.abs(onTop - (0.4 + expected)) < 1e-9, "added to the input: " + onTop);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void aBlockQueryDummyGetsOnlyTheAir(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player dummy = com.momosoftworks.coldsweat.util.world.WorldHelper.getDummyPlayer(level);
        dummy.setPos(helper.absoluteVec(new Vec3(1.5, 3, 1.5)));
        double expected = Temperature.at(level, dummy.blockPosition()) / 25.0;
        double out = new ClimateTempModifier().update(0.0, dummy, WORLD);
        helper.assertTrue(Math.abs(out - expected) < 1e-9, "a block query is the air alone, with no wind or sun: " + out + " vs " + expected);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void everyDifficultyHasTheSameComfortRange(GameTestHelper helper) {
        for (com.momosoftworks.coldsweat.config.ConfigSettings.Difficulty difficulty
                : com.momosoftworks.coldsweat.config.ConfigSettings.Difficulty.values()) {
            if (difficulty == com.momosoftworks.coldsweat.config.ConfigSettings.Difficulty.CUSTOM) {
                continue;
            }
            double min = difficulty.getSetting(com.momosoftworks.coldsweat.config.ConfigSettings.MIN_TEMP);
            double max = difficulty.getSetting(com.momosoftworks.coldsweat.config.ConfigSettings.MAX_TEMP);
            helper.assertTrue(Math.abs(min * 25 - ColdSweatCompat.COMFORT_MIN_C) < 1e-9, difficulty + " minimum: " + min * 25 + " C");
            helper.assertTrue(Math.abs(max * 25 - ColdSweatCompat.COMFORT_MAX_C) < 1e-9, difficulty + " maximum: " + max * 25 + " C");
        }
        helper.succeed();
    }

    // ---- 8e-1: rain soaks people, Cold Sweat's freezing stays off

    private static LocalWeather.Here falling(Precip kind, double strength) {
        return new LocalWeather.Here(strength, false, kind, 1, null, null);
    }

    private static double wetness(Player player) {
        return com.momosoftworks.coldsweat.api.util.Temperature
                .getModifier(player, WORLD, com.momosoftworks.coldsweat.api.temperature.modifier.WaterTempModifier.class)
                .map(com.momosoftworks.coldsweat.api.temperature.modifier.WaterTempModifier::getTemperature).orElse(0.0);
    }

    private static void dry(Player player) {
        com.momosoftworks.coldsweat.api.util.Temperature.removeModifiers(player, WORLD,
                mod -> mod instanceof com.momosoftworks.coldsweat.api.temperature.modifier.WaterTempModifier);
    }

    @GameTest(template = SCRATCH)
    public static void rainSoaksAPersonAtColdSweatsSpeed(GameTestHelper helper) {
        Player player = player(helper);
        dry(player);
        double speed = com.momosoftworks.coldsweat.config.ConfigSettings.RAIN_SOAK_SPEED.get();
        helper.assertTrue(wetness(player) == 0, "dry to begin with");
        helper.assertTrue(ColdSweatWater.soak(player, falling(Precip.RAIN, 0.6)), "rain soaks");
        helper.assertTrue(Math.abs(wetness(player) + speed) < 1e-9, "one update of rain is one soak step: " + wetness(player) + " vs " + -speed);
        ColdSweatWater.soak(player, falling(Precip.RAIN, 0.6));
        helper.assertTrue(Math.abs(wetness(player) + 2 * speed) < 1e-9, "and again: " + wetness(player));
        dry(player);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void drizzleAndSleetSoakAQuarterAsFast(GameTestHelper helper) {
        Player player = player(helper);
        double speed = com.momosoftworks.coldsweat.config.ConfigSettings.RAIN_SOAK_SPEED.get();
        dry(player);
        ColdSweatWater.soak(player, falling(Precip.RAIN, 0.15));
        helper.assertTrue(Math.abs(wetness(player) + speed / 4) < 1e-9, "drizzle: " + wetness(player));
        dry(player);
        ColdSweatWater.soak(player, falling(Precip.SLEET, 0.8));
        helper.assertTrue(Math.abs(wetness(player) + speed / 4) < 1e-9, "sleet: " + wetness(player));
        dry(player);
        ColdSweatWater.soak(player, falling(Precip.FREEZING_RAIN, 0.15));
        helper.assertTrue(Math.abs(wetness(player) + speed) < 1e-9, "freezing rain at normal speed: " + wetness(player));
        dry(player);
        ColdSweatWater.soak(player, falling(Precip.MIXED, 0.5));
        helper.assertTrue(Math.abs(wetness(player) + speed) < 1e-9, "mixed at normal speed: " + wetness(player));
        dry(player);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void nothingFallingLeavesAPersonDry(GameTestHelper helper) {
        Player player = player(helper);
        dry(player);
        helper.assertFalse(ColdSweatWater.soak(player, LocalWeather.Here.NONE), "nothing falls, nothing soaks");
        helper.assertFalse(com.momosoftworks.coldsweat.api.util.Temperature.hasModifier(player, WORLD,
                com.momosoftworks.coldsweat.api.temperature.modifier.WaterTempModifier.class), "no wetness modifier added");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void rainSoaksOnlyDownToTheCap(GameTestHelper helper) {
        Player player = player(helper);
        dry(player);
        double cap = com.momosoftworks.coldsweat.config.ConfigSettings.MAX_RAIN_SOAK.get();
        for (int i = 0; i < 2000; i++) {
            ColdSweatWater.soak(player, falling(Precip.RAIN, 1));
        }
        helper.assertTrue(Math.abs(wetness(player) + cap) < 1e-9, "stops at max rain soak " + cap + ": " + wetness(player));
        dry(player);
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void freezingIsForcedOffWhateverTheConfigSays(GameTestHelper helper) {
        var holder = com.momosoftworks.coldsweat.config.ConfigSettings.USE_CUSTOM_WATER_FREEZE_BEHAVIOR;
        holder.set(true);
        helper.assertTrue(ColdSweatWater.forceFreezeOff(), "it had been on");
        helper.assertFalse(holder.get(), "and is off");
        helper.assertFalse(ColdSweatWater.forceFreezeOff(), "already off: nothing to do");
        helper.succeed();
    }

    @GameTest(template = SCRATCH)
    public static void lightDoesNotMeltIceWherePackOwnsTheWeather(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(dev.brights0ng.enginesandempires.weather.WeatherOwnership.owns(level), "the test level is the pack's weather");
        BlockPos ice = new BlockPos(2, 2, 2);
        helper.setBlock(ice, net.minecraft.world.level.block.Blocks.ICE);
        helper.setBlock(ice.east(), net.minecraft.world.level.block.Blocks.GLOWSTONE);
        helper.runAfterDelay(5, () -> {
            BlockPos abs = helper.absolutePos(ice);
            helper.assertTrue(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, abs) > 11, "the glowstone lights the ice well past vanilla's melt level");
            for (int i = 0; i < 20; i++) {
                level.getBlockState(abs).randomTick(level, abs, level.random);
            }
            helper.assertBlock(ice, b -> b == net.minecraft.world.level.block.Blocks.ICE, () -> "the ice should still be ice");
            helper.succeed();
        });
    }
}
