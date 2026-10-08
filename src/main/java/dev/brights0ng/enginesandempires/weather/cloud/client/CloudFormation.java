package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One cloud formation: its domes, which the simulation spawns together and moves as a rigid group. The renderer
 * voxelizes a formation into one shared grid, so overlapping domes merge into one shape with aligned voxels.
 *
 * @param regionId the formation's id
 * @param members  its domes, sorted by id
 * @param anchor   the member the grid is laid out from (the lowest id, so it stays the same between updates)
 * @param time     the game time the members' offsets from the anchor are taken at: a merged layer sheet holds the
 *                 domes of several simulation clouds, each moving at its own velocity (2026-10-07)
 */
public record CloudFormation(UUID regionId, List<CloudShape> members, CloudShape anchor, double time) {

    /** One simulation cloud's domes (they move together, so the offsets are the same at any time). */
    public static CloudFormation of(UUID regionId, List<CloudShape> clusters) {
        List<CloudShape> sorted = clusters.stream().sorted(Comparator.comparing(CloudShape::id)).toList();
        return new CloudFormation(regionId, sorted, sorted.getFirst(), sorted.getFirst().simulationTick());
    }

    /** Domes from any clouds, with their offsets taken at game time {@code time}. */
    public static CloudFormation of(UUID regionId, List<CloudShape> clusters, double time) {
        List<CloudShape> sorted = clusters.stream().sorted(Comparator.comparing(CloudShape::id)).toList();
        return new CloudFormation(regionId, sorted, sorted.getFirst(), time);
    }

    /** Roughly how far the formation reaches from its anchor's centre, horizontally (blocks), anvil included. */
    public double reach() {
        double reach = 0;
        for (CloudShape m : members) {
            double dx = offsetX(m);
            double dz = offsetZ(m);
            reach = Math.max(reach, Math.sqrt(dx * dx + dz * dz) + m.radius() * (1.3 + 2.1 * m.anvilStrength()));
        }
        return reach;
    }

    /** Where member {@code m} is relative to the anchor, x (blocks). */
    public double offsetX(CloudShape m) {
        return m.xAt(time) - anchor.xAt(time);
    }

    public double offsetZ(CloudShape m) {
        return m.zAt(time) - anchor.zAt(time);
    }

    /** The darkest member's storm darkness, for outlines and info. */
    public float darkness() {
        float d = 0;
        for (CloudShape m : members) {
            d = Math.max(d, m.stormDarkness());
        }
        return d;
    }
}
