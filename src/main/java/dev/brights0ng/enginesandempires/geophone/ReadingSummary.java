package dev.brights0ng.enginesandempires.geophone;

/**
 * What a logbook shows for a saved reading: how good the fix is, how far away it is and which way, from where you are
 * standing. It shows no coordinates and no distance figure for the error itself, only the confidence tag, the distance across
 * the ground, one of eight compass points, and, if the reader knew the height, how far above or below you it is.
 *
 * <p>A reading in another dimension has no distance or direction from here, so it only says which dimension it is in (its
 * confidence tag is still meaningful, and is included regardless).
 *
 * <p>Nothing here touches Minecraft.
 *
 * @param sameDimension    whether the reading is in the dimension you are in. If not, the rest but {@link #confidence} is not
 *                         meaningful
 * @param dimensionName    a name for the dimension the reading is in, for saying where it is when it is not this one
 * @param confidence       how good the array that produced this reading was
 * @param distance         blocks across the ground from you, rounded
 * @param direction        which way it lies, or {@link Compass8#HERE} if you are practically on top of it
 * @param height           whether it is above, level with, or below you, or {@link ReaderCompass.HeightBand#NONE} if the reader
 *                         did not know the height
 * @param heightDifference how many blocks above (positive) or below (negative) you it is, rounded. Only meaningful with a height
 */
public record ReadingSummary(boolean sameDimension, String dimensionName, ReaderAccuracy.Confidence confidence, int distance,
                             Compass8 direction, ReaderCompass.HeightBand height, int heightDifference) {

    /** The points of the compass, with north up the map as in the game (towards -z). */
    public enum Compass8 {
        N, NE, E, SE, S, SW, W, NW,
        /** Closer than {@link #HERE_WITHIN} blocks: no direction is worth giving. */
        HERE
    }

    /** Within this many blocks across the ground, a reading is called "here". */
    public static final double HERE_WITHIN = 2.0;

    /** Describes a reading from where a player is. Positions are the player's own coordinates. */
    public static ReadingSummary of(ReaderReading reading, String playerDimension, double playerX, double playerY, double playerZ) {
        String name = dimensionName(reading.dimension());
        if (!reading.dimension().equals(playerDimension)) {
            return new ReadingSummary(false, name, reading.confidence(), 0, Compass8.HERE, ReaderCompass.HeightBand.NONE, 0);
        }
        // From the player to the middle of the ore block.
        double dx = reading.x() + 0.5 - playerX;
        double dy = reading.y() + 0.5 - playerY;
        double dz = reading.z() + 0.5 - playerZ;
        double across = Math.sqrt(dx * dx + dz * dz);
        return new ReadingSummary(true, name, reading.confidence(), (int) Math.round(across),
                across < HERE_WITHIN ? Compass8.HERE : compass(dx, dz),
                ReaderCompass.heightBand(dy, reading.hasHeight()), (int) Math.round(dy));
    }

    /** The compass point of an offset, in eight directions. North is towards -z, east towards +x. */
    static Compass8 compass(double dx, double dz) {
        double angle = Math.atan2(dx, -dz); // 0 is north, turning towards east
        int index = (int) Math.round(angle / (Math.PI / 4.0));
        return Compass8.values()[Math.floorMod(index, 8)];
    }

    /** A name for a dimension's id: "the Overworld" for the overworld, and the like, or its plain name for any other. */
    static String dimensionName(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> "the Overworld";
            case "minecraft:the_nether" -> "the Nether";
            case "minecraft:the_end" -> "the End";
            default -> dimensionId.substring(dimensionId.indexOf(':') + 1).replace('_', ' ');
        };
    }
}
