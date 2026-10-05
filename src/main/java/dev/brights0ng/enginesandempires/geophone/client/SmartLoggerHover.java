package dev.brights0ng.enginesandempires.geophone.client;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import dev.brights0ng.enginesandempires.geophone.LogbookEntry;
import dev.brights0ng.enginesandempires.geophone.ReadingBoardMenu;
import dev.brights0ng.enginesandempires.geophone.LoggerDisplay;
import dev.brights0ng.enginesandempires.geophone.ReaderReading;
import dev.brights0ng.enginesandempires.geophone.ReadingOres;
import dev.brights0ng.enginesandempires.geophone.SeismicContent;
import dev.brights0ng.enginesandempires.geophone.SmartLoggerBlock;
import dev.brights0ng.enginesandempires.geophone.SmartLoggerBlockEntity;

/**
 * What the local player's crosshair is pointing at on a smart logger's map: used by the goggles, to call out the reading under
 * it (and where the crosshair is), and by the renderer, to ring that reading's dot. Only ever loaded on a client.
 */
public final class SmartLoggerHover {

    /**
     * Where on this logger's top the crosshair is, as {u, v} (see {@link LoggerDisplay}), or null if it is not on the top of
     * this logger at all.
     */
    public static double[] pointer(SmartLoggerBlockEntity logger) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) {
            return null;
        }
        BlockState state = minecraft.level.getBlockState(hit.getBlockPos());
        if (!state.is(SeismicContent.SMART_LOGGER.get())
                || !SmartLoggerBlock.masterPos(hit.getBlockPos(), state).equals(logger.getBlockPos())) {
            return null;
        }
        Vec3 middle = logger.middle();
        return logger.frame().local(middle.x, middle.z, hit.getLocation().x, hit.getLocation().z);
    }

    /** Which of the logger's entries the crosshair is over, or -1. Nothing is, while the display is off. */
    public static int hoveredIndex(SmartLoggerBlockEntity logger) {
        double[] pointer = logger.displayOn() ? pointer(logger) : null;
        if (pointer == null || logger.getLevel() == null) {
            return -1;
        }
        List<ReaderReading> readings = logger.records().entries().stream().map(LogbookEntry::reading).toList();
        return LoggerDisplay.hovered(logger.view(), logger.frame(), pointer[0], pointer[1], readings,
                logger.getLevel().dimension().location().toString());
    }

    /**
     * The goggles' lines for the map, while the display is on: its scale, where the crosshair is pointing in the world, and
     * the reading under it, if any, or else the one this player has selected: its name, ore, position and how good a fix it
     * is. A selected reading's details stay up while the crosshair is anywhere on the logger.
     */
    public static void addGoggleLines(SmartLoggerBlockEntity logger, List<Component> tooltip) {
        LoggerDisplay.View view = logger.view();
        tooltip.add(line(Component.translatable(LANG + "scale", Math.round(view.blocksPerMetre() * LoggerDisplay.SCREEN_WIDTH),
                Math.round(view.blocksPerMetre() * LoggerDisplay.DEPTH), view.zoom()).withStyle(ChatFormatting.GRAY)));
        double[] pointer = pointer(logger);
        if (pointer != null && LoggerDisplay.onScreen(pointer[0], pointer[1])) {
            double[] world = LoggerDisplay.toWorld(view, logger.frame(), pointer[0], pointer[1]);
            tooltip.add(line(Component.translatable(LANG + "pointing", (long) Math.floor(world[0]), (long) Math.floor(world[1]))
                    .withStyle(ChatFormatting.DARK_GRAY)));
        }

        int index = hoveredIndex(logger);
        LogbookEntry entry = index >= 0 ? logger.records().get(index) : SmartLoggerControls.selectedEntry(logger);
        if (entry == null) {
            return;
        }
        boolean selected = entry.number() == SmartLoggerControls.selected(logger);
        ReaderReading reading = entry.reading();
        tooltip.add(Component.empty());
        if (selected) {
            tooltip.add(line(Component.translatable(LANG + (logger.docked().isEmpty() ? "selected" : "selected_send"))
                    .withStyle(ChatFormatting.YELLOW)));
        }
        tooltip.add(line(ReadingBoardMenu.displayName(entry).copy().withStyle(ChatFormatting.GOLD)));
        tooltip.add(line(ReadingOres.colouredName(reading.ore())));
        tooltip.add(line(Component.translatable(LANG + "position", reading.x(),
                reading.hasHeight() ? String.valueOf(reading.y()) : "?", reading.z()).withStyle(ChatFormatting.WHITE)));
        tooltip.add(line(Component.translatable("item.engines_and_empires.windup_reader.confidence_"
                + reading.confidence().name().toLowerCase(java.util.Locale.ROOT)).withStyle(ChatFormatting.GRAY)));
    }

    private static Component line(Component text) {
        return Component.literal("    ").append(text);
    }

    private static final String LANG = "engines_and_empires.smart_logger.";

    private SmartLoggerHover() {
    }
}
