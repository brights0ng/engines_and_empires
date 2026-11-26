// Written by Gemini.
package dev.brights0ng.empire.command;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import dev.brights0ng.empire.civilian.CivilianOccupations;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.ledger.LedgerSelection;
import dev.brights0ng.empire.region.FarmRegionScanner;
import dev.brights0ng.empire.region.HomeRegionScanner;
import dev.brights0ng.empire.region.RegionManager;
import dev.brights0ng.empire.region.RegionNetwork;
import dev.brights0ng.empire.rules.BlockKeys;
import dev.brights0ng.empire.rules.RegionOrigin;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.empire.rules.RoomLabel;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /empire debug region ...}: list, inspect, scan, validate, remove, clear and outline empire regions.
 */
public final class RegionDebugCommands {

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("region")
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource(), null))
                        .then(playerArgument()
                                .executes(ctx -> list(ctx.getSource(), player(ctx)))))
                .then(Commands.literal("info")
                        .executes(ctx -> info(ctx.getSource())))
                .then(Commands.literal("scan")
                        .then(Commands.literal("home")
                                .executes(ctx -> scanHome(ctx.getSource(), RegionOrigin.AUTO))
                                .then(Commands.literal("auto")
                                        .executes(ctx -> scanHome(ctx.getSource(), RegionOrigin.AUTO)))
                                .then(Commands.literal("manual")
                                        .executes(ctx -> scanHome(ctx.getSource(), RegionOrigin.MANUAL))))
                        .then(Commands.literal("farm")
                                .executes(ctx -> scanFarm(ctx.getSource(), RegionOrigin.AUTO))
                                .then(Commands.literal("auto")
                                        .executes(ctx -> scanFarm(ctx.getSource(), RegionOrigin.AUTO)))
                                .then(Commands.literal("manual")
                                        .executes(ctx -> scanFarm(ctx.getSource(), RegionOrigin.MANUAL)))))
                .then(Commands.literal("validate")
                        .executes(ctx -> validate(ctx.getSource())))
                .then(Commands.literal("remove")
                        .then(Commands.argument("idPrefix", StringArgumentType.word())
                                .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "idPrefix")))))
                .then(Commands.literal("clear")
                        .executes(ctx -> clear(ctx.getSource())))
                .then(Commands.literal("show")
                        .executes(ctx -> show(ctx.getSource())));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> playerArgument() {
        return Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder));
    }

    private static String player(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "player");
    }

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

    private static int list(CommandSourceStack source, @Nullable String playerArg) {
        EmpireRecord empire = empireOf(source, playerArg);
        if (empire == null) {
            return 0;
        }
        EmpireSavedData data = EmpireSavedData.of(source.getServer());
        List<RegionRecord> regions = data.regionsOf(empire.id());
        source.sendSuccess(() -> Component.literal(regions.size() + " region" + (regions.size() == 1 ? "" : "s")), false);
        for (RegionRecord region : regions) {
            String occupancy = region.type() == RegionType.HOME
                    ? "inhabitants=" + region.inhabitants().size() + "/" + RegionRules.homeCapacity(region.effectiveVolume())
                    : "workers=" + CivilianOccupations.activeWorkerCount(data, region) + "/" + RegionRules.workerCapacity(region.type());
            String status = region.valid() ? "VALID" : "INVALID(" + region.invalidReason().id() + ")";
            source.sendSuccess(() -> Component.literal("[" + shortId(region.id()) + "] "
                    + region.type().id() + " (" + region.origin().id() + ") " + status
                    + ", blocks=" + region.effectiveVolume() + "/" + region.blockCount()
                    + ", rooms=" + region.rooms().size()
                    + ", " + occupancy
                    + ", bounds=(" + region.minX() + "," + region.minY() + "," + region.minZ()
                    + ")..(" + region.maxX() + "," + region.maxY() + "," + region.maxZ() + ")"), false);
        }
        return regions.size();
    }

    private static int info(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        if (source.getLevel() != overworld) {
            source.sendFailure(Component.literal("Regions only exist in the Overworld"));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        EmpireSavedData data = EmpireSavedData.of(overworld);
        RegionRecord region = data.regionAt(pos);
        if (region == null) {
            source.sendFailure(Component.literal("No region at " + pos.toShortString()));
            return 0;
        }
        String status = region.valid() ? "VALID"
                : "INVALID (" + region.invalidReason().id() + ", since tick " + region.invalidSinceGameTime() + ")";
        String occupancy = region.type() == RegionType.HOME
                ? "inhabitants: " + region.inhabitants().size() + "/" + RegionRules.homeCapacity(region.effectiveVolume())
                : "workers: " + CivilianOccupations.activeWorkerCount(data, region) + "/" + RegionRules.workerCapacity(region.type());
        source.sendSuccess(() -> Component.literal("Region [" + shortId(region.id()) + "] "
                + region.type().id() + " (" + region.origin().id() + ") — " + status), false);
        long seed = region.seed();
        source.sendSuccess(() -> Component.literal("Blocks: " + region.blockCount()
                + " (effective: " + region.effectiveVolume() + ", separators: " + region.separators().size() + ")"
                + ", chunks: " + region.chunks().size()
                + ", " + occupancy
                + ", seed: (" + BlockKeys.getX(seed) + "," + BlockKeys.getY(seed) + "," + BlockKeys.getZ(seed) + ")"
                + ", bounds: (" + region.minX() + "," + region.minY() + "," + region.minZ()
                + ")..(" + region.maxX() + "," + region.maxY() + "," + region.maxZ() + ")"), false);

        List<RegionRecord.RoomRecord> rooms = region.rooms();
        int indoorCount = 0;
        int outdoorCount = 0;
        int shedCount = 0;
        int fieldCount = 0;
        for (RegionRecord.RoomRecord room : rooms) {
            if (room.isOutdoor()) {
                outdoorCount++;
            } else if (room.isIndoor()) {
                indoorCount++;
            }
            if (room.isShed()) {
                shedCount++;
            }
            if (room.isField()) {
                fieldCount++;
            }
        }
        String here;
        if (region.isSeparator(pos)) {
            here = "separator";
        } else {
            RegionRecord.RoomRecord standingRoom = region.roomAt(pos);
            here = standingRoom != null
                    ? "room #" + standingRoom.index() + " " + formatLabels(standingRoom) + " (" + standingRoom.blockCount() + " blocks"
                            + (standingRoom.storageCount() > 0 ? ", " + standingRoom.storageCount() + " storage" : "")
                            + (standingRoom.preferredCropId() != null ? ", crop=" + standingRoom.preferredCropId() : "") + ")"
                    : "unassigned";
        }
        int inRooms = indoorCount;
        int outRooms = outdoorCount;
        int sheds = shedCount;
        int fields = fieldCount;
        String breakdown = region.type() == RegionType.FARM
                ? sheds + " shed, " + fields + " field, storage=" + region.storageBlocks().size()
                : inRooms + " indoor, " + outRooms + " outdoor";
        source.sendSuccess(() -> Component.literal("Rooms: " + rooms.size()
                + " (" + breakdown + ") — here: " + here), false);
        if (!rooms.isEmpty()) {
            StringBuilder summary = new StringBuilder("  ");
            for (int i = 0; i < rooms.size(); i++) {
                if (i > 0) {
                    summary.append(", ");
                }
                RegionRecord.RoomRecord room = rooms.get(i);
                summary.append('#').append(room.index()).append(' ').append(formatLabels(room))
                        .append('(').append(room.blockCount()).append(')');
            }
            source.sendSuccess(() -> Component.literal(summary.toString()), false);
        }
        return 1;
    }

    private static String formatLabels(RegionRecord.RoomRecord room) {
        if (room.labels().isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (var label : room.labels()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(label.id());
        }
        return sb.append(']').toString();
    }

    private static int scanHome(CommandSourceStack source, RegionOrigin origin) {
        ServerLevel overworld = source.getServer().overworld();
        if (source.getLevel() != overworld) {
            source.sendFailure(Component.literal("Regions only exist in the Overworld"));
            return 0;
        }
        EmpireRecord empire = empireOf(source, null);
        if (empire == null) {
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        EmpireSavedData data = EmpireSavedData.of(overworld);
        HomeRegionScanner.ScanResult scan = HomeRegionScanner.scan(overworld, data, empire, pos, origin);
        if (!scan.succeeded()) {
            source.sendFailure(Component.literal("HOME scan failed at " + pos.toShortString() + ": " + scan.failure().id()));
            return 0;
        }
        RegionRecord created = scan.region();
        data.addRegion(created);
        RegionNetwork.pushToEmpire(overworld, empire.id());
        int cap = RegionRules.homeCapacity(created.effectiveVolume());
        source.sendSuccess(() -> Component.literal("Created " + origin.id() + " HOME [" + shortId(created.id())
                + "]: " + created.effectiveVolume() + " indoor/" + created.blockCount() + " total blocks, "
                + created.rooms().size() + " room(s), capacity " + cap), true);
        return 1;
    }

    private static int scanFarm(CommandSourceStack source, RegionOrigin origin) {
        ServerLevel overworld = source.getServer().overworld();
        if (source.getLevel() != overworld) {
            source.sendFailure(Component.literal("Regions only exist in the Overworld"));
            return 0;
        }
        EmpireRecord empire = empireOf(source, null);
        if (empire == null) {
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        EmpireSavedData data = EmpireSavedData.of(overworld);
        FarmRegionScanner.ScanResult scan = FarmRegionScanner.scanNewFarm(overworld, data, empire, pos, origin);
        if (!scan.succeeded()) {
            source.sendFailure(Component.literal("FARM scan failed at " + pos.toShortString() + ": " + scan.failure().id()));
            return 0;
        }
        RegionRecord created = scan.region();
        data.addRegion(created);
        RegionNetwork.pushToEmpire(overworld, empire.id());
        source.sendSuccess(() -> Component.literal("Created " + origin.id() + " FARM [" + shortId(created.id())
                + "]: " + created.blockCount() + " blocks, "
                + created.roomCountWithLabel(RoomLabel.SHED) + " shed(s), "
                + created.storageBlocks().size() + " storage"), true);
        return 1;
    }

    private static int validate(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        RegionManager.updateAllNow(overworld);
        EmpireSavedData data = EmpireSavedData.of(overworld);
        int valid = 0;
        int invalid = 0;
        for (RegionRecord region : data.allRegions()) {
            if (region.valid()) {
                valid++;
            } else {
                invalid++;
            }
        }
        int total = valid + invalid;
        int v = valid;
        int inv = invalid;
        source.sendSuccess(() -> Component.literal("Validated " + total + " region" + (total == 1 ? "" : "s")
                + " (" + v + " valid, " + inv + " invalid)"), true);
        return total;
    }

    private static int remove(CommandSourceStack source, String idPrefix) {
        String prefix = idPrefix.toLowerCase(Locale.ROOT);
        ServerLevel overworld = source.getServer().overworld();
        EmpireSavedData data = EmpireSavedData.of(overworld);
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
        RegionManager.detachAllInhabitants(overworld, data, match);
        CivilianOccupations.detachAllWorkers(overworld, data, match);
        data.removeRegion(match.id());
        LedgerSelection.clearForRegion(overworld, match.id());
        RegionNetwork.pushToEmpire(overworld, match.empireId());
        RegionRecord removed = match;
        source.sendSuccess(() -> Component.literal("Removed region [" + shortId(removed.id()) + "]"), true);
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        EmpireRecord empire = empireOf(source, null);
        if (empire == null) {
            return 0;
        }
        ServerLevel overworld = source.getServer().overworld();
        EmpireSavedData data = EmpireSavedData.of(overworld);
        List<RegionRecord> regions = data.regionsOf(empire.id());
        for (RegionRecord region : regions) {
            RegionManager.detachAllInhabitants(overworld, data, region);
            CivilianOccupations.detachAllWorkers(overworld, data, region);
            data.removeRegion(region.id());
            LedgerSelection.clearForRegion(overworld, region.id());
        }
        RegionNetwork.pushToEmpire(overworld, empire.id());
        int count = regions.size();
        source.sendSuccess(() -> Component.literal("Cleared " + count + " region" + (count == 1 ? "" : "s")), true);
        return count;
    }

    private static int show(CommandSourceStack source) {
        ServerLevel overworld = source.getServer().overworld();
        if (source.getLevel() != overworld) {
            source.sendFailure(Component.literal("Regions only exist in the Overworld"));
            return 0;
        }
        BlockPos pos = BlockPos.containing(source.getPosition());
        EmpireSavedData data = EmpireSavedData.of(overworld);
        RegionRecord region = data.regionAt(pos);
        if (region == null) {
            source.sendFailure(Component.literal("Stand inside a region to outline it (" + pos.toShortString() + ")"));
            return 0;
        }
        ParticleOptions particle = region.valid() ? ParticleTypes.END_ROD : ParticleTypes.FLAME;
        int shown = 0;
        LongIterator it = region.blocks().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int x = BlockKeys.getX(key);
            int y = BlockKeys.getY(key);
            int z = BlockKeys.getZ(key);
            boolean onEdge = false;
            for (int i = 0; i < 6; i++) {
                if (!region.containsBlock(BlockKeys.pack(x + DX[i], y + DY[i], z + DZ[i]))) {
                    onEdge = true;
                    break;
                }
            }
            if (onEdge) {
                overworld.sendParticles(particle, x + 0.5, y + 0.5, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
                shown++;
            }
        }
        int count = shown;
        source.sendSuccess(() -> Component.literal("Outlined region [" + shortId(region.id()) + "] (" + count + " edge blocks)"), false);
        return count;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private RegionDebugCommands() {
    }
}
