package dev.brights0ng.enginesandempires.oregen;

/**
 * A dimension the pack generates ore deposits in, and the heights in it that a deposit may occupy.
 *
 * <p>Ore maps are pure functions of seed, ore and position, so two realms never interfere: every ore
 * belongs to exactly one realm and only ever generates there.
 */
public enum Realm {

    /** The overworld. Deposits stay above the bedrock layer at the bottom and inside the build limit at the top. */
    OVERWORLD("minecraft:overworld", DepthProfile.BEDROCK_TOP, DepthProfile.WORLD_MAX_Y),

    /**
     * The Nether. Its floor and roof are bedrock (up to y=4 and from about y=123), and the world is only
     * 128 blocks tall, so deposits are kept between them.
     */
    NETHER("minecraft:the_nether", 5, 122);

    private final String dimensionId;
    private final int lowestY;
    private final int highestY;

    Realm(String dimensionId, int lowestY, int highestY) {
        this.dimensionId = dimensionId;
        this.lowestY = lowestY;
        this.highestY = highestY;
    }

    /** The dimension's registry id, such as {@code minecraft:the_nether}. */
    public String dimensionId() {
        return dimensionId;
    }

    /** The lowest height any block of a deposit may be placed at. */
    public int lowestY() {
        return lowestY;
    }

    /** The highest height any block of a deposit may be placed at. */
    public int highestY() {
        return highestY;
    }

    /** The realm for a dimension id, or null if the pack generates no ore deposits in that dimension. */
    public static Realm ofDimension(String dimensionId) {
        for (Realm realm : values()) {
            if (realm.dimensionId.equals(dimensionId)) {
                return realm;
            }
        }
        return null;
    }
}
