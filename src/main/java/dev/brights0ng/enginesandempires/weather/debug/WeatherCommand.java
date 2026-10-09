package dev.brights0ng.enginesandempires.weather.debug;

import java.util.Locale;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.climate.Baseline;
import dev.brights0ng.enginesandempires.weather.climate.BiomeClimate;
import dev.brights0ng.enginesandempires.weather.climate.Climate;
import dev.brights0ng.enginesandempires.weather.climate.ClimateCurves;
import dev.brights0ng.enginesandempires.weather.climate.SeasonSource;
import dev.brights0ng.enginesandempires.weather.sim.WeatherSystem;
import dev.brights0ng.enginesandempires.weather.sim.world.Atmosphere;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import dev.brights0ng.enginesandempires.weather.wind.WindColumn;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The weather's debug commands, under the pack's {@code /eae} root, operators only (permission level 2):
 * <ul>
 *   <li>{@code /eae weather}: the climate at your position, broken down into its parts.</li>
 *   <li>{@code /eae weather season}: where in the year and the day the world is.</li>
 *   <li>{@code /eae weather map}: opens the weather map (Esc closes it).</li>
 *   <li>{@code /eae weather systems}: the nearest lows and highs.</li>
 *   <li>{@code /eae weather spawn low|high [upstream]}: adds a low or high where you stand (or one spacing west).</li>
 *   <li>{@code /eae weather step <hours>}: runs the real simulation that many in-game hours ahead.</li>
 *   <li>{@code /eae weather clear}: removes every system (new ones form at the next step).</li>
 *   <li>{@code /eae weather clouds}: the clouds near you by type, and what the last spawner pass did.</li>
 *   <li>{@code /eae weather clouds spawn <type>}: a fully formed cloud of that type over you.</li>
 *   <li>{@code /eae weather clouds clear}: removes every cloud (the sky refills, already formed, at the next pass).</li>
 *   <li>{@code /eae weather clouds samples}: rows of humilis, mediocris and congestus at three sizes to the south, for
 *       comparing shapes.</li>
 *   <li>{@code /eae weather clouds natural on|off}: pauses the weather's own clouds (off removes them at once, keeping
 *       debug-spawned ones) or lets them form again. Not saved: a restart turns them back on.</li>
 *   <li>{@code /eae weather precip <kind>|auto}: makes whatever falls anywhere fall as that kind (rain, mixed, snow,
 *       sleet, freezing_rain, hail), for looking at it; {@code auto} goes back to the weather's own. Not saved.</li>
 *   <li>{@code /eae weather lightning [ground|cloud]}: makes the nearest thunder cloud flash now (ground: a ground
 *       strike, cloud: an in-cloud flash, which hits a ship or flier inside the cloud if one is in reach).</li>
 *   <li>{@code /eae weather sky}: the storm's shade where you stand (cover, gloom overhead and around, darkness) and
 *       the gameplay light it makes there.</li>
 * </ul>
 * The weather lives in the Overworld, so these always read the Overworld (at your x and z if you are elsewhere).
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class WeatherCommand {

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> weather = Commands.literal("weather")
                .executes(context -> sample(context.getSource()))
                .then(Commands.literal("season").executes(context -> season(context.getSource())))
                .then(Commands.literal("map").executes(context -> map(context.getSource())))
                .then(Commands.literal("systems").executes(context -> systems(context.getSource())))
                .then(Commands.literal("spawn")
                        .then(Commands.literal("low")
                                .executes(context -> spawn(context.getSource(), WeatherSystem.Kind.LOW, false))
                                .then(Commands.literal("upstream")
                                        .executes(context -> spawn(context.getSource(), WeatherSystem.Kind.LOW, true))))
                        .then(Commands.literal("high")
                                .executes(context -> spawn(context.getSource(), WeatherSystem.Kind.HIGH, false))
                                .then(Commands.literal("upstream")
                                        .executes(context -> spawn(context.getSource(), WeatherSystem.Kind.HIGH, true)))))
                .then(Commands.literal("step")
                        .then(Commands.argument("hours", IntegerArgumentType.integer(1, 240))
                                .executes(context -> step(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "hours")))))
                .then(Commands.literal("clear").executes(context -> clear(context.getSource())))
                .then(Commands.literal("lightning")
                        .executes(context -> lightning(context.getSource(), null))
                        .then(Commands.literal("ground").executes(context -> lightning(context.getSource(),
                                dev.brights0ng.enginesandempires.weather.lightning.LightningModel.Kind.GROUND)))
                        .then(Commands.literal("cloud").executes(context -> lightning(context.getSource(),
                                dev.brights0ng.enginesandempires.weather.lightning.LightningModel.Kind.CLOUD))))
                .then(Commands.literal("sky").executes(context -> sky(context.getSource())))
                .then(Commands.literal("precip")
                        .then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                        java.util.stream.Stream.concat(java.util.stream.Stream.of("auto"),
                                                java.util.Arrays.stream(
                                                        dev.brights0ng.enginesandempires.weather.rain.Precip.values())
                                                        .map(p -> p.name().toLowerCase(Locale.ROOT))), builder))
                                .executes(context -> forcePrecip(context.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "kind")))))
                .then(Commands.literal("clouds")
                        .executes(context -> clouds(context.getSource()))
                        .then(Commands.literal("audit")
                                .then(Commands.literal("on").executes(context -> audit(context.getSource(), true)))
                                .then(Commands.literal("off").executes(context -> audit(context.getSource(), false))))
                        .then(Commands.literal("clear").executes(context -> clearClouds(context.getSource())))
                        .then(Commands.literal("samples").executes(context -> cloudSamples(context.getSource())))
                        .then(Commands.literal("natural")
                                .executes(context -> naturalClouds(context.getSource(), null))
                                .then(Commands.literal("on").executes(context -> naturalClouds(context.getSource(), true)))
                                .then(Commands.literal("off").executes(context -> naturalClouds(context.getSource(), false))))
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider
                                                .suggest(java.util.Arrays.stream(
                                                        dev.brights0ng.enginesandempires.weather.cloud.CloudType.values())
                                                        .map(t -> t.id), builder))
                                        .executes(context -> spawnCloud(context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context,
                                                        "type"))))));
        event.getDispatcher().register(Commands.literal("eae")
                .requires(source -> source.hasPermission(WeatherDebugNetwork.PERMISSION)).then(weather));
    }

    private static int sample(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        Vec3 at = source.getPosition();
        Baseline.Sample s = Climate.sample(level, at.x, at.y, at.z);
        BiomeClimate local = s.local();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Temperature here: %.1f C (day's mean %.1f C), humidity %.0f%%.", s.temperature(), s.dailyMean(),
                100 * s.humidity())), false);
        line(source, String.format(Locale.ROOT, "Regional climate: mean %.1f C, seasonal swing +/-%.1f, humidity %.0f%%.",
                s.regional().mean(), s.regional().swing(), 100 * s.regional().humidity()));
        line(source, String.format(Locale.ROOT, "Biome underfoot: mean %.1f C, swing +/-%.1f, humidity %.0f%%, %s%s.",
                local.mean(), local.swing(), 100 * local.humidity(), local.surface().getSerializedName(),
                local.frozen() ? ", always frozen" : ""));
        line(source, String.format(Locale.ROOT, "Season %+.1f C, band %+.1f C, time of day %+.1f C, height %+.1f C%s.",
                s.seasonal(), s.band(), s.diurnal(), s.height(),
                s.clamped() ? "; held at " + BiomeClimate.FROZEN_MAX + " C (always frozen)" : ""));
        WindColumn w = Atmosphere.wind(level, at.x, at.z);
        if (w != null) {
            line(source, String.format(Locale.ROOT,
                    "Pressure %.1f hPa. Wind at the surface %.1f m/s toward %s, aloft %.1f m/s toward %s.",
                    Atmosphere.pressure(level, at.x, at.z), Math.hypot(w.surfaceX(), w.surfaceZ()),
                    compass(w.surfaceX(), w.surfaceZ()), Math.hypot(w.aloftX(), w.aloftZ()),
                    compass(w.aloftX(), w.aloftZ())));
        }
        WeatherSim sim = WeatherSim.of(level);
        if (sim != null && sim.field().covers(at.x, at.z)) {
            var env = sim.readEnv();
            var air = sim.field();
            double q = air.sample(dev.brights0ng.enginesandempires.weather.field.AtmosphereField.Var.Q, at.x, at.z, env);
            double t = air.sample(dev.brights0ng.enginesandempires.weather.field.AtmosphereField.Var.T, at.x, at.z, env);
            double p = air.sample(dev.brights0ng.enginesandempires.weather.field.AtmosphereField.Var.P, at.x, at.z, env);
            line(source, String.format(Locale.ROOT,
                    "Air mass %+.1f C from normal. Moisture %.1f mm (%.0f%% relative at sea level), rain wrung out %.1f mm.",
                    s.anomaly(), q, 100 * Math.min(1, q / dev.brights0ng.enginesandempires.weather.field.Moisture
                            .capacity(t)), p));
            double[] window = sim.fieldWindowMillis();
            line(source, String.format(Locale.ROOT, "Field: %d tiles (%d active), %d terrain cells still to work out. "
                            + "Systems step %.2f ms; field %.1f ms per 5 s spread over ticks (at most %.2f ms in one).",
                    air.tiles().size(), air.active(sim.time() - WeatherSim.STEP).size(), sim.terrainPending(),
                    sim.lastStepMillis(), window[0], window[1]));
        }
        line(source, WeatherMapBuilder.info(level));
        var need = dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld.need(level, at.x, at.z);
        if (need != null) {
            line(source, String.format(Locale.ROOT, "Clouds wanted here:%s. Relative humidity %.0f%%, cloud base %.0f m "
                            + "(%.0f blocks up), instability %.2f (sun %+.1f C).",
                    need.describe(), 100 * need.rh(), need.lclMetres(), need.lclMetres() * 0.2, need.instability(),
                    need.heating()));
            line(source, String.format(Locale.ROOT, "Lift: %s front %.0f%%%s, upslope %.0f%%, low centre %.0f%%; "
                            + "sinking (high) %.0f%%.", need.front(), 100 * need.frontal(),
                    Double.isFinite(need.warmAhead()) ? String.format(Locale.ROOT, " (warm front %.0f blocks %s)",
                            Math.abs(need.warmAhead()), need.warmAhead() >= 0 ? "away" : "past") : "",
                    100 * need.orographic(), 100 * need.convergence(), 100 * need.subsidence()));
        }
        var here = dev.brights0ng.enginesandempires.weather.rain.LocalWeather.at(level, at.x, at.y, at.z);
        double[] gust = dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld.gust(level, at.x, at.z);
        line(source, String.format(Locale.ROOT, "Falling here: %s%s, cloud cover %.0f%%. Storm outflow %.1f m/s%s.",
                here.falling() ? String.format(Locale.ROOT, "%s %.2f",
                        here.precip() == null ? "rain" : here.precip().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                        here.strength())
                        : "nothing", here.falling() && here.type() != null ? " from " + here.type() : "",
                100 * here.cover(), Math.hypot(gust[0], gust[1]),
                Math.hypot(gust[0], gust[1]) > 0.5 ? " toward " + compass(gust[0], gust[1]) : ""));
        WeatherSim simHere = WeatherSim.of(level);
        if (simHere != null) {
            double[] nose = simHere.warmNose(at.x, at.z);
            line(source, nose[0] > 0
                    ? String.format(Locale.ROOT, "Warm layer aloft: melt %.2f (%s), cold layer below %.0f%% deep.",
                    nose[0], nose[0] >= dev.brights0ng.enginesandempires.weather.rain.Precip.MELT_ALL ? "melts snow fully"
                            : nose[0] >= dev.brights0ng.enginesandempires.weather.rain.Precip.MELT_SOME
                            ? "half-melts snow" : "a trace", 100 * nose[1])
                    : "No warm layer aloft.");
        }
        return 1;
    }

    private static int forcePrecip(CommandSourceStack source, String kind) {
        if (kind.equalsIgnoreCase("auto")) {
            dev.brights0ng.enginesandempires.weather.rain.LocalWeather.forced = null;
            source.sendSuccess(() -> Component.literal("Precipitation back to the weather's own."), true);
            return 1;
        }
        try {
            var p = dev.brights0ng.enginesandempires.weather.rain.Precip.valueOf(kind.toUpperCase(Locale.ROOT));
            dev.brights0ng.enginesandempires.weather.rain.LocalWeather.forced = p;
            source.sendSuccess(() -> Component.literal("Whatever falls now falls as " + kind.toLowerCase(Locale.ROOT)
                    + " (until /eae weather precip auto or a restart). It still needs a raining cloud."), true);
            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Unknown kind: " + kind));
            return 0;
        }
    }

    private static int clouds(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        WeatherSim sim = WeatherSim.of(level);
        Vec3 at = source.getPosition();
        long now = level.getGameTime();
        java.util.Map<String, int[]> byType = new java.util.TreeMap<>();
        int near = 0;
        int raining = 0;
        for (var c : sim.cloudSim().clouds()) {
            if (Math.hypot(c.xAt(now) - at.x, c.zAt(now) - at.z) > 4096) {
                continue;
            }
            near++;
            int[] n = byType.computeIfAbsent(c.type.id, k -> new int[4]);
            n[0]++;
            if (c.dissolving) {
                n[1]++;
            }
            if (c.precipitation > 0) {
                n[Float.isInfinite(c.rainBottom) ? 2 : 3]++;
                raining++;
            }
        }
        int total = sim.cloudSim().clouds().size();
        final int nearCount = near;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d clouds in the world, %d within 4096 blocks of you:", total, nearCount)), false);
        for (var e : byType.entrySet()) {
            int[] n = e.getValue();
            line(source, String.format(Locale.ROOT, "%s x%d%s%s%s", e.getKey(), n[0],
                    n[1] > 0 ? ", " + n[1] + " dissolving" : "", n[2] > 0 ? ", " + n[2] + " raining" : "",
                    n[3] > 0 ? ", " + n[3] + " virga only" : ""));
        }
        var r = dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld.lastReport();
        if (r != null) {
            line(source, String.format(Locale.ROOT, "Last pass: %d new layer, %d new heap, %d dissolving, %d grew, "
                            + "%d removed; %d raining on the ground (%.1f mm taken from the air); %d storms with outflow;"
                            + " %d diagnoses in %.2f ms.", r.spawnedLayer(), r.spawnedHeap(), r.dissolved(),
                    r.evolved(), r.removed(), r.raining(), r.drainedMm(),
                    dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld.gustCount(), r.diagnoses(),
                    r.nanos() / 1e6));
        }
        return 1;
    }

    private static int clearClouds(CommandSourceStack source) {
        dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld.clear(source.getServer().overworld());
        source.sendSuccess(() -> Component.literal("Removed every cloud; the sky refills at the next pass."), true);
        return 1;
    }

    private static int audit(CommandSourceStack source, boolean on) {
        if (!on) {
            dev.brights0ng.enginesandempires.weather.sim.world.CloudAudit.stop();
            source.sendSuccess(() -> Component.literal("Cloud audit off."), true);
            return 1;
        }
        try {
            var file = dev.brights0ng.enginesandempires.weather.sim.world.CloudAudit.start(
                    source.getServer().overworld());
            source.sendSuccess(() -> Component.literal("Cloud audit on: every new cloud and why, and a check of the "
                    + "sky around each player every 30 s, in " + file), true);
            return 1;
        } catch (java.io.IOException e) {
            source.sendFailure(Component.literal("Couldn't start the cloud audit: " + e.getMessage()));
            return 0;
        }
    }


    private static int spawnCloud(CommandSourceStack source, String name) {
        var type = dev.brights0ng.enginesandempires.weather.cloud.CloudType.byName(name);
        if (type == null) {
            source.sendFailure(Component.literal("No cloud type called " + name + "."));
            return 0;
        }
        Vec3 at = source.getPosition();
        var c = dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld.spawn(source.getServer().overworld(),
                type, at.x, at.z);
        if (c == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Added a %s over you: base y %.0f, %.0f blocks thick, %d domes, drifting %.1f blocks/s.", type.id,
                c.baseY, c.thickness, c.domes.size(), Math.hypot(c.vx, c.vz) * 20)), true);
        return 1;
    }

    /**
     * A row of each cumulus type at three sizes (small, typical, big) to the south of you, for comparing shapes:
     * humilis 500 blocks out, mediocris 1,100, congestus 2,000. They are debug clouds: they drift but never dissolve
     * ({@code /eae weather clouds clear} removes them).
     */
    private static int cloudSamples(CommandSourceStack source) {
        Vec3 at = source.getPosition();
        var level = source.getServer().overworld();
        var types = new dev.brights0ng.enginesandempires.weather.cloud.CloudType[]{
                dev.brights0ng.enginesandempires.weather.cloud.CloudType.CUMULUS_HUMILIS,
                dev.brights0ng.enginesandempires.weather.cloud.CloudType.CUMULUS_MEDIOCRIS,
                dev.brights0ng.enginesandempires.weather.cloud.CloudType.CUMULUS_CONGESTUS};
        double[] rows = {500, 1100, 2000};
        double[] sizes = {0.5, 1.0, 1.6};
        int made = 0;
        for (int t = 0; t < types.length; t++) {
            double spacing = types[t].radiusBlocks() * 3.6;
            for (int k = 0; k < sizes.length; k++) {
                var c = dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld.spawn(level, types[t],
                        at.x + (k - 1) * spacing, at.z + rows[t], sizes[k]);
                if (c != null) {
                    made++;
                }
            }
        }
        int n = made;
        source.sendSuccess(() -> Component.literal(n + " sample cumulus to the south: humilis, mediocris and "
                + "congestus rows, each small / typical / big from west to east. /eae weather clouds clear removes "
                + "them."), true);
        return n;
    }

    /** Turns the weather's own clouds on or off ({@code on} null: says which). */
    private static int naturalClouds(CommandSourceStack source, Boolean on) {
        var level = source.getServer().overworld();
        WeatherSim sim = WeatherSim.of(level);
        if (sim == null) {
            source.sendFailure(Component.literal("The weather simulation isn't running."));
            return 0;
        }
        if (on == null) {
            boolean now = sim.cloudSim().natural();
            source.sendSuccess(() -> Component.literal("Natural clouds are " + (now ? "on" : "off") + "."), false);
            return now ? 1 : 0;
        }
        int removed = sim.cloudSim().setNatural(on);
        dev.brights0ng.enginesandempires.weather.sim.world.CloudWorld.resync(level);
        source.sendSuccess(() -> Component.literal(on
                ? "Natural clouds on: the sky fills again at the next spawner pass (within 2 s)."
                : "Natural clouds off: removed " + removed + "; debug clouds (samples, spawn) stay. A restart turns "
                + "them back on."), true);
        return 1;
    }

    private static int systems(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        Vec3 at = source.getPosition();
        java.util.List<WeatherSystem> all = new java.util.ArrayList<>(Atmosphere.systems(level));
        all.sort(java.util.Comparator.comparingDouble(s -> Math.hypot(s.x - at.x, s.z - at.z)));
        source.sendSuccess(() -> Component.literal(all.size() + " weather systems are being simulated. Nearest:"),
                false);
        dev.brights0ng.enginesandempires.weather.sim.Drift.Settings drift =
                dev.brights0ng.enginesandempires.weather.rain.WeatherConfig.drift();
        for (WeatherSystem s : all.subList(0, Math.min(8, all.size()))) {
            double dx = s.x - at.x;
            double dz = s.z - at.z;
            line(source, String.format(Locale.ROOT, "%s%s %.0f hPa, %s, day %.1f of %.1f, %.0f blocks %s%s; drift: "
                            + "speed %+.0f%%, across %+.0f%%, depth %+.0f%%",
                    s.kind == WeatherSystem.Kind.LOW ? "Low" : "High", s.blocking ? " (blocking)" : "",
                    1013 + s.signedStrength(), s.stage().label(), s.age / 24000.0, s.lifetime / 24000.0,
                    Math.hypot(dx, dz), compass(dx, dz), s.hemisphere < 0 ? ", mirrored zone" : "",
                    100 * (dev.brights0ng.enginesandempires.weather.sim.Drift.speedFactor(s.driftSpeed, drift.speed())
                            - 1), 100 * drift.cross() * s.driftCross, 100 * (s.nudge - 1)));
        }
        return 1;
    }

    private static int spawn(CommandSourceStack source, WeatherSystem.Kind kind, boolean upstream) {
        ServerLevel level = source.getServer().overworld();
        WeatherSim sim = WeatherSim.of(level);
        Vec3 at = source.getPosition();
        double x = upstream ? at.x - sim.jet().params().spacing() : at.x;
        WeatherSystem s = sim.spawn(kind, x, at.z);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Added a %s at %.0f, %.0f.",
                s.kind == WeatherSystem.Kind.LOW ? "low" : "high", s.x, s.z)), true);
        return 1;
    }

    private static int step(CommandSourceStack source, int hours) {
        ServerLevel level = source.getServer().overworld();
        long started = System.nanoTime();
        WeatherSim.of(level).advance(hours * 1000L);
        long ms = (System.nanoTime() - started) / 1_000_000;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Ran the weather %d in-game hour%s ahead (%d ms).", hours, hours == 1 ? "" : "s", ms)), true);
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        WeatherSim.of(source.getServer().overworld()).clear();
        source.sendSuccess(() -> Component.literal("Removed every weather system; new ones form within seconds."),
                true);
        return 1;
    }

    private static int lightning(CommandSourceStack source,
                                 dev.brights0ng.enginesandempires.weather.lightning.LightningModel.Kind kind) {
        return lightningNow(source, kind);
    }

    private static int sky(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        Vec3 at = source.getPosition();
        long now = level.getGameTime();
        var index = new dev.brights0ng.enginesandempires.weather.sky.StormShade.Index(
                dev.brights0ng.enginesandempires.weather.cloud.CloudSources.server(level), now);
        var s = dev.brights0ng.enginesandempires.weather.sky.StormShade.sample(index, at.x, at.z);
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(at);
        dev.brights0ng.enginesandempires.weather.sky.StormLight.invalidate(level);
        int stormDarken = dev.brights0ng.enginesandempires.weather.sky.StormLight.skyDarken(level, pos);
        int clearDarken = level.getSkyDarken();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Storm shade here: cover %.2f, gloom overhead %.2f, gloom around %.2f, darkness %.2f (light x%.2f).%n"
                        + "Sky darkening %d (clear sky: %d); light here %d (clear sky: %d).",
                s.cover(), s.overhead(), s.around(), s.darkness(),
                dev.brights0ng.enginesandempires.weather.sky.StormShade.lightFactor(s.darkness()), stormDarken,
                clearDarken, level.getMaxLocalRawBrightness(pos), level.getMaxLocalRawBrightness(pos, clearDarken))),
                false);
        return 1;
    }

    private static int lightningNow(CommandSourceStack source,
                                    dev.brights0ng.enginesandempires.weather.lightning.LightningModel.Kind kind) {
        ServerLevel level = source.getServer().overworld();
        Vec3 at = source.getPosition();
        long now = level.getGameTime();
        dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (var c : new java.util.ArrayList<>(WeatherSim.of(level).cloudSim().clouds())) {
            double d = Math.hypot(c.xAt(now) - at.x, c.zAt(now) - at.z);
            if (c.type.thunder && d < best && c.phase(now).visible()) {
                best = d;
                nearest = c;
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal(
                    "No thunder cloud showing (try /eae weather clouds spawn cumulonimbus_capillatus)."));
            return 0;
        }
        var s = dev.brights0ng.enginesandempires.weather.rain.WeatherConfig.lightning();
        var rng = new java.util.SplittableRandom(level.random.nextLong());
        var want = kind != null ? kind
                : dev.brights0ng.enginesandempires.weather.lightning.LightningModel.kind(rng.nextDouble(),
                        s.groundShare());
        var flash = dev.brights0ng.enginesandempires.weather.lightning.Lightning.flash(level, nearest, now, want, s, rng);
        if (flash == null) {
            source.sendFailure(Component.literal("That cloud can't flash right now."));
            return 0;
        }
        double dist = best;
        String type = nearest.type.id;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s flash in a %s %.0f blocks away, at y %.0f%s", flash.kind().name().toLowerCase(Locale.ROOT),
                type, dist, flash.y(), flash.struck() ? String.format(Locale.ROOT,
                        "; struck %.1f %.1f %.1f", flash.targetX(), flash.targetY(), flash.targetZ()) : "")), true);
        return 1;
    }


    /** The compass direction a vector points toward. */
    private static String compass(double x, double z) {
        String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        double yaw = Math.toDegrees(Math.atan2(-x, z));
        if (yaw < 0) {
            yaw += 360;
        }
        return names[(int) Math.round(yaw / 45) % 8];
    }

    private static int season(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        source.sendSuccess(() -> Component.literal(WeatherMapBuilder.info(level)), false);
        if (SeasonSource.available()) {
            double year = SeasonSource.yearFraction(level);
            line(source, String.format(Locale.ROOT, "Midsummer is %.0f%% through the year, midwinter %.0f%%; now %.1f%%.",
                    100 * ClimateCurves.MIDSUMMER, 100 * (ClimateCurves.MIDSUMMER + 0.5), 100 * year));
        }
        return 1;
    }

    private static int map(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can open the weather map."));
            return 0;
        }
        WeatherDebugNetwork.open(player);
        return 1;
    }

    private static void line(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("  " + text).withStyle(ChatFormatting.GRAY), false);
    }

    private WeatherCommand() {
    }
}
