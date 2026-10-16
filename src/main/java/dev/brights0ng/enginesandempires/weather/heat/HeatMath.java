package dev.brights0ng.enginesandempires.weather.heat;

import java.util.Collection;

/**
 * The arithmetic of local heat and cold (weather phase 8e-3), apart from the world. Pure.
 *
 * <p>One source adds {@link #fade} of its change, by distance. The sources of one kind add up to at most their
 * {@code maxEffect} (positive or negative). Then each kind pushes the air toward hotter or colder, but a heat source
 * stops where its limit is and a cold one stops at its own: air already past a limit is left as it is. Heat is applied
 * before cold.
 */
public final class HeatMath {

    /** Full strength out to here (blocks), as Cold Sweat's blocks do. */
    public static final double FULL_STRENGTH_DISTANCE = 0.5;

    /**
     * All the sources of one kind: their faded changes added, the kind's cap, and its limit (NaN for none).
     */
    public record Group(double sum, double maxEffect, double limit) {
    }

    /** {@code change} at {@code distance} blocks from a source with this range: linear from half a block to the range. */
    public static double fade(double change, double range, double distance) {
        if (distance >= range) {
            return 0;
        }
        if (distance <= FULL_STRENGTH_DISTANCE) {
            return change;
        }
        return change * (range - distance) / (range - FULL_STRENGTH_DISTANCE);
    }

    /** The air temperature, C, once every group has pushed it. */
    public static double apply(double airC, Collection<Group> groups) {
        double t = airC;
        for (Group g : groups) {
            double change = Math.min(g.maxEffect, g.sum);
            if (change > 0) {
                t = push(t, change, g.limit);
            }
        }
        for (Group g : groups) {
            double change = Math.max(-g.maxEffect, g.sum);
            if (change < 0) {
                t = push(t, change, g.limit);
            }
        }
        return t;
    }

    /** {@code t} moved by {@code change} (not past {@code limit} in that direction, NaN: no limit). */
    static double push(double t, double change, double limit) {
        if (Double.isNaN(limit)) {
            return t + change;
        }
        if (change > 0) {
            return t >= limit ? t : Math.min(t + change, limit);
        }
        return t <= limit ? t : Math.max(t + change, limit);
    }

    private HeatMath() {
    }
}
