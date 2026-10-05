package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Remembers where the loaded smart loggers are, so a vibration can find the ones that might hear it without scanning the
 * world. A logger adds itself when its block entity starts up and removes itself when it is broken or its chunk unloads.
 * Works the same way as {@link ReaderRegistry}.
 */
public final class LoggerRegistry {

    /** A loaded logger: its master block, and the middle of the desk, where it listens from. */
    public record Entry(BlockPos pos, Vec3 position) {
    }

    private static final Map<ServerLevel, Map<BlockPos, Entry>> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());

    static void add(ServerLevel level, BlockPos pos, Vec3 middle) {
        BlockPos key = pos.immutable();
        LEVELS.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>()).put(key, new Entry(key, middle));
    }

    static void remove(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Entry> loggers = LEVELS.get(level);
        if (loggers != null) {
            loggers.remove(pos);
        }
    }

    /** The loaded loggers whose middle is within {@code radius} of a point. A snapshot: safe to keep. */
    public static List<Entry> within(ServerLevel level, Vec3 centre, double radius) {
        Map<BlockPos, Entry> loggers = LEVELS.get(level);
        List<Entry> found = new ArrayList<>();
        if (loggers == null) {
            return found;
        }
        double limit = radius * radius;
        for (Entry logger : loggers.values()) {
            if (logger.position().distanceToSqr(centre) <= limit) {
                found.add(logger);
            }
        }
        return found;
    }

    private LoggerRegistry() {
    }
}
