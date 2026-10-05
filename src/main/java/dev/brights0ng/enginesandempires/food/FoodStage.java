package dev.brights0ng.enginesandempires.food;

import java.util.Locale;

/**
 * The freshness a player sees. Behind each of the first three sit ten substages (see {@link SpoilTimes#substage}), so there
 * are 31 in all; rotting is a single stage.
 */
public enum FoodStage {
    FRESH,
    RIPE,
    STALE,
    ROTTING;

    /** How many substages each stage is cut into. */
    public static final int SUBSTAGES = 10;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
