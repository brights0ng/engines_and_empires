// Written by Claude; extended with ability, needs, inventory and eating commands by ChatGPT; extended with sleep, bed cleanup, fulfilment and traits commands by Gemini.
package dev.brights0ng.empire.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import dev.brights0ng.empire.civilian.CivilianBeds;
import dev.brights0ng.empire.civilian.CivilianChunkLoading;
import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.civilian.CivilianFulfilmentMeters;
import dev.brights0ng.empire.civilian.CivilianOccupations;
import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.civilian.CivilianSpawning;
import dev.brights0ng.empire.civilian.TravellerArrivals;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.data.TravellerSlot;
import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.rules.ArrivalRule;
import dev.brights0ng.empire.rules.CivilianAppearance;
import dev.brights0ng.empire.rules.CivilianFood;
import dev.brights0ng.empire.rules.CivilianFulfilment;
import dev.brights0ng.empire.rules.CivilianLines;
import dev.brights0ng.empire.rules.CivilianSchedule;
import dev.brights0ng.empire.rules.CivilianTraits;
import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;
import dev.brights0ng.empire.rules.Occupation;
import dev.brights0ng.empire.rules.ScheduleClock;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
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
                .then(Commands.literal("traits")
                        .executes(ctx -> traitsInfo(ctx.getSource()))
                        .then(Commands.literal("set")
                                .then(Commands.argument("trait", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(baseTraitIds(), builder))
                                        .then(Commands.argument("value", IntegerArgumentType.integer(
                                                CivilianTraits.MIN_VALUE, CivilianTraits.MAX_VALUE))
                                                .executes(ctx -> traitsSet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "trait"),
                                                        IntegerArgumentType.getInteger(ctx, "value"))))))
                        .then(Commands.literal("neutral")
                                .executes(ctx -> traitsNeutral(ctx.getSource())))
                        .then(Commands.literal("reroll")
                                .executes(ctx -> traitsReroll(ctx.getSource()))))
                .then(Commands.literal("sleep")
                        .then(Commands.literal("claim")
                                .executes(ctx -> sleepClaim(ctx.getSource())))
                        .then(Commands.literal("release")
                                .executes(ctx -> sleepRelease(ctx.getSource())))
                        .then(Commands.literal("lastnight")
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.0F, 1.0F))
                                        .executes(ctx -> sleepLastNight(ctx.getSource(), FloatArgumentType.getFloat(ctx, "value"))))))
                .then(Commands.literal("fulfilment")
                        .executes(ctx -> fulfilment(ctx.getSource()))
                        .then(Commands.literal("eval")
                                .executes(ctx -> fulfilmentEval(ctx.getSource())))
                        .then(Commands.literal("grievance")
                                .then(Commands.argument("value", FloatArgumentType.floatArg(0.0F, 1.0F))
                                        .executes(ctx -> fulfilmentGrievance(ctx.getSource(), FloatArgumentType.getFloat(ctx, "value")))))
                        .then(Commands.literal("days")
                                .then(Commands.argument("days", DoubleArgumentType.doubleArg(0.0, 10000.0))
                                        .executes(ctx -> fulfilmentDays(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "days"))))))
                .then(Commands.literal("schedule")
                        .executes(ctx -> scheduleInfo(ctx.getSource()))
                        .then(Commands.literal("rebuild")
                                .executes(ctx -> scheduleRebuild(ctx.getSource()))))
                .then(Commands.literal("work")
                        .then(Commands.literal("clear")
                                .executes(ctx -> workClear(ctx.getSource())))
                        .then(Commands.literal("here")
                                .executes(ctx -> workAssign(ctx.getSource(), BlockPos.containing(ctx.getSource().getPosition()), 7, false))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 14))
                                        .executes(ctx -> workAssign(ctx.getSource(), BlockPos.containing(ctx.getSource().getPosition()),
                                                IntegerArgumentType.getInteger(ctx, "minutes"), false))
                                        .then(Commands.literal("indoor")
                                                .executes(ctx -> workAssign(ctx.getSource(), BlockPos.containing(ctx.getSource().getPosition()),
                                                        IntegerArgumentType.getInteger(ctx, "minutes"), true)))
                                        .then(Commands.literal("outdoor")
                                                .executes(ctx -> workAssign(ctx.getSource(), BlockPos.containing(ctx.getSource().getPosition()),
                                                        IntegerArgumentType.getInteger(ctx, "minutes"), false)))))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> workAssign(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"), 7, false))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 14))
                                        .executes(ctx -> workAssign(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"),
                                                IntegerArgumentType.getInteger(ctx, "minutes"), false))
                                        .then(Commands.literal("indoor")
                                                .executes(ctx -> workAssign(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"),
                                                        IntegerArgumentType.getInteger(ctx, "minutes"), true)))
                                        .then(Commands.literal("outdoor")
                                                .executes(ctx -> workAssign(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "pos"),
                                                        IntegerArgumentType.getInteger(ctx, "minutes"), false))))))
                .then(Commands.literal("job")
                        .then(Commands.literal("clear")
                                .executes(ctx -> jobClear(ctx.getSource())))
                        .then(Commands.literal("assign")
                                .executes(ctx -> jobAssignHere(ctx.getSource()))
                                .then(Commands.literal("here")
                                        .executes(ctx -> jobAssignHere(ctx.getSource())))
                                .then(Commands.argument("regionIdPrefix", StringArgumentType.word())
                                        .executes(ctx -> jobAssignById(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "regionIdPrefix"))))))
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
                    CivilianBeds.releaseAndDetach(source.getServer().overworld(), record.empire(), record.id(), record.bed());
                    CivilianOccupations.detachFromWorkplace(data, record.id(), record.schedule().work());
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

    private static List<String> baseTraitIds() {
        List<String> ids = new ArrayList<>(CivilianTraits.TRAIT_COUNT);
        for (BaseTrait t : BaseTrait.values()) {
            ids.add(t.id());
        }
        return ids;
    }

    private static int traitsInfo(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        for (Component line : civilian.traitsDebugLines()) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int traitsSet(CommandSourceStack source, String traitName, int value) {
        BaseTrait trait = BaseTrait.byId(traitName);
        if (trait == null) {
            source.sendFailure(Component.literal("Unknown trait: " + traitName + " (" + String.join(", ", baseTraitIds()) + ")"));
            return 0;
        }
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        civilian.setTraits(civilian.traits().with(trait, value));
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " " + trait.id() + " = " + value
                + " (abilities " + civilian.abilities().code() + ")"), true);
        return 1;
    }

    private static int traitsNeutral(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        civilian.setTraits(CivilianTraits.NEUTRAL);
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " traits reset to neutral"), true);
        return 1;
    }

    private static int traitsReroll(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        civilian.setTraits(CivilianTraits.roll(source.getLevel().random::nextLong));
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " traits rerolled (abilities "
                + civilian.abilities().code() + ")"), true);
        return 1;
    }

    private static int info(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        for (Component line : civilian.debugInfoLines(source.getLevel())) {
            source.sendSuccess(() -> line, false);
        }
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

    private static int sleepClaim(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        if (civilian.status() != CivilianEntity.Status.CITIZEN || civilian.civilianId() == null) {
            source.sendFailure(Component.literal(civilian.getName().getString() + " is not a citizen"));
            return 0;
        }
        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        CivilianRecord record = data.civilian(civilian.civilianId());
        EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
        if (empire == null) {
            source.sendFailure(Component.literal(civilian.getName().getString() + " has no empire"));
            return 0;
        }
        BlockPos claimed = CivilianBeds.ensureOrClaimBed(source.getServer().overworld(), empire, civilian);
        if (claimed == null) {
            source.sendFailure(Component.literal("No unclaimed bed within " + CivilianBeds.CLAIM_RADIUS + " blocks on empire land"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " bed " + claimed.toShortString()), true);
        return 1;
    }

    private static int sleepRelease(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        BlockPos bed = civilian.claimedBed();
        if (bed == null) {
            source.sendFailure(Component.literal(civilian.getName().getString() + " has no claimed bed"));
            return 0;
        }
        ServerLevel overworld = source.getServer().overworld();
        CivilianRecord record = civilian.civilianId() != null ? EmpireSavedData.of(overworld).civilian(civilian.civilianId()) : null;
        CivilianBeds.releaseAndDetach(overworld, record != null ? record.empire() : null, civilian.civilianId(), bed);
        civilian.setClaimedBed(null);
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " released bed " + bed.toShortString()), true);
        return 1;
    }

    private static int sleepLastNight(CommandSourceStack source, float value) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        civilian.setLastNight(value);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s lastNight %.2f",
                civilian.getName().getString(), civilian.sleep().lastNight())), true);
        return 1;
    }

    private static int fulfilment(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        for (Component line : CivilianFulfilmentMeters.formatDebugReport(source.getLevel(), civilian)) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int fulfilmentEval(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        CivilianFulfilmentMeters.stepSlot(source.getLevel(), civilian, CivilianFulfilment.SLOT_TICKS);
        for (Component line : CivilianFulfilmentMeters.formatDebugReport(source.getLevel(), civilian)) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int fulfilmentGrievance(CommandSourceStack source, float value) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        civilian.fulfilment().setGrievance(value);
        civilian.syncNeedsToRecord();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s grievance %.3f",
                civilian.getName().getString(), civilian.fulfilment().grievance())), true);
        return 1;
    }

    private static int fulfilmentDays(CommandSourceStack source, double days) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        civilian.fulfilment().setCitizenActiveTicks(Math.round(days * CivilianFood.DAY_LENGTH));
        civilian.syncNeedsToRecord();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s days lived %.1f",
                civilian.getName().getString(), civilian.fulfilment().daysLived())), true);
        return 1;
    }

    private static int scheduleInfo(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        for (Component line : civilian.aiController().formatScheduleReport(source.getLevel())) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int scheduleRebuild(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        civilian.aiController().forceRebuildSchedule(source.getLevel());
        for (Component line : civilian.aiController().formatScheduleReport(source.getLevel())) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int workAssign(CommandSourceStack source, BlockPos pos, int minutes, boolean indoor) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        int askTicks = Math.clamp(minutes * ScheduleClock.TICKS_PER_MINUTE,
                CivilianSchedule.MIN_WORK_TICKS, 16_800);
        WorkAssignment assignment = WorkAssignment.of(pos, indoor, askTicks, "debug_work");
        civilian.setWorkAssignment(assignment);
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " assigned work @ "
                + pos.toShortString() + " (" + (indoor ? "indoor" : "outdoor")
                + ", ask=" + ScheduleClock.format(askTicks) + ")"), true);
        return 1;
    }

    private static int workClear(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        CivilianOccupations.unassign(source.getServer().overworld(), EmpireSavedData.of(source.getServer()), civilian);
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " work assignment cleared"), true);
        return 1;
    }

    private static int jobAssignHere(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        if (source.getLevel() != overworld) {
            source.sendFailure(Component.literal("Regions only exist in the Overworld"));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        EmpireSavedData data = EmpireSavedData.of(overworld);
        RegionRecord region = data.regionAt(pos);
        if (region == null) {
            region = data.regionAt(pos.below());
        }
        if (region == null) {
            source.sendFailure(Component.literal("No region at " + pos.toShortString()));
            return 0;
        }
        return jobAssignRegion(source, overworld, data, region);
    }

    private static int jobAssignById(CommandSourceStack source, String idPrefix) {
        ServerLevel overworld = source.getServer().overworld();
        EmpireSavedData data = EmpireSavedData.of(overworld);
        String prefix = idPrefix.toLowerCase(Locale.ROOT);
        RegionRecord match = null;
        for (RegionRecord region : data.allRegions()) {
            if (region.id().toString().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                if (match != null) {
                    source.sendFailure(Component.literal("Ambiguous region id prefix: " + idPrefix));
                    return 0;
                }
                match = region;
            }
        }
        if (match == null) {
            source.sendFailure(Component.literal("No region matching prefix: " + idPrefix));
            return 0;
        }
        return jobAssignRegion(source, overworld, data, match);
    }

    private static int jobAssignRegion(CommandSourceStack source, ServerLevel overworld, EmpireSavedData data,
                                       RegionRecord region) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        CivilianOccupations.AssignResult result = CivilianOccupations.assign(overworld, data, civilian, region);
        if (!result.succeeded()) {
            source.sendFailure(Component.literal("Could not assign " + civilian.getName().getString()
                    + " to region [" + region.id().toString().substring(0, 8) + "]: " + result.outcome().name()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " assigned as "
                + result.occupation().id() + " @ [" + region.id().toString().substring(0, 8) + "]"), true);
        return 1;
    }

    private static int jobClear(CommandSourceStack source) {
        CivilianEntity civilian = nearest(source);
        if (civilian == null) {
            return 0;
        }
        boolean cleared = CivilianOccupations.unassign(
                source.getServer().overworld(), EmpireSavedData.of(source.getServer()), civilian);
        if (!cleared) {
            source.sendFailure(Component.literal(civilian.getName().getString() + " is already unemployed"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(civilian.getName().getString() + " is now unemployed"), true);
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
            double maxHp = civilian.traits().derivedStats().maxHealth();
            Occupation occ = CivilianOccupations.occupationOf(civilian);
            source.sendSuccess(() -> Component.literal(civilian.name() + " [" + civilian.id().toString().substring(0, 8)
                    + "] body " + (loaded ? "loaded" : "not loaded") + ", last at " + civilian.lastPos().toShortString()
                    + ", food " + civilian.food().foodLevel() + ", hp "
                    + (Float.isNaN(civilian.health()) ? "unknown" : String.format(Locale.ROOT, "%.1f", civilian.health()))
                    + String.format(Locale.ROOT, "/%.0f, sleep ", maxHp)
                    + (Float.isNaN(civilian.sleep().lastNight()) ? "none" : String.format(Locale.ROOT, "%.2f", civilian.sleep().lastNight()))
                    + String.format(Locale.ROOT, ", griev %.2f", civilian.fulfilment().grievance())
                    + (civilian.bed() != null ? ", bed " + civilian.bed().toShortString() : "")
                    + (occ.isEmployed() ? ", job " + occ.id() : "")), false);
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
