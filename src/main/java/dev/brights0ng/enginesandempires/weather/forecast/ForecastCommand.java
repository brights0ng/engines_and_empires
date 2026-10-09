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
                                        .executes(context -> forecast(context.getSource(), Forecast.Product.WEEK))))));
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
