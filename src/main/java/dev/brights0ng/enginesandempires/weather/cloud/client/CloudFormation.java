package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;

/**
 * One Project Atmosphere region: its clusters, which PA spawns together and moves as a rigid group. The renderer
 * voxelizes a formation into one shared grid, so overlapping clusters merge into one shape with aligned voxels.
 *
 * @param regionId PA's region id
 * @param members  the region's clusters, sorted by id
 * @param anchor   the member the grid is laid out from (the lowest id, so it stays the same between updates)
 */
public record CloudFormation(UUID regionId, List<CloudShape> members, CloudShape anchor) {

    public static CloudFormation of(UUID regionId, List<CloudShape> clusters) {
        List<CloudShape> sorted = clusters.stream().sorted(Comparator.comparing(CloudShape::id)).toList();
        return new CloudFormation(regionId, sorted, sorted.getFirst());
    }

    /**
     * Roughly how far the formation reaches from its anchor's centre, horizontally (blocks), anvil included, at the
     * real widths it is drawn with ({@code CloudScale.horizontal}).
     */
    public double reach() {
        double reach = 0;
        for (CloudShape m : members) {
            double dx = offsetX(m);
            double dz = offsetZ(m);
            double r = m.radius() * spread(m);
            reach = Math.max(reach, Math.sqrt(dx * dx + dz * dz) + r * (1.3 + 2.1 * m.anvilStrength()));
        }
        return reach;
    }

    /** How much wider than PA's radius cluster {@code c} is drawn. */
    public static double spread(CloudShape c) {
        return Double.isFinite(c.spread()) ? c.spread() : CloudScale.horizontal(c.typeId());
    }

    /**
     * Where member {@code m} is drawn relative to where the anchor is drawn, x (blocks). With layouts (in game): each
     * cluster's own drawn offset, so regrouping doesn't move it; without (shapes built by hand):
     * its offset from the anchor stretched by the anchor type's width.
     */
    public double offsetX(CloudShape m) {
        if (m.laidOut() && anchor.laidOut()) {
            return (m.cx() + m.dispX()) - (anchor.cx() + anchor.dispX());
        }
        return (m.cx() - anchor.cx()) * CloudScale.horizontal(anchor.typeId());
    }

    public double offsetZ(CloudShape m) {
        if (m.laidOut() && anchor.laidOut()) {
            return (m.cz() + m.dispZ()) - (anchor.cz() + anchor.dispZ());
        }
        return (m.cz() - anchor.cz()) * CloudScale.horizontal(anchor.typeId());
    }

    /** How far the anchor is drawn from PA's centre for it, x (0 without a layout). */
    public double originX() {
        return anchor.laidOut() ? anchor.dispX() : 0;
    }

    public double originZ() {
        return anchor.laidOut() ? anchor.dispZ() : 0;
    }

    /** The darkest storm tier among the members, for outlines and info. */
    public String stormTier() {
        CloudShape darkest = members.stream().max(Comparator.comparingDouble(CloudShape::stormDarkness)).orElse(anchor);
        return darkest.stormTier();
    }
}
