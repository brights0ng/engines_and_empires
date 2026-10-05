package dev.brights0ng.enginesandempires.frontier.tier;

import dev.brights0ng.enginesandempires.frontier.Tier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Finds the {@link FrontierLevel} for a level. Only the Overworld has one, so this is a single field, which also makes the
 * lookup safe from any thread (the block-change hook can be reached off the server thread by other mods).
 */
public final class FrontierLevels {

    private static volatile FrontierLevel overworld;

    public static boolean tracks(Level level) {
        return level instanceof ServerLevel && level.dimension() == Level.OVERWORLD;
    }

    static void attach(ServerLevel level) {
        if (tracks(level)) {
            overworld = new FrontierLevel(level);
        }
    }

    static void detach(ServerLevel level) {
        FrontierLevel current = overworld;
        if (current != null && current.level() == level) {
            overworld = null;
        }
    }

    /** The level's Frontier state, or null if it has none (every dimension but the Overworld, and the client). */
    public static FrontierLevel of(Level level) {
        FrontierLevel current = overworld;
        return current != null && current.level() == level ? current : null;
    }

    /** The tier at a block in any level: every dimension but the Overworld is Uninhabited everywhere. */
    public static Tier tierAt(Level level, BlockPos pos) {
        FrontierLevel frontier = of(level);
        return frontier == null ? Tier.UNINHABITED : frontier.tierAt(pos);
    }

    private FrontierLevels() {
    }
}
