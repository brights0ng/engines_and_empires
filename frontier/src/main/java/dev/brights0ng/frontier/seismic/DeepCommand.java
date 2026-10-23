package dev.brights0ng.frontier.seismic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.brights0ng.frontier.FrontierConfig;
import dev.brights0ng.frontier.debug.FrontierCommand;
import dev.brights0ng.frontier.deep.SculkListeners;
import dev.brights0ng.frontier.deep.StirredDeposits;
import dev.brights0ng.frontier.deep.ThumperIncursions;
import dev.brights0ng.seismicsurveyance.data.ShotSource;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The Deep's debug commands, under {@code /frontier} (Brigadier merges this branch with the one
 * {@code FrontierCommand} registers). Registered with the rest of Frontier's side of Seismic Surveyance
 * ({@link FrontierSeismic}), so only when Seismic Surveyance is installed. They need operator permission.
 *
 * <ul>
 *   <li>{@code /frontier deep}: the disturbance around you, its cooldown, the sculk and stirred deposits nearby, and
 *       the latest incursions thumping called up.</li>
 *   <li>{@code /frontier deep shot mechanical|combustive|dry}: what a thumper shot where you stand would set off
 *       (sculk, mobs, disturbance), without the survey.</li>
 * </ul>
 */
final class DeepCommand {

    static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> frontier = Commands.literal(FrontierCommand.ROOT)
                .requires(source -> source.hasPermission(2));
        frontier.then(Commands.literal("deep")
                .executes(context -> deep(context.getSource()))
                .then(Commands.literal("shot")
                        .then(Commands.literal("mechanical").executes(context -> shot(context.getSource(),
                                ShotSource.MECHANICAL, 512)))
                        .then(Commands.literal("combustive").executes(context -> shot(context.getSource(),
                                ShotSource.COMBUSTIVE, 1024)))
                        .then(Commands.literal("dry").executes(context -> shot(context.getSource(),
                                ShotSource.COMBUSTIVE_DRY, 128)))));

        event.getDispatcher().register(frontier);
    }

    private static int deep(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        var params = FrontierConfig.deep();
        var deep = Deep.of(level);
        long now = level.getGameTime();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int shots = deep.disturbance().countAround(cx, cz, now, params.disturbanceWindow());
        boolean quiet = deep.disturbance().isQuiet(cx, cz, now);
        long quietLeft = quiet ? deep.disturbance().quietUntil(cx, cz) - now : 0;
        int sculkMech = SculkListeners.of(level)
                .within(pos, 512 * params.sculkRangeFraction()).size();
        // A combustive shot stirs deposits up to 1024 blocks away, so look that far; list the nearest ten.
        var stirred = new ArrayList<>(StirredDeposits.of(level)
                .near(pos, 1024, now));
        stirred.sort(Comparator.comparingDouble(s -> s.centre().distSqr(pos)));
        int stirredCount = stirred.size();
        if (stirred.size() > 10) {
            stirred.subList(10, stirred.size()).clear();
        }
        source.sendSuccess(() -> Component.literal("The Deep around you:"), false);
        line(source, String.format(Locale.ROOT, "Disturbance: %d of %d shots in the last %.0f s (3x3 chunks)%s",
                shots, params.disturbanceShots(), params.disturbanceWindow() / 20.0,
                quiet ? String.format(Locale.ROOT, "; quiet for %.1f more minutes", quietLeft / 1200.0) : ""));
        line(source, sculkMech + " sculk sensors and shriekers would hear a mechanical shot here ("
                + (int) (512 * params.sculkRangeFraction()) + " blocks)");
        line(source, stirredCount + " stirred deposits within 1024 blocks" + (stirred.isEmpty() ? "" : " (nearest shown):"));
        for (var s : stirred) {
            line(source, String.format(Locale.ROOT, "  %s at %d, %d, %d (%d blocks away), stirred for %.1f more minutes",
                    s.oreId(), s.centre().getX(), s.centre().getY(), s.centre().getZ(),
                    (int) Math.sqrt(s.centre().distSqr(pos)), (s.until() - now) / 1200.0));
        }
        var calls = ThumperIncursions.recent();
        line(source, "Incursions called up by thumping: " + calls.size() + " (see /frontier incursion status)");
        for (int i = Math.max(0, calls.size() - 3); i < calls.size(); i++) {
            var call = calls.get(i);
            line(source, String.format(Locale.ROOT, "  %s at %d, %d, %d, %.1f minutes ago",
                    call.incursionId() == null ? "none (one already nearby)" : "incursion #" + call.incursionId(),
                    call.pos().getX(), call.pos().getY(), call.pos().getZ(), (now - call.time()) / 1200.0));
        }
        return shots;
    }

    private static int shot(CommandSourceStack source, ShotSource shot, int range) {
        BlockPos pos = BlockPos.containing(source.getPosition());
        var deep = Deep.of(source.getLevel());
        int before = ThumperIncursions.recent().size();
        deep.onThump(pos, shot, range);
        int pending = deep.pendingCount();
        boolean called = ThumperIncursions.recent().size() > before;
        source.sendSuccess(() -> Component.literal("A " + shot.name().toLowerCase(Locale.ROOT) + " shot here: " + pending
                + " sculk listeners and Wardens will hear it" + (called ? "; it called up an incursion" : "") + "."), true);
        return pending;
    }

    private static void line(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("  " + text).withStyle(ChatFormatting.GRAY), false);
    }

    private DeepCommand() {
    }
}
