package dev.brights0ng.enginesandempires.geophone;

/**
 * What a wind-up reader knows: where a deposit is. It is carried by the reader, both while it is placed and while it is
 * held, so a reading survives picking the reader up and putting it down, and it can be saved in a logbook and loaded back.
 *
 * <p>The position is the deposit's nearest ore block to the reader, blurred by how good the array that took the reading was
 * (see {@link ReaderAccuracy}). How good is recorded too, as {@link #confidence}, but only ever as a coarse tag, never a
 * distance a player could read as a number.
 *
 * @param dimension  the dimension the deposit is in, as its id. A reading points nowhere in any other dimension
 * @param hasHeight  whether the reader heard the deposit through enough geophones to know how high it is. Without the height
 *                   it can only give a bearing
 * @param deposit    which deposit it is (its seed), or 0 if that is not known. Never shown to anyone. It lets a logbook tell that
 *                   a new reading is of a deposit it already has, and update that entry instead of filling up with copies
 * @param takenAt    the game time the reading was taken, or 0 if that is not known, as for a reading taken before this was kept.
 *                   It lets a logbook tell which of two readings of the same deposit is the newer. An unknown time counts as
 *                   older than any known one
 * @param confidence how good the array was that produced this reading, or {@link ReaderAccuracy.Confidence#UNKNOWN} for one
 *                   taken before this was tracked, which is treated as the worst case
 * @param ore        which ore the deposit is, as its id (see {@code OreTypes}), or empty if that is not known, as for a
 *                   reading taken before this was kept
 */
public record ReaderReading(String dimension, int x, int y, int z, boolean hasHeight, long deposit, long takenAt,
                            ReaderAccuracy.Confidence confidence, String ore) {

    public ReaderReading {
        ore = ore == null ? "" : ore;
    }

    /** A reading that does not say which ore it is of, such as one made before that was kept. */
    public ReaderReading(String dimension, int x, int y, int z, boolean hasHeight, long deposit, long takenAt,
                         ReaderAccuracy.Confidence confidence) {
        this(dimension, x, y, z, hasHeight, deposit, takenAt, confidence, "");
    }

    /** A reading with no confidence figure, such as one made before this was kept. */
    public ReaderReading(String dimension, int x, int y, int z, boolean hasHeight, long deposit, long takenAt) {
        this(dimension, x, y, z, hasHeight, deposit, takenAt, ReaderAccuracy.Confidence.UNKNOWN);
    }

    /** A reading with no time taken, such as one made before the time was kept. */
    public ReaderReading(String dimension, int x, int y, int z, boolean hasHeight, long deposit) {
        this(dimension, x, y, z, hasHeight, deposit, 0L);
    }

    /** A reading whose deposit and time are not known. */
    public ReaderReading(String dimension, int x, int y, int z, boolean hasHeight) {
        this(dimension, x, y, z, hasHeight, 0L, 0L);
    }

    /** Whether this reading says which deposit it is of. */
    public boolean knowsDeposit() {
        return deposit != 0L;
    }

    /** Whether this reading says which ore its deposit is. */
    public boolean knowsOre() {
        return !ore.isEmpty();
    }

    /** Whether this is a reading of the same deposit as another, which is only ever true if both say which they are of. */
    public boolean sameDepositAs(ReaderReading other) {
        return knowsDeposit() && other.knowsDeposit() && deposit == other.deposit && dimension.equals(other.dimension);
    }

    /** The same reading, now known to be of this deposit. */
    public ReaderReading withDeposit(long deposit) {
        return new ReaderReading(dimension, x, y, z, hasHeight, deposit, takenAt, confidence, ore);
    }

    /** The same reading, now known to be of this ore. */
    public ReaderReading withOre(String ore) {
        return new ReaderReading(dimension, x, y, z, hasHeight, deposit, takenAt, confidence, ore);
    }
}
