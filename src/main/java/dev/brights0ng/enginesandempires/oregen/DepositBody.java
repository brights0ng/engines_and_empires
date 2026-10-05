package dev.brights0ng.enginesandempires.oregen;

import java.util.Arrays;

import dev.brights0ng.enginesandempires.oregen.shape.CellSample;
import dev.brights0ng.enginesandempires.oregen.shape.ShapeField;
import dev.brights0ng.enginesandempires.oregen.shape.ShapeRequest;

/**
 * The blocks of one deposit: which are ore, which are rich ore, which are the host rock around them and
 * which are hollow.
 *
 * <p>A body is described relative to its deposit's centre block (dx, dy, dz), and is a pure function of
 * the deposit's seed and its ore type: any chunk can rebuild the whole thing and place its own part, and
 * every chunk gets the same answer.
 *
 * <p>How one is made:
 * <ol>
 *   <li>The deposit's seed decides its height, and from that its size multiplier. The deposit's size is
 *       then an exact number of ore blocks, drawn from the ore's size profile and scaled by that
 *       multiplier. A shallow deposit (centred at or above its depth profile's origin) is instead scaled by
 *       its surface biome's factor, if the ore has one; this is applied after the profile's cap.</li>
 *   <li>The ore's shape builds the deposit's geometry for that size.</li>
 *   <li>If the geometry is taller than its realm has room for (a deep-sized deposit in the Nether, say),
 *       it is drawn again a few times, and if none fit, made a little smaller.</li>
 *   <li>Every block in the geometry's bounding box is asked what it is. Blocks that could be ore come
 *       back with a score.</li>
 *   <li>The highest-scoring candidates, exactly as many as the deposit's size, become ore. Because
 *       winners are picked by rank, the count is exact, edges come out ragged and holes appear inside.</li>
 *   <li>The best-scoring ore blocks, a smaller fraction, become rich ore. Rich ore therefore sits where
 *       the deposit is richest, not scattered.</li>
 * </ol>
 *
 * <p>Instances are immutable and thread-safe. Nothing here touches Minecraft.
 */
public final class DepositBody {

    public static final byte EMPTY = 0;
    public static final byte HOST = 1;
    public static final byte ORE = 2;
    public static final byte RICH = 3;
    public static final byte VOID = 4;

    /** Receives every non-empty block of a body. */
    @FunctionalInterface
    public interface CellVisitor {
        void accept(int dx, int dy, int dz, byte kind);
    }

    /** No deposit reaches further than this vertically. */
    private static final int MAX_VERTICAL_REACH = 96;
    /** How many times a shape is drawn, at most, to find geometry that fits its realm's height. */
    private static final int MAX_FIT_ATTEMPTS = 24;
    /** Tries at each size before the deposit is made a little smaller. */
    private static final int ATTEMPTS_PER_SIZE = 4;
    private static final double SHRINK = 0.85;
    /** Upper bound on the size of the box a deposit is built in. */
    private static final long MAX_CELLS = 3_000_000L;
    private static final long SALT = 0x5851F42D4C957F2DL;
    private static final byte CANDIDATE = 5; // internal: a candidate not yet decided

    private final OreType type;
    private final int attempt;
    private final int drawnY;
    private final int centerY;
    private final double sizeMultiplier;
    private final double biomeMultiplier;
    private final int requestedOre;
    private final int oreCount;
    private final int richCount;
    private final int hostCount;
    private final int voidCount;
    private final int reachX;
    private final int reachY;
    private final int reachZ;
    private final boolean clipped;
    private final ShapeField field;

    // The box the cells are stored in, and the cells themselves (sorted by position).
    private final int boxX;
    private final int boxY;
    private final int boxZ;
    private final int[] keys;
    private final byte[] kinds;

    private DepositBody(OreType type, int attempt, int drawnY, int centerY, double sizeMultiplier,
                        double biomeMultiplier, int requestedOre,
                        int oreCount, int richCount, int hostCount, int voidCount,
                        int reachX, int reachY, int reachZ, boolean clipped, ShapeField field,
                        int boxX, int boxY, int boxZ, int[] keys, byte[] kinds) {
        this.type = type;
        this.attempt = attempt;
        this.drawnY = drawnY;
        this.centerY = centerY;
        this.sizeMultiplier = sizeMultiplier;
        this.biomeMultiplier = biomeMultiplier;
        this.requestedOre = requestedOre;
        this.oreCount = oreCount;
        this.richCount = richCount;
        this.hostCount = hostCount;
        this.voidCount = voidCount;
        this.reachX = reachX;
        this.reachY = reachY;
        this.reachZ = reachZ;
        this.clipped = clipped;
        this.field = field;
        this.boxX = boxX;
        this.boxY = boxY;
        this.boxZ = boxZ;
        this.keys = keys;
        this.kinds = kinds;
    }

    /** A shape that fits, and the ore count it was finally built for. */
    record Fit(ShapeField field, int wanted) {
    }

    /** The most a deposit may reach vertically from its centre in this realm: it must fit between the realm's floor and ceiling. */
    static int verticalLimit(Realm realm) {
        int room = realm.highestY() - realm.lowestY() + 1;
        return Math.min(MAX_VERTICAL_REACH, (room - 1) / 2);
    }

    /**
     * Builds a shape for a deposit that fits its realm's height. Most fit first time, and then the random
     * stream is used exactly as if there were no such check. A shape that is too tall is drawn again, with
     * fresh random choices, a few times at the same size, and only if none fit is the deposit made smaller.
     */
    static Fit fit(OreType type, DepositRandom rng, int wanted) {
        int limit = verticalLimit(type.realm());
        ShapeField field = null;
        for (int attempt = 0; attempt < MAX_FIT_ATTEMPTS; attempt++) {
            field = type.shape().create(new ShapeRequest(rng, wanted, type.size().median()));
            if (field.reachY() <= limit) {
                break;
            }
            if (attempt % ATTEMPTS_PER_SIZE == ATTEMPTS_PER_SIZE - 1) {
                wanted = Math.max(1, (int) Math.round(wanted * SHRINK));
            }
        }
        return new Fit(field, wanted);
    }

    public static DepositBody generate(Deposit deposit, OreType type) {
        return generate(deposit, type, 0, 1.0);
    }

    /** Builds one attempt at the deposit, with no biome influence. */
    public static DepositBody generate(Deposit deposit, OreType type, int attempt) {
        return generate(deposit, type, attempt, 1.0);
    }

    /**
     * Builds the deposit's body for a given attempt. Attempt 0 is the deposit's ordinary body. Later
     * attempts are complete fresh draws (a new height, size and shape) for the same map position, used
     * when the first draw turns out to lie in open air; see {@link DepositResolver}.
     *
     * @param biomeFactor the size factor of the deposit's surface biome (see {@link BiomeRule}); used only
     *                    if this draw turns out shallow. The random stream does not depend on it, so the
     *                    deposit's height and shape choices are the same whatever the biome.
     */
    public static DepositBody generate(Deposit deposit, OreType type, int attempt, double biomeFactor) {
        return generate(deposit, type, attempt, biomeFactor, type.depth());
    }

    /**
     * Builds one attempt at the deposit under the biome rule that applies to it (null for none): its factor
     * scales the deposit if it is shallow, and a rule that raises the height range changes where it is drawn.
     */
    public static DepositBody generate(Deposit deposit, OreType type, int attempt, BiomeRule rule) {
        return rule == null
                ? generate(deposit, type, attempt, 1.0, type.depth())
                : generate(deposit, type, attempt, rule.factor(), rule.depthFor(type.depth()));
    }

    private static DepositBody generate(Deposit deposit, OreType type, int attempt, double biomeFactor, DepthProfile depth) {
        DepositRandom rng = new DepositRandom(deposit.seed() ^ SALT ^ attemptSalt(attempt));
        SizeProfile size = type.size();

        int drawnY = depth.pickY(rng);
        double multiplier = depth.sizeMultiplier(drawnY, rng.nextDouble());
        boolean usable = biomeFactor > 0.0 && Double.isFinite(biomeFactor);
        double biome = usable && depth.isShallow(drawnY) ? biomeFactor : 1.0;
        int reference = size.draw(rng);
        int wanted = (int) Math.max(1, Math.round(reference * multiplier * biome));

        Fit fit = fit(type, rng, wanted);
        ShapeField field = fit.field();
        wanted = fit.wanted();

        // The box the deposit is built in. Horizontally it is held to the ore's reach limit, which
        // worldgen relies on; a shape that overshoots is trimmed rather than trusted.
        int limit = type.maxHorizontalReach();
        int rx = Math.min(field.reachX(), limit);
        int rz = Math.min(field.reachZ(), limit);
        int ry = Math.min(field.reachY(), verticalLimit(type.realm()));
        boolean clipped = field.reachX() > rx || field.reachZ() > rz || field.reachY() > ry;
        while ((2L * rx + 1) * (2L * ry + 1) * (2L * rz + 1) > MAX_CELLS) {
            clipped = true;
            if (rx >= ry && rx >= rz) {
                rx--;
            } else if (rz >= ry) {
                rz--;
            } else {
                ry--;
            }
        }
        int sideX = 2 * rx + 1;
        int sideY = 2 * ry + 1;
        int sideZ = 2 * rz + 1;

        // Ask the shape about every block in the box, remembering only the ones that are part of the deposit.
        Cells cells = new Cells();
        CellSample sample = new CellSample();
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dy = -ry; dy <= ry; dy++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    field.sample(dx, dy, dz, sample);
                    byte kind = sample.kind();
                    if (kind == CellSample.OUTSIDE) {
                        continue;
                    }
                    int key = ((dx + rx) * sideY + (dy + ry)) * sideZ + (dz + rz);
                    switch (kind) {
                        case CellSample.HOST -> cells.add(key, HOST, 0.0);
                        case CellSample.CANDIDATE -> cells.add(key, CANDIDATE, sample.score());
                        default -> cells.add(key, VOID, 0.0);
                    }
                }
            }
        }

        int ore = cells.chooseTop(CANDIDATE, ORE, wanted);
        int richWanted = (int) Math.round(ore * type.richShareFor(Math.max(1, ore)));
        int rich = cells.chooseTop(ORE, RICH, richWanted);
        cells.demote(CANDIDATE, HOST);

        // Tally, and find how far the body really reaches.
        int host = 0;
        int voids = 0;
        int minDx = Integer.MAX_VALUE;
        int maxDx = Integer.MIN_VALUE;
        int minDy = Integer.MAX_VALUE;
        int maxDy = Integer.MIN_VALUE;
        int minDz = Integer.MAX_VALUE;
        int maxDz = Integer.MIN_VALUE;
        for (int i = 0; i < cells.size; i++) {
            if (cells.kind[i] == HOST) {
                host++;
            } else if (cells.kind[i] == VOID) {
                voids++;
            }
            int key = cells.key[i];
            int dz = key % sideZ - rz;
            int rest = key / sideZ;
            int dy = rest % sideY - ry;
            int dx = rest / sideY - rx;
            minDx = Math.min(minDx, dx);
            maxDx = Math.max(maxDx, dx);
            minDy = Math.min(minDy, dy);
            maxDy = Math.max(maxDy, dy);
            minDz = Math.min(minDz, dz);
            maxDz = Math.max(maxDz, dz);
        }
        boolean empty = cells.size == 0;
        int reachX = empty ? 0 : Math.max(-minDx, maxDx);
        int reachY = empty ? 0 : Math.max(-minDy, maxDy);
        int reachZ = empty ? 0 : Math.max(-minDz, maxDz);

        // Keep the whole deposit inside the heights its realm allows: clear of bedrock, and below the roof or sky.
        int lowest = type.realm().lowestY() + (empty ? 0 : -minDy);
        int highest = type.realm().highestY() - (empty ? 0 : maxDy);
        int centerY = lowest <= highest ? Math.max(lowest, Math.min(highest, drawnY)) : (lowest + highest) / 2;

        return new DepositBody(type, attempt, drawnY, centerY, multiplier, biome, wanted, ore, rich, host, voids,
                reachX, reachY, reachZ, clipped, field, sideX, sideY, sideZ,
                Arrays.copyOf(cells.key, cells.size), Arrays.copyOf(cells.kind, cells.size));
    }

    /** Mixes the attempt number into the deposit's random seed. Attempt 0 adds nothing, so it matches the original body exactly. */
    private static long attemptSalt(int attempt) {
        return attempt == 0 ? 0L : Hashing.mix64(attempt * 0x9E3779B97F4A7C15L);
    }

    /** {@link #EMPTY}, {@link #HOST}, {@link #ORE}, {@link #RICH} or {@link #VOID} for the block at this offset from the deposit's centre. */
    public byte at(int dx, int dy, int dz) {
        int rx = boxX / 2;
        int ry = boxY / 2;
        int rz = boxZ / 2;
        if (dx < -rx || dx > rx || dy < -ry || dy > ry || dz < -rz || dz > rz) {
            return EMPTY;
        }
        int key = ((dx + rx) * boxY + (dy + ry)) * boxZ + (dz + rz);
        int at = Arrays.binarySearch(keys, key);
        return at < 0 ? EMPTY : kinds[at];
    }

    /** Calls the visitor for every block of the body that is not empty. */
    public void forEach(CellVisitor visitor) {
        int rx = boxX / 2;
        int ry = boxY / 2;
        int rz = boxZ / 2;
        for (int i = 0; i < keys.length; i++) {
            int key = keys[i];
            int dz = key % boxZ - rz;
            int rest = key / boxZ;
            int dy = rest % boxY - ry;
            int dx = rest / boxY - rx;
            visitor.accept(dx, dy, dz, kinds[i]);
        }
    }

    public OreType type() {
        return type;
    }

    /** Which draw of the deposit this is: 0 for the ordinary body, higher if earlier draws were rejected. */
    public int attempt() {
        return attempt;
    }

    /** The height of the deposit's centre block, kept so the whole deposit fits inside its realm's usable heights. */
    public int centerY() {
        return centerY;
    }

    /** The height first drawn for the deposit, before it was moved to fit inside its realm's usable heights. */
    public int drawnY() {
        return drawnY;
    }

    /** How many times bigger than a shallow deposit this one is, because of its depth. */
    public double sizeMultiplier() {
        return sizeMultiplier;
    }

    /** How many times bigger (or smaller) this deposit is because of its surface biome. 1 for deep deposits. */
    public double biomeMultiplier() {
        return biomeMultiplier;
    }

    /** How many ore blocks the deposit was asked to hold. */
    public int requestedOre() {
        return requestedOre;
    }

    /** How many ore blocks it holds, rich ones included. Equal to {@link #requestedOre()} unless the shape had too few candidates. */
    public int oreCount() {
        return oreCount;
    }

    /** How many of its ore blocks are rich. */
    public int richCount() {
        return richCount;
    }

    /** How many blocks of host rock the deposit's footprint contains around and between its ore. */
    public int hostCount() {
        return hostCount;
    }

    /** How many hollow cavity blocks it has. */
    public int voidCount() {
        return voidCount;
    }

    /** Every block of the deposit is within this many blocks of its centre along x. */
    public int reachX() {
        return reachX;
    }

    public int reachY() {
        return reachY;
    }

    public int reachZ() {
        return reachZ;
    }

    /** True if the shape reached beyond its ore's limits and was trimmed. Should never happen; tests watch for it. */
    public boolean clipped() {
        return clipped;
    }

    /** How favourable the shape's internal structure makes this spot, or NaN. Test hook. */
    double structureAt(int dx, int dy, int dz) {
        return field.structure(dx, dy, dz);
    }

    /** Growable parallel arrays of the blocks found so far, with a few bulk operations on them. */
    private static final class Cells {

        int size;
        int[] key = new int[1024];
        byte[] kind = new byte[1024];
        double[] score = new double[1024];

        void add(int cellKey, byte cellKind, double cellScore) {
            if (size == key.length) {
                int capacity = size * 2;
                key = Arrays.copyOf(key, capacity);
                kind = Arrays.copyOf(kind, capacity);
                score = Arrays.copyOf(score, capacity);
            }
            key[size] = cellKey;
            kind[size] = cellKind;
            score[size] = cellScore;
            size++;
        }

        /**
         * Turns the {@code count} highest-scoring cells of kind {@code from} into kind {@code to}, and
         * returns how many were turned (fewer than asked if there were not enough).
         */
        int chooseTop(byte from, byte to, int count) {
            int available = 0;
            for (int i = 0; i < size; i++) {
                if (kind[i] == from) {
                    available++;
                }
            }
            int chosen = Math.min(count, available);
            if (chosen <= 0) {
                return 0;
            }
            double[] sorted = new double[available];
            int n = 0;
            for (int i = 0; i < size; i++) {
                if (kind[i] == from) {
                    sorted[n++] = score[i];
                }
            }
            Arrays.sort(sorted);
            double threshold = sorted[available - chosen];

            int turned = 0;
            for (int i = 0; i < size; i++) {
                if (kind[i] == from && score[i] > threshold) {
                    kind[i] = to;
                    turned++;
                }
            }
            // Exact ties at the threshold are practically impossible; if any, take them in position order.
            for (int i = 0; i < size && turned < chosen; i++) {
                if (kind[i] == from && score[i] == threshold) {
                    kind[i] = to;
                    turned++;
                }
            }
            return turned;
        }

        void demote(byte from, byte to) {
            for (int i = 0; i < size; i++) {
                if (kind[i] == from) {
                    kind[i] = to;
                }
            }
        }
    }
}
