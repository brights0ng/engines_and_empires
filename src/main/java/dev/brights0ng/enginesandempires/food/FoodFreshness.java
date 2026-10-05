package dev.brights0ng.enginesandempires.food;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a food stack's units are made of: its {@link Cohort}s, and which one is on {@code top} (the one that is eaten, and
 * that leaves first when the stack is split). This is the value of the {@code food_freshness} item component.
 *
 * <p>The rules, as Bright set them (2026-09-27):
 * <ul>
 *   <li>Units in the same stage share one cohort, aged to the oldest of them ("both take the less fresh substage").</li>
 *   <li>Units in different stages keep separate cohorts inside the one stack, so a stack is at most four cohorts.</li>
 *   <li>Cohorts are kept freshest first; the freshest is on top unless a player picks another. {@code top} 0 means "the
 *       freshest, whichever that is", so fresher food added to a stack goes on top; any other value stays with the stage
 *       that was picked.</li>
 * </ul>
 * Stages depend on the time, so two cohorts that are in different stages now can share one later; {@link #settle} merges
 * them. Every operation here returns a settled value.
 *
 * <p>Plain Java on purpose (no Minecraft types), so it can be unit tested.
 */
public record FoodFreshness(List<Cohort> cohorts, int top) {

    public static final FoodFreshness EMPTY = new FoodFreshness(List.of(), 0);

    public FoodFreshness {
        cohorts = List.copyOf(cohorts);
        if (top < 0 || top >= cohorts.size()) {
            top = 0;
        }
    }

    /** {@code count} units all born at {@code born}. */
    public static FoodFreshness born(long born, int count) {
        return count <= 0 ? EMPTY : new FoodFreshness(List.of(new Cohort(born, count)), 0);
    }

    public int total() {
        int total = 0;
        for (Cohort cohort : cohorts) {
            total += Math.max(0, cohort.count());
        }
        return total;
    }

    public boolean isEmpty() {
        return total() == 0;
    }

    /** The cohort on top, or null when there are no units. */
    public Cohort topCohort() {
        return cohorts.isEmpty() ? null : cohorts.get(top);
    }

    /**
     * The canonical form at {@code now}: one cohort per stage (born as early as the oldest unit in it), freshest first,
     * no empty cohorts, and the top still on the stage it was on.
     */
    public FoodFreshness settle(long now, SpoilTimes times) {
        FoodStage topStage = null;
        if (top != 0 && cohorts.get(top).count() > 0) {
            topStage = times.stage(now - cohorts.get(top).born());
        }
        Map<FoodStage, Cohort> byStage = new EnumMap<>(FoodStage.class);
        for (Cohort cohort : cohorts) {
            if (cohort.count() <= 0) {
                continue;
            }
            byStage.merge(times.stage(now - cohort.born()), cohort,
                    (a, b) -> new Cohort(Math.min(a.born(), b.born()), a.count() + b.count()));
        }
        List<Cohort> settled = new ArrayList<>(byStage.size());
        int newTop = 0;
        for (Map.Entry<FoodStage, Cohort> entry : byStage.entrySet()) {
            if (entry.getKey() == topStage) {
                newTop = settled.size();
            }
            settled.add(entry.getValue());
        }
        return new FoodFreshness(settled, newTop);
    }

    /** The result of taking units off a stack: what was taken, and what is left. */
    public record Split(FoodFreshness taken, FoodFreshness rest) {
    }

    /**
     * Takes {@code n} units off the top: the whole top cohort first, then the ones after it (wrapping round). What is taken
     * keeps the top it came from. Asking for more than there is takes everything.
     */
    public Split takeTop(int n, long now, SpoilTimes times) {
        FoodFreshness settled = settle(now, times);
        List<Cohort> source = settled.cohorts;
        int size = source.size();
        if (n <= 0 || size == 0) {
            return new Split(EMPTY, settled);
        }
        int[] left = new int[size];
        for (int i = 0; i < size; i++) {
            left[i] = source.get(i).count();
        }
        List<Cohort> taken = new ArrayList<>();
        FoodStage takenTop = null;
        int need = n;
        for (int step = 0; step < size && need > 0; step++) {
            int i = (settled.top + step) % size;
            int take = Math.min(need, left[i]);
            if (take > 0) {
                if (taken.isEmpty() && settled.top != 0) {
                    takenTop = times.stage(now - source.get(i).born());
                }
                taken.add(source.get(i).withCount(take));
                left[i] -= take;
                need -= take;
            }
        }
        List<Cohort> rest = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rest.add(source.get(i).withCount(left[i]));
        }
        // What is taken keeps a picked top (the first cohort taken came off it); otherwise its freshest is on top
        return new Split(new FoodFreshness(taken, 0).settle(now, times).withTopStage(takenTop, now, times),
                new FoodFreshness(rest, settled.top).settle(now, times));
    }

    /** This settled value with {@code stage}'s cohort on top, or unchanged when the stage is null or not here. */
    private FoodFreshness withTopStage(FoodStage stage, long now, SpoilTimes times) {
        if (stage == null) {
            return this;
        }
        for (int i = 0; i < cohorts.size(); i++) {
            if (times.stage(now - cohorts.get(i).born()) == stage) {
                return new FoodFreshness(cohorts, i);
            }
        }
        return this;
    }

    /**
     * Removes {@code n} units starting from the freshest. The safe guess when a stack shrinks and nothing says which units
     * left: it can only make what is left staler, never fresher.
     */
    public FoodFreshness removeFreshest(int n, long now, SpoilTimes times) {
        FoodFreshness settled = settle(now, times);
        List<Cohort> out = new ArrayList<>(settled.cohorts.size());
        int need = Math.max(0, n);
        for (Cohort cohort : settled.cohorts) {
            int take = Math.min(need, cohort.count());
            need -= take;
            out.add(cohort.withCount(cohort.count() - take));
        }
        return new FoodFreshness(out, settled.top).settle(now, times);
    }

    /**
     * Adds {@code n} units to the stalest cohort. The safe guess when a stack grows and nothing says where the new units came
     * from. With no cohorts at all, they are born now.
     */
    public FoodFreshness addStalest(int n, long now, SpoilTimes times) {
        FoodFreshness settled = settle(now, times);
        if (n <= 0) {
            return settled;
        }
        if (settled.cohorts.isEmpty()) {
            return born(now, n);
        }
        List<Cohort> out = new ArrayList<>(settled.cohorts);
        int last = out.size() - 1;
        out.set(last, out.get(last).withCount(out.get(last).count() + n));
        return new FoodFreshness(out, settled.top);
    }

    /** Both together. The top stays this one's, unless this one is empty. */
    public FoodFreshness merge(FoodFreshness other, long now, SpoilTimes times) {
        if (isEmpty()) {
            return other.settle(now, times);
        }
        if (other.isEmpty()) {
            return settle(now, times);
        }
        List<Cohort> all = new ArrayList<>(cohorts.size() + other.cohorts.size());
        all.addAll(cohorts);
        all.addAll(other.cohorts);
        return new FoodFreshness(all, top).settle(now, times);
    }

    /** Makes the cohorts add up to {@code count}, guessing safely (see {@link #removeFreshest} and {@link #addStalest}). */
    public FoodFreshness reconcile(int count, long now, SpoilTimes times) {
        int total = total();
        if (total > count) {
            return removeFreshest(total - count, now, times);
        }
        if (total < count) {
            return addStalest(count - total, now, times);
        }
        return settle(now, times);
    }

    /** Every cohort made older by {@code ticks} (or younger, if negative). For testing and debugging. */
    public FoodFreshness aged(long ticks) {
        List<Cohort> out = new ArrayList<>(cohorts.size());
        for (Cohort cohort : cohorts) {
            out.add(new Cohort(cohort.born() - ticks, cohort.count()));
        }
        return new FoodFreshness(out, top);
    }

    /** Every cohort at least {@code minAge} old at {@code now} (for food that is always rotting). */
    public FoodFreshness atLeastAge(long minAge, long now, SpoilTimes times) {
        long latestBorn = now - minAge;
        List<Cohort> out = new ArrayList<>(cohorts.size());
        for (Cohort cohort : cohorts) {
            out.add(new Cohort(Math.min(cohort.born(), latestBorn), cohort.count()));
        }
        return new FoodFreshness(out, top).settle(now, times);
    }

    /**
     * The next group on top: {@code by} 1 moves to the next staler group, -1 to the next fresher, wrapping round at the ends.
     * A settled value with fewer than two groups is returned as it is.
     */
    public FoodFreshness rotateTop(int by) {
        int size = cohorts.size();
        if (size < 2) {
            return this;
        }
        return new FoodFreshness(cohorts, Math.floorMod(top + by, size));
    }
}
