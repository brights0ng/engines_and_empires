// Written by Gemini; members and the god's hunger edited by Claude.
package dev.brights0ng.empire.command;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.god.GodHunger;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.ClaimGraph;
import dev.brights0ng.empire.rules.DawnClock;
import dev.brights0ng.empire.rules.GodSatiety;
import dev.brights0ng.empire.rules.StakeResult;
import dev.brights0ng.empire.rules.UnstakeResult;
import dev.brights0ng.empire.tier.TierChecks;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = Empire.MODID)
public final class EmpireCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher(), event.getBuildContext());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("empire")
                .then(Commands.literal("info")
                        .executes(ctx -> info(ctx.getSource(), null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder))
                                .executes(ctx -> info(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("here")
                        .executes(ctx -> here(ctx.getSource())))
                .then(Commands.literal("debug")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("dominion")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder))
                                        .then(Commands.literal("set")
                                                .then(Commands.argument("n", IntegerArgumentType.integer(0))
                                                        .executes(ctx -> setDominion(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "player"),
                                                                IntegerArgumentType.getInteger(ctx, "n")))))
                                        .then(Commands.literal("add")
                                                .then(Commands.argument("n", IntegerArgumentType.integer())
                                                        .executes(ctx -> addDominion(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "player"),
                                                                IntegerArgumentType.getInteger(ctx, "n")))))))
                        .then(Commands.literal("stake")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder))
                                        .executes(ctx -> debugStake(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("unstake")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder))
                                        .executes(ctx -> debugUnstake(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("disband")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder))
                                        .executes(ctx -> debugDisband(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("hunger")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder))
                                        .then(Commands.argument("ticks", LongArgumentType.longArg(0))
                                                .executes(ctx -> debugHunger(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        LongArgumentType.getLong(ctx, "ticks"))))))));
    }

    public static UUID resolvePlayerUuid(CommandSourceStack source, String playerArg) {
        try {
            return UUID.fromString(playerArg);
        } catch (IllegalArgumentException ignored) {
        }

        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(playerArg);
        if (online != null) {
            return online.getUUID();
        }

        if (source.getServer().getProfileCache() != null) {
            var opt = source.getServer().getProfileCache().get(playerArg);
            if (opt.isPresent()) {
                return opt.get().getId();
            }
        }

        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        for (EmpireRecord record : data.allEmpires()) {
            for (UUID member : record.members()) {
                if (member.toString().equalsIgnoreCase(playerArg)) {
                    return member;
                }
            }
        }

        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerArg).getBytes(StandardCharsets.UTF_8));
    }

    private static int info(CommandSourceStack source, String playerArg) {
        UUID targetId;
        String displayName;
        if (playerArg == null) {
            if (source.getEntity() instanceof ServerPlayer player) {
                targetId = player.getUUID();
                displayName = player.getScoreboardName();
            } else {
                source.sendFailure(Component.literal("Player argument required when run from console"));
                return 0;
            }
        } else {
            targetId = resolvePlayerUuid(source, playerArg);
            displayName = playerArg;
        }

        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        EmpireRecord record = data.getEmpireOf(targetId);
        if (record == null) {
            source.sendFailure(Component.translatable("message.empire.info.no_empire", displayName));
            return 0;
        }

        long day = DawnClock.dayOf(source.getLevel().getDayTime());
        source.sendSuccess(() -> Component.literal("Empire ID: " + record.id()), false);
        source.sendSuccess(() -> Component.literal("Members: " + memberNames(source, record)), false);
        source.sendSuccess(() -> Component.literal("Dominion: " + record.state().dominion() + " (Available: " + record.state().available(day) + ")"), false);
        source.sendSuccess(() -> Component.literal("Staked chunks: " + record.state().stakedCount()), false);
        source.sendSuccess(() -> Component.literal("Dormant: " + record.state().dormant().size() + " points"), false);
        source.sendSuccess(() -> Component.literal("Main bonfire: " + (record.mainBonfire() != null ? record.mainBonfire().toShortString() : "None")), false);
        source.sendSuccess(() -> Component.literal("Founding chunk: [" + ChunkKeys.getX(record.state().foundingChunk()) + ", " + ChunkKeys.getZ(record.state().foundingChunk()) + "]"), false);
        source.sendSuccess(() -> godLine(source, record), false);
        return 1;
    }

    /** "God: &lt;name&gt;, fed N days ago; abandons in M days", or "God: none". */
    private static Component godLine(CommandSourceStack source, EmpireRecord record) {
        if (record.god() == null) {
            return Component.literal("God: none");
        }
        long limit = GodHunger.limitTicks(source.getServer().overworld());
        long hunger = record.hungerTicks();
        String abandons = limit > 0
                ? "abandons in " + Math.max(0, Math.ceilDiv(limit - hunger, 24000L)) + " days"
                : "never abandons";
        String hungry = GodSatiety.state(hunger, limit) == GodSatiety.State.HUNGRY ? " (hungry)" : "";
        return Component.literal("God: ").append(GodHunger.godName(record.god()))
                .append(", fed " + hunger / 24000L + " days ago; " + abandons + hungry);
    }

    /** The members' names (their UUIDs when the server has never seen them), in joining order, or "none". */
    private static String memberNames(CommandSourceStack source, EmpireRecord record) {
        if (record.members().isEmpty()) {
            return "none";
        }
        StringBuilder names = new StringBuilder();
        for (UUID member : record.members()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(member);
            String name = member.toString();
            if (online != null) {
                name = online.getScoreboardName();
            } else if (source.getServer().getProfileCache() != null) {
                var profile = source.getServer().getProfileCache().get(member);
                if (profile.isPresent()) {
                    name = profile.get().getName();
                }
            }
            names.append(name);
        }
        return names.toString();
    }

    private static int here(CommandSourceStack source) {
        BlockPos pos = BlockPos.containing(source.getPosition());
        ChunkPos chunkPos = new ChunkPos(pos);
        long chunk = ChunkKeys.pack(chunkPos.x, chunkPos.z);
        EmpireSavedData data = EmpireSavedData.of(source.getServer());

        EmpireRecord owner = data.getEmpireByChunk(chunk);
        source.sendSuccess(() -> Component.literal("Chunk: [" + chunkPos.x + ", " + chunkPos.z + "]"), false);
        source.sendSuccess(() -> Component.literal("Owner: " + (owner != null ? "empire " + owner.id() + " (" + memberNames(source, owner) + ")" : "Unclaimed")), false);

        if (source.getEntity() instanceof ServerPlayer player) {
            EmpireRecord callerEmpire = data.getEmpireOf(player.getUUID());
            if (callerEmpire != null) {
                boolean adjacent = ClaimGraph.isAdjacent(callerEmpire.state().staked(), chunk);
                boolean bridge = ClaimGraph.isBridge(callerEmpire.state().staked(), chunk);
                long coreChunk = EmpireSavedData.coreChunk(callerEmpire);
                boolean isCore = (chunk == coreChunk);
                source.sendSuccess(() -> Component.literal("Adjacent: " + adjacent + ", Bridge: " + bridge + ", Core: " + isCore), false);
            }
        }

        boolean settled = TierChecks.columnSettled(source.getLevel(), chunkPos);
        source.sendSuccess(() -> Component.literal("Settled: " + settled), false);
        return 1;
    }

    private static int setDominion(CommandSourceStack source, String playerArg, int n) {
        UUID targetId = resolvePlayerUuid(source, playerArg);
        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        EmpireRecord record = data.getEmpireOf(targetId);
        if (record == null) {
            source.sendFailure(Component.literal("No empire for " + playerArg));
            return 0;
        }
        record.state().setDominion(n);
        data.setDirty();
        source.sendSuccess(() -> Component.literal("Set dominion to " + n + " for " + playerArg), true);
        return 1;
    }

    private static int addDominion(CommandSourceStack source, String playerArg, int n) {
        UUID targetId = resolvePlayerUuid(source, playerArg);
        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        EmpireRecord record = data.getEmpireOf(targetId);
        if (record == null) {
            source.sendFailure(Component.literal("No empire for " + playerArg));
            return 0;
        }
        record.state().addDominion(n);
        data.setDirty();
        source.sendSuccess(() -> Component.literal("Added " + n + " dominion (now " + record.state().dominion() + ") for " + playerArg), true);
        return 1;
    }

    private static int debugStake(CommandSourceStack source, String playerArg) {
        UUID targetId = resolvePlayerUuid(source, playerArg);
        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        EmpireRecord record = data.getEmpireOf(targetId);
        if (record == null) {
            source.sendFailure(Component.literal("No empire for " + playerArg));
            return 0;
        }

        BlockPos pos = BlockPos.containing(source.getPosition());
        ChunkPos chunkPos = new ChunkPos(pos);
        long chunk = ChunkKeys.pack(chunkPos.x, chunkPos.z);
        long day = DawnClock.dayOf(source.getLevel().getDayTime());
        boolean tierOk = TierChecks.columnSettled(source.getLevel(), chunkPos);

        StakeResult result = data.stakeChunk(record, chunk, day, tierOk);
        source.sendSuccess(() -> Component.literal(result.name()), true);
        return 1;
    }

    private static int debugUnstake(CommandSourceStack source, String playerArg) {
        UUID targetId = resolvePlayerUuid(source, playerArg);
        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        EmpireRecord record = data.getEmpireOf(targetId);
        if (record == null) {
            source.sendFailure(Component.literal("No empire for " + playerArg));
            return 0;
        }

        BlockPos pos = BlockPos.containing(source.getPosition());
        ChunkPos chunkPos = new ChunkPos(pos);
        long chunk = ChunkKeys.pack(chunkPos.x, chunkPos.z);
        long day = DawnClock.dayOf(source.getLevel().getDayTime());

        UnstakeResult result = data.unstakeChunk(record, chunk, day);
        source.sendSuccess(() -> Component.literal(result.name()), true);
        return 1;
    }

    private static int debugDisband(CommandSourceStack source, String playerArg) {
        UUID targetId = resolvePlayerUuid(source, playerArg);
        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        EmpireRecord record = data.getEmpireOf(targetId);
        if (record == null) {
            source.sendFailure(Component.literal("No empire for " + playerArg));
            return 0;
        }
        data.disbandEmpire(record.id(), source.getServer());
        source.sendSuccess(() -> Component.literal("Disbanded empire for " + playerArg), true);
        return 1;
    }

    private static int debugHunger(CommandSourceStack source, String playerArg, long ticks) {
        UUID targetId = resolvePlayerUuid(source, playerArg);
        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        EmpireRecord record = data.getEmpireOf(targetId);
        if (record == null) {
            source.sendFailure(Component.literal("No empire for " + playerArg));
            return 0;
        }
        if (record.god() == null) {
            source.sendFailure(Component.literal("The empire of " + playerArg + " has no god"));
            return 0;
        }
        GodHunger.setHunger(source.getServer(), data, record, ticks, GodHunger.limitTicks(source.getServer().overworld()));
        source.sendSuccess(() -> Component.literal("Set the god's hunger to " + ticks + " ticks for " + playerArg), true);
        return 1;
    }

    private EmpireCommand() {
    }
}
