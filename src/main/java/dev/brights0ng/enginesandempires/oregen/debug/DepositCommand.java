package dev.brights0ng.enginesandempires.oregen.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.geophone.DepositFinder;
import dev.brights0ng.enginesandempires.geophone.DepositScanner;
import dev.brights0ng.enginesandempires.geophone.SeismicShots;
import dev.brights0ng.enginesandempires.oregen.Deposit;
import dev.brights0ng.enginesandempires.oregen.DepositBody;
import dev.brights0ng.enginesandempires.oregen.DepositLedger;
import dev.brights0ng.enginesandempires.oregen.OreType;
import dev.brights0ng.enginesandempires.oregen.OreTypes;
import dev.brights0ng.enginesandempires.oregen.Realm;
import dev.brights0ng.enginesandempires.oregen.ResolvedDeposit;
import dev.brights0ng.enginesandempires.oregen.worldgen.LevelDeposits;
import dev.brights0ng.enginesandempires.oregen.worldgen.OreMaps;
import dev.brights0ng.enginesandempires.oregen.worldgen.OreWorldgen;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands for checking deposits in game, straight from the ore maps for this world's seed,
 * with click-to-teleport coordinates. They need operator permission.
 *
 * <ul>
 *   <li>{@code /eae deposits [radius]} lists the nearest few deposits of every ore that generates in the
 *       dimension you are in.</li>
 *   <li>{@code /eae deposits <ore> [radius]} lists the nearest several deposits of one ore, in whichever
 *       dimension that ore belongs to. The teleport links take you there.</li>
 *   <li>{@code /eae scan [ore] <radius> [flat]} is the geophone's search: every deposit whose nearest ore
 *       block is within the radius of where you stand, measured in three dimensions or, with {@code flat},
 *       across the ground. It runs off the main thread and reports how long it took.</li>
 *   <li>{@code /eae phantoms} reports how often deposits that passed the terrain check still ended up with
 *       no ore, and lists the latest ones. {@code /eae phantoms reset} starts the count again.</li>
 *   <li>{@code /eae biomes [radius]} samples surface biomes on a grid around you and reports, for each ore
 *       with biome rules, how much bigger or smaller its shallow deposits are on average there, and what
 *       that does to the ore's total. Runs off the main thread.</li>
 * </ul>
 *
 * Deposit lines show only deposits that exist: ones the terrain check dropped are counted but not listed.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class DepositCommand {

    private static final int DEFAULT_RADIUS = 3000;
    private static final int MAX_RADIUS = 10000;
    private static final int MAX_SCAN_RADIUS = 2048;
    private static final int SHOWN_PER_ORE_WHEN_ALL = 2;
    private static final int SHOWN_FOR_ONE_ORE = 8;
    private static final int SCAN_LISTED = 12;
    private static final int PHANTOMS_LISTED = 6;
    /** In sky showcase mode, teleports land this far above the top of the deposit. */
    private static final int SHOWCASE_CLEARANCE = 6;
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> deposits = Commands.literal("deposits")
                .executes(context -> listRealm(context.getSource(), DEFAULT_RADIUS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                        .executes(context -> listRealm(context.getSource(),
                                IntegerArgumentType.getInteger(context, "radius"))));

        // One literal per ore, so the ore names tab-complete and never clash with the numeric radius.
        for (OreType type : OreTypes.ALL) {
            deposits.then(Commands.literal(type.id())
                    .executes(context -> list(context.getSource(), List.of(type), DEFAULT_RADIUS, SHOWN_FOR_ONE_ORE))
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                            .executes(context -> list(context.getSource(), List.of(type),
                                    IntegerArgumentType.getInteger(context, "radius"), SHOWN_FOR_ONE_ORE))));
        }

        LiteralArgumentBuilder<CommandSourceStack> scan = Commands.literal("scan").then(scanRadius(null));
        for (OreType type : OreTypes.ALL) {
            scan.then(Commands.literal(type.id()).then(scanRadius(type)));
        }

        LiteralArgumentBuilder<CommandSourceStack> phantoms = Commands.literal("phantoms")
                .executes(context -> phantoms(context.getSource()))
                .then(Commands.literal("reset").executes(context -> resetPhantoms(context.getSource())));

        LiteralArgumentBuilder<CommandSourceStack> shot = Commands.literal("shot")
                .executes(context -> shot(context.getSource(), SeismicShots.HAMMER_RANGE))
                .then(Commands.argument("range", IntegerArgumentType.integer(1, MAX_SCAN_RADIUS))
                        .executes(context -> shot(context.getSource(), IntegerArgumentType.getInteger(context, "range"))));

        LiteralArgumentBuilder<CommandSourceStack> biomes = Commands.literal("biomes")
                .executes(context -> BiomeBalance.report(context.getSource(), BiomeBalance.DEFAULT_RADIUS))
                .then(Commands.argument("radius", IntegerArgumentType.integer(64, MAX_RADIUS))
                        .executes(context -> BiomeBalance.report(context.getSource(),
                                IntegerArgumentType.getInteger(context, "radius"))));

        event.getDispatcher().register(Commands.literal("eae")
                .requires(source -> source.hasPermission(2))
                .then(deposits)
                .then(scan)
                .then(shot)
                .then(phantoms)
                .then(biomes));
    }

    /** {@code <radius> [flat]}, for one ore or (if null) all of them. */
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> scanRadius(OreType ore) {
        return Commands.argument("radius", IntegerArgumentType.integer(1, MAX_SCAN_RADIUS))
                .executes(context -> scan(context.getSource(), ore,
                        IntegerArgumentType.getInteger(context, "radius"), DepositFinder.Metric.SPHERE))
                .then(Commands.literal("flat").executes(context -> scan(context.getSource(), ore,
                        IntegerArgumentType.getInteger(context, "radius"), DepositFinder.Metric.HORIZONTAL)));
    }

    /** Lists every ore of the dimension the command is run in. */
    private static int listRealm(CommandSourceStack source, int radius) {
        Realm realm = Realm.ofDimension(source.getLevel().dimension().location().toString());
        if (realm == null) {
            source.sendFailure(Component.literal("No ore deposits generate in this dimension. Name an ore to list its deposits."));
            return 0;
        }
        return list(source, OreTypes.forRealm(realm), radius, SHOWN_PER_ORE_WHEN_ALL);
    }

    private static int list(CommandSourceStack source, List<OreType> types, int radius, int shownPerOre) {
        int x = Mth.floor(source.getPosition().x);
        int z = Mth.floor(source.getPosition().z);
        long seed = source.getLevel().getSeed();

        String mode = OreWorldgen.SKY_SHOWCASE
                ? "sky showcase mode: deposits are built at y=" + OreWorldgen.SHOWCASE_Y
                : "real depths";
        source.sendSuccess(() -> Component.literal(
                "Deposits within " + radius + " blocks of " + x + ", " + z + " (" + mode + "):")
                .withStyle(ChatFormatting.GOLD), false);

        int total = 0;
        for (OreType type : types) {
            ServerLevel level = levelOf(source, type.realm());
            if (level == null) {
                source.sendFailure(Component.literal(type.id() + ": the " + realmName(type.realm()) + " is not loaded"));
                continue;
            }
            LevelDeposits deposits = OreMaps.of(level);
            List<Deposit> candidates = OreMaps.mapFor(seed, type).depositsNear(x, z, radius);

            // Nearest first. Stop once enough deposits that really exist have been found.
            List<ResolvedDeposit> shown = new ArrayList<>();
            int dropped = 0;
            for (Deposit candidate : candidates) {
                if (shown.size() >= shownPerOre) {
                    break;
                }
                ResolvedDeposit resolved = deposits.resolve(candidate);
                if (resolved.viable()) {
                    shown.add(resolved);
                } else {
                    dropped++;
                }
            }
            total += shown.size();

            int candidateCount = candidates.size();
            int droppedCount = dropped;
            source.sendSuccess(() -> Component.literal(
                    type.id() + " (" + type.shape().name() + ", " + realmName(type.realm())
                            + ", S=" + type.layer().scale() + "): " + candidateCount + " within range"
                            + (droppedCount > 0 ? ", " + droppedCount + " nearest had no rock and were dropped" : ""))
                    .withStyle(ChatFormatting.YELLOW), false);

            for (ResolvedDeposit resolved : shown) {
                source.sendSuccess(() -> line(resolved, x, z), false);
            }
        }
        return total;
    }

    private static Component line(ResolvedDeposit resolved, int fromX, int fromZ) {
        Deposit deposit = resolved.deposit();
        DepositBody body = resolved.body();
        double dx = (double) deposit.x() - fromX;
        double dz = (double) deposit.z() - fromZ;
        long distance = Math.round(Math.sqrt(dx * dx + dz * dz));

        boolean showcase = OreWorldgen.SKY_SHOWCASE;
        int teleportY = showcase ? OreWorldgen.SHOWCASE_Y + body.reachY() + SHOWCASE_CLEARANCE : body.centerY();
        String hover = showcase
                ? "Teleport above this deposit"
                : "Teleport to this deposit's centre (use spectator mode, it is inside solid rock)";
        MutableComponent coordinates = teleportLink(deposit.x() + ", " + deposit.z(), body.type().realm(),
                deposit.x(), teleportY, deposit.z(), hover);

        int attempt = resolved.outcome().attempt();
        String details = String.format(Locale.ROOT, "   %d blocks away, depth y=%d, %d ore (%d rich), x%.1f size%s, ~%d%% in rock%s",
                distance, body.centerY(), body.oreCount(), body.richCount(), body.sizeMultiplier(),
                body.biomeMultiplier() != 1.0 ? String.format(Locale.ROOT, " (biome x%.2f)", body.biomeMultiplier()) : "",
                Math.round(resolved.outcome().solidFraction() * 100.0),
                attempt > 0 ? ", redrawn (draw " + (attempt + 1) + ")" : "");
        return Component.literal("  ")
                .append(coordinates)
                .append(Component.literal(details).withStyle(ChatFormatting.GRAY));
    }

    /**
     * Runs the geophone's search from where the command is run, on a background thread, and reports back on
     * the main thread when it is done.
     */
    private static int scan(CommandSourceStack source, OreType ore, int radius, DepositFinder.Metric metric) {
        ServerLevel level = source.getLevel();
        Realm realm = Realm.ofDimension(level.dimension().location().toString());
        if (realm == null) {
            source.sendFailure(Component.literal("No ore deposits generate in this dimension."));
            return 0;
        }
        if (ore != null && ore.realm() != realm) {
            source.sendFailure(Component.literal(ore.id() + " generates in the " + realmName(ore.realm())
                    + ", not the " + realmName(realm) + "."));
            return 0;
        }

        Vec3 position = source.getPosition();
        DepositFinder.Query query = DepositFinder.Query.around(
                Mth.floor(position.x), Mth.floor(position.y), Mth.floor(position.z), radius, metric);
        if (ore != null) {
            query = query.onlyOres(List.of(ore.id()));
        }
        DepositFinder.Query finalQuery = query;

        source.sendSuccess(() -> Component.literal("Scanning " + radius + " blocks (" + metricName(metric) + ")...")
                .withStyle(ChatFormatting.GRAY), false);
        MinecraftServer server = source.getServer();
        DepositScanner.scanAsync(level, finalQuery).whenComplete((scan, error) -> server.execute(() -> {
            if (error != null) {
                EnginesAndEmpiresMod.LOGGER.error("Deposit scan failed", error);
                source.sendFailure(Component.literal("The scan failed: " + error.getMessage() + " (see the log)"));
            } else {
                reportScan(source, finalQuery, scan);
            }
        }));
        return 1;
    }

    private static void reportScan(CommandSourceStack source, DepositFinder.Query query, DepositScanner.Scan scan) {
        DepositFinder.Result result = scan.result();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Scan from %d, %d, %d: %d deposits within %.0f blocks (%s); checked %d candidates, %d dropped by the terrain "
                        + "check, %d mined out. Search %d ms, depletion check %d ms",
                query.x(), query.y(), query.z(), result.sightings().size(), query.radius(), metricName(query.metric()),
                result.candidates(), result.dropped(), result.depleted(),
                scan.searchNanos() / 1_000_000L, scan.depletionNanos() / 1_000_000L)).withStyle(ChatFormatting.GOLD), false);

        int listed = 0;
        for (DepositFinder.Sighting sighting : result.sightings()) {
            if (listed++ >= SCAN_LISTED) {
                break;
            }
            source.sendSuccess(() -> scanLine(sighting, query), false);
        }
        int more = result.sightings().size() - SCAN_LISTED;
        if (more > 0) {
            source.sendSuccess(() -> Component.literal("  ... and " + more + " more").withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static Component scanLine(DepositFinder.Sighting sighting, DepositFinder.Query query) {
        DepositBody body = sighting.body();
        MutableComponent nearest = teleportLink(sighting.nearestX() + ", " + sighting.nearestY() + ", " + sighting.nearestZ(),
                body.type().realm(), sighting.nearestX(), sighting.nearestY(), sighting.nearestZ(),
                "Teleport to this deposit's nearest ore (use spectator mode, it is inside solid rock)");
        String direction = sighting.distance() < 1.0 ? "here"
                : compass(sighting.nearestX() - query.x(), sighting.nearestZ() - query.z());
        String details = String.format(Locale.ROOT, "   %.0f blocks %s, %d ore left of %d, x%.1f size",
                sighting.distance(), direction, sighting.remainingOre(), body.oreCount(), body.sizeMultiplier());
        return Component.literal("  " + sighting.oreId() + " ")
                .append(nearest)
                .append(Component.literal(details).withStyle(ChatFormatting.GRAY));
    }

    /** The compass point (north is -z) of an offset, in eight directions. */
    private static String compass(int dx, int dz) {
        double angle = Math.atan2(dx, -dz); // 0 is north, positive turns towards east
        int index = (int) Math.round(angle / (Math.PI / 4.0));
        return COMPASS[Math.floorMod(index, COMPASS.length)];
    }

    /** Makes a vibration where the command is run, as a sledgehammer would, but with any range. */
    private static int shot(CommandSourceStack source, int range) {
        int listening = SeismicShots.fire(source.getLevel(), source.getPosition(), range);
        source.sendSuccess(() -> Component.literal(listening == 0
                ? "Made a vibration of range " + range + ", but no loaded geophone is within " + (2 * range) + " blocks to hear it."
                : "Made a vibration of range " + range + ". " + listening + " loaded geophone" + (listening == 1 ? " is" : "s are")
                        + " within " + (2 * range) + " blocks and may hear it.").withStyle(ChatFormatting.GOLD), false);
        return listening;
    }

    private static String metricName(DepositFinder.Metric metric) {
        return metric == DepositFinder.Metric.SPHERE ? "3D" : "flat";
    }

    /** Reports how often deposits that passed the terrain check ended up with no ore. */
    private static int phantoms(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        String dimension = level.dimension().location().toString();
        if (Realm.ofDimension(dimension) == null) {
            source.sendFailure(Component.literal("No ore deposits generate in this dimension."));
            return 0;
        }
        LevelDeposits deposits = OreMaps.of(level);
        DepositLedger.Stats stats = deposits.ledger().stats();

        source.sendSuccess(() -> Component.literal("Deposit ledger for " + dimension + ":").withStyle(ChatFormatting.GOLD), false);
        if (OreWorldgen.SKY_SHOWCASE) {
            source.sendSuccess(() -> Component.literal(
                    "  Sky showcase mode is on: ore is built into open sky, so nothing is recorded. "
                            + "Set SKY_SHOWCASE to false to measure.").withStyle(ChatFormatting.RED), false);
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  %d deposits tracked: %d complete, %d still generating, %d dropped by the terrain check, %d mined out",
                stats.tracked(), stats.completed(), stats.inProgress(), stats.dropped(), stats.depleted()))
                .withStyle(ChatFormatting.YELLOW), false);

        ChatFormatting phantomColour = stats.phantom() == 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  complete: %d ok, %d thin (<%d%% of their ore), %d phantom (%s of complete)",
                stats.ok(), stats.thin(), Math.round(DepositLedger.THIN_RATIO * 100.0), stats.phantom(),
                percent(stats.phantom(), stats.completed()))).withStyle(phantomColour), false);

        int redrawn = 0;
        StringBuilder byAttempt = new StringBuilder();
        for (int i = 1; i < stats.byAttempt().length; i++) {
            redrawn += stats.byAttempt()[i];
            if (stats.byAttempt()[i] > 0) {
                byAttempt.append(byAttempt.isEmpty() ? "" : ", ").append("draw ").append(i + 1).append(": ").append(stats.byAttempt()[i]);
            }
        }
        int redrawnCount = redrawn;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "  accepted on the first draw: %d, redrawn to find rock: %d%s",
                stats.byAttempt()[0], redrawnCount, byAttempt.isEmpty() ? "" : " (" + byAttempt + ")"))
                .withStyle(ChatFormatting.GRAY), false);

        if (stats.completed() > 0) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  the terrain estimate predicted %.0f%% of ore in rock on average; %.0f%% was actually placed",
                    stats.meanPredicted() * 100.0, stats.meanActual() * 100.0)).withStyle(ChatFormatting.GRAY), false);
        }

        List<DepositLedger.EntryView> latest = deposits.ledger().phantoms(PHANTOMS_LISTED);
        if (!latest.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  latest phantoms:").withStyle(ChatFormatting.RED), false);
            for (DepositLedger.EntryView phantom : latest) {
                source.sendSuccess(() -> {
                    MutableComponent link = teleportLink(phantom.x() + ", " + phantom.z(), deposits.realm(),
                            phantom.x(), phantom.centerY(), phantom.z(),
                            "Teleport to this deposit's centre (it may be in open air or lava; use spectator mode)");
                    return Component.literal("    " + phantom.oreId() + " ")
                            .append(link)
                            .append(Component.literal(String.format(Locale.ROOT,
                                    "   y=%d, predicted %d%% in rock, drawn on attempt %d, 0 of %d ore placed",
                                    phantom.centerY(), Math.round(phantom.solidFraction() * 100.0),
                                    phantom.attempt() + 1, phantom.expectedOre())).withStyle(ChatFormatting.GRAY));
                }, false);
            }
        }
        return stats.phantom();
    }

    private static int resetPhantoms(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        String dimension = level.dimension().location().toString();
        if (Realm.ofDimension(dimension) == null) {
            source.sendFailure(Component.literal("No ore deposits generate in this dimension."));
            return 0;
        }
        LevelDeposits deposits = OreMaps.of(level);
        int cleared = deposits.ledger().size();
        deposits.reset();
        source.sendSuccess(() -> Component.literal("Cleared the deposit ledger for " + dimension + " (" + cleared
                + " entries). Only deposits completed from now on are counted.").withStyle(ChatFormatting.GOLD), false);
        return cleared;
    }

    /** Clickable coordinates; "execute in" makes the link work from any dimension, taking you to the right one. */
    private static MutableComponent teleportLink(String label, Realm realm, int x, int y, int z, String hover) {
        String command = "/execute in " + realm.dimensionId() + " run tp @s " + x + " " + y + " " + z;
        return Component.literal(label)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    /** The loaded level for a realm, or null if the server does not have it. */
    private static ServerLevel levelOf(CommandSourceStack source, Realm realm) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(realm.dimensionId()));
        return source.getServer().getLevel(key);
    }

    private static String percent(int part, int whole) {
        return whole == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }

    private static String realmName(Realm realm) {
        return realm.name().toLowerCase(Locale.ROOT);
    }

    private DepositCommand() {
    }
}
