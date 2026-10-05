package dev.brights0ng.enginesandempires.frontier.debug;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.incursion.Incursion;
import dev.brights0ng.enginesandempires.frontier.incursion.IncursionData;
import dev.brights0ng.enginesandempires.frontier.incursion.Incursions;
import dev.brights0ng.enginesandempires.frontier.incursion.Settlement;
import dev.brights0ng.enginesandempires.frontier.incursion.SettlementClusters;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevels;
import dev.brights0ng.enginesandempires.frontier.tier.SectionKey;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands for incursions, under {@code /eae frontier incursion} (merged with {@link FrontierCommand}'s branch):
 * <ul>
 *   <li>{@code status}: the incursions under way.</li>
 *   <li>{@code odds}: the settlement you stand in, its score and tonight's chance, and whether it is immune.</li>
 *   <li>{@code start lesser|greater}: starts one against the settlement you stand in (or, outside one, against the chunks
 *       around you).</li>
 *   <li>{@code thumper}: starts a thumper incursion against the thumpers around you.</li>
 *   <li>{@code roll [force]}: rolls tonight's incursions now (with {@code force}, every settlement with a player gets one).</li>
 *   <li>{@code next}: kills the current wave, so the next comes (or the incursion is won).</li>
 *   <li>{@code stop}: ends every incursion; what is left retreats.</li>
 *   <li>{@code immunity clear}: forgets every settlement's immunity.</li>
 * </ul>
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class IncursionCommand {

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> incursion = Commands.literal("incursion")
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("odds").executes(context -> odds(context.getSource())))
                .then(Commands.literal("start")
                        .then(Commands.literal("lesser").executes(context -> start(context.getSource(), false)))
                        .then(Commands.literal("greater").executes(context -> start(context.getSource(), true))))
                .then(Commands.literal("thumper").executes(context -> thumper(context.getSource())))
                .then(Commands.literal("roll")
                        .executes(context -> roll(context.getSource(), false))
                        .then(Commands.literal("force").executes(context -> roll(context.getSource(), true))))
                .then(Commands.literal("next").executes(context -> next(context.getSource())))
                .then(Commands.literal("stop").executes(context -> stop(context.getSource())))
                .then(Commands.literal("immunity").then(Commands.literal("clear")
                        .executes(context -> clearImmunity(context.getSource()))));
        event.getDispatcher().register(Commands.literal("eae")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("frontier").then(incursion)));
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        List<Incursion> all = Incursions.of(level).all();
        source.sendSuccess(() -> Component.literal(all.isEmpty() ? "No incursions under way." : all.size() + " incursion(s):"), false);
        for (Incursion incursion : all) {
            line(source, incursion.describe(level));
        }
        line(source, "Loud machines tracked: " + (FrontierLevels.of(level) == null ? 0 : FrontierLevels.of(level).machines().tracked()));
        return all.size();
    }

    private static int odds(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        FrontierLevel frontier = FrontierLevels.of(level);
        if (frontier == null) {
            source.sendFailure(Component.literal("Only the Overworld has settlements."));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        Settlement settlement = Settlement.at(frontier, pos);
        if (settlement == null) {
            source.sendFailure(Component.literal("You are not in a settlement (Settled or Civilized land)."));
            return 0;
        }
        Settlement.Score score = settlement.score(level);
        double chance = settlement.chance(score.total());
        FrontierConfig.IncursionParams params = FrontierConfig.incursions();
        long now = level.getGameTime();
        long immune = Incursions.of(level).data().immuneUntil(settlement.columns(), now);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s settlement: %d subchunks in %d chunks.",
                settlement.civilized() ? "Civilized" : "Settled", settlement.sections().size(), settlement.columns().size())), false);
        line(source, String.format(Locale.ROOT, "Score %.2f of %.0f: %d players (x%.2f), %d villagers/pillagers/colonists (x%.2f), "
                        + "%.2f from thumper shots, %.2f from loud machines (last day)",
                score.total(), params.maxScore(), score.players(), params.playerWeight(), score.inhabitants(),
                params.inhabitantWeight(), score.shots(), score.machines()));
        line(source, String.format(Locale.ROOT, "Tonight's chance: %.1f%%%s", chance * 100,
                settlement.civilized() ? String.format(Locale.ROOT, " (%.1f%% greater)", chance * params.greaterShare() * 100) : " (lesser only)"));
        line(source, immune > now ? String.format(Locale.ROOT, "Immune for %.2f more days", (immune - now) / 24000.0) : "Not immune");
        return (int) Math.round(chance * 1000);
    }

    private static int start(CommandSourceStack source, boolean greater) {
        ServerLevel level = source.getLevel();
        FrontierLevel frontier = FrontierLevels.of(level);
        if (frontier == null) {
            source.sendFailure(Component.literal("Only the Overworld has settlements."));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        Settlement settlement = Settlement.at(frontier, pos);
        if (settlement == null) {
            // Not in a settlement: pretend the 5x5 chunks around here are one, to try waves out anywhere.
            Set<Long> sections = new HashSet<>();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    sections.add(SectionKey.of((pos.getX() >> 4) + dx, pos.getY() >> 4, (pos.getZ() >> 4) + dz));
                }
            }
            settlement = new Settlement(sections, SettlementClusters.columns(sections), greater);
        }
        Incursion incursion = Incursions.of(level).startSettlement(settlement, greater);
        source.sendSuccess(() -> Component.literal("Started " + incursion.describe(level)), true);
        return incursion.id();
    }

    private static int thumper(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Incursion incursion = Incursions.of(level).startThumper(BlockPos.containing(source.getPosition()));
        if (incursion == null) {
            source.sendFailure(Component.literal("A thumper incursion is already under way nearby."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Started " + incursion.describe(level)), true);
        return incursion.id();
    }

    private static int roll(CommandSourceStack source, boolean force) {
        ServerLevel level = source.getLevel();
        FrontierLevel frontier = FrontierLevels.of(level);
        if (frontier == null) {
            source.sendFailure(Component.literal("Only the Overworld has settlements."));
            return 0;
        }
        List<Incursions.Roll> rolls = Incursions.of(level).rollTonight(frontier, force);
        source.sendSuccess(() -> Component.literal("Rolled for " + rolls.size() + " settlement(s):"), true);
        int started = 0;
        for (Incursions.Roll roll : rolls) {
            String where = (roll.settlement().civilized() ? "Civilized" : "Settled") + ", " + roll.settlement().columns().size()
                    + " chunks";
            if (roll.score() == null) {
                line(source, where + ": skipped, " + roll.note());
                continue;
            }
            line(source, String.format(Locale.ROOT, "%s: score %.2f, chance %.1f%% -> %s", where, roll.score().total(),
                    roll.chance() * 100, roll.kind().name().toLowerCase(Locale.ROOT)));
            if (roll.kind() != dev.brights0ng.enginesandempires.frontier.incursion.IncursionOdds.Kind.NONE) {
                started++;
            }
        }
        return started;
    }

    private static int next(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        int killed = 0;
        for (var entity : level.getAllEntities()) {
            if (entity instanceof net.minecraft.world.entity.Mob mob && mob.getTags().contains(Incursion.TAG)
                    && !mob.getTags().contains(Incursion.RETREAT_TAG)) {
                mob.discard();
                killed++;
            }
        }
        int count = killed;
        source.sendSuccess(() -> Component.literal("Removed " + count + " incursion mobs."), true);
        return killed;
    }

    private static int stop(CommandSourceStack source) {
        int stopped = Incursions.of(source.getLevel()).stopAll();
        source.sendSuccess(() -> Component.literal("Ended " + stopped + " incursion(s)."), true);
        return stopped;
    }

    private static int clearImmunity(CommandSourceStack source) {
        IncursionData.of(source.getLevel()).clearImmunity();
        source.sendSuccess(() -> Component.literal("Every settlement's immunity is forgotten."), true);
        return 1;
    }

    private static void line(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("  " + text).withStyle(ChatFormatting.GRAY), false);
    }

    private IncursionCommand() {
    }
}
