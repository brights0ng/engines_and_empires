package dev.brights0ng.meteodyne.heat.world;

import java.util.Locale;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.climate.Temperature;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands for the heat simulation (weather phase 8f-2), under {@code /eae}, operators only.
 *
 * <ul>
 *   <li>{@code /eae heat}: the regions, the CPU they used in the last second, and where you stand: the region, its
 *       sources, the outside air, the offset and the air it makes.</li>
 *   <li>{@code /eae heat step <seconds>}: plans and runs every region that long now, on the server thread (for
 *       testing; a big number freezes the server for a moment).</li>
 *   <li>{@code /eae heat show}: turns the in-world view of the nearest region on or off for you ({@link HeatView}).</li>
 * </ul>
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class HeatCommand {

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> heat = Commands.literal("heat")
                .executes(context -> info(context.getSource()))
                .then(Commands.literal("show").executes(context -> {
                    boolean on = HeatView.toggle(context.getSource().getPlayerOrException());
                    context.getSource().sendSuccess(() -> Component.literal("Heat view " + (on ? "on" : "off") + "."), false);
                    return 1;
                }))
                .then(Commands.literal("step")
                        .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(1, 3600))
                                .executes(context -> step(context.getSource(),
                                        DoubleArgumentType.getDouble(context, "seconds")))));
        event.getDispatcher().register(Commands.literal("eae").requires(source -> source.hasPermission(2)).then(heat));
    }

    private static int step(CommandSourceStack source, double seconds) {
        HeatWorld world = HeatWorld.of(source.getLevel());
        if (world == null) {
            source.sendFailure(Component.literal("The heat simulation runs in the Overworld only."));
            return 0;
        }
        long start = System.nanoTime();
        world.runNow(seconds);
        double ms = (System.nanoTime() - start) / 1e6;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Ran %d regions %.0f s in %.0f ms.",
                world.regions().size(), seconds, ms)), true);
        return 1;
    }

    private static int info(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        HeatWorld world = HeatWorld.of(level);
        if (world == null) {
            source.sendFailure(Component.literal("The heat simulation runs in the Overworld only."));
            return 0;
        }
        HeatSettings s = WeatherConfig.heat();
        source.sendSuccess(() -> Component.literal("Heat simulation" + (s.enabled() ? "" : " (off)")), false);
        int saved = 0;
        int natural = 0;
        for (HeatRegion r : world.regions()) {
            if (r.naturalOnly()) {
                natural++;
            } else {
                saved++;
            }
        }
        line(source, String.format(Locale.ROOT, "%d regions (%d with placed sources, %d natural only); %d placed sources",
                world.regions().size(), saved, natural, world.placedSourceCount()));
        line(source, String.format(Locale.ROOT, "CPU in the last second: %.1f ms of a %.0f ms budget",
                world.usedMsLastSecond(), s.budgetCores() * 1000));
        Vec3 at = source.getPosition();
        double outside = Temperature.at(level, BlockPos.containing(at));
        // 8f-3: the radiant warmth here, at the middle of a body standing at this spot
        line(source, String.format(Locale.ROOT, "Radiant here (body): %+.1f C", HeatRadiant.at(level, at.add(0, 0.9, 0))));
        // 8f-5: the room you feel, aboard a ship included
        line(source, String.format(Locale.ROOT, "Room here (body, ships included): %+.1f C", world.roomAt(at.add(0, 0.9, 0))));
        HeatRegion here = null;
        for (HeatRegion r : world.regions()) {
            if (r.contains((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z))) {
                here = r;
                break;
            }
        }
        if (here == null) {
            line(source, String.format(Locale.ROOT, "Not in a region; outside air %.1f C", outside));
            return 1;
        }
        int[] b = here.box();
        HeatRegion.Published p = here.published();
        double offset = world.offsetAt(at.x, at.y + 1.0, at.z);
        long age = p == null ? -1 : (level.getGameTime() - p.gameTime()) / 20;
        final HeatRegion r = here;
        line(source, String.format(Locale.ROOT, "Region %d: %d..%d, %d..%d, %d..%d (%dx%dx%d), %d sources%s",
                r.id(), b[0], b[3], b[1], b[4], b[2], b[5], b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1,
                r.sourceCount(), r.naturalOnly() ? ", natural only" : ""));
        line(source, String.format(Locale.ROOT, "Last step %d s ago, took %.2f ms; strongest offset %.1f C",
                age, r.lastNanos() / 1e6, p == null ? 0 : p.maxMagnitude()));
        line(source, String.format(Locale.ROOT, "Here (head height): outside %.1f C %+.1f = %.1f C",
                outside, offset, outside + offset));
        return 1;
    }

    private static void line(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("  " + text).withStyle(ChatFormatting.GRAY), false);
    }

    private HeatCommand() {
    }
}
