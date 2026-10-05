package dev.brights0ng.enginesandempires.weather.wind;

import java.util.Locale;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Debug commands for the wind push, under the pack's {@code /eae} root. They need operator permission.
 *
 * <ul>
 *   <li>{@code /eae wind}: where the wind comes from, the surface and aloft wind where you stand, and the nearest
 *       physics object within 64 blocks: its outline, cover, exposure, the wind it feels and the push on it.</li>
 *   <li>{@code /eae wind set <speed> <direction>}: the same wind everywhere and at every height, in m/s, toward a
 *       Minecraft yaw (0 south, 90 west, 180 north, 270 east). Overrides Project Atmosphere until cleared; not saved.</li>
 *   <li>{@code /eae wind clear}: back to Project Atmosphere's wind.</li>
 * </ul>
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class WindCommand {

    private static final double SHIP_SEARCH = 64;

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> wind = Commands.literal("wind")
                .executes(context -> info(context.getSource()))
                .then(Commands.literal("set")
                        .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0, 200))
                                .then(Commands.argument("direction", DoubleArgumentType.doubleArg(-360, 360))
                                        .executes(context -> set(context.getSource(),
                                                DoubleArgumentType.getDouble(context, "speed"),
                                                DoubleArgumentType.getDouble(context, "direction"))))))
                .then(Commands.literal("clear").executes(context -> clear(context.getSource())));
        event.getDispatcher().register(Commands.literal("eae").requires(source -> source.hasPermission(2)).then(wind));
    }

    private static int set(CommandSourceStack source, double speed, double yaw) {
        double[] vector = WindColumn.fromYaw(speed, yaw);
        WindSources.setOverride(WindColumn.uniform(vector[0], vector[1]));
        source.sendSuccess(() -> Component.literal("Wind forced to " + speedAndHeading(vector[0], vector[1])
                + " everywhere, until /eae wind clear."), true);
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        WindSources.setOverride(null);
        source.sendSuccess(() -> Component.literal("Wind override cleared. Wind now comes from: "
                + WindSources.describe() + "."), true);
        return 1;
    }

    private static int info(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 at = source.getPosition();
        WindParams params = WindConfig.params();
        source.sendSuccess(() -> Component.literal("Wind comes from: " + WindSources.describe()
                + (params.enabled() ? "" : " (the push is switched off in the config)")), false);

        WindColumn column = WindSources.column(level, at.x, at.z);
        if (column == null) {
            line(source, "No wind here.");
        } else {
            line(source, "Surface: " + speedAndHeading(column.surfaceX(), column.surfaceZ())
                    + ". Aloft: " + speedAndHeading(column.aloftX(), column.aloftZ()) + ".");
            line(source, "Surface wind up to " + fmt(params.surfaceLayer()) + " blocks above the ground, aloft wind from y "
                    + fmt(params.aloftHeight()) + ".");
        }

        ServerSubLevel nearest = nearestShip(level, at);
        if (nearest == null) {
            line(source, "No physics object within " + (int) SHIP_SEARCH + " blocks.");
            return 1;
        }
        ShipWind ship = WindShips.peek(nearest);
        String name = nearest.getName() == null ? "unnamed" : nearest.getName();
        source.sendSuccess(() -> Component.literal("Nearest physics object: " + name), false);
        if (ship == null || !ship.built()) {
            line(source, "Not measured yet (wind only measures objects while there is wind).");
            return 1;
        }
        line(source, "Outline: " + ship.area(0) + " m² seen along x, " + ship.area(1) + " along y, " + ship.area(2)
                + " along z (its own axes).");
        line(source, String.format(Locale.ROOT, "Cover: top %.0f%%, north %.0f%%, south %.0f%%, west %.0f%%, east %.0f%%.",
                100 * ship.coverage(Shelter.TOP), 100 * ship.coverage(Shelter.NORTH), 100 * ship.coverage(Shelter.SOUTH),
                100 * ship.coverage(Shelter.WEST), 100 * ship.coverage(Shelter.EAST)));
        line(source, String.format(Locale.ROOT, "Feels %.1f m/s toward %s (%.0f%% aloft wind), exposure %.0f%%, air pressure %.2f.",
                ship.speed(), heading(ship.yaw()), 100 * ship.aloftShare(), 100 * ship.exposure(), ship.pressure()));
        line(source, String.format(Locale.ROOT, "Push: %.2f.", ship.lastForce()));
        return 1;
    }

    private static ServerSubLevel nearestShip(ServerLevel level, Vec3 at) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        ServerSubLevel best = null;
        double bestDistance = SHIP_SEARCH * SHIP_SEARCH;
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            BoundingBox3dc box = subLevel.boundingBox();
            double dx = Math.max(0, Math.max(box.minX() - at.x, at.x - box.maxX()));
            double dy = Math.max(0, Math.max(box.minY() - at.y, at.y - box.maxY()));
            double dz = Math.max(0, Math.max(box.minZ() - at.z, at.z - box.maxZ()));
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = subLevel;
            }
        }
        return best;
    }

    private static String speedAndHeading(double x, double z) {
        double speed = Math.hypot(x, z);
        if (speed < 0.05) {
            return "calm";
        }
        return String.format(Locale.ROOT, "%.1f m/s toward %s", speed, heading(WindColumn.yawOf(x, z)));
    }

    /** A yaw as a compass heading, e.g. "east (270°)". */
    private static String heading(double yaw) {
        String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        int index = (int) Math.floorMod(Math.round(yaw / 45.0), 8L);
        return String.format(Locale.ROOT, "%s (%.0f°)", names[index], yaw);
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static void line(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("  " + text).withStyle(ChatFormatting.GRAY), false);
    }

    private WindCommand() {
    }
}
