package dev.brights0ng.enginesandempires.weather.forecast;

import java.util.UUID;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.debug.WeatherDebugNetwork;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /eae weather forecast [today|week]} (phase 7b, operators only like the rest of {@code /eae weather}): asks the
 * forecaster for the area you are in and prints the answer when it arrives (after the forecaster's delay, 30 s by
 * default). Registered beside {@code WeatherCommand}; Brigadier merges the two trees.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class ForecastCommand {

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("eae")
                .requires(source -> source.hasPermission(WeatherDebugNetwork.PERMISSION))
                .then(Commands.literal("weather")
                        .then(Commands.literal("forecast")
                                .executes(context -> forecast(context.getSource(), Forecast.Product.TODAY))
                                .then(Commands.literal("today")
                                        .executes(context -> forecast(context.getSource(), Forecast.Product.TODAY)))
                                .then(Commands.literal("week")
                                        .executes(context -> forecast(context.getSource(), Forecast.Product.WEEK)))
                                .then(Commands.literal("track").executes(context -> track(context.getSource())))
                                .then(Commands.literal("untrack").executes(context -> untrack(context.getSource())))
                                .then(Commands.literal("clear").executes(context -> clearScores(context.getSource())))
                                .then(Commands.literal("probe").executes(context -> probe(context.getSource())))
                                .then(Commands.literal("score")
                                        .executes(context -> score(context.getSource()))
                                        .then(Commands.literal("csv")
                                                .executes(context -> csv(context.getSource())))))));
    }

    private static int track(CommandSourceStack source) {
        Vec3 at = source.getPosition();
        ForecastScore.track(source.getServer().overworld(), at.x, at.z);
        source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "Tracking forecasts around %.0f, %.0f (%d spot(s) tracked). Forecasts are made here automatically and "
                        + "checked against the weather hour by hour; run time on with /eae weather step (whole days, "
                        + "e.g. 24 or 48) and see /eae weather forecast score.", at.x, at.z, ForecastScore.tracked())),
                false);
        return 1;
    }

    private static int untrack(CommandSourceStack source) {
        Vec3 at = source.getPosition();
        if (!ForecastScore.untrack(at.x, at.z)) {
            source.sendFailure(Component.literal("Nothing is being tracked."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stopped tracking the nearest spot (" + ForecastScore.tracked()
                + " left). Its scores are kept until /eae weather forecast clear."), false);
        return 1;
    }

    private static int clearScores(CommandSourceStack source) {
        ForecastScore.clear();
        source.sendSuccess(() -> Component.literal("Forecast scores and records cleared (tracking carries on)."),
                false);
        return 1;
    }

    /** Debug: a day forecast's air against the live air over the next 24 hours (runs the weather 24 hours ahead). */
    private static int probe(CommandSourceStack source) {
        Vec3 at = source.getPosition();
        java.util.List<String> lines = ForecastProbe.compare(source.getServer().overworld(), at.x, at.z, 24);
        for (String line : lines) {
            EnginesAndEmpiresMod.LOGGER.info("Forecast probe: {}", line);
        }
        try {
            java.nio.file.Path dir = source.getServer().getServerDirectory().resolve("logs");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve("forecast-probe-" + System.currentTimeMillis() + ".txt");
            java.nio.file.Files.write(file, lines, java.nio.charset.StandardCharsets.UTF_8);
            source.sendSuccess(() -> Component.literal("Probe done (the weather ran 24 hours ahead); wrote "
                    + file.toAbsolutePath()), false);
        } catch (java.io.IOException e) {
            source.sendFailure(Component.literal("Probe done, but the file couldn't be written: " + e.getMessage()));
        }
        return 1;
    }

    private static int score(CommandSourceStack source) {
        java.util.List<String> lines = ForecastScore.report();
        source.sendSuccess(() -> Component.literal(lines.get(0)).withStyle(ChatFormatting.AQUA), false);
        for (String line : lines.subList(1, lines.size())) {
            source.sendSuccess(() -> Component.literal("  " + line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int csv(CommandSourceStack source) {
        try {
            java.nio.file.Path file = ForecastScore.writeCsv(source.getServer().overworld());
            source.sendSuccess(() -> Component.literal("Wrote " + file.toAbsolutePath() + " and the hour-by-hour "
                    + "trace beside it (forecast-trace-…)."), false);
            return 1;
        } catch (java.io.IOException e) {
            source.sendFailure(Component.literal("Couldn't write the CSV: " + e.getMessage()));
            return 0;
        }
    }

    private static int forecast(CommandSourceStack source, Forecast.Product product) {
        Vec3 at = source.getPosition();
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();
        UUID who = player == null ? null : player.getUUID();
        boolean accepted = ForecastService.request(server.overworld(), at.x, at.z, product,
                new ForecastService.Callback() {
                    @Override
                    public void ready(Forecast forecast) {
                        send(server, who, source, Component.literal(ForecastText.heading(forecast))
                                .withStyle(ChatFormatting.AQUA));
                        for (String line : ForecastText.lines(forecast)) {
                            send(server, who, source, Component.literal("  " + line).withStyle(ChatFormatting.GRAY));
                        }
                    }

                    @Override
                    public void failed(String why) {
                        send(server, who, source, Component.literal("The forecast didn't come through: " + why)
                                .withStyle(ChatFormatting.RED));
                    }
                });
        if (!accepted) {
            source.sendFailure(Component.literal("The forecaster is too busy (or the weather isn't running); try again "
                    + "in a little while."));
            return 0;
        }
        int delay = WeatherConfig.forecast().delaySeconds();
        source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
                "The forecaster is working out %s forecast; it will be ready in %d seconds.",
                product == Forecast.Product.TODAY ? "today's" : "the " + WeatherConfig.forecast().days() + "-day",
                delay)), false);
        return 1;
    }

    /** To the player who asked if they are still online, otherwise to the original source (the console). */
    private static void send(MinecraftServer server, UUID who, CommandSourceStack source, Component message) {
        if (who != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(who);
            if (p != null) {
                p.sendSystemMessage(message);
            }
            return;
        }
        source.sendSystemMessage(message);
    }

    private ForecastCommand() {
    }
}
