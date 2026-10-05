package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Remembers where the placed wind-up readers are, so a vibration can find the ones that might hear it without scanning the
 * world. A reader adds itself when it joins the world and removes itself when it is taken up or its chunk unloads, so only
 * readers that exist to take a reading are ever listed. See {@link GeophoneRegistry}, which works the same way.
 */
public final class ReaderRegistry {

    /** A loaded, placed reader: its entity id and where it stands. */
    public record Entry(int entityId, Vec3 position) {
    }

    private static final Map<ServerLevel, Map<Integer, Entry>> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());

    static void add(ServerLevel level, WindupReaderEntity reader) {
        LEVELS.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>())
                .put(reader.getId(), new Entry(reader.getId(), reader.position()));
    }

    static void remove(ServerLevel level, WindupReaderEntity reader) {
        Map<Integer, Entry> readers = LEVELS.get(level);
        if (readers != null) {
            readers.remove(reader.getId());
        }
    }

    /** The loaded readers standing within {@code radius} of a point. A snapshot: safe to keep. */
    public static List<Entry> within(ServerLevel level, Vec3 centre, double radius) {
        Map<Integer, Entry> readers = LEVELS.get(level);
        List<Entry> found = new ArrayList<>();
        if (readers == null) {
            return found;
        }
        double limit = radius * radius;
        for (Entry reader : readers.values()) {
            if (reader.position().distanceToSqr(centre) <= limit) {
                found.add(reader);
            }
        }
        return found;
    }

    private ReaderRegistry() {
    }
}
