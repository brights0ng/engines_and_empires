package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.CloudSources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The clouds in the player's current dimension, refreshed from {@link CloudSources} once per client tick, grouped into
 * formations, with smooth positions between the server's updates ({@link CloudMotion}: velocity measured between
 * updates, corrections eased in). Client thread only.
 */
public final class CloudTracker {

    private static List<CloudShape> clouds = List.of();
    private static List<CloudFormation> formations = List.of();
    private static final Map<UUID, CloudMotion> MOTION = new HashMap<>();
    /** Each cluster's drawn offset from its centre as of the last tick: disp x, disp z (NaN: none). */
    private static final Map<UUID, double[]> DISP = new HashMap<>();

    /** Called once per client tick. */
    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clear();
            return;
        }
        String dimension = level.dimension().location().toString();
        List<CloudShape> raw = CloudSources.client().stream().filter(c -> dimension.equals(c.dimension())).toList();
        double now = level.getGameTime();
        for (CloudShape c : raw) {
            observe(c, now);
        }
        clouds = raw;

        Set<UUID> present = new HashSet<>();
        Map<UUID, List<CloudShape>> byRegion = new LinkedHashMap<>();
        DISP.clear();
        for (CloudShape c : clouds) {
            present.add(c.id());
            DISP.put(c.id(), new double[]{c.dispX(), c.dispZ()});
            byRegion.computeIfAbsent(c.regionId(), k -> new ArrayList<>()).add(c);
        }
        MOTION.keySet().retainAll(present);

        List<CloudFormation> grouped = new ArrayList<>(byRegion.size());
        for (Map.Entry<UUID, List<CloudShape>> e : byRegion.entrySet()) {
            grouped.add(CloudFormation.of(e.getKey(), e.getValue()));
        }
        formations = grouped;
    }

    private static void observe(CloudShape c, double now) {
        CloudMotion m = MOTION.get(c.id());
        if (m == null) {
            MOTION.put(c.id(), new CloudMotion(c.cx(), c.cz(), c.simulationTick(), c.vx(), c.vz()));
        } else {
            m.observe(c.cx(), c.cz(), c.simulationTick(), c.vx(), c.vz(), now);
        }
    }

    /** Where the server's centre for cloud {@code c} is at client time {@code t} (ticks, partial allowed), smoothed. */
    public static double x(CloudShape c, double t) {
        CloudMotion m = MOTION.get(c.id());
        return m == null ? c.xAt(t) : m.x(t);
    }

    public static double z(CloudShape c, double t) {
        CloudMotion m = MOTION.get(c.id());
        return m == null ? c.zAt(t) : m.z(t);
    }

    /**
     * Where cluster {@code id} is drawn at time {@code t}: its smoothed centre plus its drawn offset. NaN if it is no
     * longer tracked.
     */
    public static double drawnX(UUID id, double t) {
        CloudMotion m = MOTION.get(id);
        double[] d = DISP.get(id);
        return m == null ? Double.NaN : m.x(t) + (d == null || !Double.isFinite(d[0]) ? 0 : d[0]);
    }

    public static double drawnZ(UUID id, double t) {
        CloudMotion m = MOTION.get(id);
        double[] d = DISP.get(id);
        return m == null ? Double.NaN : m.z(t) + (d == null || !Double.isFinite(d[1]) ? 0 : d[1]);
    }

    /** Cloud {@code c}'s velocity as measured between the server's updates (blocks per tick), not one gusty tick's. */
    public static double vx(CloudShape c) {
        CloudMotion m = MOTION.get(c.id());
        return m == null ? c.vx() : m.vx();
    }

    public static double vz(CloudShape c) {
        CloudMotion m = MOTION.get(c.id());
        return m == null ? c.vz() : m.vz();
    }

    /** The clouds as of the last client tick. */
    public static List<CloudShape> clouds() {
        return clouds;
    }

    /** The clouds grouped by formation, as of the last client tick. */
    public static List<CloudFormation> formations() {
        return formations;
    }

    private static void clear() {
        clouds = List.of();
        formations = List.of();
        MOTION.clear();
        DISP.clear();
    }

    private CloudTracker() {
    }
}
