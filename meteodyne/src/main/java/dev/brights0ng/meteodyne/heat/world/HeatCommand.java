// Extended by ChatGPT: non-blocking live thermal performance captures.
// Extended by ChatGPT: operator-only thermal response tuning with explicit status and reset.
// Extended by ChatGPT: explicit local-air diagnostic controls and sampling.
// Updated by Claude: raised the live capture limit to 1800 seconds.
// Updated by Claude: registered the air-cell count study (/meteodyne heat cellcount).
package dev.brights0ng.meteodyne.heat.world;

import java.util.Locale;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.climate.Temperature;
import dev.brights0ng.meteodyne.heat.airgrid.AirCellCountCommand;
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
 * Debug commands for the heat simulation (weather phase 8f-2), under {@code /meteodyne}, operators only.
 *
 * <ul>
 *   <li>{@code /meteodyne heat}: the regions, the CPU they used in the last second, and where you stand: the region, its
 *       sources, the outside air, the offset and the air it makes.</li>
 *   <li>{@code /meteodyne heat step <seconds>}: plans and runs every region that long now, on the server thread (for
 *       testing; a big number freezes the server for a moment).</li>
 *   <li>{@code /meteodyne heat show}: turns the in-world view of the nearest region on or off for you ({@link HeatView}).</li>
 *   <li>{@code /meteodyne heat cellcount [radius] [down] [up]}: counts the cells air-grid schemes would need around you
 *       ({@link AirCellCountCommand}).</li>
 * </ul>
 */
@EventBusSubscriber(modid = Meteodyne.MODID)
public final class HeatCommand {

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> heat = Commands.literal("heat")
                .then(LocalAirCommand.tree())
                .then(AirCellCountCommand.tree())
                .executes(context -> info(context.getSource()))
                .then(Commands.literal("response")
                        .executes(context -> response(context.getSource(),null,false))
                        .then(Commands.literal("reset").executes(context -> response(context.getSource(),null,true)))
                        .then(Commands.argument("scale",DoubleArgumentType.doubleArg(0.1,10.0))
                                .executes(context -> response(context.getSource(),DoubleArgumentType.getDouble(context,"scale"),false))))
                .then(Commands.literal("benchmark")
                        .then(Commands.literal("start").executes(context -> benchmark(context.getSource(),60))
                                .then(Commands.argument("seconds",IntegerArgumentType.integer(5,1800))
                                        .executes(context -> benchmark(context.getSource(),IntegerArgumentType.getInteger(context,"seconds")))))
                        .then(Commands.literal("status").executes(context -> benchmarkStatus(context.getSource(),false)))
                        .then(Commands.literal("stop").executes(context -> benchmarkStatus(context.getSource(),true))))
                .then(Commands.literal("show").executes(context -> {
                    boolean on = HeatView.toggle(context.getSource().getPlayerOrException());
                    context.getSource().sendSuccess(() -> Component.literal("Heat view " + (on ? "on" : "off") + "."), false);
                    return 1;
                }))
                .then(Commands.literal("step")
                        .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(1, 3600))
                                .executes(context -> step(context.getSource(),
                                        DoubleArgumentType.getDouble(context, "seconds")))));
        event.getDispatcher().register(Commands.literal("meteodyne").requires(source -> source.hasPermission(2)).then(heat));
    }

    private static int response(CommandSourceStack source,Double scale,boolean reset) {
        HeatWorld world=HeatWorld.of(source.getLevel());
        if (world==null) { source.sendFailure(Component.literal("The heat simulation runs in the Overworld only."));return 0; }
        try {
            if (reset || scale!=null) { world.setResponseScaleOverride(scale); }
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal(invalid.getMessage()));return 0;
        }
        source.sendSuccess(()->Component.literal(responseStatus(world)),false);
        return 1;
    }

    private static String responseStatus(HeatWorld world) {
        return String.format(Locale.ROOT,"Thermal response %.3fx (%s; config %.3fx). Weather time and wind m/s are unchanged. Reset: /meteodyne heat response reset",
                world.responseScale(),world.responseScaleOverride()==null?"config":"temporary override",WeatherConfig.heat().responseScale());
    }

    private static int benchmark(CommandSourceStack source,int seconds) {
        HeatWorld world=HeatWorld.of(source.getLevel());
        if (world==null) { source.sendFailure(Component.literal("The heat simulation runs in the Overworld only."));return 0; }
        return world.benchmark().start(source,seconds,10)?1:0;
    }

    private static int benchmarkStatus(CommandSourceStack source,boolean stop) {
        HeatWorld world=HeatWorld.of(source.getLevel());
        if (world==null) { return 0; }
        if(stop) { return world.benchmark().stop()?1:0; }
        source.sendSuccess(()->Component.literal(world.benchmark().status()),false);return 1;
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
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Ran %d regions %.0f game s (%.3fx thermal response) in %.0f ms.",
                world.regions().size(), seconds, world.responseScale(), ms)), true);
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
        line(source,responseStatus(world));
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
