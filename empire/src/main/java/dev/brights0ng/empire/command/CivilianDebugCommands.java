// Written by Claude; extended with ability and needs commands by ChatGPT.
package dev.brights0ng.empire.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import dev.brights0ng.empire.civilian.CivilianChunkLoading;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.civilian.TravellerArrivals;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.TravellerSlot;
import dev.brights0ng.empire.rules.ArrivalRule;
import dev.brights0ng.empire.rules.CivilianAppearance;
import dev.brights0ng.empire.rules.CivilianAbilities;
import dev.brights0ng.empire.rules.CivilianFood;
import dev.brights0ng.empire.rules.CivilianLines;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /empire debug civilian ...}: spawn, clear, inspect and prod civilians, force a traveller, recruit one and dump an
 * empire's roster. Attached under the debug literal.
 */
public final class CivilianDebugCommands {

    private static final int MAX_SPAWN = 32;
    private static final int DEFAULT_CLEAR_RADIUS = 32;
    private static final int MAX_CLEAR_RADIUS = 256;
    /** How far {@code say}, {@code look} and {@code info} look for the nearest civilian. */
    private static final double NEAREST_RADIUS = 8.0;
    private static final String TALK = "talk";

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("civilian")
                .then(Commands.literal("spawn")
                        .executes(ctx -> spawn(ctx.getSource(), 1, null))
                        .then(Commands.literal("look")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(ctx -> spawn(ctx.getSource(), 1, code(ctx)))))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_SPAWN))
                                .executes(ctx -> spawn(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count"), null))
                                .then(Commands.literal("look")
                                        .then(Commands.argument("code", StringArgumentType.word())
                                                .executes(ctx -> spawn(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "count"), code(ctx)))))))
                .then(Commands.literal("clear")
                        .executes(ctx -> clear(ctx.getSource(), DEFAULT_CLEAR_RADIUS))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_CLEAR_RADIUS))
                                .executes(ctx -> clear(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("say")
                        .then(Commands.argument("category", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(sayCategories(), builder))
                                .executes(ctx -> say(ctx.getSource(), StringArgumentType.getString(ctx, "category")))))
                .then(Commands.literal("look")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(ctx -> look(ctx.getSource(), code(ctx)))))
                .then(Commands.literal("info")
                        .executes(ctx -> info(ctx.getSource())))
                .then(Commands.literal("food")
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 20))
                                .executes(ctx -> food(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "level"), 0.0F))
                                .then(Commands.argument("saturation", FloatArgumentType.floatArg())
                                        .executes(ctx -> food(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "level"),
                                                FloatArgumentType.getFloat(ctx, "saturation"))))))
                .then(Commands.literal("abilities")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(ctx -> abilities(ctx.getSource(), code(ctx)))))
                .then(Commands.literal("traveller")
                        .then(playerArgument()
                                .executes(ctx -> traveller(ctx.getSource(), player(ctx)))))
                .then(Commands.literal("recruit")
                        .then(playerArgument()
                                .executes(ctx -> recruit(ctx.getSource(), player(ctx)))))
                .then(Commands.literal("roster")
                        .executes(ctx -> roster(ctx.getSource(), null))
                        .then(playerArgument()
                                .executes(ctx -> roster(ctx.getSource(), player(ctx)))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> playerArgument() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder));
    }

    private static String player(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "player");
    }

    /** The empire of {@code playerArg} (the source's own when null), or null after a failure message. */
    @Nullable
    private static EmpireRecord empireOf(CommandSourceStack source, @Nullable String playerArg) {
        UUID playerId;
        if (playerArg != null) {
            playerId = EmpireCommand.resolvePlayerUuid(source, playerArg);
        } else if (source.getEntity() instanceof ServerPlayer player) {
            playerId = player.getUUID();
        } else {
            source.sendFailure(Component.literal("Player argument required when run from console"));
            return null;
        }
        EmpireRecord record = EmpireSavedData.of(source.getServer()).getEmpireOf(playerId);
        if (record == null) {
            source.sendFailure(Component.literal("No empire for " + (playerArg != null ? playerArg : source.getTextName())));
        }
        return record;
    }

    private static String code(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "code");
    }

    private static List<String> sayCategories() {
        List<String> ids = new ArrayList<>();
        for (CivilianLines.Category c : CivilianLines.Category.values()) {
            ids.add(c.id());
        }
        ids.add(TALK);
        return ids;
    }

    @Nullable
    private static CivilianAppearance parse(CommandSourceStack source, String code) {
        try {
            return CivilianAppearance.parse(code);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Look codes are 10 digits, each 1-5: " + code));
            return null;
        }
    }

    private static int spawn(CommandSourceStack source, int count, @Nullable String code) {
        CivilianAppearance look = null;
        if (code != null) {
            look = parse(source, code);
            if (look == null) {
                return 0;
            }
        }
        for (int i = 0; i < count; i++) {
            CivilianEntity civilian = CivilianSpawning.spawn(source.getLevel(), source.getPosition(), look);
            String name = civilian.getName().getString();
            String lookCode = civilian.appearance().code();
            source.sendSuccess(() -> Component.literal("Spawned " + name + " (" + lookCode + ")"), true);
        }
        return count;
    }

    private static int clear(CommandSourceStack source, int radius) {
        Vec3 pos = source.getPosition();
        List<CivilianEntity> civilians = source.getLevel().getEntitiesOfClass(CivilianEntity.class,
                new AABB(pos, pos).inflate(radius), c -> c.position().distanceToSqr(pos) <= (double) radius * radius);
        // Their records and slots go too, so cleared citizens are not given new bodies.
        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        for (CivilianEntity civilian : civilians) {
            if (civilian.status() == CivilianEntity.Status.CITIZEN && civilian.civilianId() != null) {
                CivilianRecord record = data.civilian(civilian.civilianId());
                if (record != null && record.body().equals(civilian.getUUID())) {
                    data.removeCivilian(record.id());
                }
            } else if (civilian.status() == CivilianEntity.Status.TRAVELLER) {
                CivilianRoster.clearTravellerSlot(data, civilian);
            }
            civilian.discard();
        }
        int n = civilians.size();
        source.sendSuccess(() -> Component.literal("Cleared " + n + " civilian" + (n == 1 ? "" : "s")), true);
        return n;
    }

    @Nullable
    private static CivilianEntity nearest(CommandSourceStack source) {
        Vec3 pos = source.getPosition();
        CivilianEntity best = null;
        double bestD2 = NEAREST_RADIUS * NEAREST_RADIUS;
        for (CivilianEntity c : source.getLevel().getEntitiesOfClass(CivilianEntity.class,
                new AABB(pos, pos).inflate(NEAREST_RADIUS))) {
            double d2 = c.position().distanceToSqr(pos);
            if (d2 <= bestD2) {
                best = c;
                bestD2 = d2;
            }
        }
        if (best == null) {
            source.sendFailure(Component.literal("No civilian within " + (int) NEAREST_RADIUS + " blocks"));
        }
        return best;
    }

    private static int say(CommandSourceStack source, String category) {
        CivilianLines.Category chosen = null;
        for (CivilianLines.Category c : CivilianLines.Category.values()) {
            if (c.id().equals(category)) {
                chosen = c;
            }
        }
        if (chosen == null && !TALK.equals(category)) {
            source.sendFailure(Component.literal("Unknown category: " + category + " (" + String.join(", ", sayCategories()) + ")"));
            return 0;
        }
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        if (chosen != null) {
            civilian.say(chosen);
        } else {
            civilian.sayKey(CivilianLines.talkKey(0, 0));
        }
        String name = civilian.getName().getString();
        source.sendSuccess(() -> Component.literal(name + " says a " + category + " line"), false);
        return 1;
    }

    private static int look(CommandSourceStack source, String code) {
        CivilianAppearance look = parse(source, code);
        if (look == null) {
            return 0;
        }
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        civilian.setAppearance(look);
        String name = civilian.getName().getString();
        source.sendSuccess(() -> Component.literal(name + " now looks " + look.code()), true);
        return 1;
    }

    private static int abilities(CommandSourceStack source, String code) {
        CivilianAbilities abilities;
        try {
            abilities = CivilianAbilities.parse(code);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Ability codes are six scores 3-18 separated by hyphens: " + code));
            return 0;
        }
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        civilian.setAbilities(abilities);
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " abilities " + abilities.code()), true);
        return 1;
    }

    private static int info(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        String name = civilian.getName().getString();
        String code = civilian.appearance().code();
        String home = civilian.home() != null ? civilian.home().toShortString() : "none";
        UUID empireId = civilian.travellerEmpire();
        if (civilian.civilianId() != null) {
            CivilianRecord record = EmpireSavedData.of(source.getServer()).civilian(civilian.civilianId());
            empireId = record != null ? record.empire() : null;
        }
        String empire = empireId != null ? empireId.toString() : "none";
        String id = civilian.civilianId() != null ? civilian.civilianId().toString() : "none";
        String status = civilian.status().name();
        source.sendSuccess(() -> Component.literal(name + ": " + status + ", look " + code + ", home " + home
                + ", civilian id " + id + ", empire " + empire), false);
        source.sendSuccess(() -> Component.literal("Abilities " + civilian.abilities().code() + ", mean "
                + String.format(Locale.ROOT, "%.1f", civilian.abilities().mean())), false);
        CivilianFood food = civilian.food();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Food %d, saturation %.1f, exhaustion %.2f, health %.1f/%.1f, eaten today %d / yesterday %d, active ticks %d",
                food.foodLevel(), food.saturation(), food.exhaustion(), civilian.getHealth(), civilian.getMaxHealth(),
                food.foodEatenToday(), food.foodEatenYesterday(), food.activeTicks())), false);
        return 1;
    }

    private static int food(CommandSourceStack source, int level, float saturation) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) return 0;
        civilian.setFood(level, saturation);
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " food " + civilian.food().foodLevel()
                + String.format(Locale.ROOT, ", saturation %.1f", civilian.food().saturation())), true);
        return 1;
    }

    /** Forces an arrival for the player's empire now: skips the day timer only. */
    private static int traveller(CommandSourceStack source, String playerArg) {
        EmpireRecord record = empireOf(source, playerArg);
        if (record == null) {
            return 0;
        }
        TravellerArrivals.Forced forced = TravellerArrivals.force(record, source.getServer().overworld());
        if (forced.failure() != null) {
            source.sendFailure(Component.literal("No traveller: " + forced.failure()));
            return 0;
        }
        String name = forced.traveller().getName().getString();
        BlockPos spot = forced.spot();
        source.sendSuccess(() -> Component.literal("Traveller " + name + " arrives at " + spot.toShortString()), true);
        return 1;
    }

    /** The nearest civilian becomes a citizen of the player's empire: no food and no cap, but a warning past the cap. */
    private static int recruit(CommandSourceStack source, String playerArg) {
        EmpireRecord record = empireOf(source, playerArg);
        if (record == null) {
            return 0;
        }
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        if (!(civilian.level() instanceof ServerLevel level) || level != source.getServer().overworld()) {
            source.sendFailure(Component.literal("Civilians can only join an empire in the Overworld"));
            return 0;
        }
        CivilianRoster.recruit(civilian, record, level);
        int citizens = EmpireSavedData.of(level).citizenCount(record.id());
        String name = civilian.getName().getString();
        source.sendSuccess(() -> Component.literal(name + " joined the empire of " + playerArg
                + " (" + citizens + "/" + ArrivalRule.PIONEER_LIMIT + ")"), true);
        if (citizens > ArrivalRule.PIONEER_LIMIT) {
            source.sendSuccess(() -> Component.literal("Warning: over the cap of " + ArrivalRule.PIONEER_LIMIT + " citizens"), false);
        }
        return 1;
    }

    /**
     * Every record of the empire: name, the id's first 8 characters, whether the body is loaded, last position; then
     * whether its civilians load chunks and how many they hold.
     */
    private static int roster(CommandSourceStack source, @Nullable String playerArg) {
        EmpireRecord record = empireOf(source, playerArg);
        if (record == null) {
            return 0;
        }
        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        ServerLevel overworld = source.getServer().overworld();
        List<CivilianRecord> records = data.civiliansOf(record.id());
        source.sendSuccess(() -> Component.literal(records.size() + " record" + (records.size() == 1 ? "" : "s")), false);
        for (CivilianRecord civilian : records) {
            boolean loaded = overworld.getEntity(civilian.body()) != null;
            source.sendSuccess(() -> Component.literal(civilian.name() + " [" + civilian.id().toString().substring(0, 8)
                    + "] body " + (loaded ? "loaded" : "not loaded") + ", last at " + civilian.lastPos().toShortString()
                    + ", food " + civilian.food().foodLevel() + ", hp "
                    + (Float.isNaN(civilian.health()) ? "unknown" : String.format(Locale.ROOT, "%.1f", civilian.health()))
                    + "/30"), false);
        }
        TravellerSlot slot = data.traveller(record.id());
        if (slot != null) {
            boolean loaded = overworld.getEntity(slot.body()) != null;
            source.sendSuccess(() -> Component.literal("Traveller waiting: body " + (loaded ? "loaded" : "not loaded")
                    + ", want " + slot.want() + ", leaves at " + slot.leaveAt()), false);
        }
        boolean loading = CivilianChunkLoading.mayLoad(source.getServer(), record);
        int held = CivilianChunkLoading.held(record.id()).size();
        source.sendSuccess(() -> Component.literal("Chunk loading: " + (loading ? "on" : "off") + ", " + held
                + " chunk" + (held == 1 ? "" : "s") + " held"), false);
        return records.size();
    }

    private CivilianDebugCommands() {
    }
}
