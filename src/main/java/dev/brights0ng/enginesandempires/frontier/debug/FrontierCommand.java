package dev.brights0ng.enginesandempires.frontier.debug;

import java.util.Locale;

import org.joml.Vector3f;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.Tier;
import dev.brights0ng.enginesandempires.frontier.tier.AreaCounts;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevels;
import dev.brights0ng.enginesandempires.frontier.tier.TierParams;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands for Frontier, under the pack's {@code /eae} root (Brigadier merges this {@code eae} branch with the one
 * {@code DepositCommand} registers). They need operator permission.
 *
 * <ul>
 *   <li>{@code /eae frontier tier}: the tier where you stand.</li>
 *   <li>{@code /eae frontier why}: the tier, and every count that decided it.</li>
 *   <li>{@code /eae frontier map [radius]}: outlines the chunks around you at your height, coloured by tier
 *       (red Frontier, yellow Uninhabited, green Settled, blue Civilized).</li>
 *   <li>{@code /eae frontier habitation add <ticks>}: adds inhabited time where you stand.</li>
 *   <li>{@code /eae frontier habitation clear [radius]}: forgets inhabited time and residents around you.</li>
 *   <li>{@code /eae frontier torches age <ticks> [radius]}: makes the torches around you as if lit that much earlier.</li>
 *   <li>{@code /eae frontier torches sweep}: checks every loaded torch now, putting out the ones whose day is up.</li>
 *   <li>{@code /eae frontier guards [radius]}: checks the guards around you now, and lists which are free to move.</li>
 *   <li>{@code /eae frontier deep}: the disturbance around you, its cooldown, the sculk and stirred deposits nearby, and
 *       the latest incursions thumping called up.</li>
 *   <li>{@code /eae frontier deep shot mechanical|combustive|dry}: what a thumper shot where you stand would set off
 *       (sculk, mobs, disturbance), without the survey.</li>
 * </ul>
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class FrontierCommand {

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> frontier = Commands.literal("frontier")
                .then(Commands.literal("tier").executes(context -> tier(context.getSource())))
                .then(Commands.literal("why").executes(context -> why(context.getSource())))
                .then(Commands.literal("map")
                        .executes(context -> map(context.getSource(), 4))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 8))
                                .executes(context -> map(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("habitation")
                        .then(Commands.literal("add")
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1))
                                        .executes(context -> addHabitation(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "ticks")))))
                        .then(Commands.literal("clear")
                                .executes(context -> clear(context.getSource(), 4))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 16))
                                        .executes(context -> clear(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "radius"))))))
                .then(Commands.literal("torches")
                        .then(Commands.literal("age")
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1))
                                        .executes(context -> ageTorches(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "ticks"), 32))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                                .executes(context -> ageTorches(context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "ticks"),
                                                        IntegerArgumentType.getInteger(context, "radius"))))))
                        .then(Commands.literal("sweep").executes(context -> sweepTorches(context.getSource()))));
        frontier.then(Commands.literal("guards")
                .executes(context -> guards(context.getSource(), 32))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                        .executes(context -> guards(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))));
        frontier.then(Commands.literal("deep")
                .executes(context -> deep(context.getSource()))
                .then(Commands.literal("shot")
                        .then(Commands.literal("mechanical").executes(context -> shot(context.getSource(),
                                dev.brights0ng.enginesandempires.frontier.deep.ShotSource.MECHANICAL, 512)))
                        .then(Commands.literal("combustive").executes(context -> shot(context.getSource(),
                                dev.brights0ng.enginesandempires.frontier.deep.ShotSource.COMBUSTIVE, 1024)))
                        .then(Commands.literal("dry").executes(context -> shot(context.getSource(),
                                dev.brights0ng.enginesandempires.frontier.deep.ShotSource.COMBUSTIVE_DRY, 128)))));

        event.getDispatcher().register(Commands.literal("eae")
                .requires(source -> source.hasPermission(2))
                .then(frontier));
    }

    private static int tier(CommandSourceStack source) {
        BlockPos pos = BlockPos.containing(source.getPosition());
        Tier tier = FrontierLevels.tierAt(source.getLevel(), pos);
        source.sendSuccess(() -> Component.literal("Tier here: ").append(name(tier)), false);
        return tier.ordinal();
    }

    private static int why(CommandSourceStack source) {
        FrontierLevel frontier = FrontierLevels.of(source.getLevel());
        if (frontier == null) {
            source.sendSuccess(() -> Component.literal("Only the Overworld has tiers: this dimension is ")
                    .append(name(Tier.UNINHABITED)).append(" everywhere."), false);
            return Tier.UNINHABITED.ordinal();
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        FrontierLevel.Report report = frontier.report(pos);
        TierParams p = frontier.params();
        AreaCounts area = report.area();
        source.sendSuccess(() -> Component.literal("Tier here: ").append(name(report.tier()))
                .append(Component.literal("   (subchunk " + (pos.getX() >> 4) + ", " + (pos.getY() >> 4) + ", " + (pos.getZ() >> 4)
                        + ", base ").withStyle(ChatFormatting.GRAY))
                .append(name(report.base())).append(Component.literal(")").withStyle(ChatFormatting.GRAY)), false);
        line(source, String.format(Locale.ROOT, "Area within %d subchunks: %d anchor blocks (need %d), %.2f of %.2f days inhabited, "
                        + "recent resident: %s, free guards %d (need %d)",
                p.areaRadius(), area.anchors(), p.minAnchors(), days(area.habitation()), days(p.settleTicks()),
                yesNo(area.recentResident()), area.freeGuards(), p.civilizedGuards()));
        line(source, String.format(Locale.ROOT, "Settled land within %d: %s; within %d (settles %dx faster): %s; this subchunk counts as Settled land: %s",
                p.ringRadius(), yesNo(report.settledInRing()), p.nearRadius(), p.nearMultiplier(), yesNo(report.settledNear()),
                yesNo(report.isSettledSource())));
        line(source, String.format(Locale.ROOT, "Height: y %d (below %d is Frontier, below %d at best Uninhabited)",
                pos.getY(), p.frontierBelowY(), p.uninhabitedBelowY()));
        line(source, String.format(Locale.ROOT, "Tracking %d subchunks, %d of them Settled land, %d waiting to be worked out",
                report.tracked(), report.settledSections(), report.dirty()));
        return report.tier().ordinal();
    }

    private static int map(CommandSourceStack source, int radius) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BlockPos at = player.blockPosition();
        int cx = at.getX() >> 4;
        int cz = at.getZ() >> 4;
        double y = player.getY() + 0.2;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                BlockPos sample = new BlockPos((x << 4) + 8, at.getY(), (z << 4) + 8);
                Tier tier = FrontierLevels.tierAt(source.getLevel(), sample);
                DustParticleOptions dust = new DustParticleOptions(colour(tier), 1.5F);
                // An outline one block inside the chunk's edge, so neighbouring chunks' outlines stay apart.
                for (int i = 1; i <= 15; i += 2) {
                    spot(player, dust, (x << 4) + i, y, (z << 4) + 1);
                    spot(player, dust, (x << 4) + i, y, (z << 4) + 15);
                    spot(player, dust, (x << 4) + 1, y, (z << 4) + i);
                    spot(player, dust, (x << 4) + 15, y, (z << 4) + i);
                }
            }
        }
        source.sendSuccess(() -> Component.literal("Outlined " + (2 * radius + 1) * (2 * radius + 1)
                + " chunks at your height: red Frontier, yellow Uninhabited, green Settled, blue Civilized."), false);
        return 1;
    }

    private static void spot(ServerPlayer player, DustParticleOptions dust, double x, double y, double z) {
        player.serverLevel().sendParticles(player, dust, true, x, y, z, 1, 0, 0, 0, 0);
    }

    private static int addHabitation(CommandSourceStack source, int ticks) {
        FrontierLevel frontier = FrontierLevels.of(source.getLevel());
        if (frontier == null) {
            source.sendFailure(Component.literal("Only the Overworld has tiers."));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        frontier.addHabitation(pos, ticks);
        frontier.refreshAround(pos, frontier.params().areaRadius());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Added %.2f days of inhabited time to the area around you. ",
                days(ticks))).append(Component.literal("Tier here: ")).append(name(frontier.tierAt(pos))), true);
        return 1;
    }

    private static int clear(CommandSourceStack source, int radius) {
        FrontierLevel frontier = FrontierLevels.of(source.getLevel());
        if (frontier == null) {
            source.sendFailure(Component.literal("Only the Overworld has tiers."));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        frontier.clearHabitation(pos, radius);
        source.sendSuccess(() -> Component.literal("Forgot inhabited time and residents within " + radius
                + " chunks. Tier here: ").append(name(frontier.tierAt(pos))), true);
        return 1;
    }

    private static int ageTorches(CommandSourceStack source, int ticks, int radius) {
        FrontierLevel frontier = FrontierLevels.of(source.getLevel());
        if (frontier == null) {
            source.sendFailure(Component.literal("Only the Overworld has tiers."));
            return 0;
        }
        int aged = frontier.torches().age(BlockPos.containing(source.getPosition()), radius, ticks);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Aged %d torches within %d blocks by %.2f days. Run /eae frontier torches sweep to check them now.",
                aged, radius, days(ticks))), true);
        return aged;
    }

    private static int sweepTorches(CommandSourceStack source) {
        FrontierLevel frontier = FrontierLevels.of(source.getLevel());
        if (frontier == null) {
            source.sendFailure(Component.literal("Only the Overworld has tiers."));
            return 0;
        }
        int out = frontier.torches().sweep();
        source.sendSuccess(() -> Component.literal(out + " torches burnt out."), true);
        return out;
    }

    private static int guards(CommandSourceStack source, int radius) {
        return guardsReport(source, radius);
    }

    private static int deep(CommandSourceStack source) {
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        var params = dev.brights0ng.enginesandempires.frontier.FrontierConfig.deep();
        var deep = dev.brights0ng.enginesandempires.frontier.deep.Deep.of(level);
        long now = level.getGameTime();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int shots = deep.disturbance().countAround(cx, cz, now, params.disturbanceWindow());
        boolean quiet = deep.disturbance().isQuiet(cx, cz, now);
        long quietLeft = quiet ? deep.disturbance().quietUntil(cx, cz) - now : 0;
        int sculkMech = dev.brights0ng.enginesandempires.frontier.deep.SculkListeners.of(level)
                .within(pos, 512 * params.sculkRangeFraction()).size();
        // A combustive shot stirs deposits up to 1024 blocks away, so look that far; list the nearest ten.
        var stirred = new java.util.ArrayList<>(dev.brights0ng.enginesandempires.frontier.deep.StirredDeposits.of(level)
                .near(pos, 1024, now));
        stirred.sort(java.util.Comparator.comparingDouble(s -> s.centre().distSqr(pos)));
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
        var calls = dev.brights0ng.enginesandempires.frontier.deep.ThumperIncursions.recent();
        line(source, "Incursions called up by thumping: " + calls.size() + " (see /eae frontier incursion status)");
        for (int i = Math.max(0, calls.size() - 3); i < calls.size(); i++) {
            var call = calls.get(i);
            line(source, String.format(Locale.ROOT, "  %s at %d, %d, %d, %.1f minutes ago",
                    call.incursionId() == null ? "none (one already nearby)" : "incursion #" + call.incursionId(),
                    call.pos().getX(), call.pos().getY(), call.pos().getZ(), (now - call.time()) / 1200.0));
        }
        return shots;
    }

    private static int shot(CommandSourceStack source, dev.brights0ng.enginesandempires.frontier.deep.ShotSource shot, int range) {
        BlockPos pos = BlockPos.containing(source.getPosition());
        var deep = dev.brights0ng.enginesandempires.frontier.deep.Deep.of(source.getLevel());
        int before = dev.brights0ng.enginesandempires.frontier.deep.ThumperIncursions.recent().size();
        deep.onThump(pos, shot, range);
        int pending = deep.pendingCount();
        boolean called = dev.brights0ng.enginesandempires.frontier.deep.ThumperIncursions.recent().size() > before;
        source.sendSuccess(() -> Component.literal("A " + shot.name().toLowerCase(Locale.ROOT) + " shot here: " + pending
                + " sculk listeners and Wardens will hear it" + (called ? "; it called up an incursion" : "") + "."), true);
        return pending;
    }

    private static int guardsReport(CommandSourceStack source, int radius) {
        FrontierLevel frontier = FrontierLevels.of(source.getLevel());
        if (frontier == null) {
            source.sendFailure(Component.literal("Only the Overworld has tiers."));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        java.util.List<dev.brights0ng.enginesandempires.frontier.guard.GuardTracker.Report> found = frontier.guards().near(pos, radius);
        int free = 0;
        for (var report : found) {
            frontier.guards().checkNow(report.entity());
        }
        found = frontier.guards().near(pos, radius);
        for (var report : found) {
            if (report.free()) {
                free++;
            }
            BlockPos at = report.entity().blockPosition();
            String status = !report.guard() ? "not a guard (a colonist without a guard job)"
                    : report.free() ? "free (reaches " + report.reachable() + "+ spots)"
                    : "penned (reaches only " + report.reachable() + " of " + dev.brights0ng.enginesandempires.frontier.FrontierConfig.guardFreeArea() + ")";
            line(source, report.entity().getName().getString() + " at " + at.getX() + ", " + at.getY() + ", " + at.getZ() + ": " + status);
        }
        int freeCount = free;
        int total = found.size();
        source.sendSuccess(() -> Component.literal(total + " guards within " + radius + " blocks, " + freeCount
                + " free. Here the area has " + frontier.report(pos).area().freeGuards() + " free guards (Civilized needs "
                + frontier.params().civilizedGuards() + ")."), false);
        return freeCount;
    }

    private static void line(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("  " + text).withStyle(ChatFormatting.GRAY), false);
    }

    private static Component name(Tier tier) {
        ChatFormatting colour = switch (tier) {
            case FRONTIER -> ChatFormatting.RED;
            case UNINHABITED -> ChatFormatting.YELLOW;
            case SETTLED -> ChatFormatting.GREEN;
            case CIVILIZED -> ChatFormatting.AQUA;
        };
        String text = tier.name().charAt(0) + tier.name().substring(1).toLowerCase(Locale.ROOT);
        return Component.literal(text).withStyle(colour);
    }

    private static Vector3f colour(Tier tier) {
        return switch (tier) {
            case FRONTIER -> new Vector3f(0.9F, 0.15F, 0.1F);
            case UNINHABITED -> new Vector3f(0.95F, 0.85F, 0.1F);
            case SETTLED -> new Vector3f(0.2F, 0.85F, 0.2F);
            case CIVILIZED -> new Vector3f(0.2F, 0.6F, 1.0F);
        };
    }

    private static double days(long ticks) {
        return ticks / (double) TierParams.DAY;
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private FrontierCommand() {
    }
}
