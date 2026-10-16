package dev.brights0ng.seismicsurveyance.debug;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import dev.brights0ng.seismicsurveyance.DepositFinder;
import dev.brights0ng.seismicsurveyance.DepositScanner;
import dev.brights0ng.seismicsurveyance.SeismicShots;
import dev.brights0ng.seismicsurveyance.SeismicSurveyance;
import dev.brights0ng.sparseores.DepositBody;
import dev.brights0ng.sparseores.OreType;
import dev.brights0ng.sparseores.OreTypes;
import dev.brights0ng.sparseores.Realm;
import dev.brights0ng.sparseores.debug.DepositCommand;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Seismic Surveyance's debug commands (split from Sparse Ores' {@link DepositCommand} in split seam S9). They need
 * operator permission.
 *
 * <ul>
 *   <li>{@code /eae scan [ore] <radius> [flat]} is the geophone's search: every deposit whose nearest ore
 *       block is within the radius of where you stand, measured in three dimensions or, with {@code flat},
 *       across the ground. It runs off the main thread and reports how long it took.</li>
 *   <li>{@code /eae shot [range]} makes a vibration where you stand, as a sledgehammer would, but with any range.</li>
 * </ul>
 */
@EventBusSubscriber(modid = SeismicSurveyance.MODID)
public final class SeismicCommand {

    private static final int MAX_SCAN_RADIUS = 2048;
    private static final int SCAN_LISTED = 12;
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> scan = Commands.literal("scan").then(scanRadius(null));
        for (OreType type : OreTypes.ALL) {
            scan.then(Commands.literal(type.id()).then(scanRadius(type)));
        }

        LiteralArgumentBuilder<CommandSourceStack> shot = Commands.literal("shot")
                .executes(context -> shot(context.getSource(), SeismicShots.HAMMER_RANGE))
                .then(Commands.argument("range", IntegerArgumentType.integer(1, MAX_SCAN_RADIUS))
                        .executes(context -> shot(context.getSource(), IntegerArgumentType.getInteger(context, "range"))));

        event.getDispatcher().register(Commands.literal("eae")
                .requires(source -> source.hasPermission(2))
                .then(scan)
                .then(shot));
    }

    /** {@code <radius> [flat]}, for one ore or (if null) all of them. */
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> scanRadius(OreType ore) {
        return Commands.argument("radius", IntegerArgumentType.integer(1, MAX_SCAN_RADIUS))
                .executes(context -> scan(context.getSource(), ore,
                        IntegerArgumentType.getInteger(context, "radius"), DepositFinder.Metric.SPHERE))
                .then(Commands.literal("flat").executes(context -> scan(context.getSource(), ore,
                        IntegerArgumentType.getInteger(context, "radius"), DepositFinder.Metric.HORIZONTAL)));
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
            source.sendFailure(Component.literal(ore.id() + " generates in the " + DepositCommand.realmName(ore.realm())
                    + ", not the " + DepositCommand.realmName(realm) + "."));
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
                SeismicSurveyance.LOGGER.error("Deposit scan failed", error);
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
        MutableComponent nearest = DepositCommand.teleportLink(sighting.nearestX() + ", " + sighting.nearestY() + ", " + sighting.nearestZ(),
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

    private SeismicCommand() {
    }
}
