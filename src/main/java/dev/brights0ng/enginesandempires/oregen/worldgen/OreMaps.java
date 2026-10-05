package dev.brights0ng.enginesandempires.oregen.worldgen;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.BodyCache;
import dev.brights0ng.enginesandempires.oregen.OreMap;
import dev.brights0ng.enginesandempires.oregen.OreType;
import dev.brights0ng.enginesandempires.oregen.OreTypes;
import dev.brights0ng.enginesandempires.oregen.Realm;
import net.minecraft.server.level.ServerLevel;

/**
 * The one place the game gets its ore maps and deposit state from, so worldgen, commands and (later)
 * the geophone can never disagree about where deposits are or what they look like.
 *
 * <p>Ore maps say where deposits would be, for a world seed. Whether a deposit really exists, and what it
 * looks like, depends on the terrain of its dimension, so that is answered per dimension by
 * {@link LevelDeposits}, which is found with {@link #of(ServerLevel)}. When per-biome abundance arrives,
 * this is where it plugs in.
 *
 * <p>Ore maps are cheap and immutable, so this only keeps the maps for the most recently used world seed.
 */
public final class OreMaps {

    private static final BodyCache BODIES = new BodyCache();

    private record Entry(long seed, List<OreMap> maps, Map<String, OreMap> byId, Map<Realm, List<OreMap>> byRealm) {
    }

    private static volatile Entry cache;

    /** Deposit state for each loaded dimension. Weak, so a level that is unloaded is not kept alive. */
    private static final Map<ServerLevel, LevelDeposits> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());

    /** One map per ore for this world seed, in {@link OreTypes#ALL} order. */
    public static List<OreMap> forSeed(long seed) {
        return entryFor(seed).maps();
    }

    /** The maps of the ores that generate in one realm, for this world seed. */
    public static List<OreMap> forRealm(long seed, Realm realm) {
        return entryFor(seed).byRealm().get(realm);
    }

    /** The map of one ore for this world seed. */
    public static OreMap mapFor(long seed, OreType type) {
        return entryFor(seed).byId().get(type.id());
    }

    /** The ore a map places deposits of. */
    public static OreType typeOf(OreMap map) {
        return OreTypes.byId(map.layer().id());
    }

    /**
     * The deposit state of a dimension: resolution of deposits against its terrain, and the record of what
     * worldgen has done with them. Only dimensions with a {@link Realm} have any.
     */
    public static LevelDeposits of(ServerLevel level) {
        LevelDeposits deposits = LEVELS.get(level);
        if (deposits == null) {
            // Levels are attached when they load, before anything generates. If we get here something asked early;
            // work without saving rather than touching the level's data storage from an unknown thread.
            EnginesAndEmpiresMod.LOGGER.warn("Deposit state for {} was requested before the level finished loading; "
                    + "its deposit ledger will not be saved this session", level.dimension().location());
            deposits = LevelDeposits.create(level, false);
            LEVELS.put(level, deposits);
        }
        return deposits;
    }

    /** The deposit state of a dimension if it has been set up, or null. Unlike {@link #of}, never creates any. */
    static LevelDeposits existing(ServerLevel level) {
        return LEVELS.get(level);
    }

    /** Called on the main thread when a level loads. */
    static void attach(ServerLevel level) {
        LEVELS.put(level, LevelDeposits.create(level, true));
    }

    /** Called when a level unloads. */
    static void detach(ServerLevel level) {
        LEVELS.remove(level);
    }

    /** Recently built deposit bodies, shared by everything. */
    static BodyCache bodies() {
        return BODIES;
    }

    private static Entry entryFor(long seed) {
        Entry entry = cache;
        if (entry == null || entry.seed() != seed) {
            List<OreMap> maps = OreTypes.ALL.stream().map(type -> new OreMap(seed, type.layer())).toList();
            Map<String, OreMap> byId = new HashMap<>();
            for (OreMap map : maps) {
                byId.put(map.layer().id(), map);
            }
            Map<Realm, List<OreMap>> byRealm = new EnumMap<>(Realm.class);
            for (Realm realm : Realm.values()) {
                byRealm.put(realm, OreTypes.forRealm(realm).stream().map(type -> byId.get(type.id())).toList());
            }
            entry = new Entry(seed, maps, byId, byRealm);
            cache = entry;
        }
        return entry;
    }

    private OreMaps() {
    }
}
