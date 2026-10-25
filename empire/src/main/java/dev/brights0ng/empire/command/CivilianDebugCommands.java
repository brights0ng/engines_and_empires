// Written by Claude.
package dev.brights0ng.empire.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.CivilianAppearance;
import dev.brights0ng.empire.rules.CivilianLines;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** {@code /empire debug civilian ...}: spawn, clear, inspect and prod civilians. Attached under the debug literal. */
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
                        .executes(ctx -> info(ctx.getSource())));
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
        civilians.forEach(CivilianEntity::discard);
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
        return 1;
    }

    private CivilianDebugCommands() {
    }
}
