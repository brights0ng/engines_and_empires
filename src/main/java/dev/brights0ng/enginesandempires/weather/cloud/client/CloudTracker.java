package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.CloudSources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The clouds in the player's current dimension, refreshed from {@link CloudSources} once per client tick, grouped into
 * formations. Positions come straight from each cloud's drift ({@code CloudDrift}: the server and the client follow
 * the same eased curve, so nothing needs correcting; the old measured-velocity smoothing lagged one update behind and
 * made clouds speed up and slow down, Bright 2026-10-07). Client thread only.
 */
public final class CloudTracker {

    private static List<CloudShape> clouds = List.of();
    private static List<CloudFormation> formations = List.of();
    private static final Map<UUID, CloudShape> BY_ID = new HashMap<>();
    private static double lastTime;

    /** Called once per client tick. */
    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            clear();
            return;
        }
        String dimension = level.dimension().location().toString();
        double now = level.getGameTime();
        List<CloudShape> raw = CloudSources.client(now).stream().filter(c -> dimension.equals(c.dimension())).toList();
        lastTime = now;
        BY_ID.clear();
        for (CloudShape c : raw) {
            BY_ID.put(c.id(), c);
        }
        clouds = raw;

        Map<UUID, List<CloudShape>> byRegion = new LinkedHashMap<>();
        for (CloudShape c : clouds) {
            byRegion.computeIfAbsent(c.regionId(), k -> new ArrayList<>()).add(c);
        }
        formations = group(byRegion, now);
    }

    /**
     * The formations to draw: each simulation cloud on its own, except that layer clouds of one deck that touch are
     * drawn as one, so a deck is a continuous sheet (Bright, 2026-10-07). A merged sheet's id is its lowest cloud id;
     * its members' offsets are taken at {@code now}.
     */
    static List<CloudFormation> group(Map<UUID, List<CloudShape>> byRegion, double now) {
        List<UUID> ids = new ArrayList<>(byRegion.keySet());
        int n = ids.size();
        int[] parent = new int[n];
        dev.brights0ng.enginesandempires.weather.cloud.CloudType[] types =
                new dev.brights0ng.enginesandempires.weather.cloud.CloudType[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
            types[i] = dev.brights0ng.enginesandempires.weather.cloud.CloudType.of(
                    byRegion.get(ids.get(i)).getFirst().typeId());
        }
        for (int i = 0; i < n; i++) {
            if (types[i] == null || !types[i].layer()) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                if (types[j] == null || !types[j].layer() || types[j].deck != types[i].deck
                        || find(parent, i) == find(parent, j)) {
                    continue;
                }
                if (touch(byRegion.get(ids.get(i)), byRegion.get(ids.get(j)), now)) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        List<CloudFormation> out = new ArrayList<>(groups.size());
        for (List<Integer> g : groups.values()) {
            if (g.size() == 1) {
                UUID id = ids.get(g.getFirst());
                out.add(CloudFormation.of(id, byRegion.get(id)));
                continue;
            }
            UUID id = null;
            List<CloudShape> members = new ArrayList<>();
            for (int i : g) {
                UUID r = ids.get(i);
                if (id == null || r.compareTo(id) < 0) {
                    id = r;
                }
                members.addAll(byRegion.get(r));
            }
            out.add(CloudFormation.of(id, members, now));
        }
        return out;
    }

    /** Whether two clouds' domes touch at time {@code t}. */
    private static boolean touch(List<CloudShape> a, List<CloudShape> b, double t) {
        for (CloudShape p : a) {
            double px = p.xAt(t), pz = p.zAt(t);
            for (CloudShape q : b) {
                double dx = q.xAt(t) - px, dz = q.zAt(t) - pz;
                double r = (p.radius() + q.radius()) * CloudField.LAYER_REACH;
                if (dx * dx + dz * dz < r * r) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Where cloud {@code c}'s centre is at client time {@code t} (ticks, partial allowed). */
    public static double x(CloudShape c, double t) {
        return c.xAt(t);
    }

    public static double z(CloudShape c, double t) {
        return c.zAt(t);
    }

    /** Where cluster {@code id} is drawn at time {@code t}: its centre. NaN if it is no longer tracked. */
    public static double drawnX(UUID id, double t) {
        CloudShape c = BY_ID.get(id);
        return c == null ? Double.NaN : c.xAt(t);
    }

    public static double drawnZ(UUID id, double t) {
        CloudShape c = BY_ID.get(id);
        return c == null ? Double.NaN : c.zAt(t);
    }

    /** Cloud {@code c}'s velocity as of the last client tick (blocks per tick). */
    public static double vx(CloudShape c) {
        return c.vxAt(lastTime);
    }

    public static double vz(CloudShape c) {
        return c.vzAt(lastTime);
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
        BY_ID.clear();
    }

    private CloudTracker() {
    }
}
