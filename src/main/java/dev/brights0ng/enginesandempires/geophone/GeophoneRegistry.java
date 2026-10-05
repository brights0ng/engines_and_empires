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
 * Remembers where the loaded geophones are, so a vibration can find the ones that might hear it without scanning the
 * world. A geophone adds itself when it joins the world (when it is staked, or its chunk loads) and removes itself when
 * it is knocked out or its chunk unloads, so only geophones that exist to be lit up are ever listed: one in an unloaded
 * chunk cannot glow, and does not need to.
 *
 * <p>Geophones are listed by their entity id, which is what a vibration hands back to find them again, and by the
 * point where they are staked, which is where they listen. Neither changes while a geophone is loaded.
 *
 * <p>Held per level, weakly, so a level that is closed takes its list with it.
 */
public final class GeophoneRegistry {

    /** A loaded geophone: its entity id, where it listens, and what it can hear. */
    public record Entry(int entityId, Vec3 position, GeophoneTier tier) {
    }

    private static final Map<ServerLevel, Map<Integer, Entry>> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());

    static void add(ServerLevel level, GeophoneEntity geophone) {
        LEVELS.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>())
                .put(geophone.getId(), new Entry(geophone.getId(), geophone.position(), geophone.tier()));
    }

    static void remove(ServerLevel level, GeophoneEntity geophone) {
        Map<Integer, Entry> geophones = LEVELS.get(level);
        if (geophones != null) {
            geophones.remove(geophone.getId());
        }
    }

    /** The loaded geophones staked within {@code radius} of a point. A snapshot: safe to keep. */
    public static List<Entry> within(ServerLevel level, Vec3 centre, double radius) {
        Map<Integer, Entry> geophones = LEVELS.get(level);
        List<Entry> found = new ArrayList<>();
        if (geophones == null) {
            return found;
        }
        double limit = radius * radius;
        for (Entry geophone : geophones.values()) {
            if (geophone.position().distanceToSqr(centre) <= limit) {
                found.add(geophone);
            }
        }
        return found;
    }

    /** How many geophones are loaded in a level. */
    public static int count(ServerLevel level) {
        Map<Integer, Entry> geophones = LEVELS.get(level);
        return geophones == null ? 0 : geophones.size();
    }

    private GeophoneRegistry() {
    }
}
