package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dev.brights0ng.enginesandempires.oregen.Deposit;
import dev.brights0ng.enginesandempires.oregen.DepositBody;
import dev.brights0ng.enginesandempires.oregen.OreMap;
import dev.brights0ng.enginesandempires.oregen.OreType;
import dev.brights0ng.enginesandempires.oregen.OreTypes;
import dev.brights0ng.enginesandempires.oregen.ResolvedDeposit;

/**
 * Finds the deposits near a position. This is the foundation the geophone is built on: it says what is
 * there, and everything about how much a geophone tells the player of that is decided elsewhere.
 *
 * <p>The search never looks at loaded chunks. It works from the ore maps, which say where deposits are for
 * any position in the world, and from the resolver, which says which of them really exist and what they
 * look like. Both are pure functions of the seed and the terrain, so a deposit a long way from any
 * loaded chunk is found exactly as one under the player's feet is, and always the same way. That is what
 * lets a long-range survey point at places nobody has been to.
 *
 * <p>Range is measured to a deposit's <em>nearest ore block</em>, not its centre. Deposits can be a
 * hundred blocks across, so the centre says little about how close the ore is: a wide seam whose edge
 * is under your feet is close, whichever way its centre lies.
 *
 * <p>Results are ground truth. A geophone must not hand them to the player as they are; it decides what
 * to reveal, and how precisely.
 *
 * <p>Nothing here touches Minecraft, and resolving deposits is the slow part (a deposit's body takes
 * milliseconds to build the first time), so this is meant to be called off the main thread for wide
 * searches. It holds no state and is safe to call from any thread, provided the {@link Resolver} is.
 */
public final class DepositFinder {

    /** How distance to a deposit is measured. */
    public enum Metric {
        /** Straight-line distance in three dimensions. Depth counts: ore far below is far away. */
        SPHERE,
        /** Distance across the ground, ignoring depth. Ore straight below is as close as ore beside you. */
        HORIZONTAL
    }

    /**
     * What to look for.
     *
     * @param x      where the search is made from
     * @param y      how high it is made from
     * @param z      where the search is made from
     * @param radius how far to look, in blocks, measured by {@code metric} to the deposit's nearest ore block
     * @param metric how distance is measured
     * @param ores   the ids of the ores to look for, or null for every ore
     */
    public record Query(int x, int y, int z, double radius, Metric metric, Set<String> ores) {

        public Query {
            if (!(radius > 0.0)) {
                throw new IllegalArgumentException("radius must be positive");
            }
            Objects.requireNonNull(metric, "metric");
            ores = ores == null ? null : Set.copyOf(ores);
        }

        /** A search for every ore. */
        public static Query around(int x, int y, int z, double radius, Metric metric) {
            return new Query(x, y, z, radius, metric, null);
        }

        /** The same search, for only these ores. */
        public Query onlyOres(Collection<String> oreIds) {
            return new Query(x, y, z, radius, metric, Set.copyOf(oreIds));
        }

        boolean wants(String oreId) {
            return ores == null || ores.contains(oreId);
        }
    }

    /**
     * One deposit found by a search.
     *
     * @param distance the distance from where the search was made to the deposit's nearest ore block, by the
     *                 search's metric
     * @param nearestX the position of that nearest ore block
     * @param remainingOre how many ore blocks of the deposit are left, or -1 if that has not been checked. The finder
     *                 itself does not check: it knows what the deposit was, not what has been done to it since
     */
    public record Sighting(ResolvedDeposit resolved, double distance, int nearestX, int nearestY, int nearestZ,
                           int remainingOre) {

        /** A sighting whose remaining ore has not been checked. */
        public Sighting(ResolvedDeposit resolved, double distance, int nearestX, int nearestY, int nearestZ) {
            this(resolved, distance, nearestX, nearestY, nearestZ, -1);
        }

        /** The same sighting, with its remaining ore filled in. */
        public Sighting withRemaining(int remaining) {
            return new Sighting(resolved, distance, nearestX, nearestY, nearestZ, remaining);
        }

        public Deposit deposit() {
            return resolved.deposit();
        }

        public DepositBody body() {
            return resolved.body();
        }

        public String oreId() {
            return resolved.deposit().oreId();
        }

        public OreType type() {
            return OreTypes.byId(oreId());
        }

        /** The x of the deposit's centre column. */
        public int x() {
            return resolved.deposit().x();
        }

        public int z() {
            return resolved.deposit().z();
        }

        /** The height of the deposit's centre. */
        public int centerY() {
            return resolved.body().centerY();
        }
    }

    /**
     * Everything a search found, nearest first, and how much work it took.
     *
     * @param candidates how many deposits were close enough to be worth checking
     * @param dropped    how many of those turned out not to exist, having no rock to be in
     * @param depleted   how many existing deposits in range were left out because they have been mined out. The
     *                   finder leaves this 0; whoever checks the world for depletion fills it in with
     *                   {@link #afterDepletion}
     */
    public record Result(List<Sighting> sightings, int candidates, int dropped, int depleted) {

        /** The result with the mined-out deposits removed. */
        public Result afterDepletion(List<Sighting> kept, int depleted) {
            return new Result(List.copyOf(kept), candidates, dropped, depleted);
        }
    }

    /** Works out what becomes of a deposit. Usually {@code LevelDeposits::resolve}. */
    @FunctionalInterface
    public interface Resolver {
        ResolvedDeposit resolve(Deposit deposit);
    }

    /**
     * Finds the deposits of the given ore maps whose nearest ore block is within the query's radius.
     *
     * @param maps     the ore maps to look in: one per ore, for the world seed. Ores the query does not want are skipped
     * @param resolver decides which deposits really exist
     */
    public static Result find(List<OreMap> maps, Query query, Resolver resolver) {
        List<Sighting> found = new ArrayList<>();
        int candidates = 0;
        int dropped = 0;

        for (OreMap map : maps) {
            OreType type = OreTypes.byId(map.layer().id());
            if (!query.wants(type.id())) {
                continue;
            }

            // A deposit can reach up to this far sideways, along each axis, from its centre. Its ore can therefore be
            // within range while its centre is a good deal further off, up to the diagonal of that reach.
            int reach = type.maxHorizontalReach();
            double centreLimit = query.radius() + reach * Math.sqrt(2.0);
            int box = (int) Math.ceil(query.radius() + reach);

            for (Deposit deposit : map.depositsInBox(query.x() - box, query.z() - box, query.x() + box, query.z() + box)) {
                double dx = deposit.x() - query.x();
                double dz = deposit.z() - query.z();
                if (dx * dx + dz * dz > centreLimit * centreLimit) {
                    continue;
                }
                candidates++;
                ResolvedDeposit resolved = resolver.resolve(deposit);
                if (!resolved.viable()) {
                    dropped++;
                    continue;
                }
                Sighting sighting = nearestOre(resolved, query);
                if (sighting != null && sighting.distance() <= query.radius()) {
                    found.add(sighting);
                }
            }
        }

        found.sort(Comparator.comparingDouble(Sighting::distance)
                .thenComparing(Sighting::oreId)
                .thenComparingInt(Sighting::x)
                .thenComparingInt(Sighting::z));
        return new Result(List.copyOf(found), candidates, dropped, 0);
    }

    /**
     * The ore block of a deposit nearest to where a query is made, by the query's metric, or null if it has no
     * ore (which a viable deposit never lacks).
     */
    public static Sighting nearestOre(ResolvedDeposit resolved, Query query) {
        Deposit deposit = resolved.deposit();
        DepositBody body = resolved.body();
        boolean sphere = query.metric() == Metric.SPHERE;

        double[] best = {Double.POSITIVE_INFINITY};
        int[] at = new int[3];
        body.forEach((dx, dy, dz, kind) -> {
            if (kind != DepositBody.ORE && kind != DepositBody.RICH) {
                return;
            }
            int x = deposit.x() + dx;
            int y = body.centerY() + dy;
            int z = deposit.z() + dz;
            double ox = x - query.x();
            double oy = sphere ? y - query.y() : 0.0;
            double oz = z - query.z();
            double squared = ox * ox + oy * oy + oz * oz;
            if (squared < best[0]) {
                best[0] = squared;
                at[0] = x;
                at[1] = y;
                at[2] = z;
            }
        });
        if (best[0] == Double.POSITIVE_INFINITY) {
            return null;
        }
        return new Sighting(resolved, Math.sqrt(best[0]), at[0], at[1], at[2]);
    }

    private DepositFinder() {
    }
}
